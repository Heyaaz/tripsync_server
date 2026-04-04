import { HttpStatus, Injectable } from '@nestjs/common';
import { User } from '@prisma/client';
import { randomUUID } from 'crypto';
import { ok } from '../common/dto/api-response.dto';
import { readEnv } from '../common/env.util';
import { AuthProvider, YnFlag } from '../common/enums/domain.enums';
import { DomainException } from '../common/errors/domain.exception';
import { ACTIVE_DEL_YN, withActiveFilter } from '../common/soft-delete/soft-delete.util';
import { PrismaService } from '../prisma/prisma.service';
import { CreateGuestSessionDto } from './dto/create-guest-session.dto';
import { LoginDto } from './dto/login.dto';
import { RegisterDto } from './dto/register.dto';
import { hashPassword, verifyPassword } from './password.util';
import {
  extractCookie,
  extractSessionToken,
  getOauthStateCookieName,
  issueSessionToken,
  SessionPayload,
  verifySessionToken,
} from './session-token.util';

const SESSION_EXPIRY_SECONDS = 60 * 60 * 24 * 7;
type OAuthProvider = AuthProvider.GOOGLE | AuthProvider.KAKAO;

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

  private normalizeEmail(email: string) {
    return email.trim().toLowerCase();
  }

  private sanitizeRedirectPath(redirectPath?: string) {
    if (!redirectPath?.startsWith('/')) {
      return '/rooms/new';
    }

    return redirectPath;
  }

  private isOAuthProvider(provider: AuthProvider): provider is OAuthProvider {
    return provider === AuthProvider.GOOGLE || provider === AuthProvider.KAKAO;
  }

  private assertOAuthProvider(provider: AuthProvider): OAuthProvider {
    if (!this.isOAuthProvider(provider)) {
      throw new DomainException(HttpStatus.BAD_REQUEST, 'INVALID_REQUEST', '지원하지 않는 OAuth 공급자입니다.', {
        provider,
      });
    }

    return provider;
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

  private buildAuthSuccessResponse(user: Pick<User, 'id' | 'nickname' | 'authProvider' | 'isGuest'>) {
    return ok({
      user: {
        id: Number(user.id),
        nickname: user.nickname,
        isGuest: user.isGuest,
        authProvider: user.authProvider,
      },
      expiresIn: SESSION_EXPIRY_SECONDS,
    });
  }

  private getCallbackUrl(provider: OAuthProvider) {
    const baseApiUrl = readEnv('API_BASE_URL') ?? 'http://localhost:3000/api';

    if (provider === AuthProvider.KAKAO) {
      return readEnv('KAKAO_CALLBACK_URL') ?? `${baseApiUrl}/auth/kakao/callback`;
    }

    return readEnv('GOOGLE_CALLBACK_URL') ?? `${baseApiUrl}/auth/google/callback`;
  }

  private getFrontendBaseUrl() {
    return readEnv('FRONTEND_BASE_URL') ?? readEnv('APP_BASE_URL') ?? 'http://localhost:3001';
  }

  private getOAuthConfig(provider: OAuthProvider): OAuthConfig {
    if (provider === AuthProvider.KAKAO) {
      return {
        authorizeUrl: readEnv('KAKAO_AUTHORIZE_URL') ?? 'https://kauth.kakao.com/oauth/authorize',
        tokenUrl: readEnv('KAKAO_TOKEN_URL') ?? 'https://kauth.kakao.com/oauth/token',
        userInfoUrl: readEnv('KAKAO_USERINFO_URL') ?? 'https://kapi.kakao.com/v2/user/me',
        clientId: readEnv('KAKAO_CLIENT_ID'),
        clientSecret: readEnv('KAKAO_CLIENT_SECRET'),
        callbackUrl: this.getCallbackUrl(provider),
      };
    }

    return {
      authorizeUrl: readEnv('GOOGLE_AUTHORIZE_URL') ?? 'https://accounts.google.com/o/oauth2/v2/auth',
      tokenUrl: readEnv('GOOGLE_TOKEN_URL') ?? 'https://oauth2.googleapis.com/token',
      userInfoUrl: readEnv('GOOGLE_USERINFO_URL') ?? 'https://openidconnect.googleapis.com/v1/userinfo',
      clientId: readEnv('GOOGLE_CLIENT_ID'),
      clientSecret: readEnv('GOOGLE_CLIENT_SECRET'),
      callbackUrl: this.getCallbackUrl(provider),
      scope: 'openid profile email',
    };
  }

  private buildProviderAuthUrl(provider: OAuthProvider, state: string, redirectPath: string) {
    const config = this.getOAuthConfig(provider);
    const combinedState = `${state}|${redirectPath}`;

    if (!config.clientId) {
      return `${config.callbackUrl}?code=local-${provider}-code&state=${encodeURIComponent(combinedState)}&redirectPath=${encodeURIComponent(
        redirectPath,
      )}`;
    }

    const params = new URLSearchParams({
      response_type: 'code',
      client_id: config.clientId,
      redirect_uri: config.callbackUrl,
      state: combinedState,
    });

    if (config.scope) {
      params.set('scope', config.scope);
    }

    if (provider === AuthProvider.GOOGLE) {
      params.set('access_type', 'offline');
      params.set('prompt', 'consent');
      params.set('include_granted_scopes', 'true');
    }

    return `${config.authorizeUrl}?${params.toString()}`;
  }

  getOAuthRedirect(provider: OAuthProvider, redirectPath?: string) {
    const oauthProvider = this.assertOAuthProvider(provider);
    const normalizedRedirectPath = this.sanitizeRedirectPath(redirectPath);
    const state = randomUUID();
    const redirectUrl = this.buildProviderAuthUrl(oauthProvider, state, normalizedRedirectPath);

    return {
      state,
      redirectPath: normalizedRedirectPath,
      redirectUrl,
    };
  }

  private async exchangeAuthorizationCode(provider: OAuthProvider, code: string) {
    const config = this.getOAuthConfig(provider);
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
        provider,
        status: response.status,
        body: await response.text(),
      });
    }

    const token = (await response.json()) as { access_token?: string };
    if (!token.access_token) {
      throw new DomainException(HttpStatus.BAD_GATEWAY, 'OAUTH_PROVIDER_ERROR', 'OAuth access token이 응답에 없습니다.', {
        provider,
      });
    }

    return token.access_token;
  }

  private buildLocalOAuthProfile(provider: OAuthProvider, code: string): OAuthProfile {
    return {
      providerUserId: code,
      nickname: provider === AuthProvider.KAKAO ? 'kakao-host' : 'google-host',
      email: null,
      profileImageUrl: null,
    };
  }

  private mapOAuthProfile(provider: OAuthProvider, payload: Record<string, any>): OAuthProfile {
    if (provider === AuthProvider.KAKAO) {
      const kakaoAccount = payload.kakao_account ?? {};
      const profile = kakaoAccount.profile ?? payload.properties ?? {};

      return {
        providerUserId: String(payload.id),
        nickname: profile.nickname ?? 'kakao-user',
        email: typeof kakaoAccount.email === 'string' ? this.normalizeEmail(kakaoAccount.email) : null,
        profileImageUrl: profile.profile_image_url ?? profile.thumbnail_image_url ?? null,
      };
    }

    return {
      providerUserId: String(payload.sub),
      nickname: payload.name ?? (typeof payload.email === 'string' ? payload.email.split('@')[0] : 'google-user'),
      email: payload.email ? this.normalizeEmail(payload.email) : null,
      profileImageUrl: payload.picture ?? null,
    };
  }

  private async fetchOAuthProfile(provider: OAuthProvider, code: string) {
    const config = this.getOAuthConfig(provider);
    const accessToken = await this.exchangeAuthorizationCode(provider, code);

    if (!accessToken) {
      return this.buildLocalOAuthProfile(provider, code);
    }

    const response = await fetch(config.userInfoUrl, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
      },
    });

    if (!response.ok) {
      throw new DomainException(HttpStatus.BAD_GATEWAY, 'OAUTH_PROVIDER_ERROR', 'OAuth 사용자 정보 조회에 실패했습니다.', {
        provider,
        status: response.status,
        body: await response.text(),
      });
    }

    const payload = (await response.json()) as Record<string, any>;
    return this.mapOAuthProfile(provider, payload);
  }

  private async upsertOAuthUser(provider: OAuthProvider, profile: OAuthProfile) {
    return this.prisma.user.upsert({
      where: {
        authProvider_providerUserId: {
          authProvider: provider,
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
        authProvider: provider,
        providerUserId: profile.providerUserId,
        profileImageUrl: profile.profileImageUrl,
        adminYn: YnFlag.NO,
        isGuest: false,
        delYn: ACTIVE_DEL_YN,
      },
    });
  }

  async handleOAuthCallback(
    provider: OAuthProvider,
    query: Record<string, string | undefined>,
    cookieHeader?: string,
  ) {
    const oauthProvider = this.assertOAuthProvider(provider);

    if (query.error) {
      throw new DomainException(HttpStatus.BAD_GATEWAY, 'OAUTH_PROVIDER_ERROR', 'OAuth 공급자 오류가 발생했습니다.', {
        provider: oauthProvider,
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
        provider: oauthProvider,
      });
    }

    const profile = await this.fetchOAuthProfile(oauthProvider, code);
    const user = await this.upsertOAuthUser(oauthProvider, profile);
    const sessionToken = issueSessionToken(this.buildSessionPayload(user));
    const frontendBaseUrl = this.getFrontendBaseUrl();
    const redirectUrl = new URL(this.sanitizeRedirectPath(query.redirectPath ?? redirectPathFromState), frontendBaseUrl);
    redirectUrl.searchParams.set('login', 'success');
    redirectUrl.searchParams.set('provider', oauthProvider);

    return {
      sessionToken,
      redirectUrl: redirectUrl.toString(),
      user: {
        id: Number(user.id),
        nickname: user.nickname,
        authProvider: user.authProvider,
        isGuest: user.isGuest,
      },
    };
  }

  async register(dto: RegisterDto) {
    const email = this.normalizeEmail(dto.email);
    const existingUser = await this.prisma.user.findFirst({
      where: this.activeWhere({ email, authProvider: AuthProvider.LOCAL }),
    });

    if (existingUser) {
      throw new DomainException(HttpStatus.CONFLICT, 'INVALID_REQUEST', '이미 사용 중인 이메일입니다.');
    }

    const passwordHash = await hashPassword(dto.password);
    const user = await this.prisma.user.create({
      data: {
        nickname: dto.nickname,
        email,
        passwordHash,
        authProvider: AuthProvider.LOCAL,
        providerUserId: email,
        profileImageUrl: null,
        adminYn: YnFlag.NO,
        isGuest: false,
        delYn: ACTIVE_DEL_YN,
      },
    });

    const sessionToken = issueSessionToken(this.buildSessionPayload(user));
    return {
      sessionToken,
      response: this.buildAuthSuccessResponse(user),
    };
  }

  async login(dto: LoginDto) {
    const email = this.normalizeEmail(dto.email);
    const user = await this.prisma.user.findFirst({
      where: this.activeWhere({ email, authProvider: AuthProvider.LOCAL }),
    });

    if (!user || !user.passwordHash) {
      throw new DomainException(HttpStatus.UNAUTHORIZED, 'UNAUTHORIZED', '이메일 또는 비밀번호가 올바르지 않습니다.');
    }

    const valid = await verifyPassword(dto.password, user.passwordHash);
    if (!valid) {
      throw new DomainException(HttpStatus.UNAUTHORIZED, 'UNAUTHORIZED', '이메일 또는 비밀번호가 올바르지 않습니다.');
    }

    const sessionToken = issueSessionToken(this.buildSessionPayload(user));
    return {
      sessionToken,
      response: this.buildAuthSuccessResponse(user),
    };
  }

  getMe(user: User) {
    return ok({
      user: {
        id: Number(user.id),
        nickname: user.nickname,
        email: user.email ?? null,
        isGuest: user.isGuest,
        authProvider: user.authProvider,
      },
    });
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
          authProvider: user.authProvider,
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

  private activeWhere<T extends Record<string, unknown>>(where?: T) {
    return withActiveFilter(where);
  }

  assertHostUser(user: Pick<User, 'isGuest'>) {
    if (user.isGuest) {
      throw new DomainException(HttpStatus.FORBIDDEN, 'FORBIDDEN', '방장 권한이 필요합니다.');
    }
  }
}
