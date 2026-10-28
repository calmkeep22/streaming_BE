// 예약 API 부하 테스트 (Node 18+ 내장 fetch 사용, 별도 설치 불필요)
//
// 사용법:
//   node loadtest/reserve-load.mjs hot     # 1000명이 좌석 1개에 동시 예약 → 락 경합 최대
//   node loadtest/reserve-load.mjs spread  # 1000명이 좌석 100개에 나눠서 예약
//
// 옵션(환경변수): BASE_URL(기본 http://localhost:8080), CONCURRENCY(기본 200)
//   MGMT_URL: 지표(/actuator) 주소 (기본 http://localhost:8081, loadtest 프로필의 management.server.port)
//   LABEL 을 주면 결과를 loadtest/results/<LABEL>-<mode>-<시각>.json 으로 저장 (compare.mjs 로 비교)
//   DURATION_SEC 를 주면 요청 1000건 대신 그 시간 동안 계속 보낸다 (사용자 1~1000 반복)
//     WARMUP_SEC: 앞쪽 이 시간 동안은 보내기만 하고 집계에서 뺀다 (JIT 예열)
//     집계 구간의 서버 쪽 평균(예약 API 처리 시간, 커넥션 대기, 락 대기)도 /actuator/prometheus 차이로 계산한다
//   API=holds 면 예약 확정 대신 좌석 선점 API(POST .../holds)를 친다 (hold-load.mjs 와 같음)

import { readFileSync } from 'node:fs';
import { createHmac } from 'node:crypto';

// The current API authenticates users with JWT, not the legacy X-USER-ID header.
let fileSecret;
try {
  const line = readFileSync(new URL('../.env', import.meta.url), 'utf8').split(/\r?\n/)
    .find(line => /^\s*JWT_SECRET\s*=/.test(line));
  const value = line?.slice(line.indexOf('=') + 1).trim();
  fileSecret = value && /^(".*"|'.*')$/.test(value) ? value.slice(1, -1) : value;
} catch {}
const secret = process.env.JWT_SECRET ?? fileSecret;
if (!secret) throw new Error('JWT_SECRET must be configured in the environment or project .env');
const key = Buffer.from(secret, 'base64');
const tokens = new Map();
function tokenFor(userId) {
  if (!tokens.has(userId)) {
    const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
    const now = Math.floor(Date.now() / 1000);
    const data = `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode({ sub: String(userId), iat: now, exp: now + 3600 })}`;
    tokens.set(userId, `${data}.${createHmac('sha256', key).update(data).digest('base64url')}`);
  }
  return tokens.get(userId);
}
const authHeaders = userId => ({ Authorization: `Bearer ${tokenFor(userId)}` });

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:8080';
// 지표는 별도 포트로 읽는다. 같은 포트면 부하 중 스크래핑이 요청 큐 뒤에 줄을 서서 스냅샷 시점이 밀린다
const MGMT_URL = process.env.MGMT_URL ?? 'http://localhost:8081';
// 관리 포트도 Tomcat이라 tomcat.* 지표가 합산되지 않도록 앱 포트의 커넥터만 본다
const APP_CONNECTOR = `http-nio-${new URL(BASE_URL).port || 80}`;
const API = process.env.API ?? 'reservations'; // reservations | holds | seats (read)
const HTTP_URI_FILTER = API === 'seats' ? 'uri="/events/seats/{seatId}"' : `/${API}"`;
const CONCURRENCY = Number(process.env.CONCURRENCY ?? 200);
const MODE = process.argv[2] ?? 'hot';
const EVENT_ID = 1;
const USER_COUNT = 1000;
const SEAT_COUNT = 100;
const LABEL = process.env.LABEL;
const DURATION_SEC = Number(process.env.DURATION_SEC ?? 0);
const WARMUP_SEC = DURATION_SEC ? Number(process.env.WARMUP_SEC ?? 0) : 0;

const seatFor = (userId) => (MODE === 'hot' ? 1 : ((userId - 1) % SEAT_COUNT) + 1);

async function reserve(userId) {
  const seatId = seatFor(userId);
  const start = performance.now();
  try {
    const url = API === 'seats' ? `${BASE_URL}/events/seats/${seatId}` : `${BASE_URL}/events/${EVENT_ID}/seats/${seatId}/${API}`;
    const res = await fetch(url, {
      method: API === 'seats' ? 'GET' : 'POST',
      headers: authHeaders(userId),
      signal: AbortSignal.timeout(15000),
    });
    await res.arrayBuffer();
    return { status: res.status, ms: performance.now() - start, end: performance.now() };
  } catch (e) {
    return { status: `ERR:${e.cause?.code ?? e.cause?.name ?? e.name}`, ms: performance.now() - start, end: performance.now() };
  }
}

const percentile = (sorted, p) => sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];

// 서버 쪽 누적 지표 스냅샷 (구간 시작/끝의 차이로 그 구간의 평균을 구한다)
const scrape = async () => {
  try {
    const response = await fetch(`${MGMT_URL}/actuator/prometheus`, { headers: authHeaders(1), signal: AbortSignal.timeout(5000) });
    if (!response.ok) throw new Error(`Metrics HTTP ${response.status}`);
    const text = await response.text();
    const sum = (name, labelFilter = '') =>
      text.split('\n')
        .filter((l) => l.startsWith(name) && l.includes(labelFilter))
        .reduce((s, l) => s + Number(l.slice(l.lastIndexOf(' ') + 1)), 0);
    return {
      httpSum: sum('http_server_requests_seconds_sum{', HTTP_URI_FILTER),
      httpCount: sum('http_server_requests_seconds_count{', HTTP_URI_FILTER),
      acqSum: sum('hikaricp_connections_acquire_seconds_sum{'),
      acqCount: sum('hikaricp_connections_acquire_seconds_count{'),
      lockSum: sum('reservation_seat_lock_wait_seconds_sum'),
      lockCount: sum('reservation_seat_lock_wait_seconds_count'),
      holdSuccess: sum('reservation_hold_result_total{', 'result="success"'),
      holdUnavailable: sum('reservation_hold_result_total{', 'result="unavailable"'),
    };
  } catch {
    return null;
  }
};
const serverWindow = (a, b) =>
  a && b
    ? {
        reservationAvgSec: (b.httpSum - a.httpSum) / (b.httpCount - a.httpCount),
        acquireAvgSec: (b.acqSum - a.acqSum) / (b.acqCount - a.acqCount),
        lockWaitAvgSec: (b.lockSum - a.lockSum) / (b.lockCount - a.lockCount),
        serverThroughput: (b.httpCount - a.httpCount) / DURATION_SEC,
        holdSuccess: b.holdSuccess - a.holdSuccess,
        holdUnavailable: b.holdUnavailable - a.holdUnavailable,
        raw: { start: a, end: b }, // 값이 비정상(NaN)일 때 원인 추적용
      }
    : undefined;

const queue = Array.from({ length: USER_COUNT }, (_, i) => i + 1);
const results = [];
let issued = 0;
const nextUser = () => (DURATION_SEC ? (issued++ % USER_COUNT) + 1 : queue.shift());
const hasNext = (deadline) => (DURATION_SEC ? performance.now() < deadline : queue.length > 0);

// SAMPLE_METRICS=1 이면 부하 중 250ms 마다 게이지 지표를 샘플링한다 (끝난 뒤엔 0이라 스냅샷으로는 못 봄)
// 샘플링 요청도 Tomcat 스레드를 쓰므로, 스레드가 꽉 차면 샘플이 늦게 찍히는 것 자체가 포화 신호다
let warmEnd = 0; // 예열이 끝나는 시각 (샘플링·집계는 이 이후만)
const GAUGES = ['tomcat.threads.busy', 'tomcat.threads.current', 'hikaricp.connections.active', 'hikaricp.connections.pending'];
const samples = Object.fromEntries(GAUGES.map((g) => [g, []]));
const readGauge = async (name) => {
  if (performance.now() < warmEnd) return;
  try {
    const tag = name.startsWith('tomcat.') ? `?tag=name:${APP_CONNECTOR}` : '';
    const res = await fetch(`${MGMT_URL}/actuator/metrics/${name}${tag}`, { headers: authHeaders(1), signal: AbortSignal.timeout(5000) });
    if (!res.ok) return;
    const body = await res.json();
    samples[name].push(body.measurements.find((m) => m.statistic === 'VALUE')?.value ?? NaN);
  } catch {}
};
const sampler = process.env.SAMPLE_METRICS === '1'
  ? setInterval(() => GAUGES.forEach(readGauge), 250)
  : null;

console.log(
  `api=${API}, mode=${MODE}, concurrency=${CONCURRENCY}, target=${BASE_URL}, ` +
    (DURATION_SEC ? `duration=${DURATION_SEC}s (+warmup ${WARMUP_SEC}s)` : `users=${USER_COUNT}`)
);
const t0 = performance.now();
warmEnd = t0 + WARMUP_SEC * 1000;
const deadline = warmEnd + DURATION_SEC * 1000;
let snapStart = null;
const snapStartTimer = DURATION_SEC ? setTimeout(async () => { snapStart = await scrape(); }, WARMUP_SEC * 1000) : null;
let snapEnd = null;
const snapEndTimer = DURATION_SEC ? setTimeout(async () => { snapEnd = await scrape(); }, (WARMUP_SEC + DURATION_SEC) * 1000) : null;
await Promise.all(
  Array.from({ length: CONCURRENCY }, async () => {
    while (hasNext(deadline)) {
      results.push(await reserve(nextUser()));
    }
  })
);
clearInterval(sampler);
clearTimeout(snapStartTimer);
clearTimeout(snapEndTimer);
// 시간 고정 모드: 집계 구간(예열 이후 ~ 마감) 안에 끝난 응답만 센다
const counted = DURATION_SEC ? results.filter((r) => r.end >= warmEnd && r.end <= deadline) : results;
const elapsed = DURATION_SEC ? DURATION_SEC : (performance.now() - t0) / 1000;
if (DURATION_SEC && !snapEnd) snapEnd = await scrape();
const server = DURATION_SEC ? serverWindow(snapStart, snapEnd) : undefined;

const gaugeSummary = Object.fromEntries(
  Object.entries(samples)
    .filter(([, xs]) => xs.length)
    .map(([name, xs]) => [name, { samples: xs.length, avg: xs.reduce((a, b) => a + b, 0) / xs.length, max: Math.max(...xs) }])
);

const statusCount = counted.reduce((acc, r) => ((acc[r.status] = (acc[r.status] ?? 0) + 1), acc), {});
const latencies = counted.map((r) => r.ms).sort((a, b) => a - b);

console.log(`\n총 소요 시간: ${elapsed.toFixed(2)}s, 처리량: ${(counted.length / elapsed).toFixed(1)} req/s`);
if (server) {
  console.log(
    `서버 쪽(집계 구간 평균): ${API} API ${(server.reservationAvgSec * 1000).toFixed(0)}ms, ` +
      `커넥션 대기 ${(server.acquireAvgSec * 1000).toFixed(0)}ms, 락 대기 ${(server.lockWaitAvgSec * 1000).toFixed(0)}ms, ` +
      `서버 처리량 ${server.serverThroughput.toFixed(1)} req/s` +
      (API === 'holds' ? `, 선점 성공/실패 ${server.holdSuccess}/${server.holdUnavailable}` : '')
  );
}
console.log('응답 코드:', statusCount);
console.log(
  `응답 시간(ms) p50=${percentile(latencies, 50).toFixed(1)} p95=${percentile(latencies, 95).toFixed(1)} ` +
    `p99=${percentile(latencies, 99).toFixed(1)} max=${latencies.at(-1).toFixed(1)}`
);
for (const [name, g] of Object.entries(gaugeSummary)) {
  console.log(`부하 중 ${name}: 평균 ${g.avg.toFixed(1)}, 최대 ${g.max} (샘플 ${g.samples}개)`);
}
console.log(API === 'seats' ? '\n기대값: 모든 조회 응답 200' : '\n선점/예약 201 건수는 초기 좌석 상태와 예열 포함 여부에 따라 달라집니다.');

if (LABEL) {
  const { mkdirSync, writeFileSync } = await import('node:fs');
  const dir = new URL('./results/', import.meta.url);
  mkdirSync(dir, { recursive: true });
  const summary = {
    label: LABEL,
    api: API,
    mode: MODE,
    concurrency: CONCURRENCY,
    at: new Date().toISOString(),
    elapsedSec: elapsed,
    durationSec: DURATION_SEC || undefined,
    warmupSec: WARMUP_SEC || undefined,
    throughput: counted.length / elapsed,
    server,
    statusCount,
    p50: percentile(latencies, 50),
    p95: percentile(latencies, 95),
    p99: percentile(latencies, 99),
    max: latencies.at(-1),
    gauges: gaugeSummary,
  };
  const file = new URL(`${LABEL}-${MODE}-${Date.now()}.json`, dir);
  writeFileSync(file, JSON.stringify(summary, null, 2));
  console.log(`결과 저장: ${file.pathname}`);
}
