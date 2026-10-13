import { createHmac } from 'node:crypto';

export function createAuth(secret) {
  if (!secret) throw new Error('JWT_SECRET is required');
  const key = Buffer.from(secret, 'base64');
  if (key.length < 32) throw new Error('JWT_SECRET must contain at least 32 decoded bytes');
  const tokens = new Map();
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  return userId => {
    const now = Math.floor(Date.now() / 1000);
    let cached = tokens.get(userId);
    if (!cached || cached.expires < now + 60) {
      const expires = now + 3600;
      const content = `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode({ sub: String(userId), iat: now, exp: expires })}`;
      cached = { expires, token: `${content}.${createHmac('sha256', key).update(content).digest('base64url')}` };
      tokens.set(userId, cached);
    }
    return { Authorization: `Bearer ${cached.token}` };
  };
}
