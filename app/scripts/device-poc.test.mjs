import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm, chmod, copyFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
async function fixture(run) {
  await mkdir(resolve(root, 'build'), { recursive: true });
  const dir = await mkdtemp(resolve(root, 'build/device-test-'));
  const scripts = resolve(dir, 'scripts');
  await mkdir(scripts);
  await mkdir(resolve(dir, 'app/build/outputs/apk/debug'), { recursive: true });
  await writeFile(resolve(dir, 'app/build/outputs/apk/debug/app-debug.apk'), 'test');
  await copyFile(resolve(root, 'scripts/device-poc.sh'), resolve(scripts, 'device-poc.sh'));
  const fake = resolve(dir, 'adb');
  await writeFile(fake, `#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$LOG"
shift 2
case "$*" in
  'get-state') echo "\${DEVICE_STATE:-device}" ;;
  'shell pm path org.privacybubble.app') if [[ \${INSTALLED:-0} == 1 ]]; then echo 'package:/data/app/test.apk'; fi ;;
  'reverse --list') if [[ \${MAPPED:-0} == 1 ]]; then echo 'selected tcp:8787 tcp:8787'; fi ;;
esac
`);
  await chmod(fake, 0o755);
  const log = resolve(dir, 'calls');
  const invoke = (args, env = {}) => spawnSync('bash', [resolve(scripts, 'device-poc.sh'), ...args], { encoding: 'utf8', env: { ...process.env, ADB: fake, LOG: log, ...env } });
  const calls = async () => (await readFile(log, 'utf8')).trim().split('\n');
  try { await run({ invoke, calls }); } finally { await rm(dir, { recursive: true, force: true }); }
}

test('install selects one device and refuses to overwrite an existing app', async () => fixture(async ({ invoke, calls }) => {
  const result = invoke(['selected', 'install'], { INSTALLED: '1' });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /refusing to replace/);
  assert.ok((await calls()).every(line => line.startsWith('-s selected ')));
  assert.ok(!(await calls()).some(line => line.includes(' install ')));
}));

test('launch sets up the reverse port only when available', async () => fixture(async ({ invoke, calls }) => {
  assert.equal(invoke(['selected', 'launch'], { INSTALLED: '1' }).status, 0);
  const lines = await calls();
  assert.ok(lines.includes('-s selected reverse tcp:8787 tcp:8787'));
  assert.ok(lines.includes('-s selected shell am start -n org.privacybubble.app/.MainActivity'));
}));

test('existing reverse mapping and unauthorized device stop the workflow', async () => fixture(async ({ invoke, calls }) => {
  assert.notEqual(invoke(['selected', 'launch'], { INSTALLED: '1', MAPPED: '1' }).status, 0);
  assert.ok(!(await calls()).some(line => line.includes('am start')));
  assert.notEqual(invoke(['selected', 'install'], { DEVICE_STATE: 'unauthorized' }).status, 0);
}));

test('cleanup targets only the app and the specified reverse port', async () => fixture(async ({ invoke, calls }) => {
  assert.equal(invoke(['selected', 'cleanup'], { INSTALLED: '1', MAPPED: '1' }).status, 0);
  const lines = await calls();
  assert.ok(lines.includes('-s selected uninstall org.privacybubble.app'));
  assert.ok(lines.includes('-s selected reverse --remove tcp:8787'));
  assert.ok(!lines.some(line => line.includes('--remove-all') || line.includes('shell rm')));
}));
