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
type OAuthProvider = AuthProvider.GOOGLE;

interface OAuthConfig {
  authorizeUrl: string;
  tokenUrl: string;
  userInfoUrl: string;
  clientId?: string;
  clientSecret?: string;
  callbackUrl: string;
  scope?: string;
}

interface OAuthProfile {
  providerUserId: string;
  nickname: string;
  email: string | null;
  profileImageUrl: string | null;
}

@Injectable()
export class AuthService {
  constructor(private readonly prisma: PrismaService) {}

  private readEnv(name: string) {
    const value = process.env[name]?.trim();
    if (!value || value === 'replace-me') {
      return undefined;
    }
    return value;
  }

  private getCallbackUrl() {
    return this.readEnv('GOOGLE_CALLBACK_URL') ?? `${process.env.API_BASE_URL ?? 'http://localhost:3000/api'}/auth/google/callback`;
  }

  private getOAuthConfig(): OAuthConfig {
    return {
      authorizeUrl: this.readEnv('GOOGLE_AUTHORIZE_URL') ?? 'https://accounts.google.com/o/oauth2/v2/auth',
      tokenUrl: this.readEnv('GOOGLE_TOKEN_URL') ?? 'https://oauth2.googleapis.com/token',
      userInfoUrl: this.readEnv('GOOGLE_USERINFO_URL') ?? 'https://openidconnect.googleapis.com/v1/userinfo',
      clientId: this.readEnv('GOOGLE_CLIENT_ID'),
      clientSecret: this.readEnv('GOOGLE_CLIENT_SECRET'),
      callbackUrl: this.getCallbackUrl(),
      scope: 'openid profile email',
    };
  }

  private buildProviderAuthUrl(state: string, redirectPath: string) {
    const config = this.getOAuthConfig();
    const combinedState = `${state}|${redirectPath}`;

    if (!config.clientId) {
      return `${config.callbackUrl}?code=local-google-code&state=${encodeURIComponent(combinedState)}&redirectPath=${encodeURIComponent(
        redirectPath,
      )}`;
    }

    const params = new URLSearchParams({
      response_type: 'code',
      client_id: config.clientId,
      redirect_uri: config.callbackUrl,
      state: combinedState,
      scope: config.scope ?? 'openid profile email',
      access_type: 'offline',
      prompt: 'consent',
      include_granted_scopes: 'true',
    });

    return `${config.authorizeUrl}?${params.toString()}`;
  }

  getOAuthRedirect(provider: OAuthProvider, redirectPath?: string) {
    if (provider !== AuthProvider.GOOGLE) {
      throw new DomainException(HttpStatus.BAD_REQUEST, 'INVALID_REQUEST', '현재는 Google OAuth만 지원합니다.');
    }

    const normalizedRedirectPath = redirectPath ?? '/rooms/new';
    const state = randomUUID();
    const redirectUrl = this.buildProviderAuthUrl(state, normalizedRedirectPath);

    return {
      state,
      redirectPath: normalizedRedirectPath,
      redirectUrl,
    };
  }

  private async exchangeAuthorizationCode(code: string) {
    const config = this.getOAuthConfig();
    if (!config.clientId) {
      return null;
    }

    const params = new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: config.clientId,
      redirect_uri: config.callbackUrl,
      code,
    });

    if (config.clientSecret) {
      params.set('client_secret', config.clientSecret);
    }

    const response = await fetch(config.tokenUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded;charset=utf-8',
      },
      body: params.toString(),
    });

    if (!response.ok) {
      throw new DomainException(HttpStatus.BAD_GATEWAY, 'OAUTH_PROVIDER_ERROR', 'OAuth 토큰 교환에 실패했습니다.', {
        provider: AuthProvider.GOOGLE,
        status: response.status,
        body: await response.text(),
      });
    }

    const token = (await response.json()) as { access_token?: string };
    if (!token.access_token) {
      throw new DomainException(HttpStatus.BAD_GATEWAY, 'OAUTH_PROVIDER_ERROR', 'OAuth access token이 응답에 없습니다.', {
        provider: AuthProvider.GOOGLE,
      });
    }

    return token.access_token;
  }

  private buildLocalOAuthProfile(code: string): OAuthProfile {
    return {
      providerUserId: code,
      nickname: 'google-host',
      email: null,
      profileImageUrl: null,
    };
  }

  private async fetchOAuthProfile(code: string) {
    const config = this.getOAuthConfig();
    const accessToken = await this.exchangeAuthorizationCode(code);

    if (!accessToken) {
      return this.buildLocalOAuthProfile(code);
    }

    const response = await fetch(config.userInfoUrl, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
      },
    });

    if (!response.ok) {
      throw new DomainException(HttpStatus.BAD_GATEWAY, 'OAUTH_PROVIDER_ERROR', 'OAuth 사용자 정보 조회에 실패했습니다.', {
        provider: AuthProvider.GOOGLE,
        status: response.status,
        body: await response.text(),
      });
    }

    const payload = (await response.json()) as Record<string, any>;

    return {
      providerUserId: String(payload.sub),
      nickname: payload.name ?? (typeof payload.email === 'string' ? payload.email.split('@')[0] : 'google-user'),
      email: payload.email ?? null,
      profileImageUrl: payload.picture ?? null,
    } satisfies OAuthProfile;
  }

  private async upsertOAuthUser(profile: OAuthProfile) {
    return this.prisma.user.upsert({
      where: {
        authProvider_providerUserId: {
          authProvider: AuthProvider.GOOGLE,
          providerUserId: profile.providerUserId,
        },
      },
      update: {
        nickname: profile.nickname,
        email: profile.email,
        profileImageUrl: profile.profileImageUrl,
        delYn: ACTIVE_DEL_YN,
        isGuest: false,
      },
      create: {
        nickname: profile.nickname,
        email: profile.email,
        authProvider: AuthProvider.GOOGLE,
        providerUserId: profile.providerUserId,
        profileImageUrl: profile.profileImageUrl,
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
    provider: OAuthProvider,
    query: Record<string, string | undefined>,
    cookieHeader?: string,
  ) {
    if (provider !== AuthProvider.GOOGLE) {
      throw new DomainException(HttpStatus.BAD_REQUEST, 'INVALID_REQUEST', '현재는 Google OAuth만 지원합니다.');
    }

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

    const code = query.code;
    if (!code) {
      throw new DomainException(HttpStatus.BAD_GATEWAY, 'OAUTH_PROVIDER_ERROR', 'OAuth authorization code가 없습니다.', {
        provider,
      });
    }

    const profile = await this.fetchOAuthProfile(code);
    const user = await this.upsertOAuthUser(profile);
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
