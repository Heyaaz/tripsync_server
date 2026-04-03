import { AuthService } from '../src/auth/auth.service';
import { AuthProvider } from '../src/common/enums/domain.enums';

const originalEnv = { ...process.env };

describe('AuthService', () => {
  const prisma = {
    user: {
      upsert: jest.fn(),
      create: jest.fn(),
      findFirst: jest.fn(),
    },
  };

  let service: AuthService;

  beforeEach(() => {
    jest.clearAllMocks();
    process.env = { ...originalEnv };
    service = new AuthService(prisma as any);
  });

  afterAll(() => {
    process.env = originalEnv;
  });

  it('builds google oauth redirect url with scope', () => {
    process.env.GOOGLE_CLIENT_ID = 'google-client';
    process.env.GOOGLE_CALLBACK_URL = 'http://localhost:3000/api/auth/google/callback';

    const result = service.getOAuthRedirect(AuthProvider.GOOGLE, '/rooms/new');

    expect(result.redirectUrl).toContain('accounts.google.com');
    expect(result.redirectUrl).toContain('scope=openid+profile+email');
    expect(result.redirectUrl).toContain(encodeURIComponent('http://localhost:3000/api/auth/google/callback'));
  });

  it('falls back to local identity when provider credentials are not configured', async () => {
    prisma.user.upsert.mockResolvedValue({
      id: BigInt(1),
      nickname: 'kakao-host',
      authProvider: AuthProvider.KAKAO,
      isGuest: false,
    });

    const result = await service.handleOAuthCallback(
      AuthProvider.KAKAO,
      { code: 'local-kakao-code', state: 'state|/rooms/new' },
      'ts_oauth_state=state',
    );

    expect(prisma.user.upsert).toHaveBeenCalledWith(
      expect.objectContaining({
        create: expect.objectContaining({
          providerUserId: 'local-kakao-code',
          nickname: 'kakao-host',
        }),
      }),
    );
    expect(result.user.nickname).toBe('kakao-host');
    expect(result.redirectUrl).toContain('/rooms/new?login=success');
  });

  it('exchanges google oauth code and upserts provider profile', async () => {
    process.env.GOOGLE_CLIENT_ID = 'google-client';
    process.env.GOOGLE_CLIENT_SECRET = 'google-secret';
    process.env.GOOGLE_CALLBACK_URL = 'http://localhost:3000/api/auth/google/callback';

    const fetchMock = jest
      .spyOn(global, 'fetch' as any)
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ access_token: 'google-access-token' }),
      } as Response)
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          sub: 'google-user-1',
          name: 'Trip Sync',
          email: 'trip@example.com',
          picture: 'https://example.com/profile.png',
        }),
      } as Response);

    prisma.user.upsert.mockResolvedValue({
      id: BigInt(3),
      nickname: 'Trip Sync',
      authProvider: AuthProvider.GOOGLE,
      isGuest: false,
    });

    const result = await service.handleOAuthCallback(
      AuthProvider.GOOGLE,
      { code: 'oauth-code', state: 'state|/rooms/new' },
      'ts_oauth_state=state',
    );

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(prisma.user.upsert).toHaveBeenCalledWith(
      expect.objectContaining({
        where: {
          authProvider_providerUserId: {
            authProvider: AuthProvider.GOOGLE,
            providerUserId: 'google-user-1',
          },
        },
        create: expect.objectContaining({
          nickname: 'Trip Sync',
          email: 'trip@example.com',
          profileImageUrl: 'https://example.com/profile.png',
        }),
      }),
    );
    expect(result.user.authProvider).toBe(AuthProvider.GOOGLE);

    fetchMock.mockRestore();
  });
});
