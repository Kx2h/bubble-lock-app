import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { request } from 'node:https';
import { dirname, join, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const localRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../../../../.local');
mkdirSync(localRoot, { recursive: true, mode: 0o700 });
const { fixtureIdentity, startFixture } = await import('./server.mjs');

test('isolated portal fixture preserves TLS, identity and a bounded pending denial', { timeout: 20000 }, async t => {
  const directory = mkdtempSync(join(localRoot, 'portal-network-selftest-'));
  const path = name => join(directory, name);
  const openssl = (...args) => execFileSync('openssl', args, { stdio: 'ignore' });
  let running;
  try {
    openssl('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-keyout', path('ca.key'), '-out', path('ca.pem'),
      '-subj', '/CN=Isolated Portal Self Test CA', '-addext', 'basicConstraints=critical,CA:TRUE', '-addext', 'keyUsage=critical,keyCertSign,cRLSign');
    openssl('req', '-new', '-newkey', 'rsa:2048', '-nodes', '-keyout', path('server.key'), '-out', path('server.csr'), '-subj', '/CN=localhost');
    writeFileSync(path('server.ext'), 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost,IP:127.0.0.1\n', { mode: 0o600 });
    openssl('x509', '-req', '-days', '1', '-in', path('server.csr'), '-CA', path('ca.pem'), '-CAkey', path('ca.key'),
      '-CAcreateserial', '-out', path('server.pem'), '-extfile', path('server.ext'));
    running = await startFixture({ certPath: path('server.pem'), keyPath: path('server.key'), privateDir: path('state'), port: 0 });
    const ca = readFileSync(path('ca.pem'));
    const token = readFileSync(running.tokenFile, 'utf8').trim();
    function exchange({ method = 'GET', route = '/v1/portal/locations', credential = token, ...tlsOptions } = {}) {
      return new Promise((resolveResponse, reject) => {
        const headers = { accept: 'application/json', 'cache-control': 'no-store' };
        if (credential !== null) headers.authorization = 'Bearer ' + credential;
        if (method === 'POST') headers['content-length'] = '0';
        const call = request({ host: '127.0.0.1', port: running.port, servername: 'localhost', ca,
          minVersion: 'TLSv1.3', maxVersion: 'TLSv1.3', rejectUnauthorized: true, agent: false,
          method, path: route, headers, ...tlsOptions }, response => {
          const chunks = []; let bytes = 0;
          response.on('data', chunk => {
            bytes += chunk.length;
            if (bytes > 65536) call.destroy(new Error('Fixture response bound exceeded'));
            else chunks.push(chunk);
          });
          response.once('error', reject);
          response.once('end', () => resolveResponse({ status: response.statusCode, headers: response.headers,
            body: JSON.parse(Buffer.concat(chunks).toString('utf8')), protocol: response.socket.getProtocol() }));
        });
        call.setTimeout(10000, () => call.destroy(new Error('Fixture request deadline exceeded')));
        call.once('error', reject); call.end();
      });
    }

    await t.test('private generated token and dedicated directory modes', () => {
      assert.equal(statSync(running.tokenFile).mode & 0o777, 0o600);
      assert.equal(statSync(path('state')).mode & 0o777, 0o700);
    });
    let initial;
    await t.test('one signed fixture identity yields only explicit synthetic coordinates', async () => {
      initial = await exchange();
      assert.equal(initial.status, 200); assert.equal(initial.protocol, 'TLSv1.3');
      assert.deepEqual(initial.body.deviceIds, [fixtureIdentity.keyringId]);
      assert.equal(initial.body.observations.length, 1);
      const observation = initial.body.observations[0];
      assert.equal(observation.deviceId, fixtureIdentity.keyringId); assert.equal(observation.simulation, true);
      assert.deepEqual(observation.location, { lat: 37.12345, lon: 127.12345, source: 'gps', accuracyM: 8 });
    });
    await t.test('Date, no-store and integration contract accompany the five-second lease', () => {
      assert.ok(Number.isFinite(Date.parse(initial.headers.date)));
      assert.match(initial.headers['cache-control'], /(?:^|,\s*)no-store(?:,|$)/);
      assert.equal(initial.headers['x-privacy-bubble-contract'], 'privacy-bubble.integration.v1');
      const remaining = Date.parse(initial.body.readExpiresAt) - Date.now();
      assert.ok(remaining > 4000 && remaining <= 5000);
    });
    await t.test('wrong credential is denied', async () => assert.equal((await exchange({ credential: 'isolated-wrong-token' })).status, 401));
    await t.test('missing credential is denied', async () => assert.equal((await exchange({ credential: null })).status, 401));
    await t.test('a changed JWT payload without its issued RSA signature is denied', async () => {
      const parts = token.split('.');
      parts[1] = Buffer.from(JSON.stringify({ iss: 'https://wrong.invalid', exp: 9999999999 })).toString('base64url');
      assert.equal((await exchange({ credential: parts.join('.') })).status, 401);
    });
    await t.test('TLS 1.2 cannot connect', async () => assert.rejects(exchange({ minVersion: 'TLSv1.2', maxVersion: 'TLSv1.2' })));
    await t.test('an untrusted certificate cannot connect', async () => assert.rejects(exchange({ ca: undefined })));
    await t.test('a trusted certificate for the wrong hostname cannot connect', async () => {
      await assert.rejects(exchange({ servername: 'wrong-host.invalid' }), { code: 'ERR_TLS_CERT_ALTNAME_INVALID' });
    });
    await t.test('the pending-denial control requires the fixture credential', async () => {
      assert.equal((await exchange({ method: 'POST', route: '/_fixture/slow-deny', credential: null })).status, 401);
      assert.equal((await exchange()).status, 200);
      assert.equal((await exchange({ method: 'POST', route: '/_fixture/slow-deny' })).status, 200);
    });
    await t.test('next read remains pending for seven seconds and later reads stay denied', async () => {
      const started = performance.now();
      assert.equal((await exchange()).status, 403);
      const elapsed = performance.now() - started;
      assert.ok(elapsed >= 6900 && elapsed < 10000);
      assert.equal((await exchange()).status, 403);
      t.diagnostic('Delayed synthetic denial: ' + Math.round(elapsed) + ' ms');
    });
    await t.test('owned token cleanup leaves redacted metadata only', async () => {
      await running.close();
      assert.equal(existsSync(running.tokenFile), false);
      const events = readFileSync(running.eventsFile, 'utf8');
      assert.ok(!events.includes(token));
      assert.ok(events.trim().split('\n').every(line => !Object.keys(JSON.parse(line)).some(key => /authorization|token|password|secret/i.test(key))));
    });
  } finally {
    if (running) await running.close();
    rmSync(directory, { recursive: true, force: true });
  }
});
