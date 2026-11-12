import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';

const baseUrl = process.env.BASE_URL ?? 'http://localhost:8080';
const concurrency = Number(process.env.CONCURRENCY ?? 8);
const seatId = Number(process.env.SEAT_ID ?? 50);
const recoverySeconds = Number(process.env.RECOVERY_SECONDS ?? 10);
const requestTimeoutMs = Number(process.env.REQUEST_TIMEOUT_MS ?? 6000);
const failoverTimeoutSeconds = Number(process.env.FAILOVER_TIMEOUT_SECONDS ?? 30);

const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const percentile = (sorted, ratio) => sorted.length
  ? sorted[Math.max(0, Math.ceil(sorted.length * ratio) - 1)]
  : null;

function summarizeLatency(records) {
  const sorted = records.map(record => record.ms).sort((left, right) => left - right);
  return {
    count: sorted.length,
    p50Ms: percentile(sorted, 0.50),
    p95Ms: percentile(sorted, 0.95),
    p99Ms: percentile(sorted, 0.99),
    maxMs: sorted.at(-1) ?? null,
  };
}

async function login() {
  let lastError;
  for (let attempt = 1; attempt <= 5; attempt++) {
    try {
      const response = await fetch(`${baseUrl}/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          email: 'user50@loadtest.local',
          password: 'loadtest-password',
        }),
        signal: AbortSignal.timeout(30000),
      });
      if (!response.ok) throw new Error(`Login failed: HTTP ${response.status}`);
      return (await response.json()).accessToken;
    } catch (error) {
      lastError = error;
      if (attempt < 5) await sleep(2000);
    }
  }
  throw lastError;
}

function runFailover() {
  return new Promise((resolve, reject) => {
    const child = spawn(
      'powershell.exe',
      [
        '-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', 'loadtest/redis-sentinel-failover.ps1',
        '-SkipStartup', '-TimeoutSeconds', String(failoverTimeoutSeconds),
      ],
      { cwd: process.cwd(), windowsHide: true },
    );
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', chunk => { stdout += chunk; });
    child.stderr.on('data', chunk => { stderr += chunk; });
    child.on('error', reject);
    child.on('exit', code => {
      if (code !== 0) {
        reject(new Error(`Failover script exited ${code}\n${stdout}\n${stderr}`));
        return;
      }
      const match = stdout.match(/\{[\s\S]*?"PromotedRoleAfterFailover"[\s\S]*?\}/);
      if (!match) {
        reject(new Error(`Failover JSON was not found\n${stdout}\n${stderr}`));
        return;
      }
      resolve(JSON.parse(match[0]));
    });
  });
}

async function main() {
  if (!Number.isInteger(concurrency) || concurrency < 1) throw new Error('CONCURRENCY must be a positive integer');
  const token = await login();
  const records = [];
  const experimentStartedAt = performance.now();
  let keepRunning = true;
  let issued = 0;

  const workers = Array.from({ length: concurrency }, async () => {
    while (keepRunning) {
      const sequence = issued++;
      const startedAt = performance.now();
      let status;
      try {
        const response = await fetch(`${baseUrl}/events/1/seats/${seatId}/holds`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${token}` },
          signal: AbortSignal.timeout(requestTimeoutMs),
        });
        await response.arrayBuffer();
        status = response.status;
      } catch (error) {
        status = `ERR:${error.cause?.code ?? error.name}`;
      }
      const endedAt = performance.now();
      records.push({
        sequence,
        status,
        ms: endedAt - startedAt,
        startOffsetMs: startedAt - experimentStartedAt,
        endOffsetMs: endedAt - experimentStartedAt,
      });
    }
  });

  const failoverProcessStartedAt = performance.now();
  let failover;
  let failoverError = null;
  let failoverCompletedAt;
  try {
    try {
      failover = await runFailover();
    } catch (error) {
      failoverError = error.message;
    }
    failoverCompletedAt = performance.now();
    await sleep(recoverySeconds * 1000);
  } finally {
    keepRunning = false;
    await Promise.all(workers);
  }

  records.sort((left, right) => left.endOffsetMs - right.endOffsetMs);
  const statusCount = records.reduce((counts, record) => {
    counts[record.status] = (counts[record.status] ?? 0) + 1;
    return counts;
  }, {});
  const unavailable = records.filter(record => record.status === 503 || String(record.status).startsWith('ERR:'));
  const firstUnavailable = unavailable.at(0) ?? null;
  const lastUnavailable = unavailable.at(-1) ?? null;
  const recovered = lastUnavailable
    ? records.find(record => record.endOffsetMs > lastUnavailable.endOffsetMs && (record.status === 201 || record.status === 409)) ?? null
    : null;
  const successfulTransport = records.filter(record => record.status === 201 || record.status === 409);
  const experimentElapsedMs = performance.now() - experimentStartedAt;

  const summary = {
    at: new Date().toISOString(),
    configuration: { concurrency, seatId, recoverySeconds, requestTimeoutMs, failoverTimeoutSeconds },
    failover,
    failoverError,
    failoverProcessElapsedMs: failoverCompletedAt - failoverProcessStartedAt,
    failoverCompletedOffsetMs: failoverCompletedAt - experimentStartedAt,
    experimentElapsedMs,
    completedRequests: records.length,
    throughputPerSecond: records.length / (experimentElapsedMs / 1000),
    statusCount,
    availabilityPercent: records.length ? successfulTransport.length / records.length * 100 : null,
    latency: {
      all: summarizeLatency(records),
      normal: summarizeLatency(successfulTransport),
      unavailable: summarizeLatency(unavailable),
    },
    impactWindow: {
      firstUnavailableOffsetMs: firstUnavailable?.endOffsetMs ?? null,
      lastUnavailableOffsetMs: lastUnavailable?.endOffsetMs ?? null,
      firstRecoveredOffsetMs: recovered?.endOffsetMs ?? null,
      observedRecoveryAfterLastFailureMs: recovered && lastUnavailable
        ? recovered.endOffsetMs - lastUnavailable.endOffsetMs
        : null,
    },
  };

  const directory = new URL('./results/', import.meta.url);
  mkdirSync(directory, { recursive: true });
  const file = new URL(`sentinel-failover-load-${Date.now()}.json`, directory);
  writeFileSync(file, JSON.stringify({ ...summary, records }, null, 2));
  console.log(JSON.stringify(summary, null, 2));
  console.log(`Result: ${file.pathname}`);
}

await main();
