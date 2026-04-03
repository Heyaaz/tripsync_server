import { randomBytes, scrypt as scryptCallback, timingSafeEqual } from 'crypto';
import { promisify } from 'util';

const scrypt = promisify(scryptCallback);
const KEY_LENGTH = 64;

export async function hashPassword(password: string) {
  const salt = randomBytes(16).toString('hex');
  const derivedKey = (await scrypt(password, salt, KEY_LENGTH)) as Buffer;
  return `${salt}:${derivedKey.toString('hex')}`;
}

export async function verifyPassword(password: string, storedHash: string) {
  const [salt, key] = storedHash.split(':');
  if (!salt || !key) {
    return false;
  }
  const derivedKey = (await scrypt(password, salt, KEY_LENGTH)) as Buffer;
  const keyBuffer = Buffer.from(key, 'hex');
  if (derivedKey.length !== keyBuffer.length) {
    return false;
  }
  return timingSafeEqual(derivedKey, keyBuffer);
}
