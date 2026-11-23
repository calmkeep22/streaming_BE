import { appendFileSync } from 'node:fs';
import os from 'node:os';

export function redactArguments(args) {
  let secretNext = false;
  return args.map(value => {
    if (secretNext) { secretNext = false; return '[REDACTED]'; }
    if (['-a', '--pass', '--password'].includes(value)) secretNext = true;
    return value;
  });
}

export function cpuUsage(previous, current) {
  const total = current.total - previous.total;
  return total > 0 ? (1 - (current.idle - previous.idle) / total) * 100 : null;
}

const cpuTimes = () => os.cpus().reduce((result, cpu) => {
  result.idle += cpu.times.idle;
  result.total += Object.values(cpu.times).reduce((a, b) => a + b, 0);
  return result;
}, { idle: 0, total: 0 });

export function createDiagnostics(path) {
  const write = event => appendFileSync(path, JSON.stringify({ at: new Date().toISOString(), ...event }) + '\n');
  let previous = cpuTimes();
  let plannedAt = performance.now() + 1000;
  const timer = setInterval(() => {
    const current = cpuTimes();
    write({
      event: 'host_sample',
      hostCpuPercent: cpuUsage(previous, current),
      hostFreeMemoryBytes: os.freemem(),
      hostTotalMemoryBytes: os.totalmem(),
      nodeRssBytes: process.memoryUsage().rss,
      eventLoopDelayMs: Math.max(0, performance.now() - plannedAt),
    });
    previous = current;
    plannedAt = performance.now() + 1000;
  }, 1000);
  timer.unref();
  write({ event: 'diagnostics_start', pid: process.pid, hostCpuCount: os.cpus().length });
  return { write, stop() { clearInterval(timer); write({ event: 'diagnostics_stop' }); } };
}
