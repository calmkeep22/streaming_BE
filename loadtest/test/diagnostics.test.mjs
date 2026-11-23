import test from 'node:test';
import assert from 'node:assert/strict';
import { cpuUsage, redactArguments } from '../diagnostics.mjs';

test('diagnostic arguments never retain Redis passwords', () => {
  assert.deepEqual(redactArguments(['exec', 'redis-cli', '-a', 'private', 'ROLE']),
    ['exec', 'redis-cli', '-a', '[REDACTED]', 'ROLE']);
});

test('host CPU uses deltas and leaves a zero-length interval unavailable', () => {
  assert.equal(cpuUsage({ total: 100, idle: 20 }, { total: 200, idle: 45 }), 75);
  assert.equal(cpuUsage({ total: 100, idle: 20 }, { total: 100, idle: 20 }), null);
});
