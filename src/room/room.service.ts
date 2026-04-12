import { HttpStatus, Injectable } from '@nestjs/common';
import { User } from '@prisma/client';
import { TripRoomStatus, RoomMemberRole, YnFlag } from '../common/enums/domain.enums';
import { ok } from '../common/dto/api-response.dto';
import { DomainException } from '../common/errors/domain.exception';
import { findActiveRoomById, requireActiveRoomMember } from '../common/room-access.util';
import { BaseSoftDeleteService } from '../common/soft-delete/base-soft-delete.service';
import { ACTIVE_DEL_YN } from '../common/soft-delete/soft-delete.util';
import { AuthService } from '../auth/auth.service';
import { PrismaService } from '../prisma/prisma.service';
import { CreateRoomDto } from './dto/create-room.dto';
import { JoinRoomDto } from './dto/join-room.dto';

@Injectable()
export class RoomService extends BaseSoftDeleteService {
  constructor(
    private readonly authService: AuthService,
    private readonly prisma: PrismaService,
  ) {
    super();
  }

  private async requireRoomMember(roomId: bigint, userId: bigint) {
    await requireActiveRoomMember({
      prisma: this.prisma,
      activeWhere: this.activeWhere.bind(this),
      roomId,
      userId,
    });
  }

  private async getActiveRoomById(roomId: bigint) {
    return findActiveRoomById({
      prisma: this.prisma,
      activeWhere: this.activeWhere.bind(this),
      roomId,
      include: {
        hostUser: true,
        members: {
          where: this.activeWhere({}),
          include: { user: true },
          orderBy: { joinedAt: 'asc' },
        },
        memberProfiles: {
          where: this.activeWhere({}),
        },
      },
    });
  }

  private async getActiveRoomByShareCode(shareCode: string) {
    const room = await this.prisma.tripRoom.findFirst({
      where: this.activeWhere({ shareCode }),
      include: {
        hostUser: true,
        members: {
          where: this.activeWhere({}),
        },
      },
    });

    if (!room) {
      throw new DomainException(HttpStatus.NOT_FOUND, 'INVALID_SHARE_CODE', '유효하지 않은 공유 코드입니다.');
    }

    return room;
  }

  private generateShareCode() {
    const suffix = Math.random().toString(36).toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 5);
    return `CNAM${new Date().getFullYear().toString().slice(-2)}${suffix}`;
  }

  private buildRoomMemberProfileSnapshot(result: {
    mobilityScore: number;
    photoScore: number;
    budgetScore: number;
    themeScore: number;
    characterName: string;
  }) {
    return {
      mobilityScore: result.mobilityScore,
      photoScore: result.photoScore,
      budgetScore: result.budgetScore,
      themeScore: result.themeScore,
      characterName: result.characterName,
      delYn: ACTIVE_DEL_YN,
    };
  }

  private async refreshRoomStatus(roomId: bigint) {
    const activeProfileCount = await this.prisma.roomMemberProfile.count({
      where: this.activeWhere({ roomId }),
    });

    const nextStatus = activeProfileCount >= 2 ? TripRoomStatus.READY : TripRoomStatus.WAITING;
    await this.prisma.tripRoom.update({
      where: { id: roomId },
      data: { status: nextStatus },
    });

    return nextStatus;
  }

  async createRoom(dto: CreateRoomDto, host: User) {
    this.authService.assertHostUser(host);
    const tripDate = new Date(dto.tripDate);
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    if (tripDate.getTime() <= today.getTime()) {
      throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, 'INVALID_REQUEST', 'tripDate는 오늘 이후여야 합니다.');
    }

    const room = await this.prisma.tripRoom.create({
      data: {
        hostUserId: host.id,
        shareCode: this.generateShareCode(),
        destination: dto.destination,
        tripDate,
        status: TripRoomStatus.WAITING,
        delYn: ACTIVE_DEL_YN,
        members: {
          create: {
            userId: host.id,
            role: RoomMemberRole.HOST,
            delYn: ACTIVE_DEL_YN,
          },
        },
      },
    });

    return ok({
      roomId: Number(room.id),
      shareCode: room.shareCode,
      status: room.status,
    });
  }

  async getRoom(roomId: number, user: User) {
    await this.requireRoomMember(BigInt(roomId), user.id);
    const room = await this.getActiveRoomById(BigInt(roomId));

    return ok({
      roomId: Number(room.id),
      destination: room.destination,
      tripDate: room.tripDate.toISOString().slice(0, 10),
      shareCode: room.shareCode,
      status: room.status,
      hostUserId: Number(room.hostUserId),
      memberCount: room.members.length,
      createdAt: room.createdAt.toISOString(),
    });
  }

  async getShareRoom(shareCode: string) {
    const room = await this.getActiveRoomByShareCode(shareCode);

    return ok({
      roomId: Number(room.id),
      shareCode: room.shareCode,
      destination: room.destination,
      tripDate: room.tripDate.toISOString().slice(0, 10),
      hostNickname: room.hostUser.nickname,
      memberCount: room.members.length,
      status: room.status,
    });
  }

  async joinRoom(shareCode: string, dto: JoinRoomDto, user: User) {
    const room = await this.getActiveRoomByShareCode(shareCode);

    if (dto.tptiResultId != null) {
      const result = await this.prisma.tptiResult.findFirst({
        where: this.activeWhere({ id: BigInt(dto.tptiResultId), userId: user.id }),
      });

      if (!result) {
        throw new DomainException(HttpStatus.NOT_FOUND, 'TPTI_INCOMPLETE', '유효한 TPTI 결과를 찾을 수 없습니다.');
      }

      await this.prisma.roomMemberProfile.upsert({
        where: {
          roomId_userId: {
            roomId: room.id,
            userId: user.id,
          },
        },
        update: {
          tptiResultId: result.id,
          ...this.buildRoomMemberProfileSnapshot(result),
        },
        create: {
          roomId: room.id,
          userId: user.id,
          tptiResultId: result.id,
          ...this.buildRoomMemberProfileSnapshot(result),
        },
      });
    }

    const existingMembership = await this.prisma.roomMember.findFirst({
      where: this.activeWhere({ roomId: room.id, userId: user.id }),
    });

    if (!existingMembership) {
      await this.prisma.roomMember.create({
        data: {
          roomId: room.id,
          userId: user.id,
          role: room.hostUserId === user.id ? RoomMemberRole.HOST : RoomMemberRole.MEMBER,
          delYn: ACTIVE_DEL_YN,
        },
      });
    }

    const roomStatus = await this.refreshRoomStatus(room.id);

    return ok({
      roomId: Number(room.id),
      userId: Number(user.id),
      status: 'joined',
      roomStatus,
    });
  }

  async getMembers(roomId: number, user: User) {
    await this.requireRoomMember(BigInt(roomId), user.id);
    const room = await this.getActiveRoomById(BigInt(roomId));

    const profilesByUserId = new Map(
      room.memberProfiles.map((profile) => [profile.userId.toString(), profile]),
    );

    return ok({
      roomId,
      members: room.members.map((member) => {
        const profile = profilesByUserId.get(member.userId.toString());
        return {
          userId: Number(member.userId),
          nickname: member.user.nickname,
          role: member.role,
          tptiCompleted: Boolean(profile),
          scores: profile
            ? {
                mobility: profile.mobilityScore,
                photo: profile.photoScore,
                budget: profile.budgetScore,
                theme: profile.themeScore,
              }
            : null,
        };
      }),
    });
  }
}
