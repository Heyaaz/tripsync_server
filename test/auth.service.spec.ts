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

  it('registers local user with hashed password', async () => {
    prisma.user.findFirst.mockResolvedValue(null);
    prisma.user.create.mockImplementation(async ({ data }: any) => ({
      id: BigInt(11),
      nickname: data.nickname,
      authProvider: data.authProvider,
      isGuest: false,
    }));

    const result = await service.register({
      nickname: '민지',
      email: 'MinJi@example.com',
      password: 'abc12345',
    });

    expect(prisma.user.create).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({
          email: 'minji@example.com',
          authProvider: AuthProvider.LOCAL,
          providerUserId: 'minji@example.com',
          passwordHash: expect.stringContaining(':'),
        }),
      }),
    );
    expect(result.response.data?.user.authProvider).toBe(AuthProvider.LOCAL);
  });

  it('logs in local user with valid password', async () => {
    const passwordHash = await (await import('../src/auth/password.util')).hashPassword('abc12345');
    prisma.user.findFirst.mockResolvedValue({
      id: BigInt(12),
      nickname: '민지',
      authProvider: AuthProvider.LOCAL,
      isGuest: false,
      passwordHash,
    });

    const result = await service.login({
      email: 'minji@example.com',
      password: 'abc12345',
    });

    expect(result.response.data?.user.authProvider).toBe(AuthProvider.LOCAL);
    expect(result.sessionToken).toBeTruthy();
  });

  it('builds google oauth redirect url with scope', () => {
    process.env.GOOGLE_CLIENT_ID = 'google-client';
    process.env.GOOGLE_CALLBACK_URL = 'http://localhost:3000/api/auth/google/callback';

    const result = service.getOAuthRedirect(AuthProvider.GOOGLE, '/rooms/new');

    expect(result.redirectUrl).toContain('accounts.google.com');
    expect(result.redirectUrl).toContain('scope=openid+profile+email');
    expect(result.redirectUrl).toContain(encodeURIComponent('http://localhost:3000/api/auth/google/callback'));
  });

  it('falls back to local google identity when provider credentials are not configured', async () => {
    delete process.env.GOOGLE_CLIENT_ID;
    delete process.env.GOOGLE_CLIENT_SECRET;
    delete process.env.GOOGLE_CALLBACK_URL;
    prisma.user.upsert.mockResolvedValue({
      id: BigInt(1),
      nickname: 'google-host',
      authProvider: AuthProvider.GOOGLE,
      isGuest: false,
    });

    const result = await service.handleOAuthCallback(
      AuthProvider.GOOGLE,
      { code: 'local-google-code', state: 'state|/rooms/new' },
      'ts_oauth_state=state',
    );

    expect(prisma.user.upsert).toHaveBeenCalledWith(
      expect.objectContaining({
        create: expect.objectContaining({
          providerUserId: 'local-google-code',
          nickname: 'google-host',
        }),
      }),
    );
    expect(result.user.nickname).toBe('google-host');
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
