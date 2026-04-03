import { TourApiService } from '../src/tour-api/tour-api.service';

describe('TourApiService', () => {
  const authService = {
    assertHostUser: jest.fn(),
  };
  const placeService = {
    upsertTourApiPlaces: jest.fn(),
    listPlacesForEnrichment: jest.fn(),
    enrichPlaceDetails: jest.fn(),
  };
  const service = new TourApiService(authService as any, placeService as any);

  beforeEach(() => {
    jest.clearAllMocks();
    process.env.TOUR_API_SERVICE_KEY = 'test-key';
    process.env.TOUR_API_BASE_URL = 'https://example.com';
    process.env.TOUR_API_MOBILE_OS = 'ETC';
    process.env.TOUR_API_MOBILE_APP = 'TripSync';
    process.env.TOUR_API_RESPONSE_TYPE = 'json';
  });

  it('syncs configured content types and aggregates counts', async () => {
    const fetchMock = jest.spyOn(global, 'fetch' as any).mockResolvedValue({
      ok: true,
      json: async () => ({
        response: {
          header: { resultCode: '0000', resultMsg: 'OK' },
          body: {
            totalCount: 1,
            items: { item: [{ contentid: '1', contenttypeid: '12', title: '공산성', addr1: '충남 공주', mapx: '127.1', mapy: '36.4' }] },
          },
        },
      }),
    } as Response);
    placeService.upsertTourApiPlaces.mockResolvedValue({ synced: 1, skipped: 0 });

    const result = await service.syncChungnamPlaces({ isGuest: false } as any);

    expect(fetchMock).toHaveBeenCalledTimes(7);
    expect(placeService.upsertTourApiPlaces).toHaveBeenCalledTimes(7);
    expect(result.data?.contentTypeIds).toEqual([12, 14, 15, 28, 32, 38, 39]);

    fetchMock.mockRestore();
  });

  it('enriches a limited batch of places with detailCommon/detailIntro', async () => {
    placeService.listPlacesForEnrichment.mockResolvedValue([
      {
        id: BigInt(1),
        tourApiId: '2750143',
        metadataTags: { contentTypeId: '14' },
      },
    ]);

    const fetchMock = jest
      .spyOn(global, 'fetch' as any)
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          response: {
            header: { resultCode: '0000', resultMsg: 'OK' },
            body: { items: { item: [{ contentid: '2750143', title: '가가책방', firstimage: 'img.jpg' }] } },
          },
        }),
      } as Response)
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          response: {
            header: { resultCode: '0000', resultMsg: 'OK' },
            body: { items: { item: [{ contentid: '2750143', contenttypeid: '14', usefee: '5000원' }] } },
          },
        }),
      } as Response);

    const result = await service.enrichChungnamPlaces({ isGuest: false } as any, { limit: 1 });

    expect(placeService.listPlacesForEnrichment).toHaveBeenCalledWith(1);
    expect(placeService.enrichPlaceDetails).toHaveBeenCalledTimes(1);
    expect(result.data).toEqual({ limit: 1, enriched: 1, skipped: 0 });

    fetchMock.mockRestore();
  });
});
