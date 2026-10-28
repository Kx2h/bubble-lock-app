import http from 'node:http';
import https from 'node:https';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';

const MAX_RESPONSE_BYTES = 65536;

async function readBounded(stream) {
  const chunks = [];
  let size = 0;
  if (!stream) return Buffer.alloc(0);
  for await (const chunk of stream) {
    size += chunk.length;
    if (size > MAX_RESPONSE_BYTES) throw new Error('response too large');
    chunks.push(Buffer.from(chunk));
  }
  return Buffer.concat(chunks, size);
}

// Node's fetch does not expose a per-request client certificate. Use a dedicated
// HTTPS request with certificate verification and no redirect following instead.
function httpsRead(url, headers, tls) {
  return new Promise((resolve, reject) => {
    const request = https.request(url, {
      method: 'GET', headers, ca: tls.ca, cert: tls.cert, key: tls.key,
      minVersion: 'TLSv1.3', rejectUnauthorized: true,
      signal: AbortSignal.timeout(5000),
    }, async (response) => {
      try {
        const body = await readBounded(response);
        resolve({ status: response.statusCode, body, requestId: response.headers['x-m2m-ri'], resultCode: response.headers['x-m2m-rsc'] });
      } catch (error) {
        response.destroy();
        reject(error);
      }
    });
    request.on('error', reject);
    request.end();
  });
}

// Local, read-only adapter for the PC-MOBIUS-CORE v1 test contract.
export function createServer({ mobiusUrl = 'http://127.0.0.1:7599/Mobius', originator = 'pb-core-admin-local', fetchImpl = fetch, tls } = {}) {
  const base = new URL(mobiusUrl);
  if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) throw new Error('Invalid Mobius URL');
  if (base.protocol === 'https:' && (!tls?.ca || !tls?.cert || !tls?.key)) throw new Error('HTTPS requires CA, client certificate and key');
  if (base.protocol === 'http:' && tls) throw new Error('Client TLS credentials require HTTPS');
  const url = `${base.href.replace(/\/$/, '')}/privacy-bubble-test-ae/bubbleState`;
  return http.createServer(async (req, res) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Cache-Control', 'no-store');
    const send = (status, payload) => { res.writeHead(status); res.end(JSON.stringify(payload)); };
    if (req.method !== 'GET' || req.url !== '/api/test/bubble-state') return send(404, { error: 'not_found' });
    const requestId = `app-test-${randomUUID()}`;
    const headers = { Accept: 'application/json', 'X-M2M-RVI': '4', 'X-M2M-Origin': originator, 'X-M2M-RI': requestId };
    try {
      let response;
      if (base.protocol === 'https:') {
        response = await httpsRead(url, headers, tls);
      } else {
        const upstream = await fetchImpl(url, { headers, signal: AbortSignal.timeout(5000), redirect: 'error' });
        response = { status: upstream.status, body: await readBounded(upstream.body), requestId: upstream.headers.get('x-m2m-ri'), resultCode: upstream.headers.get('x-m2m-rsc') };
      }
      if (response.status !== 200) return send(502, { mode: 'test', error: 'mobius_http_error', upstreamStatus: response.status, requestId });
      // A successful HTTP response is insufficient: the oneM2M binding must
      // report a successful RETRIEVE and correlate it to this exact request.
      if (response.requestId !== requestId || response.resultCode !== '2000') return send(502, { mode: 'test', error: 'invalid_mobius_headers', requestId });
      let data;
      try { data = JSON.parse(response.body.toString('utf8')); }
      catch { return send(502, { mode: 'test', error: 'invalid_mobius_response', requestId }); }
      const resource = data?.['pb:binarySwitch'];
      const state = resource?.state;
      if (typeof state !== 'boolean') return send(502, { mode: 'test', error: 'invalid_mobius_response', requestId });
      // A label identifying bootstrap data is evidence that it is unobserved;
      // absence of the label is not evidence that a real device observed it.
      const observation = Array.isArray(resource.lbl) && resource.lbl.includes('unobserved') ? 'unobserved' : 'unknown';
      return send(200, { mode: 'test', state, observation, requestId });
    } catch {
      return send(502, { mode: 'test', error: 'mobius_unavailable', requestId });
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = Number(process.env.APP_TEST_PORT || 8787);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid port');
  const paths = [process.env.TLS_CA_FILE, process.env.TLS_CERT_FILE, process.env.TLS_KEY_FILE];
  if (paths.some(Boolean) && !paths.every(Boolean)) throw new Error('Set all TLS_CA_FILE, TLS_CERT_FILE and TLS_KEY_FILE');
  const tls = paths.every(Boolean) ? { ca: readFileSync(paths[0]), cert: readFileSync(paths[1]), key: readFileSync(paths[2]) } : undefined;
  createServer({ mobiusUrl: process.env.MOBIUS4_URL, originator: process.env.MOBIUS4_ORIGINATOR, tls }).listen(port, '127.0.0.1', () => console.log(`Test adapter listening on 127.0.0.1:${port}`));
}
