// Node 18+: node loadtest/reserve-load.mjs hot|spread
// API=holds|seats|reservations. Reservations require the user's existing hold.
// DURATION_SEC=0 sends REQUEST_COUNT requests; otherwise excludes WARMUP_SEC.
import { mkdirSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { readSettings, numberOption } from './env.mjs';
import { createAuth } from './auth.mjs';

export function loadConfig(settings, mode = 'hot') {
  const api = settings.API ?? 'holds';
  if (!['holds', 'seats', 'reservations'].includes(api)) throw new Error('Unknown API');
  if (!['hot', 'spread'].includes(mode)) throw new Error('Mode must be hot or spread');
  const duration = numberOption(settings.DURATION_SEC, 'DURATION_SEC', 0, 0, false);
  return {
    api, mode,
    baseUrl: new URL(settings.BASE_URL ?? 'http://localhost:8080').origin,
    mgmtUrl: new URL(settings.MGMT_URL ?? 'http://localhost:8081').origin,
    concurrency: numberOption(settings.CONCURRENCY, 'CONCURRENCY', 200),
    duration,
    warmup: duration ? numberOption(settings.WARMUP_SEC, 'WARMUP_SEC', 5, 0, false) : 0,
    requestCount: numberOption(settings.REQUEST_COUNT, 'REQUEST_COUNT', 1000),
    userCount: numberOption(settings.USER_COUNT, 'USER_COUNT', 1000),
    seatCount: numberOption(settings.SEAT_COUNT, 'SEAT_COUNT', 100),
    eventId: numberOption(settings.EVENT_ID, 'EVENT_ID', 1),
    timeout: numberOption(settings.REQUEST_TIMEOUT_MS, 'REQUEST_TIMEOUT_MS', 15000),
  };
}

export function percentile(sorted, percent) {
  return sorted.length ? sorted[Math.max(0, Math.ceil(sorted.length * percent / 100) - 1)] : null;
}

const statuses = records => records.reduce((counts, record) => {
  counts[record.status] = (counts[record.status] ?? 0) + 1;
  return counts;
}, {});

export async function runLoad(config, headers) {
  const results = [];
  let issued = 0;
  const start = performance.now();
  const warmEnd = start + config.warmup * 1000;
  const deadline = config.duration ? warmEnd + config.duration * 1000 : Infinity;
  await Promise.all(Array.from({ length: config.concurrency }, async () => {
    while (config.duration ? performance.now() < deadline : issued < config.requestCount) {
      const userId = issued++ % config.userCount + 1;
      const seatId = config.mode === 'hot' ? 1 : (userId - 1) % config.seatCount + 1;
      const url = config.api === 'seats' ? `${config.baseUrl}/events/seats/${seatId}`
        : `${config.baseUrl}/events/${config.eventId}/seats/${seatId}/${config.api}`;
      const requestStart = performance.now();
      let status;
      try {
        const response = await fetch(url, {
          method: config.api === 'seats' ? 'GET' : 'POST', headers: headers(userId),
          signal: AbortSignal.timeout(config.timeout),
        });
        await response.arrayBuffer();
        status = response.status;
      } catch (error) {
        status = `ERR:${error.cause?.code ?? error.name}`;
      }
      results.push({ status, ms: performance.now() - requestStart, end: performance.now() });
    }
  }));
  const counted = config.duration ? results.filter(result => result.end >= warmEnd && result.end <= deadline) : results;
  const latencies = counted.map(result => result.ms).sort((a, b) => a - b);
  const elapsed = config.duration || (performance.now() - start) / 1000;
  return {
    api: config.api, mode: config.mode, concurrency: config.concurrency, at: new Date().toISOString(),
    elapsedSec: elapsed, durationSec: config.duration, warmupSec: config.warmup,
    completed: counted.length, totalIssued: issued, totalCompleted: results.length,
    throughput: counted.length / elapsed, statusCount: statuses(counted), allStatusCount: statuses(results),
    p50: percentile(latencies, 50), p95: percentile(latencies, 95), p99: percentile(latencies, 99),
    max: latencies.at(-1) ?? null,
  };
}

export function saveSummary(summary, label) {
  if (!/^[A-Za-z0-9_.-]{1,120}$/.test(label)) throw new Error('Invalid result LABEL');
  const directory = new URL('./results/', import.meta.url);
  mkdirSync(directory, { recursive: true });
  const file = new URL(`${label}-${summary.mode}-${Date.now()}.json`, directory);
  writeFileSync(file, JSON.stringify({ ...summary, label }, null, 2));
  return file;
}

export async function main() {
  const settings = readSettings();
  const config = loadConfig(settings, process.argv[2]);
  console.log(`api=${config.api}, mode=${config.mode}, concurrency=${config.concurrency}, duration=${config.duration}s, warmup=${config.warmup}s`);
  const summary = await runLoad(config, createAuth(settings.JWT_SECRET));
  console.log(JSON.stringify(summary, null, 2));
  if (settings.LABEL) console.log(`Result: ${saveSummary(summary, settings.LABEL).pathname}`);
  if (!summary.completed || Object.keys(summary.allStatusCount).some(status => status.startsWith('ERR:') || Number(status) >= 500 || [401, 403].includes(Number(status)))) {
    process.exitCode = 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
