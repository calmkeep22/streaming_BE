import test from 'node:test';
import assert from 'node:assert/strict';
import { validateSummary } from '../current-suite.mjs';
import { serverConfig } from '../local-mysql.mjs';

test('suite validates successes and errors outside the measurement window too', () => {
  const job = { api: 'holds', expected: 1 };
  assert.deepEqual(validateSummary({ completed: 3, allStatusCount: { 201: 1, 409: 2 } }, job), []);
  assert.match(validateSummary({ completed: 3, allStatusCount: { 201: 2, 409: 1 } }, job).join(), /Expected 1/);
  assert.match(validateSummary({ completed: 3, allStatusCount: { 201: 1, 'ERR:TimeoutError': 1 } }, job).join(), /TimeoutError/);
  assert.match(validateSummary({ completed: 0, allStatusCount: {} }, { api: 'seats' }).join(), /No responses/);
});

test('server rejects the development schema and Redis DB zero', () => {
  assert.throws(() => serverConfig({ LOADTEST_DB_NAME: 'streaming' }), /dedicated/);
  assert.throws(() => serverConfig({ LOADTEST_REDIS_DATABASE: 0 }), /DATABASE/);
  const config = serverConfig({ DB_PASSWORD: 'password' });
  assert.match(config.childEnvironment.SPRING_DATASOURCE_URL, /streaming_loadtest\?/);
  assert.equal(config.childEnvironment.SPRING_DATA_REDIS_DATABASE, '15');
});
