// node loadtest/local-mysql.mjs (uses .env or LOADTEST_ENV_FILE)
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { readSettings, numberOption } from './env.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
export function serverConfig(settings) {
  const database = settings.LOADTEST_DB_NAME ?? 'streaming_loadtest';
  if (!/^[A-Za-z0-9]+(?:_[A-Za-z0-9]+)*_loadtest(?:_[A-Za-z0-9_]+)?$/.test(database)) {
    throw new Error('LOADTEST_DB_NAME must contain a dedicated _loadtest suffix');
  }
  const dbPort = numberOption(settings.LOADTEST_DB_PORT, 'LOADTEST_DB_PORT', 3306);
  const redisDatabase = numberOption(settings.LOADTEST_REDIS_DATABASE, 'LOADTEST_REDIS_DATABASE', 15);
  if (redisDatabase > 15) throw new Error('LOADTEST_REDIS_DATABASE must be between 1 and 15');
  return {
    jar: resolve(root, settings.APP_JAR ?? 'build/libs/streaming-0.0.1-SNAPSHOT.jar'),
    port: numberOption(settings.APP_PORT, 'APP_PORT', 8080),
    mgmtPort: numberOption(settings.MGMT_PORT, 'MGMT_PORT', 8081),
    childEnvironment: {
      ...process.env,
      SPRING_DATASOURCE_URL: `jdbc:mysql://localhost:${dbPort}/${database}?createDatabaseIfNotExist=true&serverTimezone=Asia/Seoul`,
      SPRING_DATASOURCE_USERNAME: settings.DB_USERNAME ?? 'root',
      SPRING_DATASOURCE_PASSWORD: settings.DB_PASSWORD ?? '',
      SPRING_DATA_REDIS_HOST: 'localhost',
      SPRING_DATA_REDIS_PORT: String(numberOption(settings.LOADTEST_REDIS_PORT, 'LOADTEST_REDIS_PORT', 6379)),
      SPRING_DATA_REDIS_DATABASE: String(redisDatabase),
      JWT_SECRET: settings.JWT_SECRET,
    },
  };
}

async function checkPort(port) {
  const socket = createServer();
  await new Promise((resolve, reject) => {
    socket.once('error', reject);
    socket.listen(port, () => socket.close(resolve));
  });
}

export async function startServer(settings) {
  const config = serverConfig(settings);
  if (!settings.JWT_SECRET) throw new Error('JWT_SECRET is required');
  await checkPort(config.port);
  await checkPort(config.mgmtPort);
  const child = spawn('java', ['-jar', config.jar, '--spring.profiles.active=loadtest',
    `--server.port=${config.port}`, `--management.server.port=${config.mgmtPort}`],
    { cwd: root, env: config.childEnvironment, stdio: 'inherit', shell: false });
  child.on('error', error => console.error(`Java startup failed: ${error.message}`));
  return child;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const child = await startServer(readSettings());
  child.on('exit', code => { process.exitCode = code ?? 1; });
  process.on('SIGINT', () => child.kill());
  process.on('SIGTERM', () => child.kill());
}
