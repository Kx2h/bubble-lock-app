import { createServer } from 'node:http';
import { open } from 'node:fs/promises';
import { constants } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { AccessError, createJwtVerifier } from './auth.mjs';
import { readLocations, validateBindings } from './location-reader.mjs';
import { parseStrictJson } from './strict-json.mjs';

const MAX_RESPONSE = 65_536;
const JSON_HEADERS = { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store, private',
  'x-content-type-options': 'nosniff', 'x-privacy-bubble-contract': 'privacy-bubble.mobile.v1', 'vary': 'Authorization' };

function reply(response, status, body, close = false) {
  if (response.destroyed || response.writableEnded) return;
  const bytes = Buffer.from(JSON.stringify(body));
  response.writeHead(status, { ...JSON_HEADERS, 'content-length': bytes.length,
    ...(close ? { connection: 'close' } : {}), ...(status === 405 ? { allow: 'GET' } : {}),
    ...(status === 401 ? { 'www-authenticate': 'Bearer realm="privacy-bubble", error="invalid_token"' } : {}) });
  response.end(bytes);
}

export function createMobileHandler({ readState, readBindings, verifyToken, clock = () => Date.now(), maxAgeMs = 300_000, maxConcurrentReads = 4 } = {}) {
  if (typeof readState !== 'function' || typeof readBindings !== 'function' || typeof verifyToken !== 'function') throw new Error('Trusted state, bindings, and signed-token verifier are required');
  if (!Number.isInteger(maxConcurrentReads) || maxConcurrentReads < 1 || maxConcurrentReads > 16) throw new Error('Invalid concurrent read bound');
  let activeReads = 0;
  return async (request, response) => {
    let reading = false;
    try {
      if (request.rawHeaders.length > 128) { request.resume(); return reply(response, 400, { error: 'invalid_request' }, true); }
      if (request.method !== 'GET') { request.resume(); return reply(response, 405, { error: 'method_not_allowed' }, true); }
      if (request.url !== '/v1/keyrings') { request.resume(); return reply(response, 404, { error: 'not_found' }, true); }
      if (request.headers['transfer-encoding'] || (request.headers['content-length'] && request.headers['content-length'] !== '0')) { request.resume(); return reply(response, 400, { error: 'request_body_not_allowed' }, true); }
      let authorizationHeaders = 0, hostHeaders = 0;
      for (let index = 0; index < request.rawHeaders.length; index += 2) {
        const name = request.rawHeaders[index].toLowerCase();
        if (name === 'authorization') authorizationHeaders++;
        if (name === 'host') hostHeaders++;
      }
      if (hostHeaders !== 1) return reply(response, 400, { error: 'invalid_request' }, true);
      if (authorizationHeaders !== 1) return reply(response, 401, { error: 'invalid_token' }, true);
      const principal = verifyToken(request.headers.authorization);
      if (activeReads >= maxConcurrentReads) return reply(response, 503, { error: 'location_service_unavailable' }, true);
      activeReads++; reading = true;
      const reads = await Promise.allSettled([Promise.resolve().then(readState), Promise.resolve().then(readBindings)]);
      if (reads.some(read => read.status !== 'fulfilled')) throw new Error('Location read failed');
      const [state, bindings] = reads.map(read => read.value);
      const result = readLocations({ state, bindings, subject: principal.subject, now: clock(), maxAgeMs });
      if (Buffer.byteLength(JSON.stringify(result)) > MAX_RESPONSE) return reply(response, 503, { error: 'location_service_unavailable' });
      reply(response, 200, result);
    } catch (error) {
      if (error instanceof AccessError) reply(response, error.status, { error: error.code });
      else reply(response, 503, { error: 'location_service_unavailable' });
    } finally { if (reading) activeReads--; }
  };
}

async function boundedBytes(path, limit) {
  const handle = await open(path, constants.O_RDONLY | constants.O_NONBLOCK);
  try {
    const info = await handle.stat();
    if (!info.isFile() || info.size > limit) throw new Error('Configuration or state exceeds bound');
    const chunks = []; let total = 0;
    while (total <= limit) {
      const chunk = Buffer.allocUnsafe(Math.min(65_536, limit - total + 1));
      const { bytesRead } = await handle.read(chunk, 0, chunk.length, null);
      if (bytesRead === 0) return Buffer.concat(chunks, total);
      total += bytesRead;
      if (total > limit) throw new Error('Configuration or state exceeds bound');
      chunks.push(chunk.subarray(0, bytesRead));
    }
    throw new Error('Configuration or state exceeds bound');
  } finally { await handle.close(); }
}

export async function readBoundedJson(path, limit) {
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 16_777_216) throw new Error('Invalid file read bound');
  return parseStrictJson(await boundedBytes(path, limit));
}

export function createMobileServer(options) {
  const server = createServer({ maxHeaderSize: 8192, connectionsCheckingInterval: 1000 }, createMobileHandler(options));
  server.maxHeadersCount = 0;
  server.maxConnections = 128;
  server.requestTimeout = 10_000; server.headersTimeout = 5000; server.keepAliveTimeout = 5000;
  server.setTimeout(10_000, socket => socket.destroy());
  return server;
}

export async function startFromEnvironment(env = process.env) {
  const root = fileURLToPath(new URL('..', import.meta.url));
  const statePath = resolve(env.PRIVACY_BUBBLE_MOBILE_STATE_PATH ?? resolve(root, 'integration/state/runtime.json'));
  if (!env.PRIVACY_BUBBLE_MOBILE_BINDINGS_PATH) throw new Error('PRIVACY_BUBBLE_MOBILE_BINDINGS_PATH is required');
  const bindingPath = resolve(env.PRIVACY_BUBBLE_MOBILE_BINDINGS_PATH);
  const readBindings = async () => validateBindings(await readBoundedJson(bindingPath, 131_072));
  await readBindings();
  const keyPath = env.PRIVACY_BUBBLE_MOBILE_PUBLIC_KEY_PATH;
  const jwksPath = env.PRIVACY_BUBBLE_MOBILE_JWKS_PATH;
  if (Boolean(keyPath) === Boolean(jwksPath)) throw new Error('Configure exactly one trusted public key or JWKS path');
  let publicKey = null, jwks = null;
  if (keyPath) {
    const bytes = await boundedBytes(resolve(keyPath), 16_384);
    publicKey = bytes;
  } else jwks = await readBoundedJson(resolve(jwksPath), 65_536);
  const verifyToken = createJwtVerifier({ issuer: env.PRIVACY_BUBBLE_MOBILE_ISSUER,
    audience: env.PRIVACY_BUBBLE_MOBILE_AUDIENCE, publicKey, jwks });
  const port = Number(env.PRIVACY_BUBBLE_MOBILE_PORT ?? 8787);
  const maxAgeMs = Number(env.PRIVACY_BUBBLE_MOBILE_MAX_AGE_SECONDS ?? 300) * 1000;
  if (!Number.isInteger(port) || port < 1 || port > 65535 || !Number.isFinite(maxAgeMs) || maxAgeMs < 1000 || maxAgeMs > 86_400_000) throw new Error('Invalid port or freshness limit');
  const server = createMobileServer({ readState: () => readBoundedJson(statePath, 16_777_216), readBindings, verifyToken, maxAgeMs });
  await new Promise((accept, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', accept); });
  console.log(JSON.stringify({ role: 'APP', service: 'mobile-location-read', endpoint: `http://127.0.0.1:${port}/v1/keyrings`, authentication: 'RS256', store: 'IntegrationStore', status: 'READY' }));
  return server;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  startFromEnvironment().then(server => {
    for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => server.close(() => process.exit(0)));
  }).catch(() => { console.error('Mobile location API configuration or startup failed. Check trusted auth, bindings, store, and port settings.'); process.exitCode = 1; });
}
