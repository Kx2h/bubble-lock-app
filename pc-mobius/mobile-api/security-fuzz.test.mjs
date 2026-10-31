import nodeTest, { after } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, sign, createHmac, createHash } from 'node:crypto';
import { connect } from 'node:net';
import { mkdir, mkdtemp, readFile, writeFile, rm, stat } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { performance } from 'node:perf_hooks';
import { createJwtVerifier, AccessError } from './auth.mjs';
import { readLocations, validateBindings } from './location-reader.mjs';
import { createMobileServer, readBoundedJson } from './server.mjs';
import { parseStrictJson, decodeBase64Url } from './strict-json.mjs';

const workspace = fileURLToPath(new URL('../../../../', import.meta.url));
const temporaryRoot = resolve(process.env.TMPDIR ?? resolve(workspace, '.local/tmp/security'));
const seed = Number(process.env.MOBILE_FUZZ_SEED ?? '0x5ec0a11');
const iterations = Number(process.env.MOBILE_FUZZ_ITERATIONS ?? 2000);
if (!Number.isSafeInteger(seed) || seed < 1 || seed > 0xffffffff) throw new Error('Fuzz seed must be 1..4294967295');
if (!Number.isSafeInteger(iterations) || iterations < 1 || iterations > 100_000) throw new Error('Fuzz iterations must be 1..100000');
const receiptPath = resolve(process.env.MOBILE_FUZZ_RECEIPT ?? join(temporaryRoot, 'mobile-api-fuzz-receipt.json'));
if (!receiptPath.startsWith(temporaryRoot + '/')) throw new Error('Fuzz receipt must remain inside the configured temporary root');
let randomState = seed;
function random() { randomState ^= randomState << 13; randomState ^= randomState >>> 17; randomState ^= randomState << 5; return randomState >>> 0; }
const pick = values => values[random() % values.length];
const receipt = { schema: 'privacy-bubble.mobile-api.security-fuzz.v1', seed, iterations, startedAt: new Date().toISOString(),
  node: process.version, network: 'ephemeral loopback listeners only', signingMaterial: 'fresh local RSA-2048 test keys',
  cases: {}, tests: { passed: 0, failed: 0 }, failures: [], resourceMeasurements: {}, limits: ['No production credentials or identity-provider requests', 'No public-host scanning', 'Local deterministic corpus and mutation testing is not a complete penetration test'] };
receipt.sourceSha256 = Object.fromEntries(await Promise.all(['auth.mjs', 'location-reader.mjs', 'server.mjs', 'strict-json.mjs', 'security-corpus.json', 'security-fuzz.test.mjs']
  .map(async path => [path, createHash('sha256').update(await readFile(new URL(path, import.meta.url))).digest('hex')])));
function test(name, operation) {
  return nodeTest(name, async context => {
    try { await operation(context); receipt.tests.passed++; }
    catch (error) { receipt.tests.failed++; receipt.failures.push({ stage: 'test', id: name, error: error.name }); throw error; }
  });
}
function count(stage) { receipt.cases[stage] = (receipt.cases[stage] ?? 0) + 1; }
function trial(stage, id, operation) {
  count(stage);
  try { return operation(); } catch (error) { receipt.failures.push({ stage, id, error: error.name }); throw error; }
}
async function asyncTrial(stage, id, operation) {
  count(stage);
  try { return await operation(); } catch (error) { receipt.failures.push({ stage, id, error: error.name }); throw error; }
}
after(async () => {
  receipt.completedAt = new Date().toISOString();
  receipt.totalCases = Object.values(receipt.cases).reduce((a, b) => a + b, 0);
  await mkdir(temporaryRoot, { recursive: true });
  await writeFile(receiptPath, JSON.stringify(receipt, null, 2) + '\n', { mode: 0o600 });
});

const corpus = JSON.parse(await readFile(new URL('./security-corpus.json', import.meta.url), 'utf8'));
const NOW = Date.parse('2026-11-01T00:00:00Z');
const issuer = 'https://identity.invalid', audience = 'mobile-location-api';
const keys = generateKeyPairSync('rsa', { modulusLength: 2048 });
const publicKey = keys.publicKey.export({ type: 'spki', format: 'pem' });
const jwk = keys.publicKey.export({ format: 'jwk' });
const validClaims = { iss: issuer, aud: audience, sub: 'alice', exp: NOW / 1000 + 300, scope: 'location:read' };
const verify = createJwtVerifier({ issuer, audience, publicKey, clock: () => NOW });
function jwt(claims = validClaims, header = { alg: 'RS256', kid: 'test' }, raw = {}) {
  const a = Buffer.from(raw.header ?? JSON.stringify(header)).toString('base64url');
  const b = Buffer.from(raw.claims ?? JSON.stringify(claims)).toString('base64url');
  const input = Buffer.from(`${a}.${b}`);
  const signature = header.alg === 'HS256' ? createHmac('sha256', publicKey).update(input).digest() : sign('RSA-SHA256', input, keys.privateKey);
  return `${a}.${b}.${signature.toString('base64url')}`;
}
const validToken = jwt();
const authorization = `Bearer ${validToken}`;
const rejectToken = token => assert.throws(() => verify(`Bearer ${token}`), AccessError);
const binding = { id: 'alice-ring', name: 'Alice ring', ueId: 'ue-alice', principalIds: ['alice'] };
const bobBinding = { id: 'bob-ring', name: 'Bob ring', ueId: 'ue-bob', principalIds: ['bob'] };
const ownEvent = { service: 'Location', ueId: 'ue-alice', timestamp: '2026-11-01T00:00:00Z', location: { lat: 37.5, lon: 127.0 } };
const bobEvent = { service: 'Location', ueId: 'ue-bob', timestamp: '2026-11-01T00:00:00Z', location: { lat: -33.123456, lon: -70.654321 } };
const validGrant = { grantId: 'grant', consentId: 'consent', status: 'ACTIVE', principalId: 'alice', subjectId: 'ue-alice', expiresAt: '2026-11-01T01:00:00Z', scopes: ['location:read'], revision: 1 };
const validConsent = { consentId: 'consent', status: 'ACTIVE', subjectId: 'ue-alice', expiresAt: '2026-11-01T01:00:00Z', scopes: ['location:read'], revision: 1 };
function snapshot(state, bindings = [binding, bobBinding], subject = 'alice') { return readLocations({ state, bindings, subject, now: NOW }); }
function coordinatesCleared(fix) {
  for (const field of ['latitude', 'longitude', 'accuracyMeters', 'observedAt', 'receivedAt', 'expiresAt']) assert.equal(fix[field], null);
}
function freeze(value) { if (value && typeof value === 'object') { for (const child of Object.values(value)) freeze(child); Object.freeze(value); } return value; }
async function listen(t, options = {}) {
  const server = createMobileServer({ readState: async () => ({ events: { own: ownEvent, other: bobEvent } }), readBindings: async () => [binding, bobBinding], verifyToken: verify, clock: () => NOW, ...options });
  await new Promise((accept, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', accept); });
  t.after(() => { server.closeAllConnections(); server.close(); });
  return { server, port: server.address().port, base: `http://127.0.0.1:${server.address().port}` };
}
function rawRequest(port, bytes, timeout = 2000) {
  return new Promise((accept, reject) => {
    const socket = connect({ host: '127.0.0.1', port }); const chunks = []; let length = 0;
    socket.setTimeout(timeout, () => socket.destroy(new Error('Socket fuzz deadline exceeded')));
    socket.on('connect', () => socket.write(bytes));
    socket.on('data', chunk => { length += chunk.length; if (length > 131_072) socket.destroy(new Error('Response exceeds fuzz bound')); else chunks.push(chunk); });
    socket.on('error', reject);
    socket.on('close', () => accept(Buffer.concat(chunks).toString('utf8')));
  });
}
function request(path = '/v1/keyrings', headers = `Authorization: ${authorization}\r\n`, method = 'GET', body = '') {
  return `${method} ${path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n${headers}\r\n${body}`;
}
function status(response) { return Number(/^HTTP\/1\.[01] (\d{3})/.exec(response)?.[1]); }

test('JWT attack corpus rejects algorithm/key confusion, JOSE directives and ambiguous protected JSON', () => {
  assert.equal(verify(authorization).subject, 'alice');
  for (const entry of corpus.jwtHeaders) trial('jwt-corpus', entry.id, () => rejectToken(jwt(validClaims, { alg: 'RS256', kid: 'test', ...entry.value })));
  for (const [i, header] of corpus.duplicateJson.entries()) trial('jwt-duplicate-json', i, () => rejectToken(jwt(validClaims, { alg: 'RS256' }, { header })));
  for (const field of ['iss', 'aud', 'sub', 'exp', 'scope']) {
    const raw = JSON.stringify(validClaims).replace(/}$/, `,${JSON.stringify(field)}:${JSON.stringify(validClaims[field])}}`);
    trial('jwt-duplicate-json', field, () => rejectToken(jwt(validClaims, { alg: 'RS256' }, { claims: raw })));
  }
  const invalidUtf8 = Buffer.concat([Buffer.from('{"sub":"'), Buffer.from([0xc0, 0xaf]), Buffer.from('"}')]);
  trial('jwt-invalid-utf8', 0, () => rejectToken(jwt(validClaims, { alg: 'RS256' }, { header: invalidUtf8 })));
  const jwksVerify = createJwtVerifier({ issuer, audience, jwks: { keys: [{ ...jwk, kid: 'test', key_ops: ['verify'] }] }, clock: () => NOW });
  for (const kid of corpus.unknownKids) trial('jwt-kid', kid, () => assert.throws(() => jwksVerify(`Bearer ${jwt(validClaims, { alg: 'RS256', kid })}`), AccessError));
  for (const key_ops of ['verify', 'notverify', {}, [1], [], ['verify', 'sign'], ['verify', 'verify']]) {
    trial('jwks-config', JSON.stringify(key_ops), () => assert.throws(() => createJwtVerifier({ issuer, audience, jwks: { keys: [{ ...jwk, kid: 'test', key_ops }] } })));
  }
  for (const field of ['d', 'p', 'q', 'dp', 'dq', 'qi', 'oth']) trial('jwks-config', field, () => assert.throws(() => createJwtVerifier({ issuer, audience, jwks: { keys: [{ ...jwk, kid: 'test', [field]: null }] } })));
  for (const [id, candidates] of [
    ['duplicate-kid', [{ ...jwk, kid: 'test' }, { ...jwk, kid: 'test' }]],
    ['empty-keys', []], ['too-many-keys', Array.from({ length: 9 }, (_, index) => ({ ...jwk, kid: String(index) }))],
    ['wrong-alg', [{ ...jwk, kid: 'test', alg: 'HS256' }]], ['wrong-use', [{ ...jwk, kid: 'test', use: 'enc' }]],
    ['wrong-kty', [{ ...jwk, kid: 'test', kty: 'EC' }]],
  ]) trial('jwks-config', id, () => assert.throws(() => createJwtVerifier({ issuer, audience, jwks: { keys: candidates } })));
  const weak = generateKeyPairSync('rsa', { modulusLength: 1024 });
  const elliptic = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  for (const [id, publicKey] of [['weak-rsa', weak.publicKey], ['non-rsa', elliptic.publicKey]]) {
    trial('jwks-config', id, () => assert.throws(() => createJwtVerifier({ issuer, audience, publicKey: publicKey.export({ type: 'spki', format: 'pem' }) })));
  }
});

test('signed claim type/identity/scope corpus fails closed rather than coercing untrusted claims', () => {
  const bad = {
    iss: [null, issuer + '/', ['https://identity.invalid'], { toString: issuer }],
    aud: [null, {}, ['wrong'], [audience, 1], 1],
    sub: [null, {}, [], 1, '', ' ', 'alice\n', '\ud800', '\udc00', 'x'.repeat(257)],
    exp: [null, true, '1793491500', 1.5, NOW / 1000, Number.MAX_SAFE_INTEGER + 1],
    nbf: [null, true, '0', 0.5, NOW / 1000 + 1],
    iat: [null, true, '0', 0.5, NOW / 1000 + 31],
    scope: [null, {}, ['location:read'], 1, '', 'location:read:all', 'Location:read', 'location:read\tother', 'location:read\n'],
  };
  for (const [field, values] of Object.entries(bad)) for (const [index, value] of values.entries()) {
    trial('jwt-claim-types', `${field}:${index}`, () => rejectToken(jwt({ ...validClaims, [field]: value, ...(field === 'scope' ? { scp: ['location:read'] } : {}) })));
  }
  for (const scp of [['location:read', 1], ['location:read', {}], 'location:read', null]) trial('jwt-claim-types', 'scp', () => rejectToken(jwt({ ...validClaims, scope: undefined, scp })));
  assert.equal(verify(`Bearer ${jwt({ ...validClaims, scope: undefined, scp: ['location:read'] })}`).subject, 'alice');
  assert.throws(() => createJwtVerifier({ issuer, audience, publicKey, clock: () => NaN })(authorization), AccessError);
});

test('seeded strict JSON compatibility and duplicate-key mutations exercise parser boundaries', () => {
  function generated(depth = 0) {
    const leaves = [null, true, false, 0, -0, random() / 1000, '\ud83d\udd11 Korean 한글', '\\"\n\t', '__proto__'];
    if (depth >= 4 || random() % 3 === 0) return pick(leaves);
    if (random() % 2) return Array.from({ length: random() % 5 }, () => generated(depth + 1));
    return Object.fromEntries(Array.from({ length: random() % 5 }, (_, index) => [`key-${index}-${random() % 8}`, generated(depth + 1)]));
  }
  for (let index = 0; index < iterations; index++) trial('json-grammar-mutations', index, () => {
    const original = JSON.stringify(generated());
    assert.deepEqual(parseStrictJson(Buffer.from(original)), JSON.parse(original));
    const payload = JSON.stringify({ value: generated() });
    const ambiguous = payload.slice(0, -1) + ',"\\u0076alue":null}';
    assert.throws(() => parseStrictJson(Buffer.from(ambiguous)));
  });
});

test('seeded JWT wire mutation rejects every altered signed token and enforces canonical encoding', () => {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-.=+/~\u0000\n';
  for (let index = 0; index < iterations; index++) trial('jwt-wire-mutations', index, () => {
    const offset = random() % validToken.length;
    let replacement = alphabet[random() % alphabet.length];
    if (replacement === validToken[offset]) replacement = replacement === 'A' ? 'B' : 'A';
    const value = validToken.slice(0, offset) + replacement + validToken.slice(offset + 1);
    rejectToken(value);
  });
  const parts = validToken.split('.');
  const alphabet64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
  const signatureLast = alphabet64.indexOf(parts[2].at(-1));
  const aliased = [...parts]; aliased[2] = parts[2].slice(0, -1) + alphabet64[signatureLast + 1];
  trial('jwt-noncanonical', 'signature-unused-bits', () => rejectToken(aliased.join('.')));
  for (const malformed of ['', 'a', 'AA=', 'AA==', '+A', '/A', 'AB']) trial('base64-corpus', malformed, () => assert.throws(() => decodeBase64Url(malformed)));
  for (const value of [null, undefined, [], {}, authorization + 'x'.repeat(8192), 'bearer ' + validToken, 'Bearer  ' + validToken, 'Bearer\t' + validToken]) trial('authorization-corpus', typeof value, () => assert.throws(() => verify(value), AccessError));
});

test('grant and consent corruption, inherited maps and revision/scope omissions never disclose a fix', () => {
  const project = (grant = validGrant, consent = validConsent) => snapshot({ events: { own: ownEvent }, grants: { grant }, consents: { consent } }, [{ ...binding, grantId: 'grant' }]).keyrings[0];
  assert.equal(project().status, 'available');
  const changes = [
    ['grantId', undefined], ['grantId', 'different'], ['principalId', 'bob'], ['subjectId', 'ue-bob'], ['consentId', undefined],
    ['revision', undefined], ['revision', null], ['revision', '1'], ['revision', 0], ['revision', -1], ['revision', 1.5], ['revision', Number.MAX_SAFE_INTEGER + 1],
    ['scopes', undefined], ['scopes', []], ['scopes', 'location:read'], ['scopes', ['location:read', {}]], ['status', 'REVOKED'], ['expiresAt', '2026-02-30T00:00:00Z'],
  ];
  for (const [field, value] of changes) trial('grant-corruption', field, () => coordinatesCleared(project({ ...validGrant, [field]: value })));
  for (const [field, value] of [['consentId', undefined], ['consentId', 'different'], ['subjectId', 'ue-bob'], ['revision', undefined], ['revision', '1'], ['revision', 2], ['scopes', undefined], ['scopes', []], ['scopes', ['location:read', {}]], ['status', 'REVOKED']]) {
    trial('consent-corruption', field, () => coordinatesCleared(project(validGrant, { ...validConsent, [field]: value })));
  }
  trial('grant-corruption', 'both-revisions-missing', () => coordinatesCleared(project({ ...validGrant, revision: undefined }, { ...validConsent, revision: undefined })));
  trial('prototype-isolation', 'inherited-grant', () => coordinatesCleared(snapshot({ events: { own: ownEvent }, grants: Object.create({ grant: validGrant }), consents: { consent: validConsent } }, [{ ...binding, grantId: 'grant' }]).keyrings[0]));
  trial('prototype-isolation', 'inherited-consent', () => coordinatesCleared(snapshot({ events: { own: ownEvent }, grants: { grant: validGrant }, consents: Object.create({ consent: validConsent }) }, [{ ...binding, grantId: 'grant' }]).keyrings[0]));
  trial('prototype-isolation', 'inherited-binding', () => assert.throws(() => validateBindings([Object.create(binding)])));
  for (const id of ['__proto__', 'constructor', 'toString']) trial('prototype-isolation', id, () => {
    const state = parseStrictJson(Buffer.from(`{"events":{},"grants":{"${id}":{"status":"ACTIVE"}}}`));
    const output = snapshot(state, [{ ...binding, grantId: id }]); coordinatesCleared(output.keyrings[0]);
    assert.equal(Object.prototype.status, undefined); assert.equal({}.polluted, undefined);
    assert.deepEqual(snapshot(state, [binding, bobBinding], id).keyrings, []);
  });
});

test('seeded location and identity-map mutations preserve tenant isolation, clearing and input immutability', () => {
  const invalidNumbers = [null, false, '37.5', {}, [], NaN, Infinity, -Infinity];
  const invalidTimes = ['2026-02-30T00:00:00Z', '2026-11-01T24:00:00Z', '2026-11-01T00:00:60Z', '2026-11-01T00:00:00+24:00', '2026-11-01T00:00:00+00:60', '', null, 0];
  for (let index = 0; index < iterations; index++) trial('location-mutations', index, () => {
    let current = structuredClone(ownEvent), bs = structuredClone([binding, bobBinding]);
    const mode = random() % 9;
    if (mode === 0) current.location.lat = pick([...invalidNumbers, -90.00001, 90.00001]);
    if (mode === 1) current.location.lon = pick([...invalidNumbers, -180.00001, 180.00001]);
    if (mode === 2) current.timestamp = pick(invalidTimes);
    if (mode === 3) current.location.accuracyMeters = pick([...invalidNumbers.filter(x => x !== null), -1, 10_000_001]);
    if (mode === 4) current.permission = 'denied';
    if (mode === 5) current.service = pick(['location', '__proto__', 'Connectivity', null]);
    if (mode === 6) current.ueId = pick(['ue-bob', '__proto__', 'constructor']);
    if (mode === 7) bs[0].principalIds = ['alice-other'];
    if (mode === 8) current.location.expiresAt = pick(invalidTimes);
    const state = freeze({ events: { injected: bobEvent, candidate: current } }); bs = freeze(bs);
    const before = createHash('sha256').update(JSON.stringify({ state, bs })).digest('hex');
    const output = snapshot(state, bs);
    assert.ok(output.keyrings.every(fix => fix.id === 'alice-ring'));
    assert.equal(output.keyrings.length, mode === 7 ? 0 : 1);
    for (const fix of output.keyrings) { assert.ok(['unavailable', 'denied'].includes(fix.status)); coordinatesCleared(fix); }
    assert.deepEqual(snapshot(state, bs, 'stranger').keyrings, []);
    assert.equal(createHash('sha256').update(JSON.stringify({ state, bs })).digest('hex'), before);
  });
});

test('real HTTP corpus covers duplicate auth/host, smuggling framing, methods, paths, headers and body bounds', async t => {
  let reads = 0;
  const { port } = await listen(t, { readState: async () => { reads++; return { events: { own: ownEvent, other: bobEvent } }; } });
  const cases = [
    ['duplicate-auth-valid-first', request('/v1/keyrings', `Authorization: ${authorization}\r\nAuthorization: Bearer attacker\r\n`), [401]],
    ['duplicate-auth-valid-last', request('/v1/keyrings', `authorization: Bearer attacker\r\nAUTHORIZATION: ${authorization}\r\n`), [401]],
    ['duplicate-host', request('/v1/keyrings', `Host: attacker.invalid\r\nAuthorization: ${authorization}\r\n`), [400]],
    ['missing-host', `GET /v1/keyrings HTTP/1.1\r\nConnection: close\r\nAuthorization: ${authorization}\r\n\r\n`, [400]],
    ['joined-auth', request('/v1/keyrings', `Authorization: ${authorization}, Bearer attacker\r\n`), [401]],
    ['fixed-body', request('/v1/keyrings', `Authorization: ${authorization}\r\nContent-Length: 2\r\n`, '{}'), [400]],
    ['huge-declared-body', request('/v1/keyrings', `Authorization: ${authorization}\r\nContent-Length: 999999999\r\n`), [400]],
    ['chunked-body', request('/v1/keyrings', `Authorization: ${authorization}\r\nTransfer-Encoding: chunked\r\n`, '2\r\n{}\r\n0\r\n\r\n'), [400]],
    ['cl-te-smuggling', request('/v1/keyrings', `Authorization: ${authorization}\r\nContent-Length: 0\r\nTransfer-Encoding: chunked\r\n`, '0\r\n\r\n'), [400]],
    ['duplicate-content-length', request('/v1/keyrings', `Authorization: ${authorization}\r\nContent-Length: 0\r\nContent-Length: 0\r\n`), [400]],
    ['header-overflow', request('/v1/keyrings', `Authorization: ${authorization}\r\nX-Large: ${'x'.repeat(9000)}\r\n`), [431]],
    ['header-count', request('/v1/keyrings', `Authorization: ${authorization}\r\n` + Array.from({ length: 65 }, (_, i) => `X-${i}: x\r\n`).join('')), [400]],
    ['request-target-overflow', request('/' + 'x'.repeat(9000)), [431]],
    ['control-in-header', request('/v1/keyrings', `Authorization: ${authorization}\u0000\r\n`), [400]],
    ...['POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS', 'TRACE'].map(method => [`method-${method}`, request('/v1/keyrings', `Authorization: ${authorization}\r\n`, method), [405]]),
    ...corpus.paths.map(path => [`path-${path}`, request(path), [404]]),
  ];
  for (const [id, wire, expected] of cases) await asyncTrial('http-corpus', id, async () => {
    const response = await rawRequest(port, wire);
    assert.ok(expected.includes(status(response)), `${id}: status ${status(response)}`);
    assert.ok(!response.includes('37.5') && !response.includes('-33.123456'));
    assert.ok(!response.includes(validToken));
  });
  assert.equal(reads, 0);
  const response = await rawRequest(port, request());
  assert.equal(status(response), 200); assert.match(response, /cache-control: no-store, private/i);
  assert.match(response, /x-content-type-options: nosniff/i); assert.doesNotMatch(response, /access-control-allow-origin/i);
  assert.equal(reads, 1);
});

test('seeded real HTTP malformed-target/header mutations never reach protected state readers', async t => {
  let reads = 0;
  const { port } = await listen(t, { readState: async () => { reads++; throw new Error('Protected reader reached'); } });
  for (let index = 0; index < Math.min(iterations, 256); index++) await asyncTrial('http-mutations', index, async () => {
    const suffix = (random() >>> 0).toString(36);
    const wire = random() % 2 ? request(`/v1/keyrings?${suffix}`, `Authorization: ${authorization}\r\n`) : request('/v1/keyrings', `Authorization: Bearer ${suffix}.invalid.signature\r\n`);
    const response = await rawRequest(port, wire);
    assert.ok([401, 404].includes(status(response)));
  });
  assert.equal(reads, 0);
});

test('seeded HTTP byte fuzz rejects control octets and malformed syntax without exposing protected state', async t => {
  let reads = 0;
  const { port } = await listen(t, { readState: async () => { reads++; throw new Error('Protected reader reached'); } });
  const octets = [0, 1, 9, 10, 13, 31, 32, 34, 37, 58, 92, 127, 128, 255];
  for (let index = 0; index < Math.min(iterations, 512); index++) await asyncTrial('http-byte-mutations', index, async () => {
    const character = String.fromCharCode(pick(octets));
    const junk = `fuzz-${random().toString(16)}${character}tail`;
    const wire = random() % 2
      ? request(`/v1/keyrings?${junk}`, 'Authorization: Bearer invalid.invalid.invalid\r\n')
      : request('/v1/keyrings', `Authorization: Bearer invalid.invalid.invalid\r\nX-${junk}: ${junk}\r\n`);
    const response = await rawRequest(port, Buffer.from(wire + '\r\n\r\n', 'latin1'));
    assert.ok([400, 401, 404, 405, 408, 417, 431, 501, 505].includes(status(response)), `byte case ${index}: ${status(response)}`);
    assert.ok(!response.includes('37.5') && !response.includes('-33.123456'));
  });
  assert.equal(reads, 0);
  assert.equal((await fetch(`http://127.0.0.1:${port}/v1/keyrings`)).status, 401);
});

test('bounded strict file reads reject malformed/duplicate/deep/oversize JSON without altering files', async t => {
  await mkdir(temporaryRoot, { recursive: true });
  const directory = await mkdtemp(join(temporaryRoot, 'mobile-api-fuzz-')); t.after(() => rm(directory, { recursive: true, force: true }));
  const file = join(directory, 'state.json');
  const invalid = ['{"events":{', '{"events":{},"events":{}}', '{"events":{},"\\u0065vents":{}}', '[1,]', '{"x":NaN}', '{"x":undefined}', '{"x":01}', '{"x":"line\nbreak"}', '{"x":"\\ud800"}', '{"x":"\\udc00"}', '['.repeat(66) + '0' + ']'.repeat(66), Buffer.from([0x7b, 0x22, 0x78, 0x22, 0x3a, 0x22, 0xc3, 0x28, 0x22, 0x7d]), Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from('{"events":{}}')])];
  for (const [index, bytes] of invalid.entries()) await asyncTrial('file-corpus', index, async () => {
    await writeFile(file, bytes); const before = await readFile(file); const metadata = await stat(file);
    await assert.rejects(readBoundedJson(file, 8192));
    assert.deepEqual(await readFile(file), before); assert.equal((await stat(file)).mtimeMs, metadata.mtimeMs);
  });
  for (const valid of ['{"emoji":"\\ud83d\\udd11","events":{}}', '{"key":"a\\\"b\\\\c","events":{}}', '{"nested":[true,false,null,1.25e2,{}]}']) {
    await asyncTrial('file-valid-json', valid.length, async () => { await writeFile(file, valid); assert.deepEqual(await readBoundedJson(file, 8192), JSON.parse(valid)); });
  }
  for (const [id, bytes, limit] of [['over-limit', '{"x":"' + 'a'.repeat(128) + '"}', 128], ['valid-at-limit', '{"events":{}}', 13]]) {
    await asyncTrial('file-corpus', id, async () => {
      await writeFile(file, bytes);
      if (id === 'over-limit') await assert.rejects(readBoundedJson(file, limit));
      else assert.deepEqual(await readBoundedJson(file, limit), { events: {} });
    });
  }
  for (const path of [join(directory, 'missing'), directory]) await asyncTrial('file-corpus', path === directory ? 'directory' : 'missing', () => assert.rejects(readBoundedJson(path, 8192)));
  for (const limit of [0, -1, NaN, Infinity, 16_777_217]) await asyncTrial('file-corpus', `limit-${limit}`, () => assert.rejects(readBoundedJson(file, limit)));
  await asyncTrial('file-corpus', 'multi-chunk-valid-read', async () => {
    await writeFile(file, JSON.stringify({ events: {}, metadata: 'x'.repeat(70_000) }));
    const output = await readBoundedJson(file, 131_072); assert.equal(output.metadata.length, 70_000);
  });
  await writeFile(file, JSON.stringify({ events: { own: ownEvent, other: bobEvent } }));
  const before = await readFile(file); const metadata = await stat(file);
  const { base } = await listen(t, { readState: () => readBoundedJson(file, 8192) });
  for (let index = 0; index < 8; index++) {
    const response = await fetch(base + '/v1/keyrings', { headers: { authorization } }); assert.equal(response.status, 200);
    const output = await response.json(); assert.deepEqual(output.keyrings.map(fix => fix.id), ['alice-ring']);
  }
  await asyncTrial('mutation-safety', 'http-file-read-only', async () => {
    assert.deepEqual(await readFile(file), before); assert.equal((await stat(file)).mtimeMs, metadata.mtimeMs);
  });
  await asyncTrial('file-corpus', 'http-malformed-file-error', async () => {
    await writeFile(file, '{"events":{},"events":{}}');
    const bad = await readFile(file);
    const response = await fetch(base + '/v1/keyrings', { headers: { authorization } });
    assert.equal(response.status, 503); assert.deepEqual(await response.json(), { error: 'location_service_unavailable' });
    assert.deepEqual(await readFile(file), bad);
  });
});

test('resource boundaries cap concurrent reads and retain health after HTTP parser rejection', async t => {
  let release; const gate = new Promise(accept => { release = accept; }); let active = 0, highWater = 0;
  const { base, port } = await listen(t, { maxConcurrentReads: 2, readState: async () => { active++; highWater = Math.max(highWater, active); await gate; active--; return { events: { own: ownEvent } }; } });
  const pending = [fetch(base + '/v1/keyrings', { headers: { authorization } }), fetch(base + '/v1/keyrings', { headers: { authorization } })];
  for (let attempt = 0; active !== 2 && attempt < 200; attempt++) await new Promise(accept => setTimeout(accept, 5));
  try {
    assert.equal(active, 2);
    await asyncTrial('resource-bounds', 'read-saturation', async () => {
      const response = await fetch(base + '/v1/keyrings', { headers: { authorization } }); assert.equal(response.status, 503);
      assert.equal(highWater, 2);
    });
  } finally { release(); }
  for (const response of await Promise.all(pending)) { assert.equal(response.status, 200); await response.arrayBuffer(); }
  await rawRequest(port, request('/v1/keyrings', 'X-Large: ' + 'x'.repeat(9000) + '\r\n'));
  const healthy = await fetch(base + '/v1/keyrings', { headers: { authorization } }); assert.equal(healthy.status, 200); await healthy.arrayBuffer();
  const bindings = Array.from({ length: 100 }, (_, index) => ({ ...binding, id: `ring-${index}` }));
  const state = { events: Object.fromEntries(Array.from({ length: 20_000 }, (_, index) => [String(index), ownEvent])) };
  const startMemory = process.memoryUsage().heapUsed; const measurements = [];
  for (let index = 0; index < 5; index++) {
    const started = performance.now(); assert.equal(snapshot(state, bindings).keyrings.length, 100); measurements.push(performance.now() - started);
  }
  receipt.resourceMeasurements = { maxEvents: 20_000, visibleKeyrings: 100, maxConcurrentReads: highWater, queryMilliseconds: measurements.map(value => Number(value.toFixed(3))), heapDeltaBytes: process.memoryUsage().heapUsed - startMemory };
  trial('resource-bounds', 'maximum-store', () => assert.ok(Math.max(...measurements) < 2000, '20k-event read exceeded two-second regression budget'));
  trial('resource-bounds', 'store-overflow', () => assert.throws(() => snapshot({ events: { ...state.events, extra: ownEvent } }, bindings)));
  trial('resource-bounds', 'binding-overflow', () => assert.throws(() => snapshot({ events: {} }, [...bindings, { ...binding, id: 'extra' }])));
  const expanded = Array.from({ length: 100 }, (_, index) => ({ ...binding, id: String(index).padStart(96, '가'), name: '나'.repeat(96) }));
  const bounded = await listen(t, { readBindings: async () => expanded });
  await asyncTrial('resource-bounds', 'response-byte-overflow', async () => {
    const response = await fetch(bounded.base + '/v1/keyrings', { headers: { authorization } });
    assert.equal(response.status, 503); assert.deepEqual(await response.json(), { error: 'location_service_unavailable' });
  });
});

test('slow incomplete header sockets expire within the configured HTTP boundary', async t => {
  const { port } = await listen(t);
  const started = performance.now();
  const response = await rawRequest(port, 'GET /v1/keyrings HTTP/1.1\r\nHost: localhost\r\nX-Slow:', 8000);
  await asyncTrial('resource-bounds', 'slow-header-timeout', async () => {
    assert.equal(status(response), 408); assert.ok(performance.now() - started < 7500);
  });
});

test('a failed binding read cannot release concurrency slots while a state read is still pending', async t => {
  let release; const gate = new Promise(accept => { release = accept; }); let active = 0, highWater = 0;
  const { base } = await listen(t, { maxConcurrentReads: 2,
    readState: async () => { active++; highWater = Math.max(highWater, active); await gate; active--; return { events: {} }; },
    readBindings: () => { throw new Error('Malformed local binding file'); } });
  const pending = [fetch(base + '/v1/keyrings', { headers: { authorization } }), fetch(base + '/v1/keyrings', { headers: { authorization } })];
  for (let attempt = 0; active !== 2 && attempt < 200; attempt++) await new Promise(accept => setTimeout(accept, 5));
  try {
    assert.equal(active, 2);
    await asyncTrial('resource-bounds', 'failed-parallel-reader-saturation', async () => {
      const response = await fetch(base + '/v1/keyrings', { headers: { authorization } }); assert.equal(response.status, 503);
      assert.equal(highWater, 2); await response.arrayBuffer();
    });
  } finally { release(); }
  for (const response of await Promise.all(pending)) { assert.equal(response.status, 503); await response.arrayBuffer(); }
});
