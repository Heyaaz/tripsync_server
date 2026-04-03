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
});
