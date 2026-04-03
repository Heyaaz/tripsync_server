import { ScheduleOptionType, SlotType } from '../src/common/enums/domain.enums';
import { LlmService } from '../src/llm/llm.service';

const originalEnv = { ...process.env };

describe('LlmService', () => {
  const input = {
    optionType: ScheduleOptionType.BALANCED,
    label: '균형형',
    summary: '기본 요약',
    room: { id: 1, destination: '충남', tripDate: '2026-05-02' },
    commonAxes: ['photo'],
    priorityAxes: ['mobility'],
    members: [
      { userId: 1, nickname: '민지' },
      { userId: 2, nickname: '지훈' },
    ],
    slotPlan: [
      {
        orderIndex: 1,
        slotType: SlotType.COMMON,
        targetUserId: null,
        reasonAxis: 'common',
        startTime: '09:00',
        endTime: '11:00',
        candidatePlaces: [
          { id: 101, name: '공산성', category: 'tourist_attraction', address: '충남 공주' },
          { id: 102, name: '외암민속마을', category: 'tourist_attraction', address: '충남 아산' },
        ],
        deterministicPlaceId: 101,
        deterministicReason: '기본 후보',
      },
    ],
  } as const;

  beforeEach(() => {
    jest.clearAllMocks();
    process.env = { ...originalEnv };
    delete process.env.OPENAI_API_KEY;
    delete process.env.GEMINI_API_KEY;
    delete process.env.LLM_PROVIDER;
  });

  afterAll(() => {
    process.env = originalEnv;
  });

  it('returns null when no llm key is configured', async () => {
    const service = new LlmService();
    await expect(service.refineScheduleOption(input as any)).resolves.toBeNull();
  });

  it('validates openai structured output against candidate place ids', async () => {
    process.env.OPENAI_API_KEY = 'openai-key';
    const service = new LlmService();
    const fetchMock = jest.spyOn(global, 'fetch' as any).mockResolvedValue({
      ok: true,
      json: async () => ({
        output_text: JSON.stringify({
          summary: 'LLM 요약',
          slots: [{ orderIndex: 1, placeId: 101, reason: '후보 내 장소 선택' }],
        }),
      }),
    } as Response);

    await expect(service.refineScheduleOption(input as any)).resolves.toEqual({
      summary: 'LLM 요약',
      slots: [{ orderIndex: 1, placeId: 101, reason: '후보 내 장소 선택' }],
      provider: 'openai:gpt-5',
    });

    fetchMock.mockRestore();
  });

  it('rejects gemini responses that select place ids outside candidates', async () => {
    process.env.GEMINI_API_KEY = 'gemini-key';
    process.env.LLM_PROVIDER = 'gemini';
    const service = new LlmService();
    const fetchMock = jest.spyOn(global, 'fetch' as any).mockResolvedValue({
      ok: true,
      json: async () => ({
        candidates: [
          {
            content: {
              parts: [
                {
                  text: JSON.stringify({
                    summary: '잘못된 응답',
                    slots: [{ orderIndex: 1, placeId: 999, reason: '후보 밖 장소' }],
                  }),
                },
              ],
            },
          },
        ],
      }),
    } as Response);

    await expect(service.refineScheduleOption(input as any)).resolves.toBeNull();

    fetchMock.mockRestore();
  });
});
