import { Injectable, NotFoundException } from '@nestjs/common';
import { ok } from '../common/dto/api-response.dto';
import { BaseSoftDeleteService } from '../common/soft-delete/base-soft-delete.service';
import { ACTIVE_DEL_YN } from '../common/soft-delete/soft-delete.util';
import { PrismaService } from '../prisma/prisma.service';
import { CreateRoomDto } from './dto/create-room.dto';
import { JoinRoomDto } from './dto/join-room.dto';
import { RoomMemberRole, TripRoomStatus } from '../common/enums/domain.enums';

@Injectable()
export class RoomService extends BaseSoftDeleteService {
  constructor(private readonly prisma: PrismaService) {
    super();
  }

  private buildActiveRoomWhere(roomId: bigint) {
    return this.activeWhere({ id: roomId });
  }

  private buildActiveShareCodeWhere(shareCode: string) {
    return this.activeWhere({ shareCode });
  }

  private async getDefaultHostUser() {
    let user = await this.prisma.user.findFirst({
      where: this.activeWhere({ isGuest: false }),
      orderBy: { id: 'asc' },
    });

    if (!user) {
      user = await this.prisma.user.create({
        data: {
          nickname: 'default-host',
          email: null,
          authProvider: 'google',
          providerUserId: `bootstrap-host`,
          profileImageUrl: null,
          adminYn: 'N',
          isGuest: false,
          delYn: ACTIVE_DEL_YN,
        },
      });
    }

    return user;
  }

  async createRoom(dto: CreateRoomDto) {
    const host = await this.getDefaultHostUser();
    const room = await this.prisma.tripRoom.create({
      data: {
        hostUserId: host.id,
        shareCode: `ROOM${Date.now().toString().slice(-6)}`,
        destination: dto.destination,
        tripDate: new Date(dto.tripDate),
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
      destination: room.destination,
      tripDate: room.tripDate.toISOString().slice(0, 10),
      shareCode: room.shareCode,
      status: room.status,
    });
  }

  async getShareRoom(shareCode: string) {
    const room = await this.prisma.tripRoom.findFirst({
      where: this.buildActiveShareCodeWhere(shareCode),
      include: {
        hostUser: true,
        members: {
          where: this.activeWhere({}),
        },
      },
    });

    if (!room) {
      throw new NotFoundException('여행 방을 찾을 수 없습니다.');
    }

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

  async joinRoom(shareCode: string, dto: JoinRoomDto) {
    const room = await this.prisma.tripRoom.findFirst({
      where: this.buildActiveShareCodeWhere(shareCode),
    });

    if (!room) {
      throw new NotFoundException('여행 방을 찾을 수 없습니다.');
    }

    let userId: bigint | null = null;
    if (dto.tptiResultId) {
      const result = await this.prisma.tptiResult.findFirst({
        where: this.activeWhere({ id: BigInt(dto.tptiResultId) }),
      });
      userId = result?.userId ?? null;

      if (result && userId) {
        await this.prisma.roomMemberProfile.upsert({
          where: {
            roomId_userId: {
              roomId: room.id,
              userId,
            },
          },
          update: {
            tptiResultId: result.id,
            mobilityScore: result.mobilityScore,
            photoScore: result.photoScore,
            budgetScore: result.budgetScore,
            themeScore: result.themeScore,
            characterName: result.characterName,
            delYn: ACTIVE_DEL_YN,
          },
          create: {
            roomId: room.id,
            userId,
            tptiResultId: result.id,
            mobilityScore: result.mobilityScore,
            photoScore: result.photoScore,
            budgetScore: result.budgetScore,
            themeScore: result.themeScore,
            characterName: result.characterName,
            delYn: ACTIVE_DEL_YN,
          },
        });
      }
    }

    if (!userId) {
      const guest = await this.prisma.user.findFirst({
        where: this.activeWhere({ isGuest: true }),
        orderBy: { id: 'desc' },
      });
      userId = guest?.id ?? null;
    }

    if (!userId) {
      throw new NotFoundException('방에 참여할 사용자를 찾을 수 없습니다.');
    }

    await this.prisma.roomMember.upsert({
      where: {
        roomId_userId: {
          roomId: room.id,
          userId,
        },
      },
      update: {
        delYn: ACTIVE_DEL_YN,
      },
      create: {
        roomId: room.id,
        userId,
        role: RoomMemberRole.MEMBER,
        delYn: ACTIVE_DEL_YN,
      },
    });

    const activeProfileCount = await this.prisma.roomMemberProfile.count({
      where: this.activeWhere({ roomId: room.id }),
    });

    const nextStatus = activeProfileCount >= 2 ? TripRoomStatus.READY : TripRoomStatus.WAITING;
    await this.prisma.tripRoom.update({
      where: { id: room.id },
      data: { status: nextStatus },
    });

    return ok({
      roomId: Number(room.id),
      shareCode,
      tptiResultId: dto.tptiResultId ?? null,
      status: 'joined',
      roomStatus: nextStatus,
    });
  }

  async getMembers(roomId: number) {
    const room = await this.prisma.tripRoom.findFirst({
      where: this.buildActiveRoomWhere(BigInt(roomId)),
      include: {
        members: {
          where: this.activeWhere({}),
          include: {
            user: true,
          },
        },
        memberProfiles: {
          where: this.activeWhere({}),
        },
      },
    });

    if (!room) {
      throw new NotFoundException('여행 방을 찾을 수 없습니다.');
    }

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
