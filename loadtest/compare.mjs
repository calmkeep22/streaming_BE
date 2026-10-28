// loadtest/results/*.json 을 label+mode 별로 묶어 중앙값으로 비교
//
// 사용법: node loadtest/compare.mjs [필터]   # label 또는 mode 에 필터 문자열이 포함된 결과만 (생략 시 전체)
//   예) node loadtest/compare.mjs 03-error-mapping__

import { readdirSync, readFileSync, existsSync } from 'node:fs';

const dir = new URL('./results/', import.meta.url);
const filter = process.argv[2];

if (!existsSync(dir)) {
  console.log('결과가 없습니다. LABEL=... node loadtest/reserve-load.mjs 로 먼저 실행하세요.');
  process.exit(0);
}

const runs = readdirSync(dir)
  .filter((f) => f.endsWith('.json'))
  .map((f) => JSON.parse(readFileSync(new URL(f, dir), 'utf8')))
  .filter((r) => !filter || r.label.includes(filter) || r.mode === filter);

const median = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  const m = Math.floor(s.length / 2);
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
};

// SAMPLE_METRICS=1 로 측정한 결과에만 있는 부하 중 게이지 (최대값의 중앙값)
const GAUGE_COLUMNS = {
  'tomcat busy max': 'tomcat.threads.busy',
  'hikari active max': 'hikaricp.connections.active',
  'hikari pending max': 'hikaricp.connections.pending',
};
const gaugeColumns = (rs) => {
  const cols = {};
  for (const [col, name] of Object.entries(GAUGE_COLUMNS)) {
    const xs = rs.map((r) => r.gauges?.[name]?.max).filter((x) => x !== undefined);
    if (xs.length) cols[col] = median(xs);
  }
  return cols;
};

// DURATION_SEC 로 측정한 결과에만 있는 집계 구간의 서버 쪽 평균 (중앙값, ms)
const serverColumns = (rs) => {
  const cols = {};
  const col = (name, pick) => {
    const xs = rs.map((r) => r.server && pick(r.server)).filter((x) => Number.isFinite(x));
    if (xs.length) cols[name] = (median(xs) * 1000).toFixed(0);
  };
  col('server api(ms)', (s) => s.reservationAvgSec);
  col('server acq(ms)', (s) => s.acquireAvgSec);
  col('server lock(ms)', (s) => s.lockWaitAvgSec);
  return cols;
};

const groups = new Map();
for (const r of runs) {
  const key = `${r.label} / ${r.mode}`;
  if (!groups.has(key)) groups.set(key, []);
  groups.get(key).push(r);
}

const rows = [...groups.entries()]
  .sort(([a], [b]) => a.localeCompare(b))
  .map(([key, rs]) => ({
    'label / mode': key,
    runs: rs.length,
    'req/s': median(rs.map((r) => r.throughput)).toFixed(1),
    'elapsed(s)': median(rs.map((r) => r.elapsedSec)).toFixed(2),
    'p50(ms)': median(rs.map((r) => r.p50)).toFixed(1),
    'p95(ms)': median(rs.map((r) => r.p95)).toFixed(1),
    'p99(ms)': median(rs.map((r) => r.p99)).toFixed(1),
    'max(ms)': median(rs.map((r) => r.max)).toFixed(1),
    status: JSON.stringify(rs.at(-1).statusCount),
    ...gaugeColumns(rs),
    ...serverColumns(rs),
  }));

console.log('각 값은 반복 실행의 중앙값입니다.');
console.table(rows);
