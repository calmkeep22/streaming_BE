import { readFileSync } from 'node:fs';
import { spawn } from 'node:child_process';

const settings = Object.fromEntries(readFileSync(new URL('../.env', import.meta.url), 'utf8')
  .split(/\r?\n/).filter(line => /^\s*[A-Za-z_][A-Za-z0-9_]*\s*=/.test(line))
  .map(line => {
    const at = line.indexOf('=');
    const value = line.slice(at + 1).trim();
    return [line.slice(0, at).trim(), /^(".*"|'.*')$/.test(value) ? value.slice(1, -1) : value];
  }));

// Dedicated schema and Redis logical database keep test initialization isolated.
const child = spawn('java', ['-jar', 'build/libs/streaming-0.0.1-SNAPSHOT.jar',
  '--spring.profiles.active=loadtest',
  '--spring.datasource.url=jdbc:mysql://localhost:3306/streaming_loadtest_codex_20261013?createDatabaseIfNotExist=true&serverTimezone=Asia/Seoul',
  `--spring.datasource.username=${settings.DB_USERNAME || 'root'}`,
  `--spring.datasource.password=${settings.DB_PASSWORD || ''}`,
  '--spring.data.redis.database=15',
  ...(settings.JWT_SECRET ? [`--jwt.secret=${settings.JWT_SECRET}`] : [])],
  { stdio: 'inherit', shell: false });
child.on('error', error => { console.error(error.message); process.exitCode = 1; });
child.on('exit', code => { process.exitCode = code ?? 1; });
process.on('SIGINT', () => child.kill());
