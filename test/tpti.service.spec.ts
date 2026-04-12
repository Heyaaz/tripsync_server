import { HttpStatus } from '@nestjs/common';
import { DomainException } from '../src/common/errors/domain.exception';
import { TptiService } from '../src/tpti/tpti.service';

describe('TptiService', () => {
  const originalEnv = { ...process.env };

  beforeEach(() => {
    process.env = { ...originalEnv };
    delete process.env.TPTI_MANUAL_ADJUSTMENTS_ENABLED;
  });

  afterAll(() => {
    process.env = originalEnv;
  });

  it('ignores manual adjustments when the feature flag is disabled', async () => {
    const prisma = {
      tptiResult: {
        create: jest.fn(async ({ data }: any) => ({
          id: 1n,
          createdAt: new Date('2026-04-03T10:00:00+09:00'),
          ...data,
        })),
      },
      roomMember: {
        findFirst: jest.fn(),
      },
    };
    const service = new TptiService(prisma as any);

    const result = await service.submitResult(
      {
        answers: [5, 1, 4, 2, 2, 4, 3, 4],
        manualAdjustments: {
          mobilityScore: 1,
          photoScore: 2,
          budgetScore: 3,
          themeScore: 4,
        },
      },
      { id: 1n } as any,
    );

    expect(prisma.tptiResult.create).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({
          mobilityScore: 100,
          photoScore: 75,
          budgetScore: 25,
          themeScore: 63,
          isManuallyAdjusted: false,
        }),
      }),
    );
    expect(result.data?.scores).toEqual({
      mobility: 100,
      photo: 75,
      budget: 25,
      theme: 63,
    });
    expect(result.data?.characterName).toBe('뚜벅이 도심 아티스트');
  });

  it('applies manual adjustments when the feature flag is enabled', async () => {
    process.env.TPTI_MANUAL_ADJUSTMENTS_ENABLED = 'true';
    const prisma = {
      tptiResult: {
        create: jest.fn(async ({ data }: any) => ({
          id: 2n,
          createdAt: new Date('2026-04-03T10:00:00+09:00'),
          ...data,
        })),
      },
      roomMember: {
        findFirst: jest.fn(),
      },
    };
    const service = new TptiService(prisma as any);

    const result = await service.submitResult(
      {
        answers: [5, 1, 4, 2, 2, 4, 3, 4],
        manualAdjustments: {
          mobilityScore: 10,
          photoScore: 20,
          budgetScore: 30,
          themeScore: 40,
        },
      },
      { id: 1n } as any,
    );

    expect(prisma.tptiResult.create).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({
          mobilityScore: 10,
          photoScore: 20,
          budgetScore: 30,
          themeScore: 40,
          isManuallyAdjusted: true,
        }),
      }),
    );
    expect(result.data?.scores).toEqual({
      mobility: 10,
      photo: 20,
      budget: 30,
      theme: 40,
    });
    expect(result.data?.characterName).toBe('힐링 자연 실속형');
  });

  it('allows latest-result access for same-room members and blocks outsiders', async () => {
    const prisma = {
      roomMember: {
        findFirst: jest
          .fn()
          .mockResolvedValueOnce({
            id: 1n,
            roomId: 10n,
            userId: 2n,
          })
          .mockResolvedValueOnce(null),
      },
      tptiResult: {
        findFirst: jest.fn(async () => ({
          id: 3n,
          userId: 1n,
          mobilityScore: 60,
          photoScore: 55,
          budgetScore: 50,
          themeScore: 45,
          characterName: '테스트 캐릭터',
          createdAt: new Date('2026-04-03T10:00:00+09:00'),
        })),
      },
    };
    const service = new TptiService(prisma as any);

    await expect(service.getLatestResult(1, { id: 2n } as any)).resolves.toMatchObject({
      data: expect.objectContaining({
        userId: 1,
        characterName: '테스트 캐릭터',
      }),
    });

    await expect(service.getLatestResult(1, { id: 3n } as any)).rejects.toBeInstanceOf(DomainException);
    await service.getLatestResult(1, { id: 3n } as any).catch((error: DomainException) => {
      expect(error.getStatus()).toBe(HttpStatus.FORBIDDEN);
      expect(error.getResponse()).toMatchObject({
        code: 'FORBIDDEN',
      });
    });
  });
});
