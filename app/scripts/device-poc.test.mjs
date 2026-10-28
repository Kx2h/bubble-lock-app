import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm, chmod, copyFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

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
if [[ "$*" == devices ]]; then
  if [[ \${LIST_FAIL:-0} == 1 ]]; then exit 1; fi
  printf 'List of devices attached\\r\\n%s\\t%s\\r\\n' "\${LIST_SERIAL:-selected}" "\${LIST_STATE:-device}"
  if [[ \${LIST_DUPLICATE:-0} == 1 ]]; then printf 'selected\\tunauthorized\\r\\n'; fi
  exit 0
fi
shift 2
case "$*" in
  'get-state') echo "\${DEVICE_STATE:-device}" ;;
  'shell pm list users')
    if [[ \${USERS_FAIL:-0} == 1 ]]; then exit 1; fi
    if [[ \${USERS_MALFORMED:-0} == 1 ]]; then echo 'unknown'; else
      printf 'Users:\\r\\n    UserInfo{0:Owner:c13} running\\r\\n'
      if [[ \${USERS_MULTIPLE:-0} == 1 ]]; then printf '    UserInfo{10:Other:10}\\r\\n'; fi
    fi ;;
  'shell am get-current-user') if [[ \${CURRENT_FAIL:-0} == 1 ]]; then exit 1; fi; echo "\${CURRENT_USER:-0}" ;;
  'shell pm path org.privacybubble.app') if [[ \${INSTALLED:-0} == 1 || -f "$STATE.installed" ]]; then echo "package:\${APK_PATH:-/data/app/test.apk}"; fi ;;
  'shell sha256sum /data/app/'*) echo "\${APK_DIGEST:-9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08}  \${APK_PATH:-/data/app/test.apk}" ;;
  "shell run-as org.privacybubble.app sh -c "*)
    if [[ \${OWNER_WRITE_FAIL:-0} == 1 ]]; then exit 1; fi
    owner_pattern='printf %s ([0-9a-f]{64})'
    if [[ "$*" =~ $owner_pattern ]]; then printf '%s' "\${BASH_REMATCH[1]}" > "$STATE.owner"; else exit 1; fi ;;
  'shell run-as org.privacybubble.app cat .device-poc-owner')
    if [[ \${OWNER_MISSING:-0} == 1 ]]; then exit 1; fi
    if [[ -n \${OWNER_TOKEN:-} ]]; then printf '%s' "$OWNER_TOKEN"; else cat "$STATE.owner"; fi ;;
  'reverse --list') if [[ \${MAPPED:-0} == 1 || -f "$STATE.mapped" ]]; then echo "selected tcp:8787 \${MAPPED_TARGET:-tcp:8787}"; fi ;;
  'reverse --no-rebind tcp:8787 tcp:8787') touch "$STATE.mapped" ;;
  'reverse --remove tcp:8787') rm -f "$STATE.mapped" ;;
  'uninstall org.privacybubble.app') rm -f "$STATE.installed" ;;
  install*) touch "$STATE.installed" ;;
esac
`);
  await chmod(fake, 0o755);
  const log = resolve(dir, 'calls');
  const invoke = (args, env = {}) => spawnSync('bash', [resolve(scripts, 'device-poc.sh'), ...args], { encoding: 'utf8', env: { ...process.env, ADB: fake, LOG: log, STATE: resolve(dir, 'state'), ...env } });
  const calls = async () => (await readFile(log, 'utf8')).trim().split('\n');
  const marker = resolve(dir, '.local/device-poc', createHash('sha256').update('selected').digest('hex'), 'package.sha256');
  try { await run({ invoke, calls, marker }); } finally { await rm(dir, { recursive: true, force: true }); }
}

test('install selects one device and refuses to overwrite an existing app', async () => fixture(async ({ invoke, calls }) => {
  const result = invoke(['selected', 'install'], { INSTALLED: '1' });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /refusing to replace/);
  assert.ok((await calls()).every(line => line === 'devices' || line.startsWith('-s selected ')));
  assert.ok(!(await calls()).some(line => line.includes(' install ')));
}));

test('launch sets up the reverse port only when available', async () => fixture(async ({ invoke, calls }) => {
  assert.equal(invoke(['selected', 'launch'], { INSTALLED: '1' }).status, 0);
  const lines = await calls();
  assert.ok(lines.includes('-s selected reverse --no-rebind tcp:8787 tcp:8787'));
  assert.ok(lines.includes('-s selected shell am start -n org.privacybubble.app/.MainActivity'));
}));

test('existing reverse mapping and unauthorized device stop the workflow', async () => fixture(async ({ invoke, calls }) => {
  assert.notEqual(invoke(['selected', 'launch'], { INSTALLED: '1', MAPPED: '1' }).status, 0);
  assert.ok(!(await calls()).some(line => line.includes('am start')));
  // Regression: get-state may report device while the devices list is unauthorized.
  assert.notEqual(invoke(['selected', 'install'], { LIST_STATE: 'unauthorized', DEVICE_STATE: 'device' }).status, 0);
}));

test('does not confuse another serial or a list failure with authorization', async () => fixture(async ({ invoke, calls }) => {
  assert.notEqual(invoke(['selected', 'install'], { LIST_SERIAL: 'selected-other' }).status, 0);
  assert.notEqual(invoke(['selected', 'install'], { LIST_FAIL: '1' }).status, 0);
  assert.notEqual(invoke(['selected', 'install'], { LIST_DUPLICATE: '1' }).status, 0);
  assert.ok(!(await calls()).some(line => line.includes(' install ')));
}));

test('cleanup targets only the app and the specified reverse port', async () => fixture(async ({ invoke, calls }) => {
  assert.equal(invoke(['selected', 'install']).status, 0);
  assert.equal(invoke(['selected', 'launch']).status, 0);
  assert.equal(invoke(['selected', 'cleanup']).status, 0);
  const lines = await calls();
  assert.ok(lines.includes('-s selected uninstall org.privacybubble.app'));
  assert.ok(lines.includes('-s selected reverse --remove tcp:8787'));
  assert.ok(!lines.some(line => line.includes('--remove-all') || line.includes('shell rm')));
}));

test('cleanup refuses an app not installed by this script or changed after installation', async () => fixture(async ({ invoke, calls }) => {
  assert.notEqual(invoke(['selected', 'cleanup'], { INSTALLED: '1' }).status, 0);
  assert.equal(invoke(['selected', 'install']).status, 0);
  assert.notEqual(invoke(['selected', 'cleanup'], { APK_DIGEST: '1'.repeat(64) }).status, 0);
  assert.ok(!(await calls()).some(line => line.includes(' uninstall ')));
}));

test('cleanup refuses a same-APK reinstall or a changed installed path', async () => fixture(async ({ invoke, calls }) => {
  assert.equal(invoke(['selected', 'install']).status, 0);
  // A reinstall can have the same hash; app-private data (and our marker) is gone.
  assert.notEqual(invoke(['selected', 'cleanup'], { OWNER_MISSING: '1' }).status, 0);
  assert.notEqual(invoke(['selected', 'cleanup'], { OWNER_TOKEN: 'a'.repeat(64) }).status, 0);
  assert.notEqual(invoke(['selected', 'cleanup'], { APK_PATH: '/data/app/replaced.apk' }).status, 0);
  assert.ok(!(await calls()).some(line => line.includes(' uninstall ')));
  assert.equal(invoke(['selected', 'cleanup']).status, 0);
}));

test('installation without a verifiable private marker cannot be automatically removed', async () => fixture(async ({ invoke, calls }) => {
  assert.notEqual(invoke(['selected', 'install'], { OWNER_WRITE_FAIL: '1' }).status, 0);
  assert.notEqual(invoke(['selected', 'cleanup']).status, 0);
  assert.ok(!(await calls()).some(line => line.includes(' uninstall ')));
}));

test('legacy hash-only ownership is not accepted for uninstall', async () => fixture(async ({ invoke, calls, marker }) => {
  assert.equal(invoke(['selected', 'install']).status, 0);
  await writeFile(marker, '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08\n');
  assert.notEqual(invoke(['selected', 'cleanup']).status, 0);
  assert.ok(!(await calls()).some(line => line.includes(' uninstall ')));
}));

test('install and cleanup reject multiple, unknown or non-owner Android users', async () => fixture(async ({ invoke, calls }) => {
  for (const env of [{ USERS_MULTIPLE: '1' }, { USERS_MALFORMED: '1' }, { USERS_FAIL: '1' }, { CURRENT_USER: '10' }, { CURRENT_FAIL: '1' }]) {
    assert.notEqual(invoke(['selected', 'install'], env).status, 0);
  }
  assert.equal(invoke(['selected', 'install']).status, 0);
  assert.notEqual(invoke(['selected', 'cleanup'], { USERS_MULTIPLE: '1' }).status, 0);
  assert.notEqual(invoke(['selected', 'cleanup'], { USERS_FAIL: '1' }).status, 0);
  assert.ok(!(await calls()).some(line => line.includes(' uninstall ')));
  assert.equal(invoke(['selected', 'cleanup']).status, 0);
}));

test('cleanup does not remove an unowned reverse mapping', async () => fixture(async ({ invoke, calls }) => {
  assert.equal(invoke(['selected', 'cleanup'], { MAPPED: '1' }).status, 0);
  assert.ok(!(await calls()).some(line => line.includes('reverse --remove')));
}));

test('cleanup-port removes its mapping even when the installed app is unowned', async () => fixture(async ({ invoke, calls }) => {
  assert.equal(invoke(['selected', 'launch'], { INSTALLED: '1' }).status, 0);
  assert.notEqual(invoke(['selected', 'cleanup'], { INSTALLED: '1' }).status, 0);
  assert.equal(invoke(['selected', 'cleanup-port'], { INSTALLED: '1' }).status, 0);
  const lines = await calls();
  assert.ok(lines.includes('-s selected reverse --remove tcp:8787'));
  assert.ok(!lines.some(line => line.includes(' uninstall ')));
}));

test('cleanup-port leaves unowned or changed mappings untouched', async () => fixture(async ({ invoke, calls }) => {
  assert.equal(invoke(['selected', 'cleanup-port'], { MAPPED: '1' }).status, 0);
  assert.equal(invoke(['selected', 'launch'], { INSTALLED: '1' }).status, 0);
  assert.notEqual(invoke(['selected', 'cleanup-port'], { MAPPED_TARGET: 'tcp:9999' }).status, 0);
  assert.ok(!(await calls()).some(line => line.includes('reverse --remove')));
  assert.equal(invoke(['selected', 'cleanup-port']).status, 0);
}));
