import { Injectable, NotFoundException } from '@nestjs/common';
import { ok } from '../common/dto/api-response.dto';
import { BaseSoftDeleteService } from '../common/soft-delete/base-soft-delete.service';
import { ACTIVE_DEL_YN } from '../common/soft-delete/soft-delete.util';
import { PrismaService } from '../prisma/prisma.service';

@Injectable()
export class ConflictService extends BaseSoftDeleteService {
  constructor(private readonly prisma: PrismaService) {
    super();
  }

  private getSeverity(gap: number) {
    if (gap <= 20) return 'common';
    if (gap <= 40) return 'minor';
    if (gap <= 60) return 'moderate';
    return 'critical';
  }

  async getConflictMap(roomId: number) {
    const room = await this.prisma.tripRoom.findFirst({
      where: this.activeWhere({ id: BigInt(roomId) }),
      include: {
        memberProfiles: {
          where: this.activeWhere({}),
          include: {
            user: true,
          },
        },
      },
    });

    if (!room) {
      throw new NotFoundException('여행 방을 찾을 수 없습니다.');
    }

    if (room.memberProfiles.length < 2) {
      throw new NotFoundException('갈등 지도를 계산할 프로필이 부족합니다.');
    }

    const axes = [
      { axis: 'mobility', values: room.memberProfiles.map((p) => p.mobilityScore) },
      { axis: 'photo', values: room.memberProfiles.map((p) => p.photoScore) },
      { axis: 'budget', values: room.memberProfiles.map((p) => p.budgetScore) },
      { axis: 'theme', values: room.memberProfiles.map((p) => p.themeScore) },
    ] as const;

    const commonAxes: string[] = [];
    const conflictAxes = axes
      .map(({ axis, values }) => {
        const min = Math.min(...values);
        const max = Math.max(...values);
        const gap = max - min;
        const severity = this.getSeverity(gap);
        if (gap <= 20) {
          commonAxes.push(axis);
        }
        return { axis, min, max, gap, severity };
      })
      .filter((entry) => entry.gap > 20);

    const summaryText =
      conflictAxes[0] != null
        ? `${conflictAxes[0].axis} 축에서 최대 ${conflictAxes[0].gap}점 차이가 있습니다.`
        : '현재 그룹은 공통 지대가 넓습니다.';

    const conflictMap = await this.prisma.conflictMap.create({
      data: {
        roomId: room.id,
        commonAxes,
        conflictAxes,
        summaryText,
        delYn: ACTIVE_DEL_YN,
      },
    });

    return ok({
      roomId,
      conflictMapId: Number(conflictMap.id),
      commonAxes,
      conflictAxes,
      summaryText,
      members: room.memberProfiles.map((profile) => ({
        userId: Number(profile.userId),
        nickname: profile.user.nickname,
        scores: {
          mobility: profile.mobilityScore,
          photo: profile.photoScore,
          budget: profile.budgetScore,
          theme: profile.themeScore,
        },
      })),
    });
  }
}
