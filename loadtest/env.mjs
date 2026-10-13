import { readFileSync } from 'node:fs';

export function parseEnv(source) {
  const result = {};
  for (const line of source.replace(/^\uFEFF/, '').split(/\r?\n/)) {
    const match = line.match(/^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$/);
    if (!match) continue;
    const value = match[2].trim();
    result[match[1]] = /^(".*"|'.*')$/.test(value) ? value.slice(1, -1) : value;
  }
  return result;
}

export function readSettings(environment = process.env) {
  const file = environment.LOADTEST_ENV_FILE ?? new URL('../.env', import.meta.url);
  let settings = {};
  try {
    settings = parseEnv(readFileSync(file, 'utf8'));
  } catch (error) {
    if (error.code !== 'ENOENT' || environment.LOADTEST_ENV_FILE) throw error;
  }
  return { ...settings, ...environment };
}

export function numberOption(value, name, fallback, min = 1, integer = true) {
  const number = Number(value ?? fallback);
  if (!Number.isFinite(number) || number < min || (integer && !Number.isInteger(number))) {
    throw new Error(`${name} must be ${integer ? 'an integer' : 'a number'} >= ${min}`);
  }
  return number;
}
