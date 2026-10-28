// 좌석 조회 API 부하 테스트 (k6): GET /events/seats/{seatId}
// 이 경로에 붙인 타이머 세 개(필터 → 인터셉터 → 서비스)의 구간 평균을 클라이언트 지연과 나란히 본다
//
// 사용법 (앱을 loadtest 프로필로 띄운 뒤, 프로젝트 루트에서):
//   k6 run loadtest/k6/seat-read.js
//   k6 run -e VUS=200 -e DURATION=60s loadtest/k6/seat-read.js
//
// 옵션(-e KEY=VALUE): BASE_URL(기본 http://localhost:8080), MGMT_URL(기본 http://localhost:8081)
//   VUS(기본 100), DURATION(기본 30s), WARMUP(기본 10s, 이 동안은 집계에서 뺀다)
//   SEAT_COUNT(기본 100, 좌석 id 1~N 중 무작위로 조회)

import http from 'k6/http';
import { check, fail } from 'k6';
import { Gauge } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MGMT_URL = __ENV.MGMT_URL || 'http://localhost:8081';
const VUS = Number(__ENV.VUS || 100);
const DURATION = __ENV.DURATION || '30s';
const WARMUP = __ENV.WARMUP || '10s';
const SEAT_COUNT = Number(__ENV.SEAT_COUNT || 100);

export const options = {
  scenarios: {
    // JIT·커넥션 풀 예열. phase 태그로 구분해 thresholds 와 요약은 measure 구간만 본다
    warmup: { executor: 'constant-vus', vus: VUS, duration: WARMUP, gracefulStop: '0s', tags: { phase: 'warmup' } },
    measure: { executor: 'constant-vus', vus: VUS, duration: DURATION, startTime: WARMUP, tags: { phase: 'measure' } },
    // measure 시작 시점의 서버 타이머 누적값을 한 번 읽는다 (끝 시점은 teardown)
    snapshot: { executor: 'shared-iterations', vus: 1, iterations: 1, startTime: WARMUP, exec: 'snapshotStart' },
  },
  thresholds: {
    // 요약에 measure 구간 값을 따로 찍기 위한 항목 (기준값은 일단 넉넉하게)
    'http_req_duration{phase:measure}': ['p(99)<1000'],
    'http_req_failed{phase:measure}': ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

// Micrometer Timer 이름 → Prometheus 이름 (점은 밑줄, 단위 _seconds)
const TIMERS = {
  filter: 'experiment_filter_duration_seconds',
  interceptor: 'experiment_interceptor_duration_seconds',
  service: 'experiment_service_duration_seconds',
};

// 시나리오(VU) 사이에는 변수를 공유할 수 없어서, 스냅샷을 Gauge 로 내보내고 handleSummary 에서 차이를 계산한다
const gauges = {};
for (const at of ['start', 'end']) {
  for (const key of Object.keys(TIMERS)) {
    gauges[`${at}_${key}_sum`] = new Gauge(`srv_${at}_${key}_sum`);
    gauges[`${at}_${key}_count`] = new Gauge(`srv_${at}_${key}_count`);
  }
}

// 관리 포트를 따로 두지 않은 프로필(기본)에서는 /actuator 도 인증이 필요해서 토큰을 붙인다.
// 다만 그 경우 스크래핑이 부하 요청 큐 뒤에 줄을 서서 스냅샷 시점이 밀리므로, 측정은 loadtest 프로필(관리 포트 8081)로 한다
function scrape(at, token) {
  const res = http.get(`${MGMT_URL}/actuator/prometheus`, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: 'scrape' },
  });
  if (res.status !== 200) {
    console.warn(`지표를 읽지 못했습니다(${res.status}): ${MGMT_URL}/actuator/prometheus`);
    return;
  }
  const lines = res.body.split('\n');
  const value = (name) => {
    const line = lines.find((l) => l.startsWith(`${name} `) || l.startsWith(`${name}{`));
    return line ? Number(line.slice(line.lastIndexOf(' ') + 1)) : 0;
  };
  for (const [key, metric] of Object.entries(TIMERS)) {
    gauges[`${at}_${key}_sum`].add(value(`${metric}_sum`));
    gauges[`${at}_${key}_count`].add(value(`${metric}_count`));
  }
}

export function setup() {
  // loadtest 프로필은 사용자를 만들지 않으므로 직접 가입한다 (이미 있으면 가입은 실패해도 로그인은 된다)
  const cred = { email: 'k6@loadtest.local', password: 'k6-password' };
  const json = { headers: { 'Content-Type': 'application/json' } };
  http.post(`${BASE_URL}/auth/signup`, JSON.stringify(cred), json);
  const login = http.post(`${BASE_URL}/auth/login`, JSON.stringify(cred), json);
  const token = login.status < 300 ? login.json('accessToken') : null;
  if (!token) fail(`로그인 실패: ${login.status} ${login.body}`);
  return { token };
}

export default function (data) {
  const seatId = Math.floor(Math.random() * SEAT_COUNT) + 1;
  const res = http.get(`${BASE_URL}/events/seats/${seatId}`, {
    headers: { Authorization: `Bearer ${data.token}` },
    tags: { name: 'GET /events/seats/{id}' },
  });
  check(res, { 'status 200': (r) => r.status === 200 });
}

export function snapshotStart(data) {
  scrape('start', data.token);
}

export function teardown(data) {
  scrape('end', data.token);
}

export function handleSummary(data) {
  const g = (name) => data.metrics[`srv_${name}`]?.values.value;
  const rows = Object.keys(TIMERS).map((key) => {
    const sum = g(`end_${key}_sum`) - g(`start_${key}_sum`);
    const count = g(`end_${key}_count`) - g(`start_${key}_count`);
    const avg = count > 0 ? `${((sum / count) * 1000).toFixed(3)}ms` : '-';
    return `    ${key.padEnd(12)} avg=${avg.padStart(10)}  count=${count}`;
  });
  // 스냅샷이 실패하거나 늦게 찍히면 계층별 count 가 어긋난다. 그런 회차는 서버 평균을 믿지 않는다
  const counts = Object.keys(TIMERS).map((key) => g(`end_${key}_count`) - g(`start_${key}_count`));
  const suspicious = counts.some((c) => !(c > 0)) || new Set(counts).size > 1;
  const client = data.metrics['http_req_duration{phase:measure}']?.values;
  const clientAvg = client ? `${client.avg.toFixed(3)}ms` : '-';

  const server = [
    '',
    `  서버 타이머 (measure 구간 ${DURATION}, 예열 제외)`,
    `    ${'client'.padEnd(12)} avg=${clientAvg.padStart(10)}  (k6 가 잰 왕복 시간)`,
    ...rows,
    '    filter > interceptor > service 순으로 바깥 구간이다. 차이 = 그 사이 단계(보안 필터, MVC 디스패치, 직렬화 등)의 시간',
    ...(suspicious ? ['    ⚠ 계층별 count 가 0 이거나 서로 달라 이번 서버 평균은 믿기 어렵다 (스냅샷 실패/지연)'] : []),
    '',
  ].join('\n');

  // srv_* Gauge 는 계산용이라 기본 요약에서는 숨긴다
  for (const name of Object.keys(data.metrics)) if (name.startsWith('srv_')) delete data.metrics[name];
  return { stdout: textSummary(data, { indent: ' ', enableColors: true }) + '\n' + server };
}
