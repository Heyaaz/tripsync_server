import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import * as fs from 'fs';
import * as path from 'path';
import {
  MatchedPersona,
  PersonaValidationResult,
  PersonaVector,
} from './dto/persona-validation-result.dto';

interface MemberSnapshot {
  userId: number;
  nickname: string;
  scores: PersonaVector;
}

interface ScheduleSlotDraft {
  orderIndex: number;
  slotType: string;
  targetUserId: number | null;
  reasonAxis: string;
  startTime: Date;
  endTime: Date;
  placeId: number;
}

interface ScheduleOptionDraft {
  optionType: string;
  label: string;
  summary: string;
  groupSatisfaction: number;
  slots: ScheduleSlotDraft[];
}

@Injectable()
export class PersonaValidationService implements OnModuleInit {
  private readonly logger = new Logger(PersonaValidationService.name);
  private personaPool: Map<string, PersonaVector> = new Map();

  async onModuleInit() {
    await this.loadPersonaVectors();
  }

  private async loadPersonaVectors() {
    try {
      const vectorPath = path.resolve(
        process.cwd(),
        'assets',
        'persona-vectors.json',
      );

      if (!fs.existsSync(vectorPath)) {
        this.logger.warn(
          'persona-vectors.json not found. Persona validation will be disabled.',
        );
        return;
      }

      const raw = fs.readFileSync(vectorPath, 'utf-8');
      const vectors = JSON.parse(raw);

      this.personaPool = new Map(Object.entries(vectors));
      this.logger.log(
        `Loaded ${this.personaPool.size} persona vectors from ${vectorPath}`,
      );
    } catch (error) {
      this.logger.error('Failed to load persona vectors:', error);
    }
  }

  validateOptions(
    options: ScheduleOptionDraft[],
    members: MemberSnapshot[],
  ): Map<string, PersonaValidationResult> {
    if (this.personaPool.size === 0) {
      this.logger.warn('Persona pool is empty. Skipping validation.');
      return new Map();
    }

    const results = new Map<string, PersonaValidationResult>();

    for (const option of options) {
      const result = this.validateOption(option, members);
      results.set(option.optionType, result);
    }

    return results;
  }

  private validateOption(
    option: ScheduleOptionDraft,
    members: MemberSnapshot[],
  ): PersonaValidationResult {
    const matchedPersonas: MatchedPersona[] = [];

    for (const member of members) {
      const matches = this.findSimilarPersonas(member, 3);
      matchedPersonas.push(...matches);
    }

    const personaAcceptanceScore = this.calculateAcceptanceScore(
      option,
      matchedPersonas,
    );

    const topPositiveSignals = this.generatePositiveSignals(
      option,
      matchedPersonas,
    );
    const objectionReasons = this.generateObjections(option, matchedPersonas);
    const persuasionPoints = this.generatePersuasionPoints(
      option,
      objectionReasons,
    );

    return {
      source: 'synthetic_research',
      dataset: 'nvidia/Nemotron-Personas-Korea',
      personaAcceptanceScore,
      matchedPersonaCount: matchedPersonas.length,
      topPositiveSignals,
      objectionReasons,
      persuasionPoints,
      matchedPersonas: matchedPersonas.slice(0, 5),
    };
  }

  private findSimilarPersonas(
    member: MemberSnapshot,
    topK: number,
  ): MatchedPersona[] {
    const scores: Array<{ uuid: string; similarity: number }> = [];

    for (const [uuid, vector] of this.personaPool.entries()) {
      const distance =
        Math.abs(member.scores.mobility - vector.mobility) +
        Math.abs(member.scores.photo - vector.photo) +
        Math.abs(member.scores.budget - vector.budget) +
        Math.abs(member.scores.theme - vector.theme);

      const similarity = 1 - distance / 400;
      scores.push({ uuid, similarity });
    }

    scores.sort((a, b) => b.similarity - a.similarity);

    return scores.slice(0, topK).map((s) => ({
      matchedUserId: member.userId,
      similarity: Math.round(s.similarity * 100) / 100,
      personaSummary: this.getPersonaSummary(s.uuid),
      scores: this.personaPool.get(s.uuid)!,
    }));
  }

  private getPersonaSummary(uuid: string): string {
    const summaries = [
      '활동적인 여행을 선호하는 직장인',
      '자연과 힐링을 찾는 가족 여행자',
      '도심 핫플을 즐기는 20대 여행자',
      '역사와 문화를 탐방하는 여행자',
      '사진과 SNS 공유를 즐기는 여행자',
      '가성비와 실속을 중시하는 여행자',
      '럭셔리와 프리미엄을 추구하는 여행자',
      '조용한 소도시를 찾는 은퇴 여행자',
    ];

    const index =
      uuid.split('').reduce((sum, char) => sum + char.charCodeAt(0), 0) %
      summaries.length;
    return summaries[index];
  }

  private calculateAcceptanceScore(
    option: ScheduleOptionDraft,
    personas: MatchedPersona[],
  ): number {
    if (personas.length === 0) return 0;

    let totalScore = 0;

    for (const persona of personas) {
      const slotScores = option.slots.map((slot) => {
        const targetVector = this.getSlotTargetVector(slot, option, persona);
        return this.calculateVectorMatch(targetVector, persona.scores);
      });

      const avgSlotScore =
        slotScores.reduce((sum, s) => sum + s, 0) / slotScores.length;
      totalScore += avgSlotScore;
    }

    return Math.round((totalScore / personas.length) * 100);
  }

  private getSlotTargetVector(
    slot: ScheduleSlotDraft,
    option: ScheduleOptionDraft,
    persona: MatchedPersona,
  ): PersonaVector {
    if (option.optionType === 'balanced') {
      return { mobility: 50, photo: 50, budget: 50, theme: 50 };
    }

    return persona.scores;
  }

  private calculateVectorMatch(
    target: PersonaVector,
    candidate: PersonaVector,
  ): number {
    const total =
      (1 - Math.abs(target.mobility - candidate.mobility) / 100) +
      (1 - Math.abs(target.photo - candidate.photo) / 100) +
      (1 - Math.abs(target.budget - candidate.budget) / 100) +
      (1 - Math.abs(target.theme - candidate.theme) / 100);

    return total / 4;
  }

  private generatePositiveSignals(
    option: ScheduleOptionDraft,
    personas: MatchedPersona[],
  ): string[] {
    const signals: string[] = [];

    if (option.optionType === 'balanced') {
      signals.push('전원의 취향이 고르게 반영되어 묂한 여행자들의 수용도가 높음');
    } else if (option.optionType === 'individual') {
      signals.push('각자의 취향이 명확히 반영되어 참고군도 긍정적으로 평가');
    } else if (option.optionType === 'discovery') {
      signals.push('새로운 장소에 대한 호기심이 높은 참고군에게 긍정적');
    }

    if (personas.some((p) => p.scores.budget < 40)) {
      signals.push('저예산 성향의 참고군도 부담 없는 가성비 코스');
    }

    return signals.slice(0, 2);
  }

  private generateObjections(
    option: ScheduleOptionDraft,
    personas: MatchedPersona[],
  ): string[] {
    const objections: string[] = [];

    const avgMobility =
      personas.reduce((sum, p) => sum + p.scores.mobility, 0) /
      personas.length;

    if (avgMobility < 30 && option.slots.length > 5) {
      objections.push('휴식형 여행자에게는 장소 이동이 다소 부담스러울 수 있음');
    }

    const avgTheme =
      personas.reduce((sum, p) => sum + p.scores.theme, 0) / personas.length;

    if (avgTheme > 70 && option.optionType === 'discovery') {
      objections.push('도심형 여행자는 인프라가 부족한 지역을 불편해할 수 있음');
    }

    return objections;
  }

  private generatePersuasionPoints(
    option: ScheduleOptionDraft,
    objections: string[],
  ): string[] {
    const points: string[] = [];

    if (option.optionType === 'balanced') {
      points.push('"모두가 조금씩 만족한다"는 공감대를 강조하세요');
    } else if (option.optionType === 'individual') {
      points.push('"오전은 내 취향, 오후는 네 취향"이라는 교대 배분을 설명하세요');
    } else if (option.optionType === 'discovery') {
      points.push('"다른 사람이 모르는 곳"이라는 희소성을 강조하세요');
    }

    if (objections.length > 0) {
      points.push('이동이 많은 날에는 중간에 휴식 시간을 확보하세요');
    }

    return points.slice(0, 2);
  }
}
