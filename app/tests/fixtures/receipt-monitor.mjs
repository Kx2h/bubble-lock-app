import http from 'node:http';
import { constants, readFileSync, writeFileSync } from 'node:fs';
import { mkdir, realpath, writeFile } from 'node:fs/promises';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

/** Synthetic display-content fixture; no CSE, device command, account or permission. */
export function createReceiptMonitorFixture({ readMode, recordCount = () => {} }) {
  let count = 0;
  return http.createServer((req, res) => {
    res.setHeader('Content-Type', 'application/json');
    res.setHeader('Cache-Control', 'no-store');
    if (req.method !== 'GET' || req.url !== '/api/test/receipt') {
      res.writeHead(404).end('{}'); return;
    }
    let mode;
    try {
      mode = readMode();
      recordCount({ count: ++count, mode });
    } catch {
      res.writeHead(503).end('{}'); return;
    }
    if (!['baseline', 'changed'].includes(mode)) {
      res.writeHead(503).end('{}'); return;
    }
    const changed = mode === 'changed';
    res.writeHead(200).end(JSON.stringify({
      mode: 'synthetic', gatewayId: 'app-isolated', deviceId: 'synthetic-keyring',
      commandId: 'synthetic-command-1', status: changed ? 'REJECTED' : 'INDETERMINATE',
      ackCode: changed ? 'SYNTHETIC_REJECTED' : 'INVALID_RECEIPT', timestampSource: 'unavailable',
      acceptedAt: null, appliedAt: null, receivedAt: '2026-01-01T00:00:00Z', readback: null,
      requestId: `fixture-${count}`,
    }));
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const workspace = resolve(dirname(fileURLToPath(import.meta.url)), '../../../../..');
  const local = await realpath(join(workspace, '.local'));
  const requested = resolve(process.env.RECEIPT_FIXTURE_DIR ?? join(local, 'receipt-monitor-fixture'));
  const withinLocal = value => { const path = relative(local, value); return path && !path.startsWith('..') && !path.startsWith('/'); };
  if (!withinLocal(requested)) throw Error('RECEIPT_FIXTURE_DIR must be below the workspace .local directory');
  await mkdir(requested, { recursive: true, mode: 0o700 });
  const dir = await realpath(requested);
  if (!withinLocal(dir)) throw Error('Fixture directory resolves outside workspace .local');
  const modeFile = join(dir, 'mode');
  try { await writeFile(modeFile, 'baseline\n', { flag: 'wx', mode: 0o600 }); }
  catch (error) { if (error.code !== 'EEXIST') throw error; }
  const portText = process.env.RECEIPT_FIXTURE_PORT ?? '8787';
  if (!/^(0|[1-9][0-9]{0,4})$/.test(portText) || Number(portText) > 65535) throw Error('Invalid fixture port');
  const server = createReceiptMonitorFixture({
    readMode: () => readFileSync(modeFile, 'utf8').trim(),
    recordCount: value => writeFileSync(join(dir, 'fixture-count.json'), JSON.stringify(value), {
      flag: constants.O_WRONLY | constants.O_CREAT | constants.O_TRUNC | constants.O_NOFOLLOW,
      mode: 0o600,
    }),
  });
  server.listen(Number(portText), '127.0.0.1', () => {
    console.log(JSON.stringify({ ready: true, host: '127.0.0.1', port: server.address().port, directory: dir }));
  });
}
