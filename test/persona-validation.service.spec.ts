import { PersonaValidationService } from '../src/persona-validation/persona-validation.service';
import * as fs from 'fs';

jest.mock('fs');

describe('PersonaValidationService', () => {
  const service = new PersonaValidationService();
  const consoleSpy = jest.spyOn(console, 'log').mockImplementation();
  const consoleWarnSpy = jest.spyOn(console, 'warn').mockImplementation();

  beforeEach(() => {
    jest.clearAllMocks();
    (service as any).personaPool = new Map();
  });

  describe('loadPersonaVectors', () => {
    it('loads vectors from file successfully', async () => {
      const mockVectors = {
        'uuid-1': { mobility: 80, photo: 60, budget: 40, theme: 70 },
        'uuid-2': { mobility: 30, photo: 90, budget: 20, theme: 50 },
      };

      (fs.existsSync as jest.Mock).mockReturnValue(true);
      (fs.readFileSync as jest.Mock).mockReturnValue(JSON.stringify(mockVectors));

      await (service as any).loadPersonaVectors();

      expect((service as any).personaPool.size).toBe(2);
      expect((service as any).personaPool.get('uuid-1')).toEqual({
        mobility: 80,
        photo: 60,
        budget: 40,
        theme: 70,
      });
    });

    it('skips loading when file does not exist', async () => {
      (fs.existsSync as jest.Mock).mockReturnValue(false);

      await (service as any).loadPersonaVectors();

      expect((service as any).personaPool.size).toBe(0);
    });

    it('handles file read errors gracefully', async () => {
      (fs.existsSync as jest.Mock).mockReturnValue(true);
      (fs.readFileSync as jest.Mock).mockImplementation(() => {
        throw new Error('Permission denied');
      });

      await expect((service as any).loadPersonaVectors()).resolves.not.toThrow();
      expect((service as any).personaPool.size).toBe(0);
    });
  });

  describe('validateOptions', () => {
    const members = [
      {
        userId: 1,
        nickname: '민지',
        scores: { mobility: 90, photo: 70, budget: 30, theme: 60 },
      },
      {
        userId: 2,
        nickname: '지훈',
        scores: { mobility: 20, photo: 65, budget: 35, theme: 58 },
      },
    ];

    const options = [
      {
        optionType: 'balanced',
        label: '균형형',
        summary: '모두가 만족하는 코스',
        groupSatisfaction: 75,
        slots: [
          {
            orderIndex: 1,
            slotType: 'common',
            targetUserId: null,
            reasonAxis: 'photo',
            startTime: new Date('2026-05-02T09:00:00'),
            endTime: new Date('2026-05-02T11:00:00'),
            placeId: 101,
          },
        ],
      },
      {
        optionType: 'individual',
        label: '개성형',
        summary: '취향 존중 코스',
        groupSatisfaction: 70,
        slots: [
          {
            orderIndex: 1,
            slotType: 'personal',
            targetUserId: 1,
            reasonAxis: 'mobility',
            startTime: new Date('2026-05-02T09:00:00'),
            endTime: new Date('2026-05-02T11:00:00'),
            placeId: 102,
          },
        ],
      },
    ];

    it('returns empty map when persona pool is empty', () => {
      const results = service.validateOptions(options, members);
      expect(results.size).toBe(0);
    });

    it('validates all options and returns results', () => {
      const mockPool = new Map([
        ['uuid-1', { mobility: 85, photo: 65, budget: 35, theme: 55 }],
        ['uuid-2', { mobility: 25, photo: 70, budget: 40, theme: 60 }],
        ['uuid-3', { mobility: 50, photo: 50, budget: 50, theme: 50 }],
        ['uuid-4', { mobility: 90, photo: 80, budget: 20, theme: 40 }],
        ['uuid-5', { mobility: 15, photo: 60, budget: 30, theme: 70 }],
        ['uuid-6', { mobility: 70, photo: 40, budget: 60, theme: 30 }],
      ]);
      (service as any).personaPool = mockPool;

      const results = service.validateOptions(options, members);

      expect(results.size).toBe(2);
      expect(results.has('balanced')).toBe(true);
      expect(results.has('individual')).toBe(true);

      const balanced = results.get('balanced')!;
      expect(balanced.source).toBe('synthetic_research');
      expect(balanced.dataset).toBe('nvidia/Nemotron-Personas-Korea');
      expect(balanced.matchedPersonaCount).toBe(members.length * 3);
      expect(balanced.personaAcceptanceScore).toBeGreaterThanOrEqual(0);
      expect(balanced.personaAcceptanceScore).toBeLessThanOrEqual(100);
      expect(balanced.topPositiveSignals).toBeInstanceOf(Array);
      expect(balanced.objectionReasons).toBeInstanceOf(Array);
      expect(balanced.persuasionPoints).toBeInstanceOf(Array);
      expect(balanced.matchedPersonas).toBeInstanceOf(Array);
      expect(balanced.matchedPersonas!.length).toBeLessThanOrEqual(5);
    });
  });

  describe('findSimilarPersonas', () => {
    beforeEach(() => {
      const mockPool = new Map([
        ['high-mobility', { mobility: 90, photo: 50, budget: 50, theme: 50 }],
        ['low-mobility', { mobility: 10, photo: 50, budget: 50, theme: 50 }],
        ['mid-mobility', { mobility: 50, photo: 50, budget: 50, theme: 50 }],
        ['high-photo', { mobility: 50, photo: 90, budget: 50, theme: 50 }],
        ['balanced', { mobility: 50, photo: 50, budget: 50, theme: 50 }],
      ]);
      (service as any).personaPool = mockPool;
    });

    it('finds top-k similar personas based on L1 distance', () => {
      const member = {
        userId: 1,
        nickname: '활동형',
        scores: { mobility: 85, photo: 55, budget: 45, theme: 50 },
      };

      const matches = (service as any).findSimilarPersonas(member, 3);

      expect(matches.length).toBe(3);
      expect(matches[0].matchedUserId).toBe(1);
      expect(matches[0].similarity).toBeGreaterThan(0.8);
      expect(matches[0].personaSummary).toBeTruthy();
      expect(matches[0].scores).toBeDefined();
    });

    it('returns results sorted by similarity descending', () => {
      const member = {
        userId: 2,
        nickname: '사진형',
        scores: { mobility: 50, photo: 85, budget: 50, theme: 50 },
      };

      const matches = (service as any).findSimilarPersonas(member, 3);

      for (let i = 0; i < matches.length - 1; i++) {
        expect(matches[i].similarity).toBeGreaterThanOrEqual(matches[i + 1].similarity);
      }
    });

    it('limits results to topK', () => {
      const member = {
        userId: 3,
        nickname: '테스트',
        scores: { mobility: 50, photo: 50, budget: 50, theme: 50 },
      };

      const matches3 = (service as any).findSimilarPersonas(member, 3);
      const matches2 = (service as any).findSimilarPersonas(member, 2);

      expect(matches3.length).toBe(3);
      expect(matches2.length).toBe(2);
    });
  });

  describe('calculateAcceptanceScore', () => {
    beforeEach(() => {
      const mockPool = new Map([
        ['uuid-1', { mobility: 80, photo: 60, budget: 40, theme: 70 }],
        ['uuid-2', { mobility: 30, photo: 70, budget: 50, theme: 60 }],
      ]);
      (service as any).personaPool = mockPool;
    });

    it('returns 0 when no personas provided', () => {
      const option = {
        optionType: 'balanced',
        label: '균형형',
        summary: '테스트',
        groupSatisfaction: 70,
        slots: [],
      };

      const score = (service as any).calculateAcceptanceScore(option, []);
      expect(score).toBe(0);
    });

    it('calculates score for balanced option', () => {
      const option = {
        optionType: 'balanced',
        label: '균형형',
        summary: '테스트',
        groupSatisfaction: 70,
        slots: [
          {
            orderIndex: 1,
            slotType: 'common',
            targetUserId: null,
            reasonAxis: 'photo',
            startTime: new Date(),
            endTime: new Date(),
            placeId: 101,
          },
        ],
      };

      const personas = [
        {
          matchedUserId: 1,
          similarity: 0.9,
          personaSummary: '활동형',
          scores: { mobility: 80, photo: 60, budget: 40, theme: 70 },
        },
      ];

      const score = (service as any).calculateAcceptanceScore(option, personas);
      expect(score).toBeGreaterThan(0);
      expect(score).toBeLessThanOrEqual(100);
    });

    it('uses persona scores for non-balanced options', () => {
      const option = {
        optionType: 'individual',
        label: '개성형',
        summary: '테스트',
        groupSatisfaction: 70,
        slots: [
          {
            orderIndex: 1,
            slotType: 'personal',
            targetUserId: 1,
            reasonAxis: 'mobility',
            startTime: new Date(),
            endTime: new Date(),
            placeId: 102,
          },
        ],
      };

      const personas = [
        {
          matchedUserId: 1,
          similarity: 0.9,
          personaSummary: '활동형',
          scores: { mobility: 80, photo: 60, budget: 40, theme: 70 },
        },
      ];

      const score = (service as any).calculateAcceptanceScore(option, personas);
      expect(score).toBeGreaterThan(0);
    });
  });

  describe('generatePositiveSignals', () => {
    it('generates signals for balanced option', () => {
      const option = {
        optionType: 'balanced',
        label: '균형형',
        summary: '테스트',
        groupSatisfaction: 75,
        slots: [],
      };

      const personas = [
        { matchedUserId: 1, similarity: 0.9, personaSummary: 'test', scores: { mobility: 50, photo: 50, budget: 30, theme: 50 } },
      ];

      const signals = (service as any).generatePositiveSignals(option, personas);
      expect(signals.length).toBeGreaterThan(0);
      expect(signals[0]).toContain('묂한');
    });

    it('generates signals for discovery option', () => {
      const option = {
        optionType: 'discovery',
        label: '지역발굴형',
        summary: '테스트',
        groupSatisfaction: 70,
        slots: [],
      };

      const personas = [
        { matchedUserId: 1, similarity: 0.9, personaSummary: 'test', scores: { mobility: 50, photo: 50, budget: 50, theme: 50 } },
      ];

      const signals = (service as any).generatePositiveSignals(option, personas);
      expect(signals.some((s: string) => s.includes('호기심'))).toBe(true);
    });
  });

  describe('generateObjections', () => {
    it('generates objection for high mobility demand with low mobility personas', () => {
      const option = {
        optionType: 'balanced',
        label: '균형형',
        summary: '테스트',
        groupSatisfaction: 70,
        slots: [
          { orderIndex: 1, slotType: 'common', targetUserId: null, reasonAxis: 'mobility', startTime: new Date(), endTime: new Date(), placeId: 101 },
          { orderIndex: 2, slotType: 'common', targetUserId: null, reasonAxis: 'photo', startTime: new Date(), endTime: new Date(), placeId: 102 },
          { orderIndex: 3, slotType: 'common', targetUserId: null, reasonAxis: 'budget', startTime: new Date(), endTime: new Date(), placeId: 103 },
          { orderIndex: 4, slotType: 'common', targetUserId: null, reasonAxis: 'theme', startTime: new Date(), endTime: new Date(), placeId: 104 },
          { orderIndex: 5, slotType: 'common', targetUserId: null, reasonAxis: 'mobility', startTime: new Date(), endTime: new Date(), placeId: 105 },
          { orderIndex: 6, slotType: 'common', targetUserId: null, reasonAxis: 'photo', startTime: new Date(), endTime: new Date(), placeId: 106 },
        ],
      };

      const personas = [
        { matchedUserId: 1, similarity: 0.9, personaSummary: 'test', scores: { mobility: 20, photo: 50, budget: 50, theme: 50 } },
      ];

      const objections = (service as any).generateObjections(option, personas);
      expect(objections.some((o: string) => o.includes('휴식형'))).toBe(true);
    });
  });

  describe('generatePersuasionPoints', () => {
    it('generates persuasion points for individual option', () => {
      const option = {
        optionType: 'individual',
        label: '개성형',
        summary: '테스트',
        groupSatisfaction: 70,
        slots: [],
      };

      const objections: string[] = [];

      const points = (service as any).generatePersuasionPoints(option, objections);
      expect(points.some((p: string) => p.includes('교대 배분'))).toBe(true);
    });

    it('adds rest suggestion when objections exist', () => {
      const option = {
        optionType: 'balanced',
        label: '균형형',
        summary: '테스트',
        groupSatisfaction: 70,
        slots: [],
      };

      const objections = ['이동이 많음'];

      const points = (service as any).generatePersuasionPoints(option, objections);
      expect(points.some((p: string) => p.includes('휴식'))).toBe(true);
    });
  });

  describe('getPersonaSummary', () => {
    it('returns consistent summary for same uuid', () => {
      const summary1 = (service as any).getPersonaSummary('test-uuid-1');
      const summary2 = (service as any).getPersonaSummary('test-uuid-1');
      expect(summary1).toBe(summary2);
    });

    it('returns different summaries for different uuids', () => {
      const summary1 = (service as any).getPersonaSummary('uuid-a');
      const summary2 = (service as any).getPersonaSummary('uuid-b');
      expect(summary1).not.toBe(summary2);
    });

    it('returns one of predefined summaries', () => {
      const summary = (service as any).getPersonaSummary('any-uuid');
      const validSummaries = [
        '활동적인 여행을 선호하는 직장인',
        '자연과 힐링을 찾는 가족 여행자',
        '도심 핫플을 즐기는 20대 여행자',
        '역사와 문화를 탐방하는 여행자',
        '사진과 SNS 공유를 즐기는 여행자',
        '가성비와 실속을 중시하는 여행자',
        '럭셔리와 프리미엄을 추구하는 여행자',
        '조용한 소도시를 찾는 은퇴 여행자',
      ];
      expect(validSummaries).toContain(summary);
    });
  });

  describe('edge cases and error handling', () => {
    it('handles empty slots without division by zero', () => {
      const option = {
        optionType: 'balanced',
        label: '균형형',
        summary: '테스트',
        groupSatisfaction: 70,
        slots: [],
      };

      const personas = [
        { matchedUserId: 1, similarity: 0.9, personaSummary: 'test', scores: { mobility: 80, photo: 60, budget: 40, theme: 70 } },
      ];

      const score = (service as any).calculateAcceptanceScore(option, personas);
      expect(score).toBe(0);
    });

    it('handles empty personas in generateObjections', () => {
      const option = {
        optionType: 'balanced',
        label: '균형형',
        summary: '테스트',
        groupSatisfaction: 70,
        slots: [
          { orderIndex: 1, slotType: 'common', targetUserId: null, reasonAxis: 'mobility', startTime: new Date(), endTime: new Date(), placeId: 101 },
          { orderIndex: 2, slotType: 'common', targetUserId: null, reasonAxis: 'photo', startTime: new Date(), endTime: new Date(), placeId: 102 },
          { orderIndex: 3, slotType: 'common', targetUserId: null, reasonAxis: 'budget', startTime: new Date(), endTime: new Date(), placeId: 103 },
          { orderIndex: 4, slotType: 'common', targetUserId: null, reasonAxis: 'theme', startTime: new Date(), endTime: new Date(), placeId: 104 },
          { orderIndex: 5, slotType: 'common', targetUserId: null, reasonAxis: 'mobility', startTime: new Date(), endTime: new Date(), placeId: 105 },
          { orderIndex: 6, slotType: 'common', targetUserId: null, reasonAxis: 'photo', startTime: new Date(), endTime: new Date(), placeId: 106 },
        ],
      };

      const objections = (service as any).generateObjections(option, []);
      expect(objections).toEqual([]);
    });

    it('handles empty members array', () => {
      const mockPool = new Map([
        ['uuid-1', { mobility: 80, photo: 60, budget: 40, theme: 70 }],
      ]);
      (service as any).personaPool = mockPool;

      const options = [
        {
          optionType: 'balanced',
          label: '균형형',
          summary: '테스트',
          groupSatisfaction: 70,
          slots: [],
        },
      ];

      const results = service.validateOptions(options, []);
      expect(results.size).toBe(1);
      const result = results.get('balanced')!;
      expect(result.personaAcceptanceScore).toBe(0);
      expect(result.matchedPersonaCount).toBe(0);
    });

    it('handles discovery option type in getSlotTargetVector', () => {
      const option = {
        optionType: 'discovery',
        label: '지역발굴형',
        summary: '테스트',
        groupSatisfaction: 65,
        slots: [
          {
            orderIndex: 1,
            slotType: 'common',
            targetUserId: null,
            reasonAxis: 'theme',
            startTime: new Date(),
            endTime: new Date(),
            placeId: 103,
          },
        ],
      };

      const persona = {
        matchedUserId: 1,
        similarity: 0.9,
        personaSummary: 'test',
        scores: { mobility: 30, photo: 70, budget: 50, theme: 80 },
      };

      const targetVector = (service as any).getSlotTargetVector(
        option.slots[0],
        option,
        persona,
      );
      expect(targetVector).toEqual(persona.scores);
    });

    it('does not generate low budget signal when budget is high', () => {
      const option = {
        optionType: 'balanced',
        label: '균형형',
        summary: '테스트',
        groupSatisfaction: 75,
        slots: [],
      };

      const personas = [
        { matchedUserId: 1, similarity: 0.9, personaSummary: 'test', scores: { mobility: 50, photo: 50, budget: 60, theme: 50 } },
      ];

      const signals = (service as any).generatePositiveSignals(option, personas);
      expect(signals.some((s: string) => s.includes('가성비'))).toBe(false);
    });

    it('does not generate mobility objection when avgMobility is high', () => {
      const option = {
        optionType: 'balanced',
        label: '균형형',
        summary: '테스트',
        groupSatisfaction: 70,
        slots: [
          { orderIndex: 1, slotType: 'common', targetUserId: null, reasonAxis: 'mobility', startTime: new Date(), endTime: new Date(), placeId: 101 },
          { orderIndex: 2, slotType: 'common', targetUserId: null, reasonAxis: 'photo', startTime: new Date(), endTime: new Date(), placeId: 102 },
          { orderIndex: 3, slotType: 'common', targetUserId: null, reasonAxis: 'budget', startTime: new Date(), endTime: new Date(), placeId: 103 },
          { orderIndex: 4, slotType: 'common', targetUserId: null, reasonAxis: 'theme', startTime: new Date(), endTime: new Date(), placeId: 104 },
          { orderIndex: 5, slotType: 'common', targetUserId: null, reasonAxis: 'mobility', startTime: new Date(), endTime: new Date(), placeId: 105 },
          { orderIndex: 6, slotType: 'common', targetUserId: null, reasonAxis: 'photo', startTime: new Date(), endTime: new Date(), placeId: 106 },
        ],
      };

      const personas = [
        { matchedUserId: 1, similarity: 0.9, personaSummary: 'test', scores: { mobility: 80, photo: 50, budget: 50, theme: 50 } },
      ];

      const objections = (service as any).generateObjections(option, personas);
      expect(objections.some((o: string) => o.includes('휴식형'))).toBe(false);
    });

    it('generates persuasion points for discovery option with objections', () => {
      const option = {
        optionType: 'discovery',
        label: '지역발굴형',
        summary: '테스트',
        groupSatisfaction: 65,
        slots: [],
      };

      const objections = ['이동이 많음'];

      const points = (service as any).generatePersuasionPoints(option, objections);
      expect(points.some((p: string) => p.includes('희소성'))).toBe(true);
      expect(points.some((p: string) => p.includes('휴식'))).toBe(true);
    });

    it('handles onModuleInit when file is missing', async () => {
      (fs.existsSync as jest.Mock).mockReturnValue(false);
      const newService = new PersonaValidationService();
      await (newService as any).onModuleInit();
      expect((newService as any).personaPool.size).toBe(0);
    });

    it('catches errors in validateOption and returns empty result', () => {
      const mockPool = new Map([
        ['uuid-1', { mobility: 80, photo: 60, budget: 40, theme: 70 }],
      ]);
      (service as any).personaPool = mockPool;

      const options = [
        {
          optionType: 'balanced',
          label: '균형형',
          summary: '테스트',
          groupSatisfaction: 70,
          slots: null,
        },
      ];

      const members = [
        { userId: 1, nickname: 'test', scores: { mobility: 50, photo: 50, budget: 50, theme: 50 } },
      ];

      const results = service.validateOptions(options as any, members);
      expect(results.size).toBe(1);
      const result = results.get('balanced')!;
      expect(result.personaAcceptanceScore).toBe(0);
      expect(result.matchedPersonaCount).toBe(0);
    });
  });
});
