import { HttpStatus, Injectable } from '@nestjs/common';
import { User } from '@prisma/client';
import { Prisma } from '@prisma/client';
import { AuthService } from '../auth/auth.service';
import { ok } from '../common/dto/api-response.dto';
import { DomainException } from '../common/errors/domain.exception';
import { BaseSoftDeleteService } from '../common/soft-delete/base-soft-delete.service';
import { ACTIVE_DEL_YN } from '../common/soft-delete/soft-delete.util';
import {
  AxisScores,
  ConsensusService,
  MemberSnapshot,
  PlaceCandidate,
  ScheduleOptionDraft,
} from '../consensus/consensus.service';
import { ScheduleOptionType, TripRoomStatus } from '../common/enums/domain.enums';
import { PrismaService } from '../prisma/prisma.service';
import { ConfirmScheduleDto } from './dto/confirm-schedule.dto';
import { GenerateScheduleDto } from './dto/generate-schedule.dto';
import { RegenerateScheduleDto } from './dto/regenerate-schedule.dto';

@Injectable()
export class ScheduleService extends BaseSoftDeleteService {
  constructor(
    private readonly authService: AuthService,
    private readonly consensusService: ConsensusService,
    private readonly prisma: PrismaService,
  ) {
    super();
  }

  private async getRoomWithMembers(roomId: number) {
    const room = await this.prisma.tripRoom.findFirst({
      where: this.activeWhere({ id: BigInt(roomId) }),
      include: {
        members: {
          where: this.activeWhere({}),
          include: { user: true },
          orderBy: { joinedAt: 'asc' },
        },
        memberProfiles: {
          where: this.activeWhere({}),
          include: { user: true },
          orderBy: { createdAt: 'asc' },
        },
      },
    });

    if (!room) {
      throw new DomainException(HttpStatus.NOT_FOUND, 'ROOM_NOT_FOUND', '존재하지 않는 여행 방입니다.');
    }

    return room;
  }

  private async ensureRoomMember(roomId: bigint, userId: bigint) {
    const membership = await this.prisma.roomMember.findFirst({
      where: this.activeWhere({ roomId, userId }),
    });

    if (!membership) {
      throw new DomainException(HttpStatus.FORBIDDEN, 'FORBIDDEN', '방 멤버만 접근할 수 있습니다.');
    }
  }

  private mapMembers(room: Awaited<ReturnType<ScheduleService['getRoomWithMembers']>>): MemberSnapshot[] {
    return room.memberProfiles.map((profile, index) => ({
      userId: Number(profile.userId),
      nickname: profile.user.nickname,
      joinedOrder: index,
      scores: {
        mobility: profile.mobilityScore,
        photo: profile.photoScore,
        budget: profile.budgetScore,
        theme: profile.themeScore,
      },
    }));
  }

  private mapPlaces(places: Array<{
    id: bigint;
    name: string;
    address: string;
    category: string;
    mobilityScore: number;
    photoScore: number;
    budgetScore: number;
    themeScore: number;
    metadataTags: Prisma.JsonValue | null;
    operatingHours: Prisma.JsonValue | null;
  }>): PlaceCandidate[] {
    return places.map((place) => ({
      id: Number(place.id),
      name: place.name,
      address: place.address,
      category: place.category,
      mobilityScore: place.mobilityScore,
      photoScore: place.photoScore,
      budgetScore: place.budgetScore,
      themeScore: place.themeScore,
      metadataTags: place.metadataTags ?? undefined,
      operatingHours: place.operatingHours ?? undefined,
    }));
  }

  private async createScheduleVersion(
    roomId: bigint,
    version: number,
    input: GenerateScheduleDto,
    options: ScheduleOptionDraft[],
  ) {
    const created = await this.prisma.$transaction(async (tx) => {
      const output: Array<{ scheduleId: number; option: ScheduleOptionDraft }> = [];

      for (const option of options) {
        const schedule = await tx.schedule.create({
          data: {
            roomId,
            version,
            optionType: option.optionType as any,
            isConfirmed: false,
            generationInput: {
              destination: input.destination,
              tripDate: input.tripDate,
              startTime: input.startTime,
              endTime: input.endTime,
            } as Prisma.InputJsonValue,
            summary: option.summary,
            groupSatisfaction: option.groupSatisfaction,
            llmProvider: 'deterministic-consensus',
            delYn: ACTIVE_DEL_YN,
          },
        });

        if (option.slots.length > 0) {
          await tx.scheduleSlot.createMany({
            data: option.slots.map((slot) => ({
              scheduleId: schedule.id,
              startTime: slot.startTime,
              endTime: slot.endTime,
              placeId: BigInt(slot.placeId),
              slotType: slot.slotType as any,
              targetUserId: slot.targetUserId != null ? BigInt(slot.targetUserId) : null,
              reasonAxis: slot.reasonAxis,
              reasonText: slot.reasonText,
              orderIndex: slot.orderIndex,
              delYn: ACTIVE_DEL_YN,
            })),
          });
        }

        if (option.satisfactionByUser.length > 0) {
          await tx.satisfactionScore.createMany({
            data: option.satisfactionByUser.map((entry) => ({
              scheduleId: schedule.id,
              userId: BigInt(entry.userId),
              score: entry.score,
              breakdown: entry.breakdown as Prisma.InputJsonValue,
              delYn: ACTIVE_DEL_YN,
            })),
          });
        }

        output.push({
          scheduleId: Number(schedule.id),
          option,
        });
      }

      return output;
    });

    return created;
  }

  async generateSchedule(roomId: number, dto: GenerateScheduleDto, user: User) {
    this.authService.assertHostUser(user);
    const room = await this.getRoomWithMembers(roomId);

    if (room.hostUserId !== user.id) {
      throw new DomainException(HttpStatus.FORBIDDEN, 'FORBIDDEN', '방장만 일정을 생성할 수 있습니다.');
    }
    if (room.status === TripRoomStatus.WAITING || room.memberProfiles.length < 2) {
      throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, 'ROOM_NOT_READY', '일정 생성 가능한 상태가 아닙니다.');
    }

    const places = await this.prisma.place.findMany({
      where: this.activeWhere({}),
      orderBy: { id: 'asc' },
    });

    const options = this.consensusService.buildScheduleOptions({
      roomId,
      destination: dto.destination,
      tripDate: dto.tripDate,
      startTime: dto.startTime,
      endTime: dto.endTime,
      members: this.mapMembers(room),
      places: this.mapPlaces(places),
    });

    const latest = await this.prisma.schedule.findFirst({
      where: this.activeWhere({ roomId: room.id }),
      orderBy: { version: 'desc' },
    });
    const version = (latest?.version ?? 0) + 1;

    const created = await this.createScheduleVersion(room.id, version, dto, options);

    await this.prisma.tripRoom.update({
      where: { id: room.id },
      data: { status: TripRoomStatus.COMPLETED as any },
    });

    return ok({
      roomId,
      version,
      options: created.map((entry) => ({
        scheduleId: entry.scheduleId,
        optionType: entry.option.optionType,
        label: entry.option.label,
        summary: entry.option.summary,
        groupSatisfaction: entry.option.groupSatisfaction,
        satisfactionByUser: entry.option.satisfactionByUser.map((score) => ({
          userId: score.userId,
          score: score.score,
        })),
      })),
    });
  }

  async getSchedule(scheduleId: number, user: User) {
    const schedule = await this.prisma.schedule.findFirst({
      where: this.activeWhere({ id: BigInt(scheduleId) }),
      include: {
        room: true,
        slots: {
          where: this.activeWhere({}),
          include: { place: true },
          orderBy: { orderIndex: 'asc' },
        },
        satisfactionScores: {
          where: this.activeWhere({}),
          orderBy: { userId: 'asc' },
        },
      },
    });

    if (!schedule) {
      throw new DomainException(HttpStatus.NOT_FOUND, 'ROOM_NOT_FOUND', '일정을 찾을 수 없습니다.');
    }

    await this.ensureRoomMember(schedule.roomId, user.id);

    return ok({
      id: Number(schedule.id),
      roomId: Number(schedule.roomId),
      version: schedule.version,
      optionType: schedule.optionType,
      isConfirmed: schedule.isConfirmed,
      groupSatisfaction: schedule.groupSatisfaction,
      summary: schedule.summary,
      slots: schedule.slots.map((slot) => ({
        orderIndex: slot.orderIndex,
        startTime: slot.startTime.toISOString(),
        endTime: slot.endTime.toISOString(),
        slotType: slot.slotType,
        targetUserId: slot.targetUserId != null ? Number(slot.targetUserId) : null,
        reasonAxis: slot.reasonAxis,
        reasonText: slot.reasonText,
        place: {
          id: Number(slot.placeId),
          name: slot.place.name,
          address: slot.place.address,
        },
      })),
      satisfactionByUser: schedule.satisfactionScores.map((score) => ({
        userId: Number(score.userId),
        score: score.score,
      })),
    });
  }

  async confirmSchedule(roomId: number, dto: ConfirmScheduleDto, user: User) {
    this.authService.assertHostUser(user);
    const room = await this.getRoomWithMembers(roomId);

    if (room.hostUserId !== user.id) {
      throw new DomainException(HttpStatus.FORBIDDEN, 'FORBIDDEN', '방장만 일정을 확정할 수 있습니다.');
    }

    const latest = await this.prisma.schedule.findFirst({
      where: this.activeWhere({ roomId: room.id }),
      orderBy: { version: 'desc' },
    });

    if (!latest) {
      throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, 'ROOM_NOT_READY', '확정할 일정 옵션이 없습니다.');
    }

    const target = await this.prisma.schedule.findFirst({
      where: this.activeWhere({ roomId: room.id, version: latest.version, optionType: dto.optionType as any }),
    });

    if (!target) {
      throw new DomainException(HttpStatus.NOT_FOUND, 'SCHEDULE_GENERATION_FAILED', '선택한 일정 옵션을 찾을 수 없습니다.');
    }

    await this.prisma.$transaction([
      this.prisma.schedule.updateMany({
        where: this.activeWhere({ roomId: room.id }),
        data: { isConfirmed: false },
      }),
      this.prisma.schedule.update({
        where: { id: target.id },
        data: { isConfirmed: true },
      }),
    ]);

    return ok({
      scheduleId: Number(target.id),
      roomId,
      optionType: dto.optionType,
      status: 'confirmed',
    });
  }

  async regenerateSchedule(scheduleId: number, dto: RegenerateScheduleDto, user: User) {
    const existing = await this.prisma.schedule.findFirst({
      where: this.activeWhere({ id: BigInt(scheduleId) }),
      include: { room: true },
    });

    if (!existing) {
      throw new DomainException(HttpStatus.NOT_FOUND, 'ROOM_NOT_FOUND', '재생성할 일정을 찾을 수 없습니다.');
    }

    const regenerated = await this.generateSchedule(
      Number(existing.roomId),
      {
        destination: existing.room.destination,
        tripDate: dto.tripDate,
        startTime: dto.startTime,
        endTime: dto.endTime,
      },
      user,
    );

    return ok({
      scheduleId,
      reason: dto.reason,
      tripDate: dto.tripDate,
      startTime: dto.startTime,
      endTime: dto.endTime,
      regenerated: regenerated.data,
      message: '새 버전 일정이 생성되었습니다.',
    });
  }

  async getPublicShareSchedule(scheduleId: number) {
    const schedule = await this.prisma.schedule.findFirst({
      where: this.activeWhere({ id: BigInt(scheduleId) }),
      include: {
        slots: {
          where: this.activeWhere({}),
          include: { place: true },
          orderBy: { orderIndex: 'asc' },
        },
      },
    });

    if (!schedule) {
      throw new DomainException(HttpStatus.NOT_FOUND, 'ROOM_NOT_FOUND', '공유할 일정을 찾을 수 없습니다.');
    }

    return ok({
      scheduleId,
      optionType: schedule.optionType,
      summary: schedule.summary,
      groupSatisfaction: schedule.groupSatisfaction,
      slots: schedule.slots.map((slot) => ({
        orderIndex: slot.orderIndex,
        startTime: slot.startTime.toISOString().slice(11, 16),
        endTime: slot.endTime.toISOString().slice(11, 16),
        placeName: slot.place.name,
      })),
    });
  }
}
