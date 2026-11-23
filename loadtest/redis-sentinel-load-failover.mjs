import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { createAuth } from './auth.mjs';
import { numberOption, readSettings } from './env.mjs';
import { analyzeScenario, toCsv } from './failover-analysis.mjs';
import { fileURLToPath } from 'node:url';
import { createDiagnostics, redactArguments } from './diagnostics.mjs';

const settings = readSettings();
// Docker Desktop에서 localhost가 ::1로 해석되면 IPv6 포트 프록시가 연결을 reset하는 경우가 있어 IPv4를 고정한다.
const baseUrl = settings.BASE_URL ?? 'http://127.0.0.1:8080';
const composeFile = settings.COMPOSE_FILE ?? 'docker-compose.sentinel.yml';
const seatId = numberOption(settings.SEAT_ID, 'SEAT_ID', 50);
const warmupSeconds = numberOption(settings.WARMUP_SECONDS, 'WARMUP_SECONDS', 3, 0, false);
const recoverySeconds = numberOption(settings.RECOVERY_SECONDS, 'RECOVERY_SECONDS', 10, 0, false);
const requestTimeoutMs = numberOption(settings.REQUEST_TIMEOUT_MS, 'REQUEST_TIMEOUT_MS', 6000);
const failoverTimeoutSeconds = numberOption(settings.FAILOVER_TIMEOUT_SECONDS, 'FAILOVER_TIMEOUT_SECONDS', 45);
const commandTimeoutSeconds = numberOption(settings.COMMAND_TIMEOUT_SECONDS, 'COMMAND_TIMEOUT_SECONDS', 90);
const failoverProcessTimeoutSeconds = numberOption(
  settings.FAILOVER_PROCESS_TIMEOUT_SECONDS,
  'FAILOVER_PROCESS_TIMEOUT_SECONDS',
  240,
);
const baselineMinAvailability = numberOption(
  settings.BASELINE_MIN_AVAILABILITY,
  'BASELINE_MIN_AVAILABILITY',
  95,
  0,
  false,
);
if (baselineMinAvailability > 100) throw new Error('BASELINE_MIN_AVAILABILITY must be <= 100');
const concurrencyValues = (settings.CONCURRENCIES ?? settings.CONCURRENCY ?? '10,50,100')
  .split(',')
  .map(value => numberOption(value.trim(), 'CONCURRENCIES', undefined));
const jwtSecret = settings.JWT_SECRET ?? 'dGVzdC1vbmx5LWp3dC1zZWNyZXQta2V5LTMyLWJ5dGVzISE=';
const authHeaders = createAuth(jwtSecret);
const redisPassword = settings.REDIS_PASSWORD ?? 'streaming-redis-dev';
const sentinelPassword = settings.REDIS_SENTINEL_PASSWORD ?? 'streaming-sentinel-dev';
const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const activeChildren = new Set();
const resultsDirectory = new URL('./results/', import.meta.url);
mkdirSync(resultsDirectory, { recursive: true });
const runId = Date.now();
const diagnosticFile = new URL(`sentinel-diagnostics-${runId}.jsonl`, resultsDirectory);
const powerShellDiagnosticFile = new URL(`sentinel-commands-${runId}.jsonl`, resultsDirectory);
const diagnostics = createDiagnostics(diagnosticFile);
console.log(`Diagnostics: ${fileURLToPath(diagnosticFile)}`);
console.log(`PowerShell commands: ${fileURLToPath(powerShellDiagnosticFile)}`);

function terminateProcessTree(child) {
  if (!child || child.exitCode !== null) return;
  if (process.platform === 'win32') {
    spawn('taskkill.exe', ['/pid', String(child.pid), '/T', '/F'], {
      windowsHide: true,
      stdio: 'ignore',
    });
  } else {
    child.kill('SIGTERM');
  }
}

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.once(signal, () => {
    for (const child of activeChildren) terminateProcessTree(child);
    setTimeout(() => process.exit(130), 500).unref();
  });
}

function runProcess(command, args, timeoutMs = commandTimeoutSeconds * 1000) {
  return new Promise((resolve, reject) => {
    const commandId = crypto.randomUUID();
    const started = performance.now();
    diagnostics.write({ event: 'command_start', commandId, command, args: redactArguments(args), timeoutMs });
    const child = spawn(command, args, {
      cwd: process.cwd(), windowsHide: true,
      env: { ...process.env, LOADTEST_DIAGNOSTICS_PATH: fileURLToPath(powerShellDiagnosticFile) },
    });
    activeChildren.add(child);
    let stdout = '';
    let stderr = '';
    let settled = false;
    const finish = (callback, outcome) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      activeChildren.delete(child);
      diagnostics.write({ event: 'command_end', commandId, command, elapsedMs: performance.now() - started, ...outcome });
      callback();
    };
    const timer = setTimeout(() => {
      terminateProcessTree(child);
      finish(() => reject(new Error(`${command} timed out after ${timeoutMs}ms`)), { timedOut: true });
    }, timeoutMs);
    child.stdout.on('data', chunk => { stdout += chunk; });
    child.stderr.on('data', chunk => { stderr += chunk; });
    child.on('error', error => finish(() => reject(error), { error: error.message }));
    child.on('exit', code => finish(() => code === 0
      ? resolve(stdout)
      : reject(new Error(`${command} exited ${code}\n${stdout}\n${stderr}`)), { exitCode: code }));
  });
}

const compose = (...args) => runProcess('docker', ['compose', '-f', composeFile, ...args]);

async function clearSeatHold() {
  const output = await compose(
    'exec', '-T', 'redis-sentinel-1',
    'redis-cli', '--raw', '--no-auth-warning', '-a', sentinelPassword, '-p', '26379',
    'SENTINEL', 'get-master-addr-by-name', 'streaming-master',
  );
  const [host] = output.trim().split(/\r?\n/);
  const service = host === '172.29.0.10' ? 'redis-primary'
    : host === '172.29.0.11' ? 'redis-replica'
      : null;
  if (!service) throw new Error(`Unknown Sentinel master: ${host}`);
  await compose(
    'exec', '-T', service,
    'redis-cli', '--raw', '--no-auth-warning', '-a', redisPassword,
    'DEL', `seat:hold:${seatId}`,
  );
}

async function runFailover() {
  const stdout = await runProcess('powershell.exe', [
    '-NoProfile', '-ExecutionPolicy', 'Bypass',
    '-File', 'loadtest/redis-sentinel-failover.ps1',
    '-SkipStartup',
    '-FailureMode', 'Kill',
    '-ObservedKey', `seat:hold:${seatId}`,
    '-TimeoutSeconds', String(failoverTimeoutSeconds),
  ], failoverProcessTimeoutSeconds * 1000);
  const match = stdout.match(/\{[\s\S]*?"PromotedRoleAfterFailover"[\s\S]*?\}/);
  if (!match) throw new Error(`Failover JSON was not found\n${stdout}`);
  return JSON.parse(match[0]);
}

async function runScenario(concurrency) {
  diagnostics.write({ event: 'scenario_start', concurrency });
  await clearSeatHold();
  const records = [];
  const startedAt = performance.now();
  let keepRunning = true;
  let issued = 0;

  const workers = Array.from({ length: concurrency }, (_, workerIndex) => (async () => {
    const headers = authHeaders(workerIndex + 1);
    while (keepRunning) {
      const sequence = issued++;
      const requestStartedAt = performance.now();
      const startedAtUtcMs = Date.now();
      let status;
      try {
        const response = await fetch(`${baseUrl}/events/1/seats/${seatId}/holds`, {
          method: 'POST',
          headers,
          signal: AbortSignal.timeout(requestTimeoutMs),
        });
        await response.arrayBuffer();
        status = response.status;
      } catch (error) {
        status = `ERR:${error.cause?.code ?? error.name}`;
      }
      const requestEndedAt = performance.now();
      records.push({
        sequence,
        worker: workerIndex + 1,
        status,
        ms: requestEndedAt - requestStartedAt,
        startedAtUtcMs,
        endedAtUtcMs: Date.now(),
        startOffsetMs: requestStartedAt - startedAt,
        endOffsetMs: requestEndedAt - startedAt,
      });
    }
  })());

  let failover;
  let failoverError = null;
  try {
    await sleep(warmupSeconds * 1000);
    const baselineRecords = [...records];
    const baselineHealthy = baselineRecords.filter(record => record.status === 201 || record.status === 409).length;
    const baselineStatusCount = baselineRecords.reduce((counts, record) => {
      counts[record.status] = (counts[record.status] ?? 0) + 1;
      return counts;
    }, {});
    const baselineAvailability = baselineRecords.length
      ? baselineHealthy / baselineRecords.length * 100
      : 0;
    if (!baselineRecords.length || baselineAvailability < baselineMinAvailability) {
      throw new Error(
        `Baseline availability ${baselineAvailability.toFixed(3)}% is below ${baselineMinAvailability}% `
        + `(${baselineHealthy}/${baselineRecords.length} healthy responses, `
        + `statuses=${JSON.stringify(baselineStatusCount)}); failover was not injected.`,
      );
    }
    try {
      failover = await runFailover();
    } catch (error) {
      failoverError = error.message;
    }
    await sleep(recoverySeconds * 1000);
  } catch (error) {
    failoverError = error.message;
  } finally {
    keepRunning = false;
    await Promise.all(workers);
  }

  records.sort((left, right) => left.endedAtUtcMs - right.endedAtUtcMs);
  const elapsedMs = performance.now() - startedAt;
  const summary = analyzeScenario({
    records,
    failover,
    elapsedMs,
    configuration: {
      concurrency,
      seatId,
      warmupSeconds,
      recoverySeconds,
      requestTimeoutMs,
      failoverTimeoutSeconds,
      commandTimeoutSeconds,
      failoverProcessTimeoutSeconds,
      baselineMinAvailability,
    },
  });
  summary.failoverError = failoverError;
  summary.diagnosticFiles = { host: fileURLToPath(diagnosticFile), commands: fileURLToPath(powerShellDiagnosticFile) };
  diagnostics.write({ event: 'scenario_end', concurrency, failoverError });
  return { summary, records };
}

async function main() {
  const scenarios = [];
  const directory = new URL('./results/', import.meta.url);
  mkdirSync(directory, { recursive: true });
  const timestamp = Date.now();
  const jsonFile = new URL(`sentinel-failover-load-${timestamp}.json`, directory);
  const csvFile = new URL(`sentinel-failover-load-${timestamp}.csv`, directory);
  const saveResults = () => {
    writeFileSync(jsonFile, JSON.stringify({
      at: new Date().toISOString(),
      scenarios,
    }, null, 2));
    writeFileSync(csvFile, toCsv(scenarios.map(result => result.summary)));
  };

  for (const concurrency of concurrencyValues) {
    console.log(`Starting failover load scenario: concurrency=${concurrency}`);
    const result = await runScenario(concurrency);
    scenarios.push(result);
    saveResults();
    console.log(JSON.stringify(result.summary, null, 2));
    if (result.summary.failoverError
        || !result.summary.correctness.singleWinnerPreserved
        || !result.summary.correctness.dataPreserved
        || !result.summary.timeline.t4FirstHealthyResponseAtUtc) {
      console.error('Stopping suite after an invalid scenario; inspect saved results before retrying.');
      break;
    }
  }

  console.log(`JSON result: ${jsonFile.pathname}`);
  console.log(`CSV result: ${csvFile.pathname}`);

  const failed = scenarios.some(({ summary }) =>
    summary.failoverError
    || !summary.correctness.singleWinnerPreserved
    || !summary.correctness.dataPreserved
    || !summary.timeline.t4FirstHealthyResponseAtUtc);
  if (failed) process.exitCode = 1;
}

try {
  await main();
} finally {
  diagnostics.stop();
}
