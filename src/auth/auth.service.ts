import { Injectable } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';
import { AuthProvider, YnFlag } from '../common/enums/domain.enums';
import { ok } from '../common/dto/api-response.dto';
import { ACTIVE_DEL_YN, buildActiveYnRecord, withActiveFilter } from '../common/soft-delete/soft-delete.util';
import { CreateGuestSessionDto } from './dto/create-guest-session.dto';

@Injectable()
export class AuthService {
  constructor(private readonly prisma: PrismaService) {}

  getOAuthRedirect(provider: Exclude<AuthProvider, AuthProvider.GUEST>, redirectPath?: string) {
    return ok({
      provider,
      redirectPath: redirectPath ?? '/rooms/new',
      message: `${provider} OAuth redirect should be implemented here`,
    });
  }

  async handleOAuthCallback(provider: Exclude<AuthProvider, AuthProvider.GUEST>, query: Record<string, string | undefined>) {
    const providerUserId = query.code ?? `mock-${provider}-user`;
    const user = await this.prisma.user.upsert({
      where: {
        authProvider_providerUserId: {
          authProvider: provider,
          providerUserId,
        },
      },
      update: {
        delYn: ACTIVE_DEL_YN,
      },
      create: {
        nickname: `${provider}-host`,
        email: null,
        authProvider: provider,
        providerUserId,
        profileImageUrl: null,
        adminYn: YnFlag.NO,
        isGuest: false,
        delYn: ACTIVE_DEL_YN,
      },
    });

    return ok({
      provider,
      query,
      user: {
        id: Number(user.id),
        nickname: user.nickname,
        authProvider: user.authProvider,
        adminYn: user.adminYn,
      },
      message: `${provider} OAuth callback handling placeholder completed with local upsert`,
    });
  }

  async createGuestSession(dto: CreateGuestSessionDto) {
    const guestUserDraft = buildActiveYnRecord({
      nickname: dto.nickname,
      authProvider: AuthProvider.GUEST,
      providerUserId: null,
      profileImageUrl: null,
      adminYn: YnFlag.NO,
      isGuest: true,
    });

    const user = await this.prisma.user.create({
      data: guestUserDraft,
    });

    const activeUsers = await this.prisma.user.count({
      where: withActiveFilter({}),
    });

    return ok({
      user: guestUserDraft,
      userId: Number(user.id),
      shareCode: dto.shareCode ?? null,
      expiresIn: 604800,
      activeUserCount: activeUsers,
    });
  }
}
