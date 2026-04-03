import { AuthProvider } from '../src/common/enums/domain.enums';
import { issueSessionToken, verifySessionToken } from '../src/auth/session-token.util';

describe('session-token util', () => {
  it('rejects malformed signatures without throwing', () => {
    const token = issueSessionToken({
      sub: 1,
      provider: AuthProvider.GUEST,
      isGuest: true,
      nickname: '민지',
      exp: Math.floor(Date.now() / 1000) + 60,
    });

    const malformed = `${token.slice(0, -1)}x`;
    expect(() => verifySessionToken(malformed)).not.toThrow();
    expect(verifySessionToken(malformed)).toBeNull();
  });
});
