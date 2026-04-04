import { AuthProvider, ScheduleOptionType, TripRoomStatus } from '../src/common/enums/domain.enums';
import { ConflictService } from '../src/conflict/conflict.service';
import { ConsensusService } from '../src/consensus/consensus.service';
import { PlaceCandidate } from '../src/consensus/consensus.service';
import { RoomService } from '../src/room/room.service';
import { ScheduleService } from '../src/schedule/schedule.service';
import { TptiService } from '../src/tpti/tpti.service';

type StoredUser = {
  id: bigint;
  nickname: string;
  email?: string | null;
  authProvider: AuthProvider;
  isGuest: boolean;
};

const ACTIVE_DEL_YN = 'N';

function createPlaces(): Array<{
  id: bigint;
  name: string;
  address: string;
  category: string;
  latitude: number;
  longitude: number;
  mobilityScore: number;
  photoScore: number;
  budgetScore: number;
  themeScore: number;
  metadataTags: Record<string, unknown> | null;
  operatingHours: Record<string, unknown> | null;
  delYn: string;
}> {
  const places: PlaceCandidate[] = [
    {
      id: 101,
      name: '공산성',
      address: '충남 공주',
      category: 'tourist_attraction',
      latitude: 36.4626,
      longitude: 127.1194,
      mobilityScore: 88,
      photoScore: 70,
      budgetScore: 35,
      themeScore: 58,
      operatingHours: { status: 'always' },
      metadataTags: { contentTypeId: '12' },
    },
    {
      id: 102,
      name: '외암민속마을',
      address: '충남 아산',
      category: 'tourist_attraction',
      latitude: 36.7291,
      longitude: 126.9862,
      mobilityScore: 52,
      photoScore: 78,
      budgetScore: 45,
      themeScore: 40,
      operatingHours: { status: 'always' },
      metadataTags: { contentTypeId: '12', hiddenGem: true },
    },
    {
      id: 103,
      name: '향토식당',
      address: '충남 공주',
      category: 'restaurant',
      latitude: 36.4554,
      longitude: 127.1248,
      mobilityScore: 45,
      photoScore: 52,
      budgetScore: 48,
      themeScore: 55,
      operatingHours: {
        status: 'known',
        entries: [{ openMinutes: 600, closeMinutes: 840, closesNextDay: false, raw: '10:00~14:00', sourceKey: 'usetimefood' }],
      },
      metadataTags: { contentTypeId: '39' },
    },
    {
      id: 104,
      name: '뷰맛집 카페',
      address: '충남 태안',
      category: 'restaurant',
      latitude: 36.5035,
      longitude: 126.3381,
      mobilityScore: 44,
      photoScore: 72,
      budgetScore: 52,
      themeScore: 58,
      operatingHours: {
        status: 'known',
        entries: [{ openMinutes: 1020, closeMinutes: 1320, closesNextDay: false, raw: '17:00~22:00', sourceKey: 'usetimefood' }],
      },
      metadataTags: { contentTypeId: '39' },
    },
    {
      id: 105,
      name: '천리포수목원',
      address: '충남 태안',
      category: 'tourist_attraction',
      latitude: 36.7993,
      longitude: 126.1459,
      mobilityScore: 45,
      photoScore: 75,
      budgetScore: 42,
      themeScore: 20,
      operatingHours: { status: 'always' },
      metadataTags: { contentTypeId: '12', populationDeclineArea: true },
    },
    {
      id: 106,
      name: '전통시장',
      address: '충남 공주',
      category: 'shopping',
      latitude: 36.4568,
      longitude: 127.1197,
      mobilityScore: 55,
      photoScore: 57,
      budgetScore: 70,
      themeScore: 74,
      operatingHours: { status: 'always' },
      metadataTags: { contentTypeId: '38' },
    },
    {
      id: 107,
      name: '민속 박물관',
      address: '충남 공주',
      category: 'cultural_facility',
      latitude: 36.4645,
      longitude: 127.1083,
      mobilityScore: 43,
      photoScore: 55,
      budgetScore: 40,
      themeScore: 62,
      operatingHours: { status: 'always' },
      metadataTags: { contentTypeId: '14' },
    },
  ];

  return places.map((place) => ({
    ...place,
    id: BigInt(place.id),
    delYn: ACTIVE_DEL_YN,
  }));
}

function createPrismaStub(users: StoredUser[]) {
  const state = {
    tripRooms: [] as any[],
    roomMembers: [] as any[],
    roomMemberProfiles: [] as any[],
    tptiResults: [] as any[],
    conflictMaps: [] as any[],
    schedules: [] as any[],
    scheduleSlots: [] as any[],
    satisfactionScores: [] as any[],
    places: createPlaces(),
  };

  const ids = {
    room: 1n,
    roomMember: 1n,
    roomProfile: 1n,
    tptiResult: 1n,
    conflictMap: 1n,
    schedule: 1n,
    scheduleSlot: 1n,
    satisfaction: 1n,
  };

  const eq = (a: unknown, b: unknown) => String(a) === String(b);
  const active = <T extends { delYn?: string }>(items: T[]) => items.filter((item) => item.delYn !== 'Y');
  const findUser = (userId: bigint) => users.find((user) => eq(user.id, userId))!;

  const buildRoom = (room: any, include: any = {}) => ({
    ...room,
    hostUser: include.hostUser ? findUser(room.hostUserId) : undefined,
    members: include.members
      ? active(state.roomMembers)
          .filter((member) => eq(member.roomId, room.id))
          .map((member) => ({
            ...member,
            user: include.members.include?.user ? findUser(member.userId) : undefined,
          }))
          .sort((a, b) => a.joinedAt.getTime() - b.joinedAt.getTime())
      : undefined,
    memberProfiles: include.memberProfiles
      ? active(state.roomMemberProfiles)
          .filter((profile) => eq(profile.roomId, room.id))
          .map((profile) => ({
            ...profile,
            user: include.memberProfiles.include?.user ? findUser(profile.userId) : undefined,
          }))
          .sort((a, b) => a.createdAt.getTime() - b.createdAt.getTime())
      : undefined,
  });

  const buildSchedule = (schedule: any, include: any = {}) => ({
    ...schedule,
    room: include.room ? state.tripRooms.find((room) => eq(room.id, schedule.roomId)) : undefined,
    slots: include.slots
      ? active(state.scheduleSlots)
          .filter((slot) => eq(slot.scheduleId, schedule.id))
          .map((slot) => ({
            ...slot,
            place: include.slots.include?.place ? state.places.find((place) => eq(place.id, slot.placeId)) : undefined,
          }))
          .sort((a, b) => a.orderIndex - b.orderIndex)
      : undefined,
    satisfactionScores: include.satisfactionScores
      ? active(state.satisfactionScores)
          .filter((score) => eq(score.scheduleId, schedule.id))
          .sort((a, b) => Number(a.userId) - Number(b.userId))
      : undefined,
  });

  const prisma = {
    tripRoom: {
      create: async ({ data }: any) => {
        const room = {
          id: ids.room++,
          hostUserId: data.hostUserId,
          shareCode: data.shareCode,
          destination: data.destination,
          tripDate: data.tripDate,
          status: data.status,
          delYn: data.delYn,
          createdAt: new Date('2026-04-03T10:00:00+09:00'),
        };
        state.tripRooms.push(room);
        if (data.members?.create) {
          state.roomMembers.push({
            id: ids.roomMember++,
            roomId: room.id,
            userId: data.members.create.userId,
            role: data.members.create.role,
            delYn: data.members.create.delYn,
            joinedAt: new Date('2026-04-03T10:00:00+09:00'),
          });
        }
        return room;
      },
      findFirst: async ({ where, include }: any) => {
        const room = active(state.tripRooms).find((item) => {
          if (where?.id != null && !eq(item.id, where.id)) return false;
          if (where?.shareCode != null && item.shareCode !== where.shareCode) return false;
          return true;
        });
        return room ? buildRoom(room, include) : null;
      },
      update: async ({ where, data }: any) => {
        const room = state.tripRooms.find((item) => eq(item.id, where.id));
        Object.assign(room, data);
        return room;
      },
    },
    roomMember: {
      findFirst: async ({ where }: any) => {
        return active(state.roomMembers).find((item) => {
          if (where?.roomId != null && !eq(item.roomId, where.roomId)) return false;
          if (where?.userId != null && !eq(item.userId, where.userId)) return false;
          return true;
        }) ?? null;
      },
      create: async ({ data }: any) => {
        const record = {
          id: ids.roomMember++,
          roomId: data.roomId,
          userId: data.userId,
          role: data.role,
          delYn: data.delYn,
          joinedAt: new Date('2026-04-03T10:05:00+09:00'),
        };
        state.roomMembers.push(record);
        return record;
      },
    },
    roomMemberProfile: {
      count: async ({ where }: any) => {
        return active(state.roomMemberProfiles).filter((item) => eq(item.roomId, where.roomId)).length;
      },
      upsert: async ({ where, update, create }: any) => {
        const existing = active(state.roomMemberProfiles).find(
          (item) => eq(item.roomId, where.roomId_userId.roomId) && eq(item.userId, where.roomId_userId.userId),
        );
        if (existing) {
          Object.assign(existing, update);
          return existing;
        }
        const record = {
          id: ids.roomProfile++,
          createdAt: new Date('2026-04-03T10:10:00+09:00'),
          ...create,
        };
        state.roomMemberProfiles.push(record);
        return record;
      },
    },
    tptiResult: {
      create: async ({ data }: any) => {
        const record = {
          id: ids.tptiResult++,
          ...data,
          createdAt: new Date('2026-04-03T10:00:00+09:00'),
        };
        state.tptiResults.push(record);
        return record;
      },
      findFirst: async ({ where, orderBy }: any) => {
        let items = active(state.tptiResults).filter((item) => {
          if (where?.id != null && !eq(item.id, where.id)) return false;
          if (where?.userId != null && !eq(item.userId, where.userId)) return false;
          return true;
        });
        if (orderBy?.createdAt === 'desc') {
          items = [...items].sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime());
        }
        return items[0] ?? null;
      },
    },
    conflictMap: {
      create: async ({ data }: any) => {
        const record = {
          id: ids.conflictMap++,
          createdAt: new Date('2026-04-03T11:00:00+09:00'),
          ...data,
        };
        state.conflictMaps.push(record);
        return record;
      },
    },
    place: {
      findMany: async () => active(state.places),
    },
    schedule: {
      findFirst: async ({ where, orderBy, include }: any) => {
        let items = active(state.schedules).filter((item) => {
          if (where?.id != null && !eq(item.id, where.id)) return false;
          if (where?.roomId != null && !eq(item.roomId, where.roomId)) return false;
          if (where?.version != null && item.version !== where.version) return false;
          if (where?.optionType != null && item.optionType !== where.optionType) return false;
          return true;
        });
        if (orderBy?.version === 'desc') {
          items = [...items].sort((a, b) => b.version - a.version);
        }
        const schedule = items[0] ?? null;
        return schedule ? buildSchedule(schedule, include) : null;
      },
      updateMany: async ({ where, data }: any) => {
        const targets = active(state.schedules).filter((item) => {
          if (where?.roomId != null && !eq(item.roomId, where.roomId)) return false;
          return true;
        });
        for (const target of targets) {
          Object.assign(target, data);
        }
        return { count: targets.length };
      },
      update: async ({ where, data }: any) => {
        const target = state.schedules.find((item) => eq(item.id, where.id));
        Object.assign(target, data);
        return target;
      },
      create: async ({ data }: any) => {
        const record = {
          id: ids.schedule++,
          createdAt: new Date('2026-04-03T11:10:00+09:00'),
          ...data,
        };
        state.schedules.push(record);
        return record;
      },
    },
    scheduleSlot: {
      createMany: async ({ data }: any) => {
        for (const row of data) {
          state.scheduleSlots.push({
            id: ids.scheduleSlot++,
            ...row,
          });
        }
        return { count: data.length };
      },
    },
    satisfactionScore: {
      createMany: async ({ data }: any) => {
        for (const row of data) {
          state.satisfactionScores.push({
            id: ids.satisfaction++,
            createdAt: new Date('2026-04-03T11:10:00+09:00'),
            ...row,
          });
        }
        return { count: data.length };
      },
    },
    $transaction: async (input: any) => {
      if (typeof input === 'function') {
        return input({
          schedule: prisma.schedule,
          scheduleSlot: prisma.scheduleSlot,
          satisfactionScore: prisma.satisfactionScore,
        });
      }
      return Promise.all(input);
    },
  };

  return { prisma, state };
}

describe('Travel flow integration', () => {
  const users: StoredUser[] = [
    { id: 1n, nickname: '호스트', email: 'host@example.com', authProvider: AuthProvider.LOCAL, isGuest: false },
    { id: 2n, nickname: '민지', email: 'minji@example.com', authProvider: AuthProvider.GUEST, isGuest: true },
    { id: 3n, nickname: '지훈', email: 'jihun@example.com', authProvider: AuthProvider.GUEST, isGuest: true },
  ];

  const authService = {
    assertHostUser: jest.fn((user: StoredUser) => {
      if (user.isGuest) {
        throw new Error('host only');
      }
    }),
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('runs room creation, TPTI, conflict map, schedule generation, and confirmation in one flow', async () => {
    const { prisma, state } = createPrismaStub(users);
    const consensusService = new ConsensusService({ refineScheduleOption: jest.fn().mockResolvedValue(null) } as any);
    const roomService = new RoomService(authService as any, prisma as any);
    const tptiService = new TptiService(authService as any, prisma as any);
    const conflictService = new ConflictService(authService as any, consensusService, prisma as any);
    const scheduleService = new ScheduleService(authService as any, consensusService, prisma as any);

    const createRoomResult = await roomService.createRoom(
      {
        destination: '충남',
        tripDate: '2026-05-02',
      },
      users[0] as any,
    );

    const roomId = createRoomResult.data!.roomId;
    const shareCode = createRoomResult.data!.shareCode;

    const hostTpti = await tptiService.submitResult({ answers: [5, 1, 4, 2, 2, 4, 3, 4] }, users[0] as any);
    const guest1Tpti = await tptiService.submitResult({ answers: [2, 4, 5, 1, 3, 3, 4, 2] }, users[1] as any);
    const guest2Tpti = await tptiService.submitResult({ answers: [4, 2, 3, 3, 5, 1, 2, 5] }, users[2] as any);

    await roomService.joinRoom(shareCode, { tptiResultId: hostTpti.data!.resultId }, users[0] as any);
    await roomService.joinRoom(shareCode, { tptiResultId: guest1Tpti.data!.resultId }, users[1] as any);
    await roomService.joinRoom(shareCode, { tptiResultId: guest2Tpti.data!.resultId }, users[2] as any);

    const room = await roomService.getRoom(roomId, users[0] as any);
    expect(room.data?.status).toBe(TripRoomStatus.READY);

    const conflictMap = await conflictService.getConflictMap(roomId, users[0] as any);
    expect(conflictMap.data?.members).toHaveLength(3);
    expect(conflictMap.data?.conflictAxes.length).toBeGreaterThan(0);

    const generated = await scheduleService.generateSchedule(
      roomId,
      {
        destination: '충남',
        tripDate: '2026-05-02',
        startTime: '09:00',
        endTime: '21:00',
      },
      users[0] as any,
    );

    expect(generated.data?.options).toHaveLength(3);
    expect(generated.data?.options.map((option) => option.optionType)).toEqual([
      ScheduleOptionType.BALANCED,
      ScheduleOptionType.INDIVIDUAL,
      ScheduleOptionType.DISCOVERY,
    ]);
    expect(generated.data?.options[0]?.slots?.[0]?.place).toMatchObject({
      latitude: expect.any(Number),
      longitude: expect.any(Number),
    });

    const confirmed = await scheduleService.confirmSchedule(
      roomId,
      { optionType: ScheduleOptionType.BALANCED },
      users[0] as any,
    );

    expect(confirmed.data).toEqual({
      scheduleId: expect.any(Number),
      roomId,
      optionType: ScheduleOptionType.BALANCED,
      status: 'confirmed',
    });
    expect(state.schedules.filter((schedule) => schedule.isConfirmed)).toHaveLength(1);
    expect(state.schedules.find((schedule) => schedule.isConfirmed)?.optionType).toBe(ScheduleOptionType.BALANCED);
    expect(state.scheduleSlots.length).toBeGreaterThanOrEqual(15);
  });
});
