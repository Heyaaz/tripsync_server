import { PlaceService } from '../src/place/place.service';

describe('PlaceService', () => {
  const prisma = {
    place: {
      upsert: jest.fn(),
      findMany: jest.fn(),
      findUnique: jest.fn(),
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
    prisma.place.findMany.mockResolvedValue([]);
    prisma.place.upsert.mockResolvedValue({});
    const result = await service.upsertTourApiPlaces([
      { contentid: '1', contenttypeid: '12', title: '공산성', addr1: '충남 공주', mapx: '127.1', mapy: '36.4' },
      { contentid: '2', contenttypeid: '12', title: '좌표없음' },
    ]);

    expect(prisma.place.upsert).toHaveBeenCalledTimes(1);
    expect(result).toEqual({ synced: 1, skipped: 1, unchanged: 0 });
  });

  it('skips unchanged upserts when source modified time matches', async () => {
    prisma.place.findMany.mockResolvedValue([{
      tourApiId: '1',
      delYn: 'N',
      metadataTags: { sourceModifiedTime: '20260403101010' },
    }]);

    const result = await service.upsertTourApiPlaces([
      {
        contentid: '1',
        contenttypeid: '12',
        title: '공산성',
        addr1: '충남 공주',
        mapx: '127.1',
        mapy: '36.4',
        modifiedtime: '20260403101010',
      },
    ]);

    expect(prisma.place.upsert).not.toHaveBeenCalled();
    expect(result).toEqual({ synced: 0, skipped: 0, unchanged: 1 });
  });

  it('merges detailCommon/detailIntro into existing place', async () => {
    prisma.place.findUnique = jest.fn().mockResolvedValue({
      id: BigInt(1),
      name: '기존명',
      address: '기존주소',
      latitude: 36.4,
      longitude: 127.1,
      imageUrl: null,
      operatingHours: { status: 'unknown' },
      admissionFee: null,
      metadataTags: { contentTypeId: '14' },
    });
    prisma.place.update = jest.fn().mockResolvedValue({});

    await service.enrichPlaceDetails(
      BigInt(1),
      { title: '가가책방', addr1: '충남 공주', firstimage: 'img.jpg', overview: '설명', tel: '010' },
      { usetimeculture: '09:00~18:00', usefee: '5000원' },
    );

    expect(prisma.place.update).toHaveBeenCalledWith(
      expect.objectContaining({
        where: { id: BigInt(1) },
        data: expect.objectContaining({
          name: '가가책방',
          imageUrl: 'img.jpg',
          admissionFee: expect.stringContaining('5000원'),
          operatingHours: expect.objectContaining({
            status: 'known',
            entries: expect.arrayContaining([
              expect.objectContaining({
                openMinutes: 540,
                closeMinutes: 1080,
              }),
            ]),
          }),
        }),
      }),
    );
  });

  it('stores always-open status when intro indicates all-day operation', async () => {
    prisma.place.findUnique = jest.fn().mockResolvedValue({
      id: BigInt(2),
      name: '기존명',
      address: '기존주소',
      latitude: 36.4,
      longitude: 127.1,
      imageUrl: null,
      operatingHours: { status: 'unknown' },
      admissionFee: null,
      metadataTags: { contentTypeId: '39' },
    });
    prisma.place.update = jest.fn().mockResolvedValue({});

    await service.enrichPlaceDetails(BigInt(2), null, { usetimefood: '24시간 영업 / 연중무휴' });

    expect(prisma.place.update).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({
          operatingHours: expect.objectContaining({
            status: 'always',
          }),
        }),
      }),
    );
  });

  it('detects stale detail enrichment from source modified time', () => {
    expect(
      service.needsDetailEnrichment({
        metadataTags: {
          sourceModifiedTime: '20260403120000',
          detailEnrichedAt: '2026-04-03T10:00:00+09:00',
        },
      } as any),
    ).toBe(true);

    expect(
      service.needsDetailEnrichment({
        metadataTags: {
          sourceModifiedTime: '20260403100000',
          detailEnrichedAt: '2026-04-03T10:30:00+09:00',
        },
      } as any),
    ).toBe(false);
  });
});
