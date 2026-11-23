const percentile = (sorted, ratio) => sorted.length
  ? sorted[Math.max(0, Math.ceil(sorted.length * ratio) - 1)]
  : null;

export function summarizeLatency(records) {
  const sorted = records.map(record => record.ms).sort((left, right) => left - right);
  return {
    count: sorted.length,
    p50Ms: percentile(sorted, 0.50),
    p95Ms: percentile(sorted, 0.95),
    p99Ms: percentile(sorted, 0.99),
    maxMs: sorted.at(-1) ?? null,
  };
}

function countStatuses(records) {
  return records.reduce((counts, record) => {
    counts[record.status] = (counts[record.status] ?? 0) + 1;
    return counts;
  }, {});
}

function summarizePhase(records) {
  return {
    requests: records.length,
    statusCount: countStatuses(records),
    latency: summarizeLatency(records),
  };
}

export function analyzeScenario({ records, failover, elapsedMs, configuration }) {
  const timeline = failover?.Timeline ?? {};
  const t0 = Date.parse(timeline.FailureCommandStartedAtUtc);
  const primaryUnavailableDetected = Date.parse(timeline.PrimaryUnavailableAtUtc);
  const t1 = Date.parse(timeline.SentinelSubjectiveDownAtUtc);
  const t2 = Date.parse(timeline.SentinelObjectiveDownAtUtc);
  const t3 = Date.parse(timeline.PromotionReportedAtUtc);
  const healthy = record => record.status === 201 || record.status === 409;
  const unavailable = record => record.status === 503 || String(record.status).startsWith('ERR:');
  const firstHealthyAfterPromotion = Number.isFinite(t3)
    ? records.find(record => record.startedAtUtcMs >= t3 && healthy(record)) ?? null
    : null;
  const t4 = firstHealthyAfterPromotion?.endedAtUtcMs ?? null;
  const statusCount = countStatuses(records);
  const healthyRecords = records.filter(healthy);
  const unavailableRecords = records.filter(unavailable);
  const createdCount = statusCount[201] ?? 0;
  const total = records.length;

  return {
    at: new Date().toISOString(),
    configuration,
    failover,
    timeline: {
      t0FailureCommandStartedAtUtc: Number.isFinite(t0) ? new Date(t0).toISOString() : null,
      primaryUnavailableDetectedAtUtc: Number.isFinite(primaryUnavailableDetected)
        ? new Date(primaryUnavailableDetected).toISOString()
        : null,
      t1SentinelSubjectiveDownAtUtc: Number.isFinite(t1) ? new Date(t1).toISOString() : null,
      t2SentinelObjectiveDownAtUtc: Number.isFinite(t2) ? new Date(t2).toISOString() : null,
      t3PromotionReportedAtUtc: Number.isFinite(t3) ? new Date(t3).toISOString() : null,
      t4FirstHealthyResponseAtUtc: t4 ? new Date(t4).toISOString() : null,
      t0ToT1Ms: Number.isFinite(t0) && Number.isFinite(t1) ? t1 - t0 : null,
      t0ToPrimaryUnavailableDetectedMs: Number.isFinite(t0) && Number.isFinite(primaryUnavailableDetected)
        ? primaryUnavailableDetected - t0
        : null,
      t1ToT2Ms: Number.isFinite(t1) && Number.isFinite(t2) ? t2 - t1 : null,
      t2ToT3Ms: Number.isFinite(t2) && Number.isFinite(t3) ? t3 - t2 : null,
      t3ToT4Ms: Number.isFinite(t3) && t4 ? t4 - t3 : null,
      t0ToT4Ms: Number.isFinite(t0) && t4 ? t4 - t0 : null,
    },
    experimentElapsedMs: elapsedMs,
    completedRequests: total,
    throughputPerSecond: elapsedMs > 0 ? total / (elapsedMs / 1000) : null,
    statusCount,
    availabilityPercent: total ? healthyRecords.length / total * 100 : null,
    serviceUnavailablePercent: total ? (statusCount[503] ?? 0) / total * 100 : null,
    transportErrorPercent: total
      ? records.filter(record => String(record.status).startsWith('ERR:')).length / total * 100
      : null,
    latency: {
      all: summarizeLatency(records),
      healthy: summarizeLatency(healthyRecords),
      unavailable: summarizeLatency(unavailableRecords),
    },
    phases: {
      beforeFailure: summarizePhase(Number.isFinite(t0)
        ? records.filter(record => record.endedAtUtcMs < t0)
        : []),
      failover: summarizePhase(Number.isFinite(t0) && t4
        ? records.filter(record => record.endedAtUtcMs >= t0 && record.endedAtUtcMs < t4)
        : []),
      afterRecovery: summarizePhase(t4
        ? records.filter(record => record.endedAtUtcMs >= t4)
        : []),
    },
    correctness: {
      successfulHolds: createdCount,
      duplicateHolds: Math.max(0, createdCount - 1),
      singleWinnerPreserved: createdCount <= 1,
      probeKeyPreserved: failover?.ProbeKeyPreserved === true,
      observedSeatKeyPresentBefore: failover?.ObservedKeyPresentBefore ?? null,
      observedSeatKeyPreserved: failover?.ObservedKeyPreserved ?? null,
      dataPreserved: failover?.ProbeKeyPreserved === true && failover?.ObservedKeyPreserved !== false,
    },
  };
}

export function toCsv(summaries) {
  const columns = [
    'concurrency', 'completedRequests', 'throughputPerSecond', 'availabilityPercent',
    'serviceUnavailablePercent', 'transportErrorPercent', 'p95Ms', 'p99Ms',
    't0ToT1Ms', 't1ToT2Ms', 't2ToT3Ms', 't3ToT4Ms', 't0ToT4Ms',
    'successfulHolds', 'duplicateHolds', 'singleWinnerPreserved', 'dataPreserved',
  ];
  const rows = summaries.map(summary => [
    summary.configuration.concurrency,
    summary.completedRequests,
    summary.throughputPerSecond,
    summary.availabilityPercent,
    summary.serviceUnavailablePercent,
    summary.transportErrorPercent,
    summary.latency.all.p95Ms,
    summary.latency.all.p99Ms,
    summary.timeline.t0ToT1Ms,
    summary.timeline.t1ToT2Ms,
    summary.timeline.t2ToT3Ms,
    summary.timeline.t3ToT4Ms,
    summary.timeline.t0ToT4Ms,
    summary.correctness.successfulHolds,
    summary.correctness.duplicateHolds,
    summary.correctness.singleWinnerPreserved,
    summary.correctness.dataPreserved,
  ]);
  return [columns, ...rows].map(row => row.map(value => value ?? '').join(',')).join('\n') + '\n';
}
