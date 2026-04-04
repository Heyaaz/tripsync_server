import { ScheduleService } from '../src/schedule/schedule.service';
import { ScheduleOptionType, TripRoomStatus } from '../src/common/enums/domain.enums';

describe('ScheduleService', () => {
  const authService = {
    requireSessionUser: jest.fn(),
    assertHostUser: jest.fn(),
  };

  const prisma = {
    tripRoom: {
      findFirst: jest.fn(),
      update: jest.fn(),
    },
    roomMember: {
      findFirst: jest.fn(),
    },
    place: {
      findMany: jest.fn(),
    },
    schedule: {
      findFirst: jest.fn(),
      updateMany: jest.fn(),
      update: jest.fn(),
    },
    scheduleSlot: {
      createMany: jest.fn(),
    },
    satisfactionScore: {
      createMany: jest.fn(),
    },
    $transaction: jest.fn(),
  };

  const consensusService = {
    buildScheduleOptions: jest.fn(),
  };

  const service = new ScheduleService(authService as any, consensusService as any, prisma as any);

  beforeEach(() => {
    jest.clearAllMocks();
    authService.requireSessionUser.mockResolvedValue({ id: BigInt(1), isGuest: false });
    authService.assertHostUser.mockImplementation(() => undefined);
  });

  it('generates and stores three option schedules for the next version', async () => {
    prisma.tripRoom.findFirst.mockResolvedValue({
      id: BigInt(10),
      hostUserId: BigInt(1),
      destination: '충남',
      status: TripRoomStatus.READY,
      members: [],
      memberProfiles: [
        { userId: BigInt(1), user: { nickname: '민지' }, mobilityScore: 90, photoScore: 70, budgetScore: 30, themeScore: 60 },
        { userId: BigInt(2), user: { nickname: '지훈' }, mobilityScore: 20, photoScore: 65, budgetScore: 35, themeScore: 58 },
      ],
    });
    prisma.place.findMany.mockResolvedValue([
      {
        id: BigInt(101),
        name: '공산성',
        address: '충남 공주',
        category: 'history',
        mobilityScore: 80,
        photoScore: 70,
        budgetScore: 35,
        themeScore: 50,
        latitude: 36.4626,
        longitude: 127.1194,
        metadataTags: null,
        operatingHours: { status: 'known' },
      },
    ]);
    prisma.schedule.findFirst.mockResolvedValue(null);

    const options = [
      {
        optionType: ScheduleOptionType.BALANCED,
        label: '균형형',
        summary: '요약',
        groupSatisfaction: 70,
        slots: [
          {
            orderIndex: 1,
            slotType: 'common',
            targetUserId: null,
            reasonAxis: 'common',
            reasonText: '그룹 공통 지대 반영',
            startTime: new Date('2026-05-02T09:00:00+09:00'),
            endTime: new Date('2026-05-02T11:00:00+09:00'),
            placeId: 101,
            placeName: '공산성',
            placeAddress: '충남 공주',
            isHiddenGem: false,
          },
        ],
        satisfactionByUser: [
          { userId: 1, score: 72, breakdown: { overall: 72, byAxis: { mobility: 0.8, photo: 0.7, budget: 0.6, theme: 0.7 } } },
          { userId: 2, score: 68, breakdown: { overall: 68, byAxis: { mobility: 0.6, photo: 0.7, budget: 0.7, theme: 0.7 } } },
        ],
      },
      {
        optionType: ScheduleOptionType.INDIVIDUAL,
        label: '개성형',
        summary: '요약',
        groupSatisfaction: 66,
        slots: [],
        satisfactionByUser: [],
      },
      {
        optionType: ScheduleOptionType.DISCOVERY,
        label: '지역 발굴형',
        summary: '요약',
        groupSatisfaction: 60,
        slots: [],
        satisfactionByUser: [],
      },
    ];
    consensusService.buildScheduleOptions.mockReturnValue(options);

    prisma.$transaction.mockImplementation(async (callbackOrOperations: any) => {
      if (typeof callbackOrOperations === 'function') {
        let id = 5000;
        return callbackOrOperations({
          schedule: {
            create: jest.fn().mockImplementation(async ({ data }: any) => ({ id: BigInt(++id), ...data })),
          },
          scheduleSlot: {
            createMany: jest.fn().mockResolvedValue({ count: 1 }),
          },
          satisfactionScore: {
            createMany: jest.fn().mockResolvedValue({ count: 2 }),
          },
        });
      }
      return callbackOrOperations;
    });

    const result = await service.generateSchedule(
      10,
      {
        destination: '충남',
        tripDate: '2026-05-02',
        startTime: '09:00',
        endTime: '21:00',
      },
      { id: BigInt(1), isGuest: false } as any,
    );

    expect(consensusService.buildScheduleOptions).toHaveBeenCalled();
    expect(result.data?.version).toBe(1);
    expect(result.data?.options).toHaveLength(3);
    expect(result.data?.options[0]).toMatchObject({
      scheduleId: 5001,
      optionType: ScheduleOptionType.BALANCED,
      slots: [
        {
          orderIndex: 1,
          targetNickname: null,
          reason: '그룹 공통 지대 반영',
          place: {
            id: 101,
            name: '공산성',
            address: '충남 공주',
            latitude: 36.4626,
            longitude: 127.1194,
            isDepopulationArea: false,
          },
        },
      ],
      satisfactionByUser: [
        { userId: 1, nickname: '민지', score: 72 },
        { userId: 2, nickname: '지훈', score: 68 },
      ],
    });
    expect(prisma.tripRoom.update).toHaveBeenCalledWith({
      where: { id: BigInt(10) },
      data: { status: TripRoomStatus.COMPLETED },
    });
  });

  it('confirms the selected latest option', async () => {
    prisma.tripRoom.findFirst.mockResolvedValue({
      id: BigInt(10),
      hostUserId: BigInt(1),
      memberProfiles: [],
      members: [],
    });
    prisma.schedule.findFirst
      .mockResolvedValueOnce({ id: BigInt(6001), roomId: BigInt(10), version: 2 })
      .mockResolvedValueOnce({ id: BigInt(6003), roomId: BigInt(10), version: 2, optionType: ScheduleOptionType.DISCOVERY });
    prisma.schedule.updateMany.mockResolvedValue({ count: 3 });
    prisma.schedule.update.mockResolvedValue({ id: BigInt(6003) });
    prisma.$transaction.mockImplementation(async (operations: any) => operations);

    const result = await service.confirmSchedule(
      10,
      { optionType: ScheduleOptionType.DISCOVERY },
      { id: BigInt(1), isGuest: false } as any,
    );

    expect(result.data).toEqual({
      scheduleId: 6003,
      roomId: 10,
      optionType: ScheduleOptionType.DISCOVERY,
      status: 'confirmed',
    });
    expect(prisma.schedule.updateMany).toHaveBeenCalledWith({
      where: { roomId: BigInt(10), delYn: 'N' },
      data: { isConfirmed: false },
    });
    expect(prisma.schedule.update).toHaveBeenCalled();
  });

  it('includes room summary and place coordinates in public share responses', async () => {
    prisma.schedule.findFirst.mockResolvedValue({
      id: BigInt(7001),
      optionType: ScheduleOptionType.BALANCED,
      summary: '모두가 조금씩 만족하는 일정',
      groupSatisfaction: 74,
      room: {
        destination: '충남',
        tripDate: '2026-05-02',
      },
      slots: [
        {
          orderIndex: 1,
          startTime: new Date('2026-05-02T09:00:00+09:00'),
          endTime: new Date('2026-05-02T11:00:00+09:00'),
          place: {
            id: BigInt(101),
            name: '공산성',
            address: '충남 공주',
            category: 'history',
            latitude: 36.4626,
            longitude: 127.1194,
            metadataTags: null,
          },
        },
      ],
    });

    const result = await service.getPublicShareSchedule(7001);

    expect(result.data).toEqual({
      scheduleId: 7001,
      destination: '충남',
      tripDate: '2026-05-02',
      optionType: ScheduleOptionType.BALANCED,
      summary: '모두가 조금씩 만족하는 일정',
      groupSatisfaction: 74,
      slots: [
        {
          orderIndex: 1,
          startTime: '00:00',
          endTime: '02:00',
          placeName: '공산성',
          place: {
            id: 101,
            name: '공산성',
            address: '충남 공주',
            category: 'history',
            latitude: 36.4626,
            longitude: 127.1194,
            isDepopulationArea: false,
          },
        },
      ],
    });
  });
});
