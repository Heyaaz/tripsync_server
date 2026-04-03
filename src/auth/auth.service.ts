import { HttpStatus, Injectable } from '@nestjs/common';
import { User } from '@prisma/client';
import { randomUUID } from 'crypto';
import { PrismaService } from '../prisma/prisma.service';
import { AuthProvider, YnFlag } from '../common/enums/domain.enums';
import { ok } from '../common/dto/api-response.dto';
import { DomainException } from '../common/errors/domain.exception';
import { ACTIVE_DEL_YN, withActiveFilter } from '../common/soft-delete/soft-delete.util';
import { CreateGuestSessionDto } from './dto/create-guest-session.dto';
import {
  extractCookie,
  extractSessionToken,
  getOauthStateCookieName,
  issueSessionToken,
  SessionPayload,
  verifySessionToken,
} from './session-token.util';

const SESSION_EXPIRY_SECONDS = 60 * 60 * 24 * 7;

@Injectable()
export class AuthService {
  constructor(private readonly prisma: PrismaService) {}

  private buildProviderAuthUrl(
    provider: Exclude<AuthProvider, AuthProvider.GUEST>,
    state: string,
    redirectPath: string,
  ) {
    const apiBaseUrl = process.env.API_BASE_URL ?? 'http://localhost:3001/api';
    const callbackUrl = `${apiBaseUrl}/auth/${provider}/callback`;
    const combinedState = `${state}|${redirectPath}`;

    const providerAuthorizeUrl =
      provider === AuthProvider.KAKAO
        ? process.env.KAKAO_AUTHORIZE_URL ?? 'https://kauth.kakao.com/oauth/authorize'
        : process.env.GOOGLE_AUTHORIZE_URL ?? 'https://accounts.google.com/o/oauth2/v2/auth';
    const clientId =
      provider === AuthProvider.KAKAO ? process.env.KAKAO_CLIENT_ID : process.env.GOOGLE_CLIENT_ID;

    if (!clientId) {
      return `${callbackUrl}?code=local-${provider}-code&state=${encodeURIComponent(combinedState)}&redirectPath=${encodeURIComponent(
        redirectPath,
      )}`;
    }

    const params = new URLSearchParams({
      response_type: 'code',
      client_id: clientId,
      redirect_uri: callbackUrl,
      state: combinedState,
    });

    if (provider === AuthProvider.GOOGLE) {
      params.set('scope', 'openid profile email');
      params.set('access_type', 'offline');
      params.set('prompt', 'consent');
    }

    return `${providerAuthorizeUrl}?${params.toString()}`;
  }

  getOAuthRedirect(provider: Exclude<AuthProvider, AuthProvider.GUEST>, redirectPath?: string) {
    const normalizedRedirectPath = redirectPath ?? '/rooms/new';
    const state = randomUUID();
    const redirectUrl = this.buildProviderAuthUrl(provider, state, normalizedRedirectPath);

    return {
      state,
      redirectPath: normalizedRedirectPath,
      redirectUrl,
    };
  }

  private async upsertOAuthUser(
    provider: Exclude<AuthProvider, AuthProvider.GUEST>,
    providerUserId: string,
  ) {
    return this.prisma.user.upsert({
      where: {
        authProvider_providerUserId: {
          authProvider: provider,
          providerUserId,
        },
      },
      update: {
        delYn: ACTIVE_DEL_YN,
        isGuest: false,
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
  }

  private buildSessionPayload(user: Pick<User, 'id' | 'nickname' | 'authProvider' | 'isGuest'>): SessionPayload {
    return {
      sub: Number(user.id),
      provider: user.authProvider as AuthProvider,
      isGuest: user.isGuest,
      nickname: user.nickname,
      exp: Math.floor(Date.now() / 1000) + SESSION_EXPIRY_SECONDS,
    };
  }

  async handleOAuthCallback(
    provider: Exclude<AuthProvider, AuthProvider.GUEST>,
    query: Record<string, string | undefined>,
    cookieHeader?: string,
  ) {
    if (query.error) {
      throw new DomainException(HttpStatus.BAD_GATEWAY, 'OAUTH_PROVIDER_ERROR', 'OAuth 공급자 오류가 발생했습니다.', {
        provider,
        error: query.error,
      });
    }

    const [stateValue, redirectPathFromState] = (query.state ?? '').split('|');
    const expectedState = extractCookie(cookieHeader, getOauthStateCookieName());
    if (!stateValue || !expectedState || expectedState !== stateValue) {
      throw new DomainException(HttpStatus.UNAUTHORIZED, 'OAUTH_STATE_INVALID', 'OAuth state 검증에 실패했습니다.');
    }

    const providerUserId = query.code ?? `mock-${provider}-${randomUUID().slice(0, 8)}`;
    const user = await this.upsertOAuthUser(provider, providerUserId);
    const sessionToken = issueSessionToken(this.buildSessionPayload(user));
    const frontendBaseUrl = process.env.APP_BASE_URL ?? 'http://localhost:3000';

    return {
      sessionToken,
      redirectUrl: `${frontendBaseUrl}${query.redirectPath ?? redirectPathFromState ?? '/rooms/new'}?login=success`,
      user: {
        id: Number(user.id),
        nickname: user.nickname,
        authProvider: user.authProvider,
        isGuest: user.isGuest,
      },
    };
  }

  async createGuestSession(dto: CreateGuestSessionDto) {
    const user = await this.prisma.user.create({
      data: {
        nickname: dto.nickname,
        authProvider: AuthProvider.GUEST,
        providerUserId: null,
        profileImageUrl: null,
        adminYn: YnFlag.NO,
        isGuest: true,
        delYn: ACTIVE_DEL_YN,
      },
    });

    const sessionToken = issueSessionToken(this.buildSessionPayload(user));

    return {
      sessionToken,
      response: ok({
        user: {
          id: Number(user.id),
          nickname: user.nickname,
          isGuest: true,
        },
        expiresIn: SESSION_EXPIRY_SECONDS,
      }),
    };
  }

  async resolveSessionUser(authorization?: string, cookieHeader?: string) {
    const token = extractSessionToken(authorization, cookieHeader);
    if (!token) {
      return null;
    }

    const payload = verifySessionToken(token);
    if (!payload) {
      return null;
    }

    return this.prisma.user.findFirst({
      where: withActiveFilter({ id: BigInt(payload.sub) }),
    });
  }

  async requireSessionUser(authorization?: string, cookieHeader?: string) {
    const user = await this.resolveSessionUser(authorization, cookieHeader);
    if (!user) {
      throw new DomainException(HttpStatus.UNAUTHORIZED, 'UNAUTHORIZED', '로그인이 필요합니다.');
    }

    return user;
  }

  assertHostUser(user: Pick<User, 'isGuest'>) {
    if (user.isGuest) {
      throw new DomainException(HttpStatus.FORBIDDEN, 'FORBIDDEN', '방장 권한이 필요합니다.');
    }
  }
}
