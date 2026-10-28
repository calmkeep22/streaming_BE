import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createHmac } from 'node:crypto';
import { spawn } from 'node:child_process';

const line = readFileSync('.env', 'utf8').split(/\r?\n/).find(x => /^JWT_SECRET=/.test(x));
const raw = line?.slice(line.indexOf('=') + 1).trim();
if (!raw) throw new Error('JWT_SECRET missing');
const secret = /^(".*"|'.*')$/.test(raw) ? raw.slice(1, -1) : raw;
const enc = value => Buffer.from(JSON.stringify(value)).toString('base64url');
const now = Math.floor(Date.now() / 1000);
const payload = `${enc({ alg: 'HS256', typ: 'JWT' })}.${enc({ sub: '1', iat: now, exp: now + 3600 })}`;
const token = `${payload}.${createHmac('sha256', Buffer.from(secret, 'base64')).update(payload).digest('base64url')}`;
const headers = { Authorization: `Bearer ${token}` };
for (const url of ['http://localhost:8081/actuator/health', 'http://localhost:8080/events/seats/100']) {
  const response = await fetch(url, { headers, signal: AbortSignal.timeout(10000) });
  console.log(`Preflight ${url}: ${response.status}`);
  if (response.status !== 200) throw new Error(`Preflight failed: ${await response.text()}`);
  if (url.endsWith('health')) {
    const health = await response.json();
    console.log(`Health: ${health.status}`);
    if (health.status !== 'UP') throw new Error('Application health is not UP');
  } else await response.arrayBuffer();
}
if (process.argv.includes('--diagnostics')) {
  const response = await fetch('http://localhost:8081/actuator/prometheus', { headers, signal: AbortSignal.timeout(15000) });
  if (!response.ok) throw new Error(`Metrics HTTP ${response.status}`);
  const metrics = await response.text();
  const file = 'loadtest/results/local-mysql-20261013-final.prometheus.txt';
  writeFileSync(file, metrics);
  const sums = {};
  for (const line of metrics.split('\n')) {
    const match = line.match(/^(experiment_(?:getseat|service)[^ {]*_seconds_(?:sum|count)|hikaricp_connections_acquire_seconds_(?:sum|count))(?:\{[^}]*\})? (\S+)/);
    if (match) sums[match[1]] = (sums[match[1]] || 0) + Number(match[2]);
  }
  for (const [name, sum] of Object.entries(sums)) if (name.endsWith('_sum')) {
    const count = sums[name.replace(/_sum$/, '_count')];
    console.log(`${name.replace(/_seconds_sum$/, '')}: average=${(sum / count * 1000).toFixed(2)}ms count=${count}`);
  }
  console.log(`Final metrics saved: ${file}`);
  process.exit(0);
}
const label = `local-mysql-20261013-${Date.now()}`;
const jobs = [
  { api: 'holds', mode: 'hot', concurrency: 200, duration: 0, expected: 1 },
  { api: 'holds', mode: 'spread', concurrency: 200, duration: 0, expected: 99 },
  { api: 'holds', mode: 'hot', concurrency: 200, duration: 30 },
  { api: 'holds', mode: 'spread', concurrency: 200, duration: 30 },
  { api: 'seats', mode: 'spread', concurrency: 50, duration: 30 },
  { api: 'seats', mode: 'spread', concurrency: 200, duration: 30 },
];
mkdirSync('loadtest/results/logs', { recursive: true });
for (const job of jobs) {
  const tag = `${label}-${job.api}-c${job.concurrency}-${job.duration ? 'timed' : 'finite'}`;
  console.log(`\nStarting ${tag} ${job.mode}${job.expected !== undefined ? ` (expected 201=${job.expected}, total=1000)` : ''}`);
  let output = '';
  const child = spawn(process.execPath, ['loadtest/reserve-load.mjs', job.mode], {
    env: { ...process.env, JWT_SECRET: secret, API: job.api, CONCURRENCY: String(job.concurrency),
      DURATION_SEC: String(job.duration), WARMUP_SEC: job.duration ? '5' : '0',
      SAMPLE_METRICS: '1', LABEL: tag }, shell: false, stdio: ['ignore', 'pipe', 'pipe'],
  });
  for (const stream of [child.stdout, child.stderr]) stream.on('data', data => { output += data.toString(); process.stdout.write(data); });
  const code = await new Promise((resolve, reject) => { child.on('error', reject); child.on('exit', resolve); });
  writeFileSync(`loadtest/results/logs/${tag}-${job.mode}.txt`, output);
  if (code !== 0) throw new Error(`Load job failed: ${code}`);
}
console.log(`Suite complete: ${label}`);
