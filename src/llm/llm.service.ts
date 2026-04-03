import { Injectable, Logger } from '@nestjs/common';
import { ReasonAxis, ScheduleOptionType, ScoreAxis, SlotType } from '../common/enums/domain.enums';

interface LlmCandidatePlace {
  id: number;
  name: string;
  category: string;
  address: string;
}

interface LlmSlotPlan {
  orderIndex: number;
  slotType: SlotType;
  targetUserId: number | null;
  reasonAxis: ReasonAxis | ScoreAxis;
  startTime: string;
  endTime: string;
  candidatePlaces: LlmCandidatePlace[];
  deterministicPlaceId: number;
  deterministicReason: string;
}

interface LlmRefineRequest {
  optionType: ScheduleOptionType;
  label: string;
  summary: string;
  room: {
    id: number;
    destination: string;
    tripDate: string;
  };
  commonAxes: string[];
  priorityAxes: string[];
  members: Array<{
    userId: number;
    nickname: string;
  }>;
  slotPlan: LlmSlotPlan[];
}

interface LlmStructuredResponse {
  summary: string;
  slots: Array<{
    orderIndex: number;
    placeId: number;
    reason: string;
  }>;
}

export interface LlmRefineResult {
  summary: string;
  slots: Array<{
    orderIndex: number;
    placeId: number;
    reason: string;
  }>;
  provider: string;
}

@Injectable()
export class LlmService {
  private readonly logger = new Logger(LlmService.name);

  private readEnv(name: string) {
    const value = process.env[name]?.trim();
    if (!value || value === 'replace-me') {
      return undefined;
    }
    return value;
  }

  private pickProvider() {
    const forced = this.readEnv('LLM_PROVIDER');
    if (forced === 'openai' && this.readEnv('OPENAI_API_KEY')) {
      return 'openai' as const;
    }
    if (forced === 'gemini' && this.readEnv('GEMINI_API_KEY')) {
      return 'gemini' as const;
    }
    if (this.readEnv('OPENAI_API_KEY')) {
      return 'openai' as const;
    }
    if (this.readEnv('GEMINI_API_KEY')) {
      return 'gemini' as const;
    }
    return null;
  }

  async refineScheduleOption(input: LlmRefineRequest): Promise<LlmRefineResult | null> {
    const provider = this.pickProvider();
    if (!provider) {
      return null;
    }

    try {
      if (provider === 'openai') {
        return await this.requestOpenAi(input);
      }
      return await this.requestGemini(input);
    } catch (error) {
      this.logger.warn(`LLM refinement failed: ${error instanceof Error ? error.message : 'unknown error'}`);
      return null;
    }
  }

  private buildSchema() {
    return {
      type: 'object',
      additionalProperties: false,
      required: ['summary', 'slots'],
      properties: {
        summary: { type: 'string' },
        slots: {
          type: 'array',
          items: {
            type: 'object',
            additionalProperties: false,
            required: ['orderIndex', 'placeId', 'reason'],
            properties: {
              orderIndex: { type: 'integer' },
              placeId: { type: 'integer' },
              reason: { type: 'string' },
            },
          },
        },
      },
    } as const;
  }

  private buildSystemPrompt() {
    return [
      'You are a TripSync schedule formatter.',
      'Choose exactly one placeId per slot from the provided candidatePlaces only.',
      'Never invent a placeId outside candidatePlaces.',
      'Keep reasons concise and under 80 Korean characters.',
      'Prefer natural trip flow and category fit for each slot.',
      'Return only JSON that matches the schema.',
    ].join(' ');
  }

  private validateResponse(input: LlmRefineRequest, payload: unknown): LlmStructuredResponse | null {
    if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
      return null;
    }

    const candidateMap = new Map(
      input.slotPlan.map((slot) => [
        slot.orderIndex,
        new Set(slot.candidatePlaces.map((candidate) => candidate.id)),
      ]),
    );

    const rawSummary = (payload as Record<string, unknown>).summary;
    const summary = typeof rawSummary === 'string'
      ? rawSummary.trim()
      : '';
    const slots = Array.isArray((payload as Record<string, unknown>).slots)
      ? ((payload as Record<string, unknown>).slots as Array<Record<string, unknown>>)
      : null;

    if (!summary || !slots || slots.length !== input.slotPlan.length) {
      return null;
    }

    const normalized = slots.map((slot) => {
      const orderIndex = Number(slot.orderIndex);
      const placeId = Number(slot.placeId);
      const reason = typeof slot.reason === 'string' ? slot.reason.trim() : '';
      if (!Number.isInteger(orderIndex) || !Number.isInteger(placeId) || !reason) {
        return null;
      }

      const allowedPlaces = candidateMap.get(orderIndex);
      if (!allowedPlaces || !allowedPlaces.has(placeId) || reason.length > 80) {
        return null;
      }

      return { orderIndex, placeId, reason };
    });

    if (normalized.some((slot) => slot == null)) {
      return null;
    }

    const sortedOrder = normalized.map((slot) => slot!.orderIndex).sort((a, b) => a - b);
    const expectedOrder = input.slotPlan.map((slot) => slot.orderIndex).sort((a, b) => a - b);
    if (JSON.stringify(sortedOrder) !== JSON.stringify(expectedOrder)) {
      return null;
    }

    return {
      summary,
      slots: normalized as Array<{ orderIndex: number; placeId: number; reason: string }>,
    };
  }

  private async requestOpenAi(input: LlmRefineRequest): Promise<LlmRefineResult | null> {
    const apiKey = this.readEnv('OPENAI_API_KEY');
    if (!apiKey) {
      return null;
    }

    const model = this.readEnv('OPENAI_MODEL') ?? 'gpt-5';
    const response = await fetch('https://api.openai.com/v1/responses', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model,
        input: [
          {
            role: 'system',
            content: [{ type: 'input_text', text: this.buildSystemPrompt() }],
          },
          {
            role: 'user',
            content: [{ type: 'input_text', text: JSON.stringify(input) }],
          },
        ],
        text: {
          format: {
            type: 'json_schema',
            name: 'tripsync_schedule_refinement',
            strict: true,
            schema: this.buildSchema(),
          },
        },
      }),
    });

    if (!response.ok) {
      throw new Error(`OpenAI ${response.status}: ${await response.text()}`);
    }

    const payload = (await response.json()) as Record<string, unknown>;
    const outputText = typeof payload.output_text === 'string'
      ? payload.output_text
      : this.findResponseText(payload);
    if (!outputText) {
      return null;
    }

    const parsed = this.validateResponse(input, JSON.parse(outputText));
    return parsed
      ? {
          ...parsed,
          provider: `openai:${model}`,
        }
      : null;
  }

  private findResponseText(payload: Record<string, unknown>) {
    const output = Array.isArray(payload.output) ? payload.output : [];
    for (const item of output) {
      if (!item || typeof item !== 'object' || Array.isArray(item)) {
        continue;
      }
      const content = Array.isArray((item as Record<string, unknown>).content)
        ? ((item as Record<string, unknown>).content as Array<Record<string, unknown>>)
        : [];
      for (const part of content) {
        if (typeof part?.text === 'string') {
          return part.text;
        }
      }
    }
    return null;
  }

  private async requestGemini(input: LlmRefineRequest): Promise<LlmRefineResult | null> {
    const apiKey = this.readEnv('GEMINI_API_KEY');
    if (!apiKey) {
      return null;
    }

    const model = this.readEnv('GEMINI_MODEL') ?? 'gemini-3-flash-preview';
    const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
      method: 'POST',
      headers: {
        'x-goog-api-key': apiKey,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        contents: [
          {
            parts: [
              {
                text: `${this.buildSystemPrompt()}\n\n${JSON.stringify(input)}`,
              },
            ],
          },
        ],
        generationConfig: {
          responseMimeType: 'application/json',
          responseJsonSchema: this.buildSchema(),
        },
      }),
    });

    if (!response.ok) {
      throw new Error(`Gemini ${response.status}: ${await response.text()}`);
    }

    const payload = (await response.json()) as Record<string, unknown>;
    const text = this.findGeminiText(payload);
    if (!text) {
      return null;
    }

    const parsed = this.validateResponse(input, JSON.parse(text));
    return parsed
      ? {
          ...parsed,
          provider: `gemini:${model}`,
        }
      : null;
  }

  private findGeminiText(payload: Record<string, unknown>) {
    const candidates = Array.isArray(payload.candidates) ? payload.candidates : [];
    const first = candidates[0];
    if (!first || typeof first !== 'object' || Array.isArray(first)) {
      return null;
    }
    const content = (first as Record<string, unknown>).content;
    if (!content || typeof content !== 'object' || Array.isArray(content)) {
      return null;
    }
    const parts = Array.isArray((content as Record<string, unknown>).parts)
      ? ((content as Record<string, unknown>).parts as Array<Record<string, unknown>>)
      : [];
    const textPart = parts.find((part) => typeof part.text === 'string');
    return typeof textPart?.text === 'string' ? textPart.text : null;
  }
}
