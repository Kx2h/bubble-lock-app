import http from 'node:http';
import https from 'node:https';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';

const MAX_RESPONSE_BYTES = 65536;

// Expose only the Mobius timestamp form this test adapter understands. The
// value has no offset in its wire form, so do not invent a timezone or turn it
// into a device-observation time. Invalid/unsupported values remain unknown.
function resourceTimestamp(value) {
  if (typeof value !== 'string' || value.length !== 15 || !/^\d{8}T\d{6}$/.test(value)) return null;
  const year = Number(value.slice(0, 4)), month = Number(value.slice(4, 6));
  const day = Number(value.slice(6, 8)), hour = Number(value.slice(9, 11));
  const minute = Number(value.slice(11, 13)), second = Number(value.slice(13, 15));
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (year < 1 || month < 1 || month > 12 || day < 1 || day > days[month - 1]
      || hour > 23 || minute > 59 || second > 59) return null;
  return value;
}

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
export function createServer({ mobiusUrl = 'http://127.0.0.1:7599/Mobius', originator = 'pb-core-admin-local', testAeName = 'privacy-bubble-test-ae', fetchImpl = fetch, tls } = {}) {
  const base = new URL(mobiusUrl);
  if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) throw new Error('Invalid Mobius URL');
  if (typeof testAeName !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(testAeName)) throw new Error('Invalid test AE name');
  if (base.protocol === 'https:' && (!tls?.ca || !tls?.cert || !tls?.key)) throw new Error('HTTPS requires CA, client certificate and key');
  if (base.protocol === 'http:' && tls) throw new Error('Client TLS credentials require HTTPS');
  const url = `${base.href.replace(/\/$/, '')}/${testAeName}/bubbleState`;
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
      // A boolean alone is not enough to establish that the returned resource
      // is the binarySwitch named by the CORE test contract.
      if (resource?.rn !== 'bubbleState'
          || resource?.cnd !== 'org.onem2m.common.moduleclass.binarySwitch'
          || typeof state !== 'boolean') return send(502, { mode: 'test', error: 'invalid_mobius_response', requestId });
      // CORE updates state without clearing the bootstrap label. Neither its
      // presence nor absence establishes whether a device has been observed.
      const bootstrapLabelPresent = Array.isArray(resource.lbl) && resource.lbl.includes('unobserved');
      return send(200, { mode: 'test', state, observation: 'unknown', bootstrapLabelPresent, resourceLastModifiedRaw: resourceTimestamp(resource.lt), requestId });
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
  createServer({ mobiusUrl: process.env.MOBIUS4_URL, originator: process.env.MOBIUS4_ORIGINATOR, testAeName: process.env.MOBIUS4_TEST_AE, tls }).listen(port, '127.0.0.1', () => console.log(`Test adapter listening on 127.0.0.1:${port}`));
}
