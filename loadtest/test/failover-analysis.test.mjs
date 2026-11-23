import test from 'node:test';
import assert from 'node:assert/strict';
import { analyzeScenario, summarizeLatency, toCsv } from '../failover-analysis.mjs';

const failover = {
  ProbeKeyPreserved: true,
  ObservedKeyPresentBefore: true,
  ObservedKeyPreserved: true,
  Timeline: {
    FailureCommandStartedAtUtc: '2026-01-01T00:00:01.000Z',
    PrimaryUnavailableAtUtc: '2026-01-01T00:00:01.000Z',
    SentinelSubjectiveDownAtUtc: '2026-01-01T00:00:04.000Z',
    SentinelObjectiveDownAtUtc: '2026-01-01T00:00:04.500Z',
    PromotionReportedAtUtc: '2026-01-01T00:00:06.000Z',
  },
};

test('summarizeLatency calculates nearest-rank percentiles', () => {
  const records = [1, 2, 3, 4, 100].map(ms => ({ ms }));
  assert.deepEqual(summarizeLatency(records), {
    count: 5, p50Ms: 3, p95Ms: 100, p99Ms: 100, maxMs: 100,
  });
});

test('analyzeScenario builds T0-T4 and validates a single hold winner', () => {
  const records = [
    { status: 201, ms: 10, startedAtUtcMs: Date.parse('2026-01-01T00:00:00.000Z'), endedAtUtcMs: Date.parse('2026-01-01T00:00:00.010Z') },
    { status: 503, ms: 20, startedAtUtcMs: Date.parse('2026-01-01T00:00:02.000Z'), endedAtUtcMs: Date.parse('2026-01-01T00:00:02.020Z') },
    { status: 409, ms: 30, startedAtUtcMs: Date.parse('2026-01-01T00:00:06.100Z'), endedAtUtcMs: Date.parse('2026-01-01T00:00:06.130Z') },
  ];
  const summary = analyzeScenario({ records, failover, elapsedMs: 1000, configuration: { concurrency: 10 } });
  assert.equal(summary.timeline.t0ToT1Ms, 3000);
  assert.equal(summary.timeline.t1ToT2Ms, 500);
  assert.equal(summary.timeline.t2ToT3Ms, 1500);
  assert.equal(summary.timeline.t3ToT4Ms, 130);
  assert.equal(summary.timeline.t0ToT4Ms, 5130);
  assert.equal(summary.correctness.duplicateHolds, 0);
  assert.equal(summary.correctness.singleWinnerPreserved, true);
  assert.equal(summary.correctness.dataPreserved, true);
  assert.ok(Math.abs(summary.serviceUnavailablePercent - (100 / 3)) < 1e-9);
});

test('analyzeScenario detects duplicate successful holds', () => {
  const records = [
    { status: 201, ms: 1, startedAtUtcMs: 0, endedAtUtcMs: 1 },
    { status: 201, ms: 1, startedAtUtcMs: 2, endedAtUtcMs: 3 },
  ];
  const summary = analyzeScenario({ records, failover, elapsedMs: 10, configuration: { concurrency: 50 } });
  assert.equal(summary.correctness.duplicateHolds, 1);
  assert.equal(summary.correctness.singleWinnerPreserved, false);
});

test('toCsv emits one row per concurrency', () => {
  const summary = analyzeScenario({ records: [], failover, elapsedMs: 1, configuration: { concurrency: 10 } });
  const csv = toCsv([summary]);
  assert.match(csv, /^concurrency,/);
  assert.match(csv, /\n10,/);
});
