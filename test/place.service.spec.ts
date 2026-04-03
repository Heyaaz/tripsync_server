import { PlaceService } from '../src/place/place.service';

describe('PlaceService', () => {
  const prisma = {
    place: {
      upsert: jest.fn(),
      findMany: jest.fn(),
    },
  };
  const service = new PlaceService(prisma as any);

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('normalizes tour api place into place payload', () => {
    const result = service.normalizeTourApiPlace({
      contentid: '123',
      contenttypeid: '39',
      title: '예산 시장 맛집',
      addr1: '충남 예산군',
      mapx: '126.8',
      mapy: '36.6',
      areacode: '34',
      sigungucode: '12',
    });

    expect(result).toMatchObject({
      tourApiId: '123',
      category: 'restaurant',
      budgetScore: expect.any(Number),
      themeScore: expect.any(Number),
    });
    expect(result?.budgetScore).toBeGreaterThanOrEqual(55);
  });

  it('upserts only valid places', async () => {
    prisma.place.upsert.mockResolvedValue({});
    const result = await service.upsertTourApiPlaces([
      { contentid: '1', contenttypeid: '12', title: '공산성', addr1: '충남 공주', mapx: '127.1', mapy: '36.4' },
      { contentid: '2', contenttypeid: '12', title: '좌표없음' },
    ]);

    expect(prisma.place.upsert).toHaveBeenCalledTimes(1);
    expect(result).toEqual({ synced: 1, skipped: 1 });
  });
});
