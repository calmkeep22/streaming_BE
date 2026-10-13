const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

export function sumMetric(text, name, filter = '') {
  const lines = text.split('\n').filter(line =>
    (line.startsWith(`${name}{`) || line.startsWith(`${name} `)) && line.includes(filter));
  if (!lines.length) return null;
  const values = lines.map(line => Number(line.slice(line.lastIndexOf(' ') + 1)));
  return values.every(Number.isFinite) ? values.reduce((a, b) => a + b, 0) : null;
}

export function parseSnapshot(text, api) {
  const uri = api === 'seats' ? '/events/seats/{seatId}' : `/events/{eventId}/seats/{seatId}/${api}`;
  const read = name => sumMetric(text, name);
  return {
    httpSum: sumMetric(text, 'http_server_requests_seconds_sum', `uri="${uri}"`),
    httpCount: sumMetric(text, 'http_server_requests_seconds_count', `uri="${uri}"`),
    acquireSum: read('hikaricp_connections_acquire_seconds_sum'),
    acquireCount: read('hikaricp_connections_acquire_seconds_count'),
    redisSum: read('reservation_hold_redis_seconds_sum'),
    redisCount: read('reservation_hold_redis_seconds_count'),
  };
}

export function metricWindow(start, end) {
  if (!start || !end) return { valid: false, reason: 'snapshot_unavailable' };
  if (start.lagMs > 2000 || end.lagMs > 2000) return { valid: false, reason: 'snapshot_delayed' };
  const delta = key => start[key] === null || end[key] === null ? null : end[key] - start[key];
  const count = delta('httpCount');
  const httpSum = delta('httpSum');
  if (!(count > 0) || httpSum === null || httpSum < 0) return { valid: false, reason: 'missing_or_reset_http_timer' };
  const elapsed = (end.receivedAt - start.receivedAt) / 1000;
  if (!(elapsed > 0)) return { valid: false, reason: 'invalid_window' };
  const mean = (sum, count) => delta(sum) !== null && delta(sum) >= 0 && delta(count) > 0 ? delta(sum) / delta(count) : null;
  return {
    valid: true, elapsedSec: elapsed, apiAvgSec: httpSum / count, throughput: count / elapsed,
    acquireAvgSec: mean('acquireSum', 'acquireCount'), redisHoldAvgSec: mean('redisSum', 'redisCount'),
  };
}

export function observeMetrics(config, headers, start, warmEnd, deadline) {
  const samples = {};
  const names = ['tomcat.threads.busy', 'tomcat.threads.current', 'hikaricp.connections.active', 'hikaricp.connections.pending'];
  let stopped = false;
  let failures = 0;
  const request = async path => {
    const response = await fetch(`${config.mgmtUrl}${path}`, { headers: headers(1), signal: AbortSignal.timeout(5000) });
    if (!response.ok) throw new Error(`Metrics HTTP ${response.status}`);
    return response;
  };
  const snapshot = async plannedAt => {
    try {
      const response = await request('/actuator/prometheus');
      const text = await response.text();
      const receivedAt = performance.now();
      return { ...parseSnapshot(text, config.api), receivedAt, lagMs: receivedAt - plannedAt };
    } catch { failures++; return null; }
  };
  const startSnapshot = config.duration ? delay(Math.max(0, warmEnd - performance.now())).then(() => snapshot(warmEnd)) : null;
  const endSnapshot = config.duration ? delay(Math.max(0, deadline - performance.now())).then(() => snapshot(deadline)) : null;
  const sampling = (async () => {
    await delay(Math.max(0, warmEnd - performance.now()));
    while (!stopped && performance.now() <= deadline) {
      await Promise.all(names.map(async name => {
        try {
          const connector = `http-nio-${new URL(config.baseUrl).port || 80}`;
          const tag = name.startsWith('tomcat.') ? `?tag=name:${connector}` : '';
          const body = await (await request(`/actuator/metrics/${name}${tag}`)).json();
          const value = body.measurements.find(item => item.statistic === 'VALUE')?.value;
          if (Number.isFinite(value) && !stopped && performance.now() <= deadline) (samples[name] ??= []).push(value);
        } catch { failures++; }
      }));
      if (!stopped) await delay(250);
    }
  })();
  return {
    async finish() {
      stopped = true;
      const snapshots = config.duration ? await Promise.all([startSnapshot, endSnapshot]) : [null, null];
      await sampling;
      const gauges = Object.fromEntries(Object.entries(samples).map(([name, values]) => [name,
        { samples: values.length, avg: values.reduce((a, b) => a + b, 0) / values.length, max: Math.max(...values) }]));
      return { server: config.duration ? metricWindow(...snapshots) : null, gauges, metricsFailures: failures };
    },
  };
}
