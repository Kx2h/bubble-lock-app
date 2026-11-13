import { createServer } from 'node:https';
import { createHash, generateKeyPairSync, randomUUID, sign, timingSafeEqual, verify } from 'node:crypto';
import { appendFileSync, chmodSync, mkdirSync, readFileSync, realpathSync, statSync, unlinkSync, writeFileSync } from 'node:fs';
import { dirname, isAbsolute, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const fixtureIdentity = Object.freeze({
  issuer: 'https://localhost:9443/fixture-idp', audience: 'portal-network-native',
  subject: 'isolated-native-portal', scope: 'portal:read', keyringId: 'portal-network-fixture-keyring',
});
const sourceDirectory = dirname(fileURLToPath(import.meta.url));
const localRoot = realpathSync(resolve(sourceDirectory, '../../../../../.local'));
const inside = (parent, path) => { const item = relative(parent, path); return item !== '..' && !item.startsWith('../') && !isAbsolute(item); };
const digest = value => createHash('sha256').update(value).digest();
function privateInput(path) {
  const file = realpathSync(resolve(path));
  if (!inside(localRoot, file) || !statSync(file).isFile() || statSync(file).size > 65536) throw new Error('Invalid local fixture certificate input');
  return readFileSync(file);
}

export async function startFixture({ certPath, keyPath, privateDir, port = 9443 } = {}) {
  if (!certPath || !keyPath || !privateDir || !Number.isSafeInteger(port) || port < 0 || port > 65535) throw new Error('Explicit local fixture inputs required');
  mkdirSync(privateDir, { recursive: true, mode: 0o700 });
  const directory = realpathSync(resolve(privateDir));
  if (!inside(localRoot, directory) || directory === localRoot) throw new Error('Fixture state must be in a dedicated workspace-local directory');
  chmodSync(directory, 0o700);
  const cert = privateInput(certPath), key = privateInput(keyPath);
  const tokenFile = resolve(directory, 'portal-network-token');
  const eventsFile = resolve(directory, 'portal-network-events.jsonl');
  const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const now = Math.floor(Date.now() / 1000);
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const head = encode({ alg: 'RS256', typ: 'JWT', kid: 'isolated-portal-network-key' });
  const payload = encode({ iss: fixtureIdentity.issuer, aud: fixtureIdentity.audience, sub: fixtureIdentity.subject,
    scope: fixtureIdentity.scope, iat: now, nbf: now - 2, exp: now + 600 });
  const unsigned = head + '.' + payload;
  const issuedToken = unsigned + '.' + sign('RSA-SHA256', Buffer.from(unsigned), privateKey).toString('base64url');
  try {
    writeFileSync(tokenFile, issuedToken + '\n', { mode: 0o600, flag: 'wx' });
    writeFileSync(eventsFile, '', { mode: 0o600, flag: 'wx' });
  } catch (failure) {
    try { if (readFileSync(tokenFile, 'utf8').trim() === issuedToken) unlinkSync(tokenFile); } catch { }
    throw failure;
  }
  const tokenDigest = digest(issuedToken), session = randomUUID();
  const sockets = new Set(), timers = new Set();
  let stopped = false, deny = false, delayNext = false, reads = 0, eventCount = 0;
  function record(event, fields = {}) {
    if (eventCount++ >= 500) return;
    appendFileSync(eventsFile, JSON.stringify({ event, session, at: new Date().toISOString(), ...fields }) + '\n');
  }
  function authorized(request) {
    let count = 0;
    for (let index = 0; index < request.rawHeaders.length; index += 2) if (request.rawHeaders[index].toLowerCase() === 'authorization') count++;
    const authorization = request.headers.authorization;
    if (count !== 1 || typeof authorization !== 'string' || !authorization.startsWith('Bearer ') || authorization.length > 8192) return false;
    const candidate = authorization.slice(7);
    if (!timingSafeEqual(digest(candidate), tokenDigest)) return false;
    try {
      const parts = candidate.split('.');
      if (parts.length !== 3 || parts.some(part => !/^[A-Za-z0-9_-]+$/.test(part))) return false;
      const header = JSON.parse(Buffer.from(parts[0], 'base64url'));
      const claims = JSON.parse(Buffer.from(parts[1], 'base64url'));
      if (header.alg !== 'RS256' || header.typ !== 'JWT' || header.kid !== 'isolated-portal-network-key'
        || !verify('RSA-SHA256', Buffer.from(parts[0] + '.' + parts[1]), publicKey, Buffer.from(parts[2], 'base64url'))) return false;
      const current = Math.floor(Date.now() / 1000);
      return claims.iss === fixtureIdentity.issuer && claims.aud === fixtureIdentity.audience && claims.sub === fixtureIdentity.subject
        && claims.scope === fixtureIdentity.scope && Number.isSafeInteger(claims.exp) && claims.exp > current
        && Number.isSafeInteger(claims.nbf) && claims.nbf <= current && Number.isSafeInteger(claims.iat) && claims.iat <= current;
    } catch { return false; }
  }
  function reply(response, status, body) {
    if (response.destroyed || response.writableEnded) return;
    const bytes = Buffer.from(JSON.stringify(body));
    response.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store, private',
      'x-privacy-bubble-contract': 'privacy-bubble.integration.v1', 'x-content-type-options': 'nosniff',
      'content-length': bytes.length, connection: 'close' });
    response.end(bytes);
  }
  const server = createServer({ cert, key, minVersion: 'TLSv1.3', maxVersion: 'TLSv1.3', maxHeaderSize: 8192 }, (request, response) => {
    request.resume();
    if (!authorized(request)) { record('authentication-denied'); reply(response, 401, { error: 'isolated_fixture_identity_required' }); return; }
    if (request.url === '/_fixture/slow-deny' && request.method === 'POST') {
      if (request.headers['transfer-encoding'] || (request.headers['content-length'] && request.headers['content-length'] !== '0')) { reply(response, 400, { error: 'empty_fixture_control_required' }); return; }
      deny = true; delayNext = true;
      record('slow-deny-enabled'); reply(response, 200, { fixture: true, nextLocationReadDelayMillis: 7000 }); return;
    }
    if (request.url !== '/v1/portal/locations' || request.method !== 'GET') { reply(response, 404, { error: 'isolated_fixture_route_not_found' }); return; }
    if (request.headers['transfer-encoding'] || (request.headers['content-length'] && request.headers['content-length'] !== '0')) { reply(response, 400, { error: 'body_not_allowed' }); return; }
    const read = ++reads;
    if (deny) {
      if (delayNext) {
        delayNext = false; record('location-read-pending-denial', { read, delayMillis: 7000 });
        const timer = setTimeout(() => { timers.delete(timer); record('location-read-denied', { read, delayed: true }); reply(response, 403, { error: 'isolated_fixture_location_lease_revoked' }); }, 7000);
        timers.add(timer); response.once('close', () => { clearTimeout(timer); timers.delete(timer); });
      } else { record('location-read-denied', { read, delayed: false }); reply(response, 403, { error: 'isolated_fixture_location_lease_revoked' }); }
      return;
    }
    const current = Date.now();
    record('location-read-authorized', { read, simulation: true });
    reply(response, 200, { deviceIds: [fixtureIdentity.keyringId], observations: [{ deviceId: fixtureIdentity.keyringId,
      gatewayId: 'portal-network-fixture-gateway', observedAt: new Date(current - 1000).toISOString(), simulation: true,
      location: { lat: 37.12345, lon: 127.12345, source: 'gps', accuracyM: 8 }, rssiDbm: -61 }],
      readExpiresAt: new Date(current + 5000).toISOString() });
  });
  server.maxConnections = 16; server.requestTimeout = 12000; server.headersTimeout = 5000; server.keepAliveTimeout = 500;
  server.on('connection', socket => { sockets.add(socket); socket.once('close', () => sockets.delete(socket)); });
  try { await new Promise((resolveStarted, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolveStarted); }); }
  catch (failure) { try { if (readFileSync(tokenFile, 'utf8').trim() === issuedToken) unlinkSync(tokenFile); } catch { } throw failure; }
  const address = server.address(); record('ready', { port: address.port, host: '127.0.0.1', tls: 'TLSv1.3', simulation: true });
  async function close() {
    if (stopped) return;
    stopped = true;
    for (const timer of timers) clearTimeout(timer);
    timers.clear();
    for (const socket of sockets) socket.destroy();
    await new Promise(resolveClosed => server.close(resolveClosed));
    try { if (readFileSync(tokenFile, 'utf8').trim() === issuedToken) unlinkSync(tokenFile); } catch { }
    record('closed');
  }
  return { port: address.port, tokenFile, eventsFile, close };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = {};
  for (let index = 2; index < process.argv.length; index += 2) {
    const flag = process.argv[index], value = process.argv[index + 1];
    if (!['--cert', '--key', '--private-dir', '--port'].includes(flag) || !value || args[flag] !== undefined) throw new Error('Use --cert, --key, --private-dir and optional --port');
    args[flag] = value;
  }
  const running = await startFixture({ certPath: args['--cert'], keyPath: args['--key'], privateDir: args['--private-dir'], port: args['--port'] === undefined ? 9443 : Number(args['--port']) });
  process.stdout.write(JSON.stringify({ event: 'ready', host: '127.0.0.1', port: running.port, tokenFile: 'portal-network-token', fixture: true }) + '\n');
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => { void running.close().then(() => process.exit(0)); });
}
