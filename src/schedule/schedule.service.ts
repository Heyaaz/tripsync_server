import { HttpStatus, Injectable } from '@nestjs/common';
import { User } from '@prisma/client';
import { Prisma } from '@prisma/client';
import { AuthService } from '../auth/auth.service';
import { ok } from '../common/dto/api-response.dto';
import { DomainException } from '../common/errors/domain.exception';
import { findActiveRoomById, requireActiveRoomMember } from '../common/room-access.util';
import { readMetadataObject } from '../common/metadata.util';
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
    return findActiveRoomById({
      prisma: this.prisma,
      activeWhere: this.activeWhere.bind(this),
      roomId: BigInt(roomId),
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
  }

  private async ensureRoomMember(roomId: bigint, userId: bigint) {
    await requireActiveRoomMember({
      prisma: this.prisma,
      activeWhere: this.activeWhere.bind(this),
      roomId,
      userId,
    });
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

  private buildMemberNicknameMap(
    room: Awaited<ReturnType<ScheduleService['getRoomWithMembers']>>
    | {
        memberProfiles?: Array<{
          userId: bigint;
          user: { nickname: string };
        }>;
      },
  ) {
    return new Map<number, string>(
      (room.memberProfiles ?? []).map((profile) => [Number(profile.userId), profile.user.nickname] as const),
    );
  }

  private formatCoordinate(value: unknown) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : undefined;
  }

  private isDepopulationArea(metadataTags: Prisma.JsonValue | null | undefined) {
    if (!metadataTags) {
      return false;
    }
    if (Array.isArray(metadataTags)) {
      return metadataTags.some((entry) => typeof entry === 'string' && entry === 'population_decline');
    }

    const metadata = readMetadataObject(metadataTags);
    if (!metadata) {
      return false;
    }

    return metadata.populationDeclineArea === true || metadata.regionType === 'population_decline';
  }

  private formatPlaceResponse(place: {
    id: bigint | number;
    name: string;
    address: string;
    category?: string | null;
    latitude?: Prisma.Decimal | number | null;
    longitude?: Prisma.Decimal | number | null;
    metadataTags?: Prisma.JsonValue | null;
  }) {
    return {
      id: Number(place.id),
      name: place.name,
      address: place.address,
      category: place.category ?? undefined,
      latitude: this.formatCoordinate(place.latitude),
      longitude: this.formatCoordinate(place.longitude),
      isDepopulationArea: this.isDepopulationArea(place.metadataTags),
    };
  }

  private formatTargetUser(
    targetUserId: bigint | number | null | undefined,
    memberNicknames: Map<number, string>,
  ) {
    const normalizedTargetUserId = targetUserId != null ? Number(targetUserId) : null;

    return {
      targetUserId: normalizedTargetUserId,
      targetNickname: normalizedTargetUserId != null ? memberNicknames.get(normalizedTargetUserId) ?? null : null,
    };
  }

  private formatSatisfactionByUser(
    satisfactionScores: Array<{
      userId: bigint | number;
      score: number;
    }>,
    memberNicknames: Map<number, string>,
  ) {
    return satisfactionScores.map((score) => {
      const userId = Number(score.userId);
      return {
        userId,
        nickname: memberNicknames.get(userId) ?? null,
        score: score.score,
      };
    });
  }

  private formatStoredScheduleSlot(
    slot: {
      orderIndex: number;
      startTime: Date;
      endTime: Date;
      slotType: string;
      targetUserId: bigint | null;
      reasonAxis: string | null;
      reasonText: string | null;
      place: {
        id: bigint;
        name: string;
        address: string;
        category?: string | null;
        latitude?: Prisma.Decimal | number | null;
        longitude?: Prisma.Decimal | number | null;
        metadataTags?: Prisma.JsonValue | null;
      };
    },
    memberNicknames: Map<number, string>,
  ) {
    const targetUser = this.formatTargetUser(slot.targetUserId, memberNicknames);

    return {
      orderIndex: slot.orderIndex,
      startTime: slot.startTime.toISOString(),
      endTime: slot.endTime.toISOString(),
      slotType: slot.slotType,
      targetUserId: targetUser.targetUserId,
      targetNickname: targetUser.targetNickname,
      reasonAxis: slot.reasonAxis,
      reason: slot.reasonText,
      reasonText: slot.reasonText,
      place: this.formatPlaceResponse(slot.place),
    };
  }

  private formatGeneratedScheduleSlot(
    slot: ScheduleOptionDraft['slots'][number],
    memberNicknames: Map<number, string>,
    placesById: Map<number, {
      id: bigint;
      name: string;
      address: string;
      category: string;
      latitude: Prisma.Decimal | number | null;
      longitude: Prisma.Decimal | number | null;
      metadataTags: Prisma.JsonValue | null;
    }>,
  ) {
    const place = placesById.get(slot.placeId);
    const targetUser = this.formatTargetUser(slot.targetUserId, memberNicknames);

    return {
      orderIndex: slot.orderIndex,
      startTime: slot.startTime.toISOString(),
      endTime: slot.endTime.toISOString(),
      slotType: slot.slotType,
      targetUserId: targetUser.targetUserId,
      targetNickname: targetUser.targetNickname,
      reasonAxis: slot.reasonAxis,
      reason: slot.reasonText,
      reasonText: slot.reasonText,
      place: place
        ? this.formatPlaceResponse(place)
        : {
            id: slot.placeId,
            name: slot.placeName,
            address: slot.placeAddress,
            isDepopulationArea: slot.isHiddenGem,
          },
    };
  }

  private formatGeneratedOption(
    scheduleId: number,
    option: ScheduleOptionDraft,
    memberNicknames: Map<number, string>,
    placesById: Map<number, {
      id: bigint;
      name: string;
      address: string;
      category: string;
      latitude: Prisma.Decimal | number | null;
      longitude: Prisma.Decimal | number | null;
      metadataTags: Prisma.JsonValue | null;
    }>,
  ) {
    return {
      scheduleId,
      optionType: option.optionType,
      label: option.label,
      summary: option.summary,
      groupSatisfaction: option.groupSatisfaction,
      slots: option.slots.map((slot) => this.formatGeneratedScheduleSlot(slot, memberNicknames, placesById)),
      satisfactionByUser: this.formatSatisfactionByUser(option.satisfactionByUser, memberNicknames),
    };
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
            llmProvider: option.llmProvider,
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

    const options = await this.consensusService.buildScheduleOptions({
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
    const memberNicknames = this.buildMemberNicknameMap(room);
    const placesById = new Map(places.map((place) => [Number(place.id), place] as const));

    await this.prisma.tripRoom.update({
      where: { id: room.id },
      data: { status: TripRoomStatus.COMPLETED as any },
    });

    return ok({
      roomId,
      version,
      options: created.map((entry) => this.formatGeneratedOption(entry.scheduleId, entry.option, memberNicknames, placesById)),
    });
  }

  async getSchedule(scheduleId: number, user: User) {
    const schedule = await this.prisma.schedule.findFirst({
      where: this.activeWhere({ id: BigInt(scheduleId) }),
      include: {
        room: {
          include: {
            memberProfiles: {
              where: this.activeWhere({}),
              include: { user: true },
              orderBy: { createdAt: 'asc' },
            },
          },
        },
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
      throw new DomainException(HttpStatus.NOT_FOUND, 'SCHEDULE_NOT_FOUND', '일정을 찾을 수 없습니다.');
    }

    await this.ensureRoomMember(schedule.roomId, user.id);
    const memberNicknames = this.buildMemberNicknameMap(schedule.room);

    return ok({
      id: Number(schedule.id),
      roomId: Number(schedule.roomId),
      destination: schedule.room.destination,
      tripDate: schedule.room.tripDate,
      version: schedule.version,
      optionType: schedule.optionType,
      isConfirmed: schedule.isConfirmed,
      groupSatisfaction: schedule.groupSatisfaction,
      summary: schedule.summary,
      slots: schedule.slots.map((slot) => this.formatStoredScheduleSlot(slot, memberNicknames)),
      satisfactionByUser: this.formatSatisfactionByUser(schedule.satisfactionScores, memberNicknames),
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
      throw new DomainException(HttpStatus.NOT_FOUND, 'SCHEDULE_NOT_FOUND', '확정할 일정 옵션이 없습니다.');
    }

    const target = await this.prisma.schedule.findFirst({
      where: this.activeWhere({ roomId: room.id, version: latest.version, optionType: dto.optionType as any }),
    });

    if (!target) {
      throw new DomainException(HttpStatus.NOT_FOUND, 'SCHEDULE_NOT_FOUND', '선택한 일정 옵션을 찾을 수 없습니다.');
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
      throw new DomainException(HttpStatus.NOT_FOUND, 'SCHEDULE_NOT_FOUND', '재생성할 일정을 찾을 수 없습니다.');
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
        room: true,
        slots: {
          where: this.activeWhere({}),
          include: { place: true },
          orderBy: { orderIndex: 'asc' },
        },
      },
    });

    if (!schedule) {
      throw new DomainException(HttpStatus.NOT_FOUND, 'SCHEDULE_NOT_FOUND', '공유할 일정을 찾을 수 없습니다.');
    }

    return ok({
      scheduleId,
      destination: schedule.room.destination,
      tripDate: schedule.room.tripDate,
      optionType: schedule.optionType,
      summary: schedule.summary,
      groupSatisfaction: schedule.groupSatisfaction,
      slots: schedule.slots.map((slot) => ({
        orderIndex: slot.orderIndex,
        startTime: slot.startTime.toISOString().slice(11, 16),
        endTime: slot.endTime.toISOString().slice(11, 16),
        placeName: slot.place.name,
        place: this.formatPlaceResponse(slot.place),
      })),
    });
  }
}
