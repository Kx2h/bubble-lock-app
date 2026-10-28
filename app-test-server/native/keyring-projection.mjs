import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdir, mkdtemp, realpath, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const execute = promisify(execFile);
const revision = 'd233c0f7c445be7b7505a69582a8fb0f471c57c2';
const sourcePath = fileURLToPath(new URL('../../../../.local/keyring-app-pinned/', import.meta.url));
const temporaryRoot = fileURLToPath(new URL('../../app/build/', import.meta.url));
const helper = fileURLToPath(new URL('./keyring-projection-check.py', import.meta.url));
const modulePath = 'keyring/raspberry-pi-gateway/src/bubble_gateway';

/** Verify the exact team checkout before any CSE resources are created. */
export async function checkKeyringSource() {
  const source = await realpath(sourcePath);
  const [top, head, status] = await Promise.all([
    execute('git', ['-C', source, 'rev-parse', '--show-toplevel']),
    execute('git', ['-C', source, 'rev-parse', 'HEAD']),
    execute('git', ['-C', source, 'status', '--porcelain', '--untracked-files=all', '--', modulePath]),
  ]);
  if (await realpath(top.stdout.trim()) !== source || head.stdout.trim() !== revision || status.stdout.trim()) {
    throw Error('Pinned KEYRING source is missing, modified or has the wrong revision');
  }
  return source;
}

/** Run team code with a new isolated SQLite journal, then remove only this directory. */
export async function keyringProjection(source, aeName, aeId) {
  await mkdir(temporaryRoot, { recursive: true });
  const dir = await mkdtemp(join(temporaryRoot, 'keyring-projection-'));
  try {
    const result = await execute('python3', ['-I', '-B', helper, source, join(dir, 'outbox.sqlite'), aeName, aeId],
      { timeout: 10000, maxBuffer: 65536 });
    return JSON.parse(result.stdout);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}
