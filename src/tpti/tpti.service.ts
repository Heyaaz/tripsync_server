import { Injectable, NotFoundException } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';
import { ok } from '../common/dto/api-response.dto';
import { BaseSoftDeleteService } from '../common/soft-delete/base-soft-delete.service';
import { ACTIVE_DEL_YN } from '../common/soft-delete/soft-delete.util';
import { SubmitTptiDto } from './dto/submit-tpti.dto';

@Injectable()
export class TptiService extends BaseSoftDeleteService {
  constructor(private readonly prisma: PrismaService) {
    super();
  }

  private buildActiveResultWhere(userId: bigint) {
    return this.activeWhere({ userId });
  }

  getQuestions() {
    return ok({
      version: 'v1',
      questions: [
        {
          id: 1,
          axis: 'mobility',
          reverseScored: false,
          text: '여행 가면 많이 걷고 여러 장소를 도는 편이 좋다.',
        },
      ],
    });
  }

  private async getDefaultActiveUser() {
    const user = await this.prisma.user.findFirst({
      where: this.activeWhere({}),
      orderBy: { id: 'asc' },
    });

    if (!user) {
      throw new NotFoundException('활성 사용자 세션이 없습니다.');
    }

    return user;
  }

  async submitResult(dto: SubmitTptiDto) {
    const user = await this.getDefaultActiveUser();
    const baseScore = Math.round((dto.answers.reduce((sum, value) => sum + value, 0) / 40) * 100);
    const scores = dto.manualAdjustments ?? {
      mobilityScore: baseScore,
      photoScore: baseScore,
      budgetScore: baseScore,
      themeScore: baseScore,
    };

    const result = await this.prisma.tptiResult.create({
      data: {
        userId: user.id,
        mobilityScore: scores.mobilityScore,
        photoScore: scores.photoScore,
        budgetScore: scores.budgetScore,
        themeScore: scores.themeScore,
        characterName: '뚜벅이 탐험가',
        sourceAnswers: dto.answers,
        isManuallyAdjusted: Boolean(dto.manualAdjustments),
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
      characterName: '뚜벅이 탐험가',
    });
  }

  async getLatestResult(userId: number) {
    const result = await this.prisma.tptiResult.findFirst({
      where: this.buildActiveResultWhere(BigInt(userId)),
      orderBy: { createdAt: 'desc' },
    });

    if (!result) {
      throw new NotFoundException('TPTI 결과를 찾을 수 없습니다.');
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
      throw new NotFoundException('공유할 TPTI 결과를 찾을 수 없습니다.');
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
