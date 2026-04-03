import { HttpStatus, Injectable } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { AuthService } from '../auth/auth.service';
import { ok } from '../common/dto/api-response.dto';
import { DomainException } from '../common/errors/domain.exception';
import { BaseSoftDeleteService } from '../common/soft-delete/base-soft-delete.service';
import { ACTIVE_DEL_YN } from '../common/soft-delete/soft-delete.util';
import { PrismaService } from '../prisma/prisma.service';
import { SubmitTptiDto } from './dto/submit-tpti.dto';

export interface QuestionDefinition {
  id: number;
  axis: 'mobility' | 'photo' | 'budget' | 'theme';
  reverseScored: boolean;
  text: string;
}

const QUESTIONS: QuestionDefinition[] = [
  { id: 1, axis: 'mobility', reverseScored: false, text: '여행 가면 많이 걷고 여러 장소를 도는 편이 좋다.' },
  { id: 2, axis: 'mobility', reverseScored: true, text: '여행에서는 이동보다 숙소나 카페에서 오래 쉬는 편이 좋다.' },
  { id: 3, axis: 'photo', reverseScored: false, text: '예쁜 포토스팟과 사진 기록이 일정에서 중요하다.' },
  { id: 4, axis: 'photo', reverseScored: true, text: '사진보다 현장에서 눈으로 보는 경험이 더 중요하다.' },
  { id: 5, axis: 'budget', reverseScored: false, text: '특별한 경험이라면 예산을 더 써도 괜찮다.' },
  { id: 6, axis: 'budget', reverseScored: true, text: '여행에서는 지출을 최대한 아끼는 편이 좋다.' },
  { id: 7, axis: 'theme', reverseScored: true, text: '도심 핫플보다 자연 풍경과 힐링 장소가 더 끌린다.' },
  { id: 8, axis: 'theme', reverseScored: false, text: '자연보다 도심 분위기와 핫플 탐방이 더 좋다.' },
];

@Injectable()
export class TptiService extends BaseSoftDeleteService {
  constructor(
    private readonly authService: AuthService,
    private readonly prisma: PrismaService,
  ) {
    super();
  }

  getQuestions() {
    return ok({
      version: 'v1',
      questions: QUESTIONS,
    });
  }

  private calculateScores(answers: number[]) {
    const totals = {
      mobility: [] as number[],
      photo: [] as number[],
      budget: [] as number[],
      theme: [] as number[],
    };

    QUESTIONS.forEach((question, index) => {
      const answer = answers[index];
      const normalized = ((answer - 1) / 4) * 100;
      const score = question.reverseScored ? 100 - normalized : normalized;
      totals[question.axis].push(Math.round(score));
    });

    return {
      mobilityScore: Math.round(totals.mobility.reduce((sum, value) => sum + value, 0) / totals.mobility.length),
      photoScore: Math.round(totals.photo.reduce((sum, value) => sum + value, 0) / totals.photo.length),
      budgetScore: Math.round(totals.budget.reduce((sum, value) => sum + value, 0) / totals.budget.length),
      themeScore: Math.round(totals.theme.reduce((sum, value) => sum + value, 0) / totals.theme.length),
    };
  }

  private buildCharacterName(scores: { mobilityScore: number; photoScore: number; budgetScore: number; themeScore: number }) {
    const mobility = scores.mobilityScore >= 60 ? '뚜벅이' : '힐링';
    const theme = scores.themeScore >= 60 ? '도심' : '자연';
    const photo = scores.photoScore >= 60 ? '아티스트' : '실속형';
    return `${mobility} ${theme} ${photo}`;
  }

  private async ensureResultReadable(requesterId: bigint, targetUserId: bigint) {
    if (requesterId === targetUserId) {
      return;
    }

    const sharedRoom = await this.prisma.roomMember.findFirst({
      where: {
        delYn: ACTIVE_DEL_YN,
        userId: requesterId,
        room: {
          delYn: ACTIVE_DEL_YN,
          members: {
            some: {
              delYn: ACTIVE_DEL_YN,
              userId: targetUserId,
            },
          },
        },
      },
    });

    if (!sharedRoom) {
      throw new DomainException(HttpStatus.FORBIDDEN, 'FORBIDDEN', '본인 또는 같은 방 멤버만 조회할 수 있습니다.');
    }
  }

  async submitResult(dto: SubmitTptiDto, authorization?: string, cookieHeader?: string) {
    const user = await this.authService.requireSessionUser(authorization, cookieHeader);
    const calculatedScores = this.calculateScores(dto.answers);
    const shouldApplyManualAdjustments = process.env.TPTI_MANUAL_ADJUSTMENTS_ENABLED === 'true';
    const finalScores = dto.manualAdjustments && shouldApplyManualAdjustments ? dto.manualAdjustments : calculatedScores;
    const characterName = this.buildCharacterName(finalScores);

    const result = await this.prisma.tptiResult.create({
      data: {
        userId: user.id,
        mobilityScore: finalScores.mobilityScore,
        photoScore: finalScores.photoScore,
        budgetScore: finalScores.budgetScore,
        themeScore: finalScores.themeScore,
        characterName,
        sourceAnswers: dto.answers as unknown as Prisma.InputJsonValue,
        isManuallyAdjusted: Boolean(dto.manualAdjustments && shouldApplyManualAdjustments),
        delYn: ACTIVE_DEL_YN,
      },
    });

    return ok({
      resultId: Number(result.id),
      userId: Number(user.id),
      scores: {
        mobility: result.mobilityScore,
        photo: result.photoScore,
        budget: result.budgetScore,
        theme: result.themeScore,
      },
      characterName,
    });
  }

  async getLatestResult(userId: number, authorization?: string, cookieHeader?: string) {
    const requester = await this.authService.requireSessionUser(authorization, cookieHeader);
    await this.ensureResultReadable(requester.id, BigInt(userId));

    const result = await this.prisma.tptiResult.findFirst({
      where: this.activeWhere({ userId: BigInt(userId) }),
      orderBy: { createdAt: 'desc' },
    });

    if (!result) {
      throw new DomainException(HttpStatus.NOT_FOUND, 'TPTI_INCOMPLETE', 'TPTI 결과를 찾을 수 없습니다.');
    }

    return ok({
      userId,
      scores: {
        mobility: result.mobilityScore,
        photo: result.photoScore,
        budget: result.budgetScore,
        theme: result.themeScore,
      },
      characterName: result.characterName,
      createdAt: result.createdAt.toISOString(),
    });
  }

  async getPublicShareResult(resultId: number) {
    const result = await this.prisma.tptiResult.findFirst({
      where: this.activeWhere({ id: BigInt(resultId) }),
      include: {
        user: true,
      },
    });

    if (!result) {
      throw new DomainException(HttpStatus.NOT_FOUND, 'RESOURCE_DELETED', '공유할 TPTI 결과를 찾을 수 없습니다.');
    }

    return ok({
      resultId,
      nickname: result.user.nickname,
      characterName: result.characterName,
      scores: {
        mobility: result.mobilityScore,
        photo: result.photoScore,
        budget: result.budgetScore,
        theme: result.themeScore,
      },
    });
  }
}
