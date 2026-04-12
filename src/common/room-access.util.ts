import { HttpStatus } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import { DomainException } from './errors/domain.exception';

type ActiveWhereBuilder = (where?: Record<string, unknown>) => Record<string, unknown>;

export async function requireActiveRoomMember(params: {
  prisma: Pick<PrismaService, 'roomMember'>;
  activeWhere: ActiveWhereBuilder;
  roomId: bigint;
  userId: bigint;
}) {
  const membership = await params.prisma.roomMember.findFirst({
    where: params.activeWhere({ roomId: params.roomId, userId: params.userId }),
  });

  if (!membership) {
    throw new DomainException(HttpStatus.FORBIDDEN, 'FORBIDDEN', '방 멤버만 접근할 수 있습니다.');
  }
}

export async function findActiveRoomById<TInclude extends Prisma.TripRoomInclude>(params: {
  prisma: Pick<PrismaService, 'tripRoom'>;
  activeWhere: ActiveWhereBuilder;
  roomId: bigint;
  include: TInclude;
  notFoundCode?: string;
  notFoundMessage?: string;
}): Promise<Prisma.TripRoomGetPayload<{ include: TInclude }>> {
  const room = await params.prisma.tripRoom.findFirst({
    where: params.activeWhere({ id: params.roomId }),
    include: params.include,
  });

  if (!room) {
    throw new DomainException(
      HttpStatus.NOT_FOUND,
      params.notFoundCode ?? 'ROOM_NOT_FOUND',
      params.notFoundMessage ?? '존재하지 않는 여행 방입니다.',
    );
  }

  return room as Prisma.TripRoomGetPayload<{ include: TInclude }>;
}
