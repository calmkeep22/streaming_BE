// node loadtest/current-suite.mjs starts a fresh dedicated test server and stops it.
// DURATION_SEC, WARMUP_SEC, CONCURRENCY, READ_CONCURRENCIES, LOADTEST_ENV_FILE configure runs.
import { mkdirSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { readSettings, numberOption } from './env.mjs';
import { createAuth } from './auth.mjs';
import { startServer } from './local-mysql.mjs';
import { loadConfig, runLoad, saveSummary } from './reserve-load.mjs';

export function validateSummary(summary, job) {
  const errors = [];
  const allowed = job.api === 'seats' ? ['200'] : ['201', '409'];
  for (const [status, count] of Object.entries(summary.allStatusCount)) {
    if (!allowed.includes(status) && count) errors.push(`${status}: ${count}`);
  }
  if (!summary.completed) errors.push('No responses completed in the measurement window');
  if (job.expected !== undefined && (summary.allStatusCount['201'] ?? 0) !== job.expected) {
    errors.push(`Expected ${job.expected} successful holds, got ${summary.allStatusCount['201'] ?? 0}`);
  }
  return errors;
}

export async function main() {
  const settings = readSettings();
  const headers = createAuth(settings.JWT_SECRET);
  const appPort = numberOption(settings.APP_PORT, 'APP_PORT', 8080);
  const mgmtPort = numberOption(settings.MGMT_PORT, 'MGMT_PORT', 8081);
  const baseUrl = settings.BASE_URL ?? `http://localhost:${appPort}`;
  const mgmtUrl = settings.MGMT_URL ?? `http://localhost:${mgmtPort}`;
  const existing = process.argv.includes('--existing-server');
  const child = existing ? null : await startServer(settings);
  const stop = () => child?.kill();
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
  const label = settings.LABEL ?? `mysql-${Date.now()}`;
  const summaries = [];
  try {
    const deadline = performance.now() + numberOption(settings.SERVER_START_TIMEOUT_MS, 'SERVER_START_TIMEOUT_MS', 120000);
    let ready = false;
    while (performance.now() < deadline) {
      if (child && (child.exitCode !== null || child.signalCode !== null)) throw new Error('Server exited before readiness');
      try {
        const health = await fetch(`${mgmtUrl}/actuator/health`, { headers: headers(1), signal: AbortSignal.timeout(3000) });
        const seat = await fetch(`${baseUrl}/events/seats/100`, { headers: headers(1), signal: AbortSignal.timeout(3000) });
        ready = health.ok && (await health.json()).status === 'UP' && seat.ok;
        await seat.arrayBuffer();
        if (ready) break;
      } catch {}
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
    if (!ready) throw new Error('Server or seeded data did not become ready');
    for (let seatId = 1; seatId <= 100; seatId++) {
      const response = await fetch(`${baseUrl}/events/seats/${seatId}`, { headers: headers(1), signal: AbortSignal.timeout(15000) });
      if (!response.ok || (await response.json()).status !== 'AVAILABLE') {
        throw new Error(`Seat ${seatId} is not available; suite requires fresh loadtest data`);
      }
    }
    const duration = numberOption(settings.DURATION_SEC, 'DURATION_SEC', 30, 0.1, false);
    const warmup = numberOption(settings.WARMUP_SEC, 'WARMUP_SEC', 5, 0, false);
    const concurrency = numberOption(settings.CONCURRENCY, 'CONCURRENCY', 200);
    const readLevels = (settings.READ_CONCURRENCIES ?? '50,200').split(',').map(value => numberOption(value, 'READ_CONCURRENCIES', 50));
    const jobs = [
      { api: 'holds', mode: 'hot', concurrency, duration: 0, expected: 1 },
      { api: 'holds', mode: 'spread', concurrency, duration: 0, expected: 99 },
      { api: 'holds', mode: 'hot', concurrency, duration },
      { api: 'holds', mode: 'spread', concurrency, duration },
      ...readLevels.map(concurrency => ({ api: 'seats', mode: 'spread', concurrency, duration })),
    ];
    for (const job of jobs) {
      const config = loadConfig({ ...settings, BASE_URL: baseUrl, MGMT_URL: mgmtUrl,
        API: job.api, CONCURRENCY: job.concurrency, DURATION_SEC: job.duration, WARMUP_SEC: warmup, SAMPLE_METRICS: settings.SAMPLE_METRICS ?? '1',
        EVENT_ID: 1, USER_COUNT: 1000, SEAT_COUNT: 100, REQUEST_COUNT: 1000 }, job.mode);
      console.log(`Running ${job.api}/${job.mode}, concurrency=${job.concurrency}, duration=${job.duration}s`);
      const summary = await runLoad(config, headers);
      const errors = validateSummary(summary, job);
      summary.validation = { valid: errors.length === 0, errors };
      console.log(JSON.stringify(summary, null, 2));
      saveSummary(summary, `${label}-${job.api}-c${job.concurrency}-${job.duration ? 'timed' : 'finite'}`);
      summaries.push(summary);
      if (errors.length) throw new Error(errors.join('; '));
    }
    const finalHealth = await fetch(`${mgmtUrl}/actuator/health`, { headers: headers(1), signal: AbortSignal.timeout(10000) });
    if (!finalHealth.ok || (await finalHealth.json()).status !== 'UP') throw new Error('Final health is not UP');
    console.log('Suite passed; final health UP');
  } finally {
    if (/^[A-Za-z0-9_.-]{1,90}$/.test(label)) {
      const directory = new URL('./results/', import.meta.url);
      mkdirSync(directory, { recursive: true });
      writeFileSync(new URL(`${label}-suite.json`, directory), JSON.stringify(summaries, null, 2));
    }
    stop();
    process.removeListener('SIGINT', stop);
    process.removeListener('SIGTERM', stop);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
