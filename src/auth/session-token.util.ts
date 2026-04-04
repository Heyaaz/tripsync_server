import { createHmac, timingSafeEqual } from 'crypto';
import { AuthProvider } from '../common/enums/domain.enums';
import { readEnv } from '../common/env.util';

export interface SessionPayload {
  sub: number;
  provider: AuthProvider;
  isGuest: boolean;
  nickname: string;
  exp: number;
}

const DEFAULT_SECRET = 'tripsync-local-dev-secret';
const SESSION_COOKIE_NAME = 'ts_access_token';
const OAUTH_STATE_COOKIE_NAME = 'ts_oauth_state';

function base64UrlEncode(input: string) {
  return Buffer.from(input).toString('base64url');
}

function base64UrlDecode(input: string) {
  return Buffer.from(input, 'base64url').toString('utf8');
}

function sign(unsignedToken: string, secret: string) {
  return createHmac('sha256', secret).update(unsignedToken).digest('base64url');
}

export function getSessionCookieName() {
  return SESSION_COOKIE_NAME;
}

export function getOauthStateCookieName() {
  return OAUTH_STATE_COOKIE_NAME;
}

export function issueSessionToken(payload: SessionPayload, secret = readEnv('JWT_SECRET') ?? DEFAULT_SECRET) {
  const header = base64UrlEncode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = base64UrlEncode(JSON.stringify(payload));
  const unsigned = `${header}.${body}`;
  const signature = sign(unsigned, secret);
  return `${unsigned}.${signature}`;
}

export function verifySessionToken(token: string, secret = readEnv('JWT_SECRET') ?? DEFAULT_SECRET) {
  const [header, body, signature] = token.split('.');
  if (!header || !body || !signature) {
    return null;
  }

  const unsigned = `${header}.${body}`;
  const expected = sign(unsigned, secret);
  if (signature.length !== expected.length) {
    return null;
  }
  const valid = timingSafeEqual(Buffer.from(signature), Buffer.from(expected));
  if (!valid) {
    return null;
  }

  const payload = JSON.parse(base64UrlDecode(body)) as SessionPayload;
  if (payload.exp * 1000 <= Date.now()) {
    return null;
  }
  return payload;
}

export function extractCookie(cookieHeader: string | undefined, name: string) {
  if (!cookieHeader) {
    return null;
  }

  const target = cookieHeader
    .split(';')
    .map((entry) => entry.trim())
    .find((entry) => entry.startsWith(`${name}=`));

  return target ? decodeURIComponent(target.split('=').slice(1).join('=')) : null;
}

export function extractSessionToken(authorization?: string, cookieHeader?: string) {
  if (authorization?.startsWith('Bearer ')) {
    return authorization.slice(7).trim();
  }

  return extractCookie(cookieHeader, SESSION_COOKIE_NAME);
}

export function buildCookieHeader(name: string, value: string, maxAgeSeconds: number) {
  const secure = process.env.NODE_ENV === 'production' ? '; Secure' : '';
  return `${name}=${encodeURIComponent(value)}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${maxAgeSeconds}${secure}`;
}

export function buildExpiredCookieHeader(name: string) {
  const secure = process.env.NODE_ENV === 'production' ? '; Secure' : '';
  return `${name}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0${secure}`;
}
