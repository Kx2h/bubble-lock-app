import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { AccessError, createJwtVerifier } from './auth.mjs';
import { readLocations, validateBindings } from './location-reader.mjs';

const MAX_RESPONSE = 65_536;
const JSON_HEADERS = { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store, private',
  'x-content-type-options': 'nosniff', 'x-privacy-bubble-contract': 'privacy-bubble.mobile.v1', 'vary': 'Authorization' };

function reply(response, status, body) {
  const bytes = Buffer.from(JSON.stringify(body));
  response.writeHead(status, { ...JSON_HEADERS, 'content-length': bytes.length,
    ...(status === 401 ? { 'www-authenticate': 'Bearer realm="privacy-bubble", error="invalid_token"' } : {}) });
  response.end(bytes);
}

export function createMobileHandler({ readState, readBindings, verifyToken, clock = () => Date.now(), maxAgeMs = 300_000 } = {}) {
  if (typeof readState !== 'function' || typeof readBindings !== 'function' || typeof verifyToken !== 'function') throw new Error('Trusted state, bindings, and signed-token verifier are required');
  return async (request, response) => {
    try {
      if (request.method !== 'GET') { request.resume(); return reply(response, 405, { error: 'method_not_allowed' }); }
      if (request.url !== '/v1/keyrings') return reply(response, 404, { error: 'not_found' });
      if (request.headers['transfer-encoding'] || (request.headers['content-length'] && request.headers['content-length'] !== '0')) { request.resume(); return reply(response, 400, { error: 'request_body_not_allowed' }); }
      const principal = verifyToken(request.headers.authorization);
      const [state, bindings] = await Promise.all([readState(), readBindings()]);
      const result = readLocations({ state, bindings, subject: principal.subject, now: clock(), maxAgeMs });
      if (Buffer.byteLength(JSON.stringify(result)) > MAX_RESPONSE) return reply(response, 503, { error: 'location_service_unavailable' });
      reply(response, 200, result);
    } catch (error) {
      if (error instanceof AccessError) reply(response, error.status, { error: error.code });
      else reply(response, 503, { error: 'location_service_unavailable' });
    }
  };
}

async function boundedJson(path, limit) {
  const info = await stat(path);
  if (!info.isFile() || info.size > limit) throw new Error('Configuration or state exceeds bound');
  const value = await readFile(path);
  if (value.length > limit) throw new Error('Configuration or state exceeds bound');
  return JSON.parse(value.toString('utf8'));
}

export async function startFromEnvironment(env = process.env) {
  const root = fileURLToPath(new URL('..', import.meta.url));
  const statePath = resolve(env.PRIVACY_BUBBLE_MOBILE_STATE_PATH ?? resolve(root, 'integration/state/runtime.json'));
  if (!env.PRIVACY_BUBBLE_MOBILE_BINDINGS_PATH) throw new Error('PRIVACY_BUBBLE_MOBILE_BINDINGS_PATH is required');
  const bindingPath = resolve(env.PRIVACY_BUBBLE_MOBILE_BINDINGS_PATH);
  const readBindings = async () => validateBindings(await boundedJson(bindingPath, 131_072));
  await readBindings();
  const keyPath = env.PRIVACY_BUBBLE_MOBILE_PUBLIC_KEY_PATH;
  const jwksPath = env.PRIVACY_BUBBLE_MOBILE_JWKS_PATH;
  if (Boolean(keyPath) === Boolean(jwksPath)) throw new Error('Configure exactly one trusted public key or JWKS path');
  let publicKey = null, jwks = null;
  if (keyPath) {
    const bytes = await readFile(resolve(keyPath));
    if (bytes.length > 16_384) throw new Error('Public key exceeds bound');
    publicKey = bytes;
  } else jwks = await boundedJson(resolve(jwksPath), 65_536);
  const verifyToken = createJwtVerifier({ issuer: env.PRIVACY_BUBBLE_MOBILE_ISSUER,
    audience: env.PRIVACY_BUBBLE_MOBILE_AUDIENCE, publicKey, jwks });
  const port = Number(env.PRIVACY_BUBBLE_MOBILE_PORT ?? 8787);
  const maxAgeMs = Number(env.PRIVACY_BUBBLE_MOBILE_MAX_AGE_SECONDS ?? 300) * 1000;
  if (!Number.isInteger(port) || port < 1 || port > 65535 || !Number.isFinite(maxAgeMs) || maxAgeMs < 1000 || maxAgeMs > 86_400_000) throw new Error('Invalid port or freshness limit');
  const server = createServer({ maxHeaderSize: 8192 }, createMobileHandler({ readState: () => boundedJson(statePath, 16_777_216), readBindings, verifyToken, maxAgeMs }));
  server.requestTimeout = 10_000; server.headersTimeout = 5000; server.keepAliveTimeout = 5000;
  await new Promise((accept, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', accept); });
  console.log(JSON.stringify({ role: 'APP', service: 'mobile-location-read', endpoint: `http://127.0.0.1:${port}/v1/keyrings`, authentication: 'RS256', store: 'IntegrationStore', status: 'READY' }));
  return server;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  startFromEnvironment().then(server => {
    for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => server.close(() => process.exit(0)));
  }).catch(() => { console.error('Mobile location API configuration or startup failed. Check trusted auth, bindings, store, and port settings.'); process.exitCode = 1; });
}
