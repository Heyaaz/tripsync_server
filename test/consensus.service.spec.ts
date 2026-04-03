import { ConsensusService, MemberSnapshot, PlaceCandidate } from '../src/consensus/consensus.service';
import { ScheduleOptionType } from '../src/common/enums/domain.enums';

describe('ConsensusService', () => {
  const service = new ConsensusService();

  const members: MemberSnapshot[] = [
    {
      userId: 1,
      nickname: '민지',
      joinedOrder: 0,
      scores: { mobility: 90, photo: 70, budget: 30, theme: 60 },
    },
    {
      userId: 2,
      nickname: '지훈',
      joinedOrder: 1,
      scores: { mobility: 20, photo: 65, budget: 35, theme: 58 },
    },
    {
      userId: 3,
      nickname: '수아',
      joinedOrder: 2,
      scores: { mobility: 55, photo: 72, budget: 40, theme: 55 },
    },
  ];

  const places: PlaceCandidate[] = [
    {
      id: 101,
      name: '공산성',
      address: '충남 공주',
      category: 'history',
      mobilityScore: 88,
      photoScore: 70,
      budgetScore: 35,
      themeScore: 58,
      operatingHours: { status: 'known' },
    },
    {
      id: 102,
      name: '외암민속마을',
      address: '충남 아산',
      category: 'village',
      mobilityScore: 52,
      photoScore: 78,
      budgetScore: 45,
      themeScore: 40,
      operatingHours: { status: 'known' },
      metadataTags: { hiddenGem: true },
    },
    {
      id: 103,
      name: '대천해수욕장',
      address: '충남 보령',
      category: 'beach',
      mobilityScore: 60,
      photoScore: 82,
      budgetScore: 50,
      themeScore: 48,
      operatingHours: { status: 'known' },
    },
    {
      id: 104,
      name: '현충사',
      address: '충남 아산',
      category: 'history',
      mobilityScore: 65,
      photoScore: 55,
      budgetScore: 28,
      themeScore: 52,
      operatingHours: { status: 'known' },
    },
    {
      id: 105,
      name: '성주사지',
      address: '충남 보령',
      category: 'heritage',
      mobilityScore: 70,
      photoScore: 62,
      budgetScore: 32,
      themeScore: 46,
      operatingHours: { status: 'known' },
      metadataTags: ['hidden_gem'],
    },
    {
      id: 106,
      name: '천리포수목원',
      address: '충남 태안',
      category: 'garden',
      mobilityScore: 45,
      photoScore: 75,
      budgetScore: 42,
      themeScore: 20,
      operatingHours: { status: 'known' },
      metadataTags: { populationDeclineArea: true },
    },
  ];

  it('analyzes conflicts and common axes', () => {
    const analysis = service.analyzeGroup(members);

    expect(analysis.commonAxes).toContain('photo');
    expect(analysis.conflictAxes[0]).toMatchObject({ axis: 'mobility', severity: 'critical' });
    expect(analysis.priorityAxes[0]).toBe('mobility');
  });

  it('builds balanced, individual, and discovery options with satisfaction and hidden gems', () => {
    const options = service.buildScheduleOptions({
      roomId: 1,
      destination: '충남',
      tripDate: '2026-05-02',
      startTime: '09:00',
      endTime: '21:00',
      members,
      places,
    });

    expect(options.map((option) => option.optionType)).toEqual([
      ScheduleOptionType.BALANCED,
      ScheduleOptionType.INDIVIDUAL,
      ScheduleOptionType.DISCOVERY,
    ]);

    for (const option of options) {
      expect(option.slots.length).toBeGreaterThanOrEqual(5);
      expect(option.satisfactionByUser).toHaveLength(3);
      expect(option.groupSatisfaction).toBeGreaterThanOrEqual(55);
    }

    const discovery = options.find((option) => option.optionType === ScheduleOptionType.DISCOVERY);
    expect(discovery?.slots.some((slot) => slot.isHiddenGem)).toBe(true);
  });

  it('prioritizes restaurants for meal slots and avoids accommodations before the final slot', () => {
    const curatedPlaces: PlaceCandidate[] = [
      {
        id: 201,
        name: '호수공원 산책로',
        address: '충남 예산',
        category: 'tourist_attraction',
        mobilityScore: 58,
        photoScore: 62,
        budgetScore: 42,
        themeScore: 50,
        operatingHours: { status: 'known' },
        metadataTags: { contentTypeId: '12' },
      },
      {
        id: 202,
        name: '현대 아울렛',
        address: '충남 천안',
        category: 'shopping',
        mobilityScore: 55,
        photoScore: 60,
        budgetScore: 72,
        themeScore: 72,
        operatingHours: { status: 'known' },
        metadataTags: { contentTypeId: '38' },
      },
      {
        id: 203,
        name: '향토식당',
        address: '충남 공주',
        category: 'restaurant',
        mobilityScore: 46,
        photoScore: 52,
        budgetScore: 48,
        themeScore: 55,
        operatingHours: { status: 'known' },
        metadataTags: { contentTypeId: '39' },
      },
      {
        id: 204,
        name: '뷰맛집 카페',
        address: '충남 태안',
        category: 'restaurant',
        mobilityScore: 44,
        photoScore: 72,
        budgetScore: 52,
        themeScore: 58,
        operatingHours: { status: 'known' },
        metadataTags: { contentTypeId: '39' },
      },
      {
        id: 205,
        name: '오션 리조트',
        address: '충남 보령',
        category: 'accommodation',
        mobilityScore: 50,
        photoScore: 50,
        budgetScore: 50,
        themeScore: 50,
        operatingHours: { status: 'known' },
        metadataTags: { contentTypeId: '32' },
      },
      {
        id: 206,
        name: '문화예술관',
        address: '충남 천안',
        category: 'cultural_facility',
        mobilityScore: 42,
        photoScore: 58,
        budgetScore: 46,
        themeScore: 63,
        operatingHours: { status: 'known' },
        metadataTags: { contentTypeId: '14' },
      },
      {
        id: 207,
        name: '레포츠 파크',
        address: '충남 서산',
        category: 'leisure_sports',
        mobilityScore: 78,
        photoScore: 49,
        budgetScore: 55,
        themeScore: 45,
        operatingHours: { status: 'known' },
        metadataTags: { contentTypeId: '28' },
      },
    ];

    const options = service.buildScheduleOptions({
      roomId: 1,
      destination: '충남',
      tripDate: '2026-05-02',
      startTime: '09:00',
      endTime: '21:00',
      members,
      places: curatedPlaces,
    });

    const balanced = options.find((option) => option.optionType === ScheduleOptionType.BALANCED)!;
    const lunchSlot = balanced.slots.find((slot) => slot.orderIndex === 2)!;
    const finalSlot = balanced.slots.find((slot) => slot.orderIndex === balanced.slots.length)!;

    expect(curatedPlaces.find((place) => place.id === lunchSlot.placeId)?.category).toBe('restaurant');
    expect(
      balanced.slots
        .filter((slot) => slot.orderIndex < balanced.slots.length)
        .some((slot) => curatedPlaces.find((place) => place.id === slot.placeId)?.category === 'accommodation'),
    ).toBe(false);
    expect(['restaurant', 'shopping']).toContain(curatedPlaces.find((place) => place.id === finalSlot.placeId)?.category);
  });

  it('filters out date-mismatched festivals when generating schedules', () => {
    const datedPlaces: PlaceCandidate[] = [
      {
        id: 301,
        name: '벚꽃 축제',
        address: '충남 공주',
        category: 'festival',
        mobilityScore: 58,
        photoScore: 78,
        budgetScore: 55,
        themeScore: 68,
        operatingHours: { status: 'known' },
        metadataTags: {
          contentTypeId: '15',
          introFields: { eventstartdate: '20260501', eventenddate: '20260510' },
        },
      },
      {
        id: 302,
        name: '지난 축제',
        address: '충남 공주',
        category: 'festival',
        mobilityScore: 58,
        photoScore: 78,
        budgetScore: 55,
        themeScore: 68,
        operatingHours: { status: 'known' },
        metadataTags: {
          contentTypeId: '15',
          introFields: { eventstartdate: '20260401', eventenddate: '20260402' },
        },
      },
      {
        id: 303,
        name: '향토식당',
        address: '충남 공주',
        category: 'restaurant',
        mobilityScore: 46,
        photoScore: 50,
        budgetScore: 48,
        themeScore: 53,
        operatingHours: { status: 'known' },
        metadataTags: { contentTypeId: '39' },
      },
      {
        id: 304,
        name: '역사 유적지',
        address: '충남 공주',
        category: 'tourist_attraction',
        mobilityScore: 60,
        photoScore: 61,
        budgetScore: 42,
        themeScore: 55,
        operatingHours: { status: 'known' },
        metadataTags: { contentTypeId: '12' },
      },
      {
        id: 305,
        name: '중앙 시장',
        address: '충남 공주',
        category: 'shopping',
        mobilityScore: 55,
        photoScore: 57,
        budgetScore: 70,
        themeScore: 74,
        operatingHours: { status: 'known' },
        metadataTags: { contentTypeId: '38' },
      },
      {
        id: 306,
        name: '민속 박물관',
        address: '충남 공주',
        category: 'cultural_facility',
        mobilityScore: 43,
        photoScore: 55,
        budgetScore: 40,
        themeScore: 62,
        operatingHours: { status: 'known' },
        metadataTags: { contentTypeId: '14' },
      },
    ];

    const options = service.buildScheduleOptions({
      roomId: 1,
      destination: '충남',
      tripDate: '2026-05-02',
      startTime: '09:00',
      endTime: '21:00',
      members,
      places: datedPlaces,
    });

    for (const option of options) {
      expect(option.slots.some((slot) => slot.placeId === 302)).toBe(false);
    }
  });
});
