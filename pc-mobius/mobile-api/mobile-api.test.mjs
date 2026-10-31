import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, sign } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdir, mkdtemp, readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createJwtVerifier, AccessError } from './auth.mjs';
import { readLocations, validateBindings } from './location-reader.mjs';
import { createMobileHandler } from './server.mjs';
import { IntegrationStore } from '../integration/src/integration-store.mjs';
import { MecCallbackIngress } from '../integration/src/mec-adapter.mjs';

const NOW = Date.parse('2026-10-31T12:00:00Z');
const issuer = 'https://identity.example.org', audience = 'privacy-bubble-api';
const pair = generateKeyPairSync('rsa', { modulusLength: 2048 });
const other = generateKeyPairSync('rsa', { modulusLength: 2048 });
const publicKey = pair.publicKey.export({ type: 'spki', format: 'pem' });
const bindings = [{ id: 'ring-1', name: '나의 키링', ueId: 'ue-1', principalIds: ['alice'] },
  { id: 'ring-2', name: '다른 사용자 키링', ueId: 'ue-2', principalIds: ['bob'] }];
function token(changes = {}, header = {}, privateKey = pair.privateKey) {
  const a = Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT', kid: 'unit', ...header })).toString('base64url');
  const b = Buffer.from(JSON.stringify({ iss: issuer, aud: audience, sub: 'alice', exp: Math.floor(NOW / 1000) + 300, scope: 'location:read', ...changes })).toString('base64url');
  return `${a}.${b}.${sign('RSA-SHA256', Buffer.from(`${a}.${b}`), privateKey).toString('base64url')}`;
}
const verifier = () => createJwtVerifier({ issuer, audience, publicKey, clock: () => NOW });
const event = (changes = {}) => ({ eventId: 'event-1', service: 'Location', ueId: 'ue-1', timestamp: '2026-10-31T11:59:50Z',
  location: { lat: 37.5, lon: 127.0, accuracyMeters: 15 }, ...changes });
const snapshot = (events, extra = {}, bs = bindings) => readLocations({ state: { events, ...extra }, bindings: bs, subject: 'alice', now: NOW });

test('configuration fails closed without trusted signing material or HTTPS issuer', () => {
  assert.throws(() => createJwtVerifier());
  assert.throws(() => createJwtVerifier({ issuer, audience }));
  assert.throws(() => createJwtVerifier({ issuer: 'http://identity.example.org', audience, publicKey }));
  assert.throws(() => createJwtVerifier({ issuer, audience, publicKey, jwks: { keys: [] } }));
  assert.throws(() => createMobileHandler());
});

test('signed access token authenticates exact issuer/audience and required scope', () => {
  assert.equal(verifier()(`Bearer ${token()}`).subject, 'alice');
  assert.equal(verifier()(`Bearer ${token({ aud: ['another-api', audience] })}`).subject, 'alice');
  assert.equal(verifier()(`Bearer ${token({ scope: undefined, scp: ['location:read'] })}`).subject, 'alice');
});

test('forged signatures, unsigned algorithms, wrong issuer/audience, expiry and future nbf are rejected', () => {
  const verify = verifier();
  for (const value of [token({}, {}, other.privateKey), token({}, { alg: 'none' }), token({ iss: 'https://attacker.example' }),
    token({ aud: 'wrong-api' }), token({ exp: Math.floor(NOW / 1000) }), token({ exp: undefined }), token({ nbf: Math.floor(NOW / 1000) + 1 }),
    token({}, { jku: 'https://attacker.example/key' }), 'plain-principal', 'a.b.c']) {
    assert.throws(() => verify(`Bearer ${value}`), AccessError);
  }
  assert.throws(() => verify(`Bearer ${token({ scope: '' })}`), error => error.status === 403);
});

test('trusted JWKS selects a configured key id and rejects unknown ids', () => {
  const jwk = pair.publicKey.export({ format: 'jwk' });
  const verify = createJwtVerifier({ issuer, audience, jwks: { keys: [{ ...jwk, kid: 'unit', alg: 'RS256', use: 'sig' }] }, clock: () => NOW });
  assert.equal(verify(`Bearer ${token()}`).subject, 'alice');
  assert.throws(() => verify(`Bearer ${token({}, { kid: 'not-configured' })}`), AccessError);
});

test('only administrator-bound keyrings are disclosed, missing coordinates are not invented', () => {
  const result = snapshot({});
  assert.equal(result.keyrings.length, 1);
  assert.equal(result.keyrings[0].id, 'ring-1');
  assert.equal(result.keyrings[0].status, 'unavailable');
  assert.equal(result.keyrings[0].latitude, null);
  assert.equal(result.keyrings[0].connectivity, 'unknown');
  assert.deepEqual(readLocations({ state: { events: {} }, bindings, subject: 'stranger', now: NOW }).keyrings, []);
});

test('valid MEC fix keeps observed time, bounded accuracy, battery and freshness', () => {
  const fix = snapshot({ a: event({ batteryPercent: 92, connectivity: 'online' }) }).keyrings[0];
  assert.equal(fix.status, 'available'); assert.equal(fix.latitude, 37.5); assert.equal(fix.longitude, 127);
  assert.equal(fix.observedAt, '2026-10-31T11:59:50.000Z'); assert.equal(fix.expiresAt, '2026-10-31T12:04:50.000Z');
  assert.equal(fix.accuracyMeters, 15); assert.equal(fix.batteryPercent, 92);
});

test('older out-of-order callbacks do not replace a newer observed fix', () => {
  const fix = snapshot({ newer: event(), old: event({ eventId: 'old', timestamp: '2026-10-31T11:58:00Z', location: { lat: 36, lon: 126 } }) }).keyrings[0];
  assert.equal(fix.latitude, 37.5);
});

test('invalid latest coordinates clear prior coordinates instead of falling back to a fresh old fix', () => {
  const fix = snapshot({ older: event({ timestamp: '2026-10-31T11:59:00Z' }), latest: event({ location: { lat: 999, lon: 127 } }) }).keyrings[0];
  assert.equal(fix.status, 'unavailable'); assert.equal(fix.latitude, null); assert.equal(fix.observedAt, null);
});

test('undatable latest event remains unavailable even if followed by an older valid callback', () => {
  const fix = snapshot({ fresh: event(), invalid: event({ timestamp: 'not-a-time' }), lateOld: event({ timestamp: '2026-10-31T11:58:00Z' }) }).keyrings[0];
  assert.equal(fix.status, 'unavailable');
});

test('invalid calendar, future time, numeric-string coordinates and negative accuracy are unavailable', () => {
  for (const bad of [event({ timestamp: '2026-02-31T11:59:50Z' }), event({ timestamp: '2026-10-31T13:00:00Z' }),
    event({ location: { lat: '37.5', lon: 127 } }), event({ location: { lat: 37.5, lon: 127, accuracyMeters: -1 } }),
    event({ observedAt: '2026-10-31T11:59:50Z', timestamp: '2026-10-31T11:00:00Z' })]) {
    assert.equal(snapshot({ bad }).keyrings[0].status, 'unavailable');
  }
});

test('expired fixes remain explicitly stale, never gain time from a recent receipt', () => {
  const fix = snapshot({ old: event({ observedAt: '2026-10-31T11:00:00Z' }) }).keyrings[0];
  assert.equal(fix.status, 'stale'); assert.equal(fix.latitude, 37.5);
  assert.equal(fix.expiresAt, '2026-10-31T11:05:00.000Z');
});

test('upstream expiry caps freshness and invalid expiry clears a fix', () => {
  assert.equal(snapshot({ a: event({ expiresAt: '2026-10-31T11:59:55Z' }) }).keyrings[0].status, 'stale');
  assert.equal(snapshot({ a: event({ expiresAt: '2026-10-31T11:00:00Z' }) }).keyrings[0].status, 'unavailable');
  assert.equal(snapshot({ a: event({ expiresAt: '2027-10-31T11:00:00Z' }) }).keyrings[0].expiresAt, '2026-10-31T12:04:50.000Z');
});

test('permission denial and disabled binding clear coordinates and location metadata', () => {
  for (const fix of [snapshot({ a: event({ location: { lat: 37.5, lon: 127, status: 'denied' } }) }).keyrings[0],
    snapshot({ a: event() }, {}, [{ ...bindings[0], active: false }]).keyrings[0]]) {
    assert.equal(fix.status, 'denied'); assert.equal(fix.latitude, null); assert.equal(fix.observedAt, null);
  }
});

test('grant revocation, consent revocation, expiry, wrong principal and revision revoke visible coordinates', () => {
  const grant = { status: 'ACTIVE', principalId: 'alice', subjectId: 'ue-1', expiresAt: '2026-10-31T13:00:00Z', scopes: ['location:read'], consentId: 'consent-1', revision: 2 };
  const consent = { status: 'ACTIVE', subjectId: 'ue-1', expiresAt: '2026-10-31T13:00:00Z', revision: 2 };
  const bs = [{ ...bindings[0], grantId: 'grant-1' }];
  const project = (g, c) => snapshot({ a: event() }, { grants: { 'grant-1': g }, consents: { 'consent-1': c } }, bs).keyrings[0];
  assert.equal(project(grant, consent).status, 'available');
  for (const [g, c] of [[{ ...grant, status: 'REVOKED' }, consent], [grant, { ...consent, status: 'REVOKED' }],
    [{ ...grant, expiresAt: '2026-10-31T11:59:59Z' }, consent], [{ ...grant, principalId: 'bob' }, consent],
    [grant, { ...consent, revision: 3 }], [{ ...grant, scopes: [] }, consent],
    [{ ...grant, subjectId: 'another-keyring' }, consent], [grant, { ...consent, subjectId: 'another-keyring' }]]) {
    assert.equal(project(g, c).status, 'denied'); assert.equal(project(g, c).latitude, null);
  }
});

test('binding/result/store limits fail closed without truncating another user keyring into a response', () => {
  assert.throws(() => validateBindings([{ ...bindings[0], principalIds: ['alice', 'alice'] }]));
  assert.throws(() => validateBindings(Array.from({ length: 101 }, (_, i) => ({ ...bindings[0], id: String(i) }))));
  assert.throws(() => snapshot(Object.fromEntries(Array.from({ length: 20_001 }, (_, i) => [i, event()]))));
});

test('HTTP requires signed auth, rejects mutation/body/query, returns no-store without CORS', async t => {
  let state = { events: { a: event() } }, currentBindings = bindings;
  const handler = createMobileHandler({ readState: async () => state, readBindings: async () => currentBindings, verifyToken: verifier(), clock: () => NOW });
  const server = createServer(handler); await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const headers = { authorization: `Bearer ${token()}` };
  const missing = await fetch(base + '/v1/keyrings'); assert.equal(missing.status, 401); assert.equal(missing.headers.get('access-control-allow-origin'), null);
  assert.equal((await fetch(base + '/v1/keyrings', { method: 'POST', headers, body: JSON.stringify({ principal: { subject: 'alice' } }) })).status, 405);
  assert.equal((await fetch(base + '/v1/keyrings?subject=bob', { headers })).status, 404);
  const valid = await fetch(base + '/v1/keyrings', { headers }); assert.equal(valid.status, 200); assert.match(valid.headers.get('cache-control'), /no-store/);
  assert.equal((await valid.json()).keyrings[0].latitude, 37.5);
  currentBindings = []; assert.deepEqual((await (await fetch(base + '/v1/keyrings', { headers })).json()).keyrings, []);
  state = null; assert.equal((await fetch(base + '/v1/keyrings', { headers })).status, 503);
});

test('actual IntegrationStore/MEC ingress writes can be read without modifying upstream modules', async t => {
  const scratch = fileURLToPath(new URL('../../../../.local/mobile-api-tests/', import.meta.url));
  await mkdir(scratch, { recursive: true });
  const directory = await mkdtemp(join(scratch, 'mobile-location-read-')); t.after(() => rm(directory, { recursive: true, force: true }));
  const statePath = join(directory, 'runtime.json');
  const store = await new IntegrationStore(statePath).init();
  const ingress = new MecCallbackIngress({ store, clock: () => new Date(NOW).toISOString() });
  await ingress.ingest(event());
  const state = JSON.parse(await readFile(statePath, 'utf8'));
  const fix = readLocations({ state, bindings, subject: 'alice', now: NOW }).keyrings[0];
  assert.equal(fix.latitude, 37.5); assert.equal(fix.status, 'available');
  assert.equal(state.events['event-1'].status, 'RECONCILED');
});
