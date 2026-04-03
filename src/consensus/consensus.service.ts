import { HttpStatus, Injectable } from '@nestjs/common';
import {
  ConflictSeverity,
  ReasonAxis,
  SCORE_AXES,
  ScheduleOptionType,
  ScoreAxis,
  SlotType,
} from '../common/enums/domain.enums';
import { DomainException } from '../common/errors/domain.exception';

export interface AxisScores {
  mobility: number;
  photo: number;
  budget: number;
  theme: number;
}

export interface MemberSnapshot {
  userId: number;
  nickname: string;
  scores: AxisScores;
  joinedOrder: number;
}

export interface PlaceCandidate {
  id: number;
  name: string;
  address: string;
  category: string;
  mobilityScore: number;
  photoScore: number;
  budgetScore: number;
  themeScore: number;
  metadataTags?: unknown;
  operatingHours?: unknown;
}

export interface ConflictAxisAnalysis {
  axis: ScoreAxis;
  min: number;
  max: number;
  gap: number;
  severity: ConflictSeverity;
  highUserId: number;
  lowUserId: number;
}

export interface ScheduleSlotDraft {
  orderIndex: number;
  slotType: SlotType;
  targetUserId: number | null;
  reasonAxis: ReasonAxis | ScoreAxis;
  reasonText: string;
  startTime: Date;
  endTime: Date;
  placeId: number;
  placeName: string;
  placeAddress: string;
  isHiddenGem: boolean;
}

export interface SatisfactionDraft {
  userId: number;
  score: number;
  breakdown: {
    overall: number;
    byAxis: Record<ScoreAxis, number>;
  };
}

export interface ScheduleOptionDraft {
  optionType: ScheduleOptionType;
  label: string;
  summary: string;
  groupSatisfaction: number;
  slots: ScheduleSlotDraft[];
  satisfactionByUser: SatisfactionDraft[];
}

interface SlotShape {
  orderIndex: number;
  slotType: SlotType;
  targetUserId: number | null;
  reasonAxis: ReasonAxis | ScoreAxis;
  reasonText: string;
  startTime: Date;
  endTime: Date;
}

interface TargetVector {
  scores: AxisScores;
  targetUserId: number | null;
  slotType: SlotType;
  reasonAxis: ReasonAxis | ScoreAxis;
  reasonText: string;
  startTime: Date;
  endTime: Date;
}

interface SlotSelectionProfile {
  isMealSlot: boolean;
  isFinalSlot: boolean;
  isEarlySlot: boolean;
  isLateSlot: boolean;
  startMinutes: number;
  endMinutes: number;
}

interface OptionContext {
  roomId: number;
  destination: string;
  tripDate: string;
  startTime: string;
  endTime: string;
  members: MemberSnapshot[];
  places: PlaceCandidate[];
}

const SLOT_TEMPLATES: Record<5 | 6 | 7, number[]> = {
  5: [150, 120, 150, 120, 180],
  6: [120, 120, 120, 120, 120, 120],
  7: [90, 120, 90, 120, 90, 120, 90],
};

@Injectable()
export class ConsensusService {
  analyzeGroup(members: MemberSnapshot[]) {
    if (members.length < 2) {
      throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, 'ROOM_NOT_READY', '갈등 분석을 위해 최소 2명의 멤버가 필요합니다.');
    }

    const conflictAxes: ConflictAxisAnalysis[] = SCORE_AXES.map((axis) => {
      const sorted = [...members].sort((a, b) => a.scores[axis] - b.scores[axis] || a.joinedOrder - b.joinedOrder);
      const lowMember = sorted[0];
      const highMember = sorted[sorted.length - 1];
      const min = lowMember.scores[axis];
      const max = highMember.scores[axis];
      const gap = max - min;
      return {
        axis,
        min,
        max,
        gap,
        severity: this.classifySeverity(gap),
        highUserId: highMember.userId,
        lowUserId: lowMember.userId,
      };
    });

    const commonAxes = conflictAxes.filter((axis) => axis.gap <= 20).map((axis) => axis.axis);
    const onlyConflicts = conflictAxes.filter((axis) => axis.gap > 20).sort((a, b) => b.gap - a.gap);

    return {
      commonAxes,
      conflictAxes: onlyConflicts,
      criticalAxes: onlyConflicts.filter((axis) => axis.severity === 'critical').map((axis) => axis.axis),
      priorityAxes: onlyConflicts.map((axis) => axis.axis),
      allAxes: conflictAxes,
    };
  }

  buildScheduleOptions(context: OptionContext): ScheduleOptionDraft[] {
    if (context.destination !== '충남') {
      throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, 'INVALID_REQUEST', 'MVP에서는 충남 일정만 생성할 수 있습니다.');
    }
    if (context.startTime !== '09:00' || context.endTime !== '21:00') {
      throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, 'INVALID_REQUEST', 'MVP 일정 생성 시간은 09:00~21:00으로 고정됩니다.');
    }
    if (context.members.length < 2 || context.members.length > 5) {
      throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, 'ROOM_NOT_READY', '일정 생성 가능한 멤버 수는 2~5명입니다.');
    }
    if (context.places.length === 0) {
      throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, 'PLACE_CANDIDATE_EMPTY', '일정 생성 후보 장소가 부족합니다.');
    }

    const analysis = this.analyzeGroup(context.members);
    const slotTemplate = this.buildSlotTemplate(context.tripDate, analysis, context.members.length);
    const baseShapes = this.buildIndividualSlotShapes(slotTemplate, analysis, context.members);
    const averageVector = this.getAverageScores(context.members);

    const individual = this.materializeOption(
      ScheduleOptionType.INDIVIDUAL,
      '개성형',
      '각자의 취향이 살아있는 교대 배분 일정',
      baseShapes.map((shape) => this.buildIndividualTarget(shape, analysis, context.members, averageVector)),
      context.places,
      60,
      false,
      context.members,
      context.tripDate,
    );

    const balanced = this.materializeOption(
      ScheduleOptionType.BALANCED,
      '균형형',
      '모두가 조금씩 만족하는 안전한 선택',
      baseShapes.map((shape) => ({
        scores: averageVector,
        targetUserId: null,
        slotType: SlotType.COMMON,
        reasonAxis: ReasonAxis.COMMON,
        reasonText: '그룹 전원의 평균 취향 반영',
        startTime: shape.startTime,
        endTime: shape.endTime,
      })),
      context.places,
      65,
      false,
      context.members,
      context.tripDate,
    );

    const discovery = this.materializeOption(
      ScheduleOptionType.DISCOVERY,
      '지역 발굴형',
      '충남 인구감소지역 숨은 명소 중심 탐험 일정',
      baseShapes.map((shape) => this.buildIndividualTarget(shape, analysis, context.members, averageVector)),
      context.places,
      55,
      true,
      context.members,
      context.tripDate,
    );

    return [balanced, individual, discovery];
  }

  private classifySeverity(gap: number): ConflictSeverity {
    if (gap <= 20) return 'common';
    if (gap <= 40) return 'minor';
    if (gap <= 60) return 'moderate';
    return 'critical';
  }

  private buildSlotTemplate(
    tripDate: string,
    analysis: ReturnType<ConsensusService['analyzeGroup']>,
    memberCount: number,
  ) {
    const slotCount =
      analysis.criticalAxes.length > 0 || memberCount >= 4
        ? 7
        : analysis.conflictAxes.length >= 2 || analysis.conflictAxes.some((axis) => axis.severity === 'moderate')
          ? 6
          : 5;

    const durations = SLOT_TEMPLATES[slotCount as 5 | 6 | 7];
    let currentMinutes = 9 * 60;

    return durations.map((duration, index) => {
      const startTime = this.toSeoulDateTime(tripDate, currentMinutes);
      currentMinutes += duration;
      const endTime = this.toSeoulDateTime(tripDate, currentMinutes);
      return {
        orderIndex: index + 1,
        duration,
        startTime,
        endTime,
      };
    });
  }

  private buildIndividualSlotShapes(
    slots: Array<{ orderIndex: number; duration: number; startTime: Date; endTime: Date }>,
    analysis: ReturnType<ConsensusService['analyzeGroup']>,
    members: MemberSnapshot[],
  ): SlotShape[] {
    const commonSlotIndexes = new Set<number>([1, slots.length]);
    if (analysis.commonAxes.length >= 2 && slots.length >= 6) {
      commonSlotIndexes.add(Math.ceil(slots.length / 2));
    }

    const personalSlots = slots.filter((slot) => !commonSlotIndexes.has(slot.orderIndex));
    const allocation = this.allocateConflictAxes(personalSlots.length, analysis.conflictAxes);
    const personalTargets: Array<Pick<SlotShape, 'slotType' | 'targetUserId' | 'reasonAxis' | 'reasonText'>> = [];

    for (const axisEntry of analysis.conflictAxes) {
      const count = allocation.get(axisEntry.axis) ?? 0;
      for (let index = 0; index < count; index += 1) {
        const userId = index % 2 === 0 ? axisEntry.highUserId : axisEntry.lowUserId;
        const member = members.find((item) => item.userId === userId);
        personalTargets.push({
          slotType: SlotType.PERSONAL,
          targetUserId: userId,
          reasonAxis: axisEntry.axis,
          reasonText: `${member?.nickname ?? '동행자'}의 ${this.axisLabel(axisEntry.axis)} 취향 반영`,
        });
      }
    }

    while (personalTargets.length < personalSlots.length) {
      personalTargets.push({
        slotType: SlotType.COMMON,
        targetUserId: null,
        reasonAxis: ReasonAxis.COMMON,
        reasonText: '그룹 공통 지대 반영',
      });
    }

    return slots.map((slot) => {
      if (commonSlotIndexes.has(slot.orderIndex)) {
        return {
          orderIndex: slot.orderIndex,
          slotType: SlotType.COMMON,
          targetUserId: null,
          reasonAxis: ReasonAxis.COMMON,
          reasonText: '그룹 공통 지대 반영',
          startTime: slot.startTime,
          endTime: slot.endTime,
        };
      }

      const next = personalTargets.shift();
      return {
        orderIndex: slot.orderIndex,
        slotType: next?.slotType ?? SlotType.COMMON,
        targetUserId: next?.targetUserId ?? null,
        reasonAxis: next?.reasonAxis ?? ReasonAxis.COMMON,
        reasonText: next?.reasonText ?? '그룹 공통 지대 반영',
        startTime: slot.startTime,
        endTime: slot.endTime,
      };
    });
  }

  private allocateConflictAxes(personalSlotCount: number, conflictAxes: ConflictAxisAnalysis[]) {
    const allocation = new Map<ScoreAxis, number>();
    if (personalSlotCount <= 0 || conflictAxes.length === 0) {
      return allocation;
    }

    const totalGap = conflictAxes.reduce((sum, axis) => sum + axis.gap, 0);
    let allocated = 0;

    for (const axis of conflictAxes) {
      const baseCount = totalGap === 0 ? 0 : Math.round((axis.gap / totalGap) * personalSlotCount);
      const adjusted = Math.min(personalSlotCount, Math.max(axis.severity === 'critical' ? 1 : 0, baseCount));
      allocation.set(axis.axis, adjusted);
      allocated += adjusted;
    }

    while (allocated < personalSlotCount) {
      for (const axis of conflictAxes) {
        if (allocated >= personalSlotCount) break;
        allocation.set(axis.axis, (allocation.get(axis.axis) ?? 0) + 1);
        allocated += 1;
      }
    }

    while (allocated > personalSlotCount) {
      for (const axis of [...conflictAxes].reverse()) {
        if (allocated <= personalSlotCount) break;
        const current = allocation.get(axis.axis) ?? 0;
        const minimum = axis.severity === 'critical' ? 1 : 0;
        if (current > minimum) {
          allocation.set(axis.axis, current - 1);
          allocated -= 1;
        }
      }
    }

    return allocation;
  }

  private buildIndividualTarget(
    shape: SlotShape,
    analysis: ReturnType<ConsensusService['analyzeGroup']>,
    members: MemberSnapshot[],
    averageVector: AxisScores,
  ) {
    if (shape.slotType === SlotType.COMMON || shape.targetUserId == null || shape.reasonAxis === ReasonAxis.COMMON) {
      return {
        scores: averageVector,
        targetUserId: null,
        slotType: SlotType.COMMON,
        reasonAxis: ReasonAxis.COMMON,
        reasonText: '그룹 공통 지대 반영',
        startTime: shape.startTime,
        endTime: shape.endTime,
      };
    }

    const targetMember = members.find((member) => member.userId === shape.targetUserId);
    if (!targetMember) {
      return {
        scores: averageVector,
        targetUserId: null,
        slotType: SlotType.COMMON,
        reasonAxis: ReasonAxis.COMMON,
        reasonText: '그룹 공통 지대 반영',
        startTime: shape.startTime,
        endTime: shape.endTime,
      };
    }

    const mergedScores = SCORE_AXES.reduce<AxisScores>((acc, axis) => {
      if (axis === shape.reasonAxis) {
        acc[axis] = targetMember.scores[axis];
      } else if (analysis.commonAxes.includes(axis)) {
        acc[axis] = averageVector[axis];
      } else {
        acc[axis] = Math.round(targetMember.scores[axis] * 0.7 + averageVector[axis] * 0.3);
      }
      return acc;
    }, { mobility: 0, photo: 0, budget: 0, theme: 0 });

    return {
      scores: mergedScores,
      targetUserId: shape.targetUserId,
      slotType: shape.slotType,
      reasonAxis: shape.reasonAxis,
      reasonText: shape.reasonText,
      startTime: shape.startTime,
      endTime: shape.endTime,
    };
  }

  private materializeOption(
    optionType: ScheduleOptionType,
    label: string,
    summary: string,
    targets: TargetVector[],
    places: PlaceCandidate[],
    threshold: number,
    preferHiddenGem: boolean,
    members: MemberSnapshot[],
    tripDate: string,
  ): ScheduleOptionDraft {
    const chosenPlaces: PlaceCandidate[] = [];
    const forcedHiddenGemIndex = preferHiddenGem ? this.pickForcedHiddenGemSlot(targets) : -1;
    const slots = targets.map((target, index) => {
      const profile = this.buildSlotSelectionProfile(target.startTime, target.endTime, index + 1, targets.length);
      const place = this.selectBestPlace({
        targetVector: target.scores,
        places,
        usedPlaceIds: new Set(chosenPlaces.map((candidate) => candidate.id)),
        previousPlace: chosenPlaces.at(-1),
        preferHiddenGem,
        mustBeHiddenGem: index === forcedHiddenGemIndex,
        tripDate,
        profile,
      });

      chosenPlaces.push(place);
      return {
        orderIndex: index + 1,
        slotType: target.slotType,
        targetUserId: target.targetUserId,
        reasonAxis: target.reasonAxis,
        reasonText: target.reasonText,
        startTime: target.startTime,
        endTime: target.endTime,
        placeId: place.id,
        placeName: place.name,
        placeAddress: place.address,
        isHiddenGem: this.isHiddenGem(place),
      } satisfies ScheduleSlotDraft;
    });

    const satisfactionByUser = this.buildSatisfaction(optionType, slots, chosenPlaces, members);
    const groupSatisfaction = Math.max(
      threshold,
      Math.min(...satisfactionByUser.map((entry) => entry.score)),
    );

    return {
      optionType,
      label,
      summary,
      groupSatisfaction,
      slots,
      satisfactionByUser,
    };
  }

  private buildSatisfaction(
    optionType: ScheduleOptionType,
    slots: ScheduleSlotDraft[],
    places: PlaceCandidate[],
    members: MemberSnapshot[],
  ): SatisfactionDraft[] {
    return members.map((member) => {
      const userId = member.userId;
      const userVector = member.scores;
      const weightedScore = slots.reduce((sum, slot, index) => {
        const durationMinutes = (slot.endTime.getTime() - slot.startTime.getTime()) / 60000;
        const matchScore = this.calculateVectorMatch(userVector, this.placeScores(places[index]));
        const ownershipBonus = slot.targetUserId === userId && optionType !== ScheduleOptionType.BALANCED ? 0.05 : 0;
        return sum + durationMinutes * Math.min(1, matchScore + ownershipBonus);
      }, 0);
      const totalDuration = slots.reduce((sum, slot) => sum + (slot.endTime.getTime() - slot.startTime.getTime()) / 60000, 0);
      const overall = Math.round((weightedScore / totalDuration) * 100);

      const byAxis = SCORE_AXES.reduce<Record<ScoreAxis, number>>((acc, axis) => {
        const axisAverage = places.reduce((sum, place) => {
          const diff = Math.abs(this.placeScores(place)[axis] - userVector[axis]);
          return sum + (1 - diff / 100);
        }, 0) / places.length;
        acc[axis] = Number(axisAverage.toFixed(2));
        return acc;
      }, { mobility: 0, photo: 0, budget: 0, theme: 0 });

      return {
        userId,
        score: overall,
        breakdown: {
          overall,
          byAxis,
        },
      };
    });
  }

  private pickForcedHiddenGemSlot(targets: TargetVector[]) {
    const personalIndex = targets.findIndex((target) => target.slotType === SlotType.PERSONAL);
    return personalIndex >= 0 ? personalIndex : Math.floor(targets.length / 2);
  }

  private selectBestPlace(input: {
    targetVector: AxisScores;
    places: PlaceCandidate[];
    usedPlaceIds: Set<number>;
    previousPlace?: PlaceCandidate;
    preferHiddenGem: boolean;
    mustBeHiddenGem: boolean;
    tripDate: string;
    profile: SlotSelectionProfile;
  }) {
    const source = input.mustBeHiddenGem
      ? input.places.filter((place) => this.isHiddenGem(place))
      : input.places;
    let pool = source.length > 0 ? source : input.places;

    const validFestivalPool = pool.filter((place) => !this.isFestivalPlace(place) || this.isFestivalAvailableOnTripDate(place, input.tripDate) !== false);
    if (validFestivalPool.length > 0) {
      pool = validFestivalPool;
    }

    const openNowPool = pool.filter((place) => this.isPlaceOpenDuringSlot(place, input.profile) !== false);
    if (openNowPool.length > 0) {
      pool = openNowPool;
    }

    if (!input.profile.isFinalSlot) {
      const withoutAccommodation = pool.filter((place) => !this.isAccommodationPlace(place));
      if (withoutAccommodation.length > 0) {
        pool = withoutAccommodation;
      }
    }

    if (input.profile.isMealSlot) {
      const restaurants = pool.filter((place) => this.isRestaurantPlace(place));
      if (restaurants.length > 0) {
        pool = restaurants;
      }
    }

    const ranked = [...pool].sort((a, b) => {
      return this.placeRankingScore(b, input.targetVector, input.previousPlace, input.preferHiddenGem, input.usedPlaceIds, input.tripDate, input.profile) -
        this.placeRankingScore(a, input.targetVector, input.previousPlace, input.preferHiddenGem, input.usedPlaceIds, input.tripDate, input.profile);
    });

    const candidate = ranked[0];
    if (!candidate) {
      throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, 'PLACE_CANDIDATE_EMPTY', '일정 생성 후보 장소가 부족합니다.');
    }

    return candidate;
  }

  private placeRankingScore(
    place: PlaceCandidate,
    targetVector: AxisScores,
    previousPlace: PlaceCandidate | undefined,
    preferHiddenGem: boolean,
    usedPlaceIds: Set<number>,
    tripDate: string,
    profile: SlotSelectionProfile,
  ) {
    let score = this.calculateVectorMatch(targetVector, this.placeScores(place));
    if (usedPlaceIds.has(place.id)) {
      score -= 0.2;
    }
    if (previousPlace && previousPlace.category === place.category) {
      score -= 0.08;
    }
    if (this.hasUnknownHours(place)) {
      score -= 0.08;
    }
    if (preferHiddenGem && this.isHiddenGem(place)) {
      score += 0.2;
    }
    const operatingAvailability = this.isPlaceOpenDuringSlot(place, profile);
    if (operatingAvailability === true) {
      score += 0.05;
    } else if (operatingAvailability === false) {
      score -= 0.35;
    }
    score += this.placeCategoryModifier(place, tripDate, profile);
    return score;
  }

  private placeCategoryModifier(place: PlaceCandidate, tripDate: string, profile: SlotSelectionProfile) {
    let modifier = 0;

    if (this.isRestaurantPlace(place)) {
      if (profile.isMealSlot) {
        modifier += 0.35;
      } else {
        modifier -= 0.12;
      }
    }

    if (this.isShoppingPlace(place)) {
      if (profile.isLateSlot) {
        modifier += 0.18;
      } else if (profile.isEarlySlot) {
        modifier -= 0.12;
      } else {
        modifier -= 0.03;
      }
    }

    if (this.isAccommodationPlace(place)) {
      modifier += profile.isFinalSlot ? -0.08 : -0.45;
    }

    const festivalAvailability = this.isFestivalAvailableOnTripDate(place, tripDate);
    if (festivalAvailability === true) {
      modifier += profile.isLateSlot ? 0.18 : 0.1;
    } else if (festivalAvailability === false) {
      modifier -= 0.5;
    } else if (this.isFestivalPlace(place)) {
      modifier -= 0.05;
    }

    if (!profile.isMealSlot && !profile.isLateSlot && this.isDayActivityPlace(place)) {
      modifier += 0.08;
    }

    return modifier;
  }

  private calculateVectorMatch(target: AxisScores, candidate: AxisScores) {
    const total = SCORE_AXES.reduce((sum, axis) => sum + (1 - Math.abs(target[axis] - candidate[axis]) / 100), 0);
    return total / SCORE_AXES.length;
  }

  private hasUnknownHours(place: PlaceCandidate) {
    if (!place.operatingHours || typeof place.operatingHours !== 'object') {
      return true;
    }
    const status = (place.operatingHours as Record<string, unknown>).status;
    return status === 'unknown';
  }

  private isHiddenGem(place: PlaceCandidate) {
    const tags = place.metadataTags;
    if (!tags) {
      return false;
    }
    if (Array.isArray(tags)) {
      return tags.some((entry) => typeof entry === 'string' && ['hidden_gem', 'population_decline'].includes(entry));
    }
    if (typeof tags === 'object') {
      const map = tags as Record<string, unknown>;
      return map.hiddenGem === true || map.populationDeclineArea === true || map.regionType === 'population_decline';
    }
    return false;
  }

  private buildSlotSelectionProfile(startTime: Date, endTime: Date, orderIndex: number, totalSlots: number): SlotSelectionProfile {
    const startMinutes = this.getSeoulMinutes(startTime);
    const endMinutes = this.getSeoulMinutes(endTime);
    const overlapsLunch = startMinutes < 13 * 60 + 30 && endMinutes > 11 * 60 + 30;
    const overlapsDinner = startMinutes < 20 * 60 && endMinutes > 17 * 60;

    return {
      isMealSlot: overlapsLunch || overlapsDinner,
      isFinalSlot: orderIndex === totalSlots,
      isEarlySlot: orderIndex === 1 || startMinutes < 11 * 60,
      isLateSlot: startMinutes >= 16 * 60 || endMinutes > 18 * 60,
      startMinutes,
      endMinutes,
    };
  }

  private getSeoulMinutes(date: Date) {
    return ((date.getUTCHours() + 9) % 24) * 60 + date.getUTCMinutes();
  }

  private readMetadataObject(place: PlaceCandidate) {
    if (!place.metadataTags || typeof place.metadataTags !== 'object' || Array.isArray(place.metadataTags)) {
      return null;
    }
    return place.metadataTags as Record<string, unknown>;
  }

  private getPlaceContentTypeId(place: PlaceCandidate) {
    const metadata = this.readMetadataObject(place);
    const value = metadata?.contentTypeId;
    return typeof value === 'string' ? value : null;
  }

  private isRestaurantPlace(place: PlaceCandidate) {
    return place.category === 'restaurant' || this.getPlaceContentTypeId(place) === '39';
  }

  private isShoppingPlace(place: PlaceCandidate) {
    return place.category === 'shopping' || this.getPlaceContentTypeId(place) === '38';
  }

  private isAccommodationPlace(place: PlaceCandidate) {
    return place.category === 'accommodation' || this.getPlaceContentTypeId(place) === '32';
  }

  private isFestivalPlace(place: PlaceCandidate) {
    return place.category === 'festival' || this.getPlaceContentTypeId(place) === '15';
  }

  private isDayActivityPlace(place: PlaceCandidate) {
    return ['tourist_attraction', 'cultural_facility', 'leisure_sports', 'festival'].includes(place.category);
  }

  private isFestivalAvailableOnTripDate(place: PlaceCandidate, tripDate: string): boolean | null {
    if (!this.isFestivalPlace(place)) {
      return null;
    }

    const metadata = this.readMetadataObject(place);
    const introFields =
      metadata?.introFields && typeof metadata.introFields === 'object' && !Array.isArray(metadata.introFields)
        ? (metadata.introFields as Record<string, unknown>)
        : null;

    const start = this.parseYmdDate(introFields?.eventstartdate ?? introFields?.eventStartDate ?? metadata?.eventstartdate ?? metadata?.eventStartDate);
    const end = this.parseYmdDate(introFields?.eventenddate ?? introFields?.eventEndDate ?? metadata?.eventenddate ?? metadata?.eventEndDate);

    if (!start || !end) {
      return null;
    }

    const normalizedTripDate = tripDate.replaceAll('-', '');
    return normalizedTripDate >= start && normalizedTripDate <= end;
  }

  private parseYmdDate(value: unknown) {
    if (value == null) {
      return null;
    }
    const digits = String(value).replace(/\D/g, '');
    return digits.length >= 8 ? digits.slice(0, 8) : null;
  }

  private isPlaceOpenDuringSlot(place: PlaceCandidate, profile: SlotSelectionProfile): boolean | null {
    if (!place.operatingHours || typeof place.operatingHours !== 'object' || Array.isArray(place.operatingHours)) {
      return null;
    }

    const hours = place.operatingHours as Record<string, unknown>;
    const status = hours.status;
    if (status === 'always') {
      return true;
    }
    if (status !== 'known') {
      return null;
    }

    const entries = Array.isArray(hours.entries) ? hours.entries : [];
    if (entries.length === 0) {
      return null;
    }

    return entries.some((entry) => {
      if (!entry || typeof entry !== 'object' || Array.isArray(entry)) {
        return false;
      }
      const record = entry as Record<string, unknown>;
      const openMinutes = typeof record.openMinutes === 'number' ? record.openMinutes : null;
      const closeMinutes = typeof record.closeMinutes === 'number' ? record.closeMinutes : null;
      if (openMinutes == null || closeMinutes == null) {
        return false;
      }

      return profile.startMinutes >= openMinutes && profile.endMinutes <= closeMinutes;
    });
  }

  private placeScores(place: PlaceCandidate): AxisScores {
    return {
      mobility: place.mobilityScore,
      photo: place.photoScore,
      budget: place.budgetScore,
      theme: place.themeScore,
    };
  }

  private getAverageScores(members: MemberSnapshot[]): AxisScores {
    const memberCount = members.length;
    return SCORE_AXES.reduce<AxisScores>((acc, axis) => {
      acc[axis] = Math.round(members.reduce((sum, member) => sum + member.scores[axis], 0) / memberCount);
      return acc;
    }, { mobility: 0, photo: 0, budget: 0, theme: 0 });
  }

  private axisLabel(axis: ScoreAxis) {
    switch (axis) {
      case 'mobility':
        return '활동성';
      case 'photo':
        return '기록';
      case 'budget':
        return '예산';
      case 'theme':
        return '테마';
    }
  }

  private toSeoulDateTime(tripDate: string, minutesFromMidnight: number) {
    const hours = Math.floor(minutesFromMidnight / 60)
      .toString()
      .padStart(2, '0');
    const minutes = (minutesFromMidnight % 60).toString().padStart(2, '0');
    return new Date(`${tripDate}T${hours}:${minutes}:00+09:00`);
  }
}
