import { HttpStatus } from '@nestjs/common';
import { DomainException } from '../src/common/errors/domain.exception';
import { ConflictService } from '../src/conflict/conflict.service';

describe('ConflictService', () => {
  const authService = {};
  const consensusService = {
    analyzeGroup: jest.fn(),
  };
  const prisma = {
    roomMember: {
      findFirst: jest.fn(),
    },
    tripRoom: {
      findFirst: jest.fn(),
    },
    conflictMap: {
      create: jest.fn(),
    },
  };

  const service = new ConflictService(authService as any, consensusService as any, prisma as any);

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('rejects conflict-map access for non-members', async () => {
    prisma.roomMember.findFirst.mockResolvedValue(null);

    await expect(service.getConflictMap(10, { id: 1n } as any)).rejects.toBeInstanceOf(DomainException);
    await service.getConflictMap(10, { id: 1n } as any).catch((error: DomainException) => {
      expect(error.getStatus()).toBe(HttpStatus.FORBIDDEN);
      expect(error.getResponse()).toMatchObject({ code: 'FORBIDDEN' });
    });
  });

  it('returns room-not-found after membership passes but the room is missing', async () => {
    prisma.roomMember.findFirst.mockResolvedValue({ id: 1n, roomId: 10n, userId: 1n });
    prisma.tripRoom.findFirst.mockResolvedValue(null);

    await expect(service.getConflictMap(10, { id: 1n } as any)).rejects.toBeInstanceOf(DomainException);
    await service.getConflictMap(10, { id: 1n } as any).catch((error: DomainException) => {
      expect(error.getStatus()).toBe(HttpStatus.NOT_FOUND);
      expect(error.getResponse()).toMatchObject({ code: 'ROOM_NOT_FOUND' });
    });
  });
});
