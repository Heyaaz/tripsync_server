import { HttpStatus, Injectable } from '@nestjs/common';
import { User } from '@prisma/client';
import { Prisma } from '@prisma/client';
import { AuthService } from '../auth/auth.service';
import { ok } from '../common/dto/api-response.dto';
import { DomainException } from '../common/errors/domain.exception';
import { BaseSoftDeleteService } from '../common/soft-delete/base-soft-delete.service';
import { ACTIVE_DEL_YN } from '../common/soft-delete/soft-delete.util';
import { ConsensusService } from '../consensus/consensus.service';
import { PrismaService } from '../prisma/prisma.service';

@Injectable()
export class ConflictService extends BaseSoftDeleteService {
  constructor(
    private readonly authService: AuthService,
    private readonly consensusService: ConsensusService,
    private readonly prisma: PrismaService,
  ) {
    super();
  }

  private async requireRoomMember(roomId: bigint, userId: bigint) {
    const membership = await this.prisma.roomMember.findFirst({
      where: this.activeWhere({ roomId, userId }),
    });

    if (!membership) {
      throw new DomainException(HttpStatus.FORBIDDEN, 'FORBIDDEN', '방 멤버만 접근할 수 있습니다.');
    }
  }

  async getConflictMap(roomId: number, user: User) {
    await this.requireRoomMember(BigInt(roomId), user.id);

    const room = await this.prisma.tripRoom.findFirst({
      where: this.activeWhere({ id: BigInt(roomId) }),
      include: {
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
    if (room.memberProfiles.length < 2) {
      throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, 'ROOM_NOT_READY', '갈등 지도를 계산할 프로필이 부족합니다.');
    }

    const members = room.memberProfiles.map((profile, index) => ({
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

    const analysis = this.consensusService.analyzeGroup(members);
    const topConflict = analysis.conflictAxes[0];
    const highMember = topConflict ? members.find((member) => member.userId === topConflict.highUserId) : null;
    const lowMember = topConflict ? members.find((member) => member.userId === topConflict.lowUserId) : null;
    const summaryText = topConflict
      ? `${highMember?.nickname ?? 'A님'}과 ${lowMember?.nickname ?? 'B님'}은 ${this.axisLabel(topConflict.axis)}에서 ${topConflict.gap}점 차이로 충돌합니다.`
      : '현재 그룹은 공통 지대가 넓습니다.';

    const conflictMap = await this.prisma.conflictMap.create({
      data: {
        roomId: room.id,
        commonAxes: analysis.commonAxes,
        conflictAxes: analysis.conflictAxes as unknown as Prisma.InputJsonValue,
        summaryText,
        delYn: ACTIVE_DEL_YN,
      },
    });

    return ok({
      roomId,
      conflictMapId: Number(conflictMap.id),
      commonAxes: analysis.commonAxes,
      conflictAxes: analysis.conflictAxes.map(({ axis, gap, severity }) => ({ axis, gap, severity })),
      summaryText,
      members: members.map((member) => ({
        userId: member.userId,
        nickname: member.nickname,
        scores: member.scores,
      })),
    });
  }

  private axisLabel(axis: string) {
    switch (axis) {
      case 'mobility':
        return '활동성';
      case 'photo':
        return '기록';
      case 'budget':
        return '예산';
      case 'theme':
        return '테마';
      default:
        return axis;
    }
  }
}
