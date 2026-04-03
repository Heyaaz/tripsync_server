import { Injectable, NotFoundException } from '@nestjs/common';
import { ok } from '../common/dto/api-response.dto';
import { ACTIVE_DEL_YN } from '../common/soft-delete/soft-delete.util';
import { BaseSoftDeleteService } from '../common/soft-delete/base-soft-delete.service';
import { ConsensusService } from '../consensus/consensus.service';
import { PrismaService } from '../prisma/prisma.service';
import { GenerateScheduleDto } from './dto/generate-schedule.dto';
import { RegenerateScheduleDto } from './dto/regenerate-schedule.dto';
import { SlotType } from '../common/enums/domain.enums';

@Injectable()
export class ScheduleService extends BaseSoftDeleteService {
  constructor(
    private readonly consensusService: ConsensusService,
    private readonly prisma: PrismaService,
  ) {
    super();
  }

  private async getActiveRoom(roomId: number) {
    const room = await this.prisma.tripRoom.findFirst({
      where: this.activeWhere({ id: BigInt(roomId) }),
      include: {
        memberProfiles: {
          where: this.activeWhere({}),
        },
      },
    });

    if (!room) {
      throw new NotFoundException('일정을 생성할 여행 방을 찾을 수 없습니다.');
    }

    return room;
  }

  async generateSchedule(roomId: number, dto: GenerateScheduleDto) {
    const room = await this.getActiveRoom(roomId);
    const latestVersion = await this.prisma.schedule.findFirst({
      where: this.activeWhere({ roomId: room.id }),
      orderBy: { version: 'desc' },
    });

    const version = (latestVersion?.version ?? 0) + 1;
    const groupSatisfaction = Math.max(65, 80 - room.memberProfiles.length * 2);

    const schedule = await this.prisma.schedule.create({
      data: {
        roomId: room.id,
        version,
        generationInput: {
          destination: dto.destination,
          tripDate: dto.tripDate,
          startTime: dto.startTime,
          endTime: dto.endTime,
        },
        groupSatisfaction,
        llmProvider: 'local-placeholder',
        delYn: ACTIVE_DEL_YN,
      },
    });

    const place = await this.prisma.place.findFirst({
      where: this.activeWhere({}),
      orderBy: { id: 'asc' },
    });

    if (place) {
      await this.prisma.scheduleSlot.create({
        data: {
          scheduleId: schedule.id,
          startTime: new Date(`${dto.tripDate}T09:00:00+09:00`),
          endTime: new Date(`${dto.tripDate}T11:00:00+09:00`),
          placeId: place.id,
          slotType: SlotType.COMMON,
          targetUserId: null,
          reasonAxis: 'common',
          orderIndex: 1,
          delYn: ACTIVE_DEL_YN,
        },
      });
    }

    await this.prisma.tripRoom.update({
      where: { id: room.id },
      data: { status: 'completed' },
    });

    return ok({
      ...this.consensusService.buildScheduleDraft(roomId),
      scheduleId: Number(schedule.id),
      version: schedule.version,
      groupSatisfaction: schedule.groupSatisfaction,
      destination: dto.destination,
      tripDate: dto.tripDate,
      startTime: dto.startTime,
      endTime: dto.endTime,
    });
  }

  async getSchedule(scheduleId: number) {
    const schedule = await this.prisma.schedule.findFirst({
      where: this.activeWhere({ id: BigInt(scheduleId) }),
      include: {
        slots: {
          where: this.activeWhere({}),
          include: {
            place: true,
          },
          orderBy: { orderIndex: 'asc' },
        },
        satisfactionScores: {
          where: this.activeWhere({}),
        },
      },
    });

    if (!schedule) {
      throw new NotFoundException('일정을 찾을 수 없습니다.');
    }

    return ok({
      id: Number(schedule.id),
      roomId: Number(schedule.roomId),
      version: schedule.version,
      groupSatisfaction: schedule.groupSatisfaction,
      summary: '오전 활동, 오후 휴식 중심의 균형 일정',
      slots: schedule.slots.map((slot) => ({
        orderIndex: slot.orderIndex,
        startTime: slot.startTime.toISOString(),
        endTime: slot.endTime.toISOString(),
        slotType: slot.slotType,
        targetUserId: slot.targetUserId ? Number(slot.targetUserId) : null,
        reasonAxis: slot.reasonAxis,
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

  async regenerateSchedule(scheduleId: number, dto: RegenerateScheduleDto) {
    const existing = await this.prisma.schedule.findFirst({
      where: this.activeWhere({ id: BigInt(scheduleId) }),
    });

    if (!existing) {
      throw new NotFoundException('재생성할 일정이 없습니다.');
    }

    const regenerated = await this.generateSchedule(Number(existing.roomId), {
      destination: '서울',
      tripDate: dto.tripDate,
      startTime: dto.startTime,
      endTime: dto.endTime,
    });

    return ok({
      scheduleId: existing.id.toString(),
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
      throw new NotFoundException('공유할 일정을 찾을 수 없습니다.');
    }

    return ok({
      scheduleId,
      destination: '서울',
      tripDate: new Date(schedule.createdAt).toISOString().slice(0, 10),
      summary: '오전 활동, 오후 휴식 중심의 균형 일정',
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
