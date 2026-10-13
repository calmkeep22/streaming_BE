import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { parseEnv } from '../env.mjs';
import { createAuth } from '../auth.mjs';
import { loadConfig, percentile } from '../reserve-load.mjs';

test('env values preserve equals signs and normalize wrapping quotes', () => {
  assert.deepEqual(parseEnv('\uFEFF# comment\r\nDB_PASSWORD="a=b"\r\nexport JWT_SECRET=abc==\r\nBAD LINE'),
    { DB_PASSWORD: 'a=b', JWT_SECRET: 'abc==' });
});

test('JWT subject and signature match the configured user and decoded key', () => {
  const key = Buffer.alloc(32, 7);
  const header = createAuth(key.toString('base64'))(42);
  const [a, b, signature] = header.Authorization.slice(7).split('.');
  assert.equal(JSON.parse(Buffer.from(b, 'base64url')).sub, '42');
  assert.equal(signature, createHmac('sha256', key).update(`${a}.${b}`).digest('base64url'));
  assert.throws(() => createAuth('short'), /32/);
});

test('invalid workloads fail early and empty latency samples remain unavailable', () => {
  assert.throws(() => loadConfig({ CONCURRENCY: '0' }), /CONCURRENCY/);
  assert.throws(() => loadConfig({ DURATION_SEC: '-1' }), /DURATION_SEC/);
  assert.throws(() => loadConfig({ API: 'unknown' }), /API/);
  assert.equal(percentile([], 95), null);
  assert.equal(percentile([1, 2, 3, 4], 50), 2);
});
