import test from 'node:test';
import assert from 'node:assert/strict';
import { sumMetric, parseSnapshot, metricWindow } from '../metrics.mjs';

test('HTTP snapshots select the exact read route and combine statuses', () => {
  const snapshot = parseSnapshot([
    'http_server_requests_seconds_sum{uri="/events/seats/{seatId}",status="200"} 4',
    'http_server_requests_seconds_count{uri="/events/seats/{seatId}",status="200"} 2',
    'http_server_requests_seconds_count{uri="/events/seats/{seatId}",status="500"} 1',
    'http_server_requests_seconds_count{uri="/actuator/health"} 99',
  ].join('\n'), 'seats');
  assert.equal(snapshot.httpCount, 3);
  assert.equal(snapshot.httpSum, 4);
  assert.equal(snapshot.acquireCount, null);
  assert.equal(sumMetric('x 0', 'x'), 0);
});

test('missing, delayed and reset snapshots are not reported as zero latency', () => {
  const a = { httpCount: 10, httpSum: 2, receivedAt: 1000, lagMs: 0, acquireSum: null, acquireCount: null, redisSum: 1, redisCount: 10 };
  const b = { ...a, httpCount: 20, httpSum: 4, redisSum: 2, redisCount: 20, receivedAt: 3000 };
  assert.equal(metricWindow(null, b).valid, false);
  assert.equal(metricWindow(a, { ...b, lagMs: 3000 }).valid, false);
  assert.equal(metricWindow(a, { ...b, httpCount: 0 }).valid, false);
  assert.deepEqual(metricWindow(a, b), { valid: true, elapsedSec: 2, apiAvgSec: 0.2, throughput: 5, acquireAvgSec: null, redisHoldAvgSec: 0.1 });
});
