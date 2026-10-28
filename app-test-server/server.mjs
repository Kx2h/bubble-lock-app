import http from 'node:http';
import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';

// Local, read-only adapter for the PC-MOBIUS-CORE v1 test contract.
export function createServer({ mobiusUrl = 'http://127.0.0.1:7599/Mobius', originator = 'pb-core-admin-local', fetchImpl = fetch } = {}) {
  const base = new URL(mobiusUrl);
  if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) throw new Error('Invalid Mobius URL');
  const url = `${base.href.replace(/\/$/, '')}/privacy-bubble-test-ae/bubbleState`;
  return http.createServer(async (req, res) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Cache-Control', 'no-store');
    const send = (status, payload) => { res.writeHead(status); res.end(JSON.stringify(payload)); };
    if (req.method !== 'GET' || req.url !== '/api/test/bubble-state') return send(404, { error: 'not_found' });
    const requestId = `app-test-${randomUUID()}`;
    try {
      const response = await fetchImpl(url, { headers: { Accept: 'application/json', 'X-M2M-RVI': '4', 'X-M2M-Origin': originator, 'X-M2M-RI': requestId }, signal: AbortSignal.timeout(5000), redirect: 'error' });
      if (!response.ok) return send(502, { mode: 'test', error: 'mobius_http_error', upstreamStatus: response.status, requestId });
      const data = await response.json();
      const state = data?.['pb:binarySwitch']?.state;
      if (typeof state !== 'boolean') return send(502, { mode: 'test', error: 'invalid_mobius_response', requestId });
      return send(200, { mode: 'test', state, requestId });
    } catch {
      return send(502, { mode: 'test', error: 'mobius_unavailable', requestId });
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = Number(process.env.APP_TEST_PORT || 8787);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid port');
  createServer({ mobiusUrl: process.env.MOBIUS4_URL, originator: process.env.MOBIUS4_ORIGINATOR }).listen(port, '127.0.0.1', () => console.log(`Test adapter listening on 127.0.0.1:${port}`));
}
