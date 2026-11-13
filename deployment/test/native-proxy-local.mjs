import assert from 'node:assert/strict';
import { spawn, spawnSync, execFileSync } from 'node:child_process';
import { createHash, X509Certificate } from 'node:crypto';
import { createSocket } from 'node:dgram';
import { createServer as httpsServer, request } from 'node:https';
import { createServer as netServer } from 'node:net';
import { mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, isAbsolute, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

process.umask(0o077);
const sourcePath = fileURLToPath(import.meta.url);
const repo = resolve(dirname(sourcePath), '../..');
const workspace = realpathSync(resolve(repo, '../..'));
if (!process.env.NGINX_BIN) throw new Error('Set NGINX_BIN explicitly to a workspace-local Nginx executable');
const binary = realpathSync(resolve(process.env.NGINX_BIN));
const binaryRelative = relative(workspace, binary);
if (!statSync(binary).isFile() || binaryRelative === '..' || binaryRelative.startsWith('../') || isAbsolute(binaryRelative))
  throw new Error('NGINX_BIN must resolve inside the workspace');
const version = spawnSync(binary, ['-v'], { encoding: 'utf8' });
if (version.status !== 0 || !/nginx\/1\.30\.6(?:\s|$)/.test(version.stderr)) throw new Error('This fixture requires Nginx 1.30.6');
const localRoot = resolve(workspace, '.local'); mkdirSync(localRoot, { recursive: true, mode: 0o700 });
const templatePath = resolve(repo, 'deployment/nginx.conf.template');
const template = readFileSync(templatePath, 'utf8');
const run = mkdtempSync(resolve(localRoot, 'native-proxy-validation-'));
const path = name => resolve(run, name);
const openssl = args => execFileSync('openssl', args, { stdio: 'ignore' });
let nginx, upstream, dns;
async function stopNginx() {
  if (!nginx) return;
  const child = nginx; nginx = null;
  if (!child.pid || child.exitCode !== null || child.signalCode !== null) return;
  await new Promise(resolveStopped => {
    const force = setTimeout(() => child.kill('SIGKILL'), 3000);
    child.once('exit', () => { clearTimeout(force); resolveStopped(); }); child.kill('SIGTERM');
  });
}
async function stopUpstream() {
  if (!upstream) return;
  const server = upstream; upstream = null; server.closeAllConnections(); await new Promise(resolveClosed => server.close(resolveClosed));
}
try {
for (const name of ['ca', 'rogue-ca']) openssl(['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-keyout', path(name + '.key'), '-out', path(name + '.pem'), '-subj', '/CN=' + name, '-addext', 'basicConstraints=critical,CA:TRUE', '-addext', 'keyUsage=critical,keyCertSign,cRLSign']);
function leaf(name, dns, ca = 'ca', usage = 'serverAuth') {
  openssl(['req', '-new', '-newkey', 'rsa:2048', '-nodes', '-keyout', path(name + '.key'), '-out', path(name + '.csr'), '-subj', '/CN=' + dns]);
  writeFileSync(path(name + '.ext'), 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=' + usage + '\nsubjectAltName=DNS:' + dns + '\n');
  openssl(['x509', '-req', '-days', '1', '-in', path(name + '.csr'), '-CA', path(ca + '.pem'), '-CAkey', path(ca + '.key'), '-CAcreateserial', '-out', path(name + '.pem'), '-extfile', path(name + '.ext')]);
}
leaf('public', 'native-proxy.fixture'); leaf('api-good', 'api'); leaf('api-wrong-name', 'wrong-api'); leaf('api-untrusted', 'api', 'rogue-ca'); leaf('gateway', 'fixture-gateway', 'ca', 'clientAuth');
const ca = readFileSync(path('ca.pem'));
const gateway = { cert: readFileSync(path('gateway.pem')), key: readFileSync(path('gateway.key')) };
const fingerprint = new X509Certificate(gateway.cert).fingerprint.replaceAll(':', '').toLowerCase();
dns = createSocket('udp4');
dns.on('message', (packet, remote) => {
  if (packet.length < 17) return;
  let end = 12;
  while (end < packet.length && packet[end]) end += 1 + packet[end];
  end += 5;
  if (end > packet.length || packet.readUInt16BE(end - 4) !== 1) return;
  const header = Buffer.alloc(12); packet.copy(header, 0, 0, 2); header.writeUInt16BE(0x8180, 2); header.writeUInt16BE(1, 4); header.writeUInt16BE(1, 6);
  const answer = Buffer.from([0xc0,0x0c,0,1,0,1,0,0,0,1,0,4,127,0,0,1]);
  dns.send(Buffer.concat([header, packet.subarray(12, end), answer]), remote.port, remote.address);
});
await new Promise(resolveReady => dns.bind(0, '127.0.0.1', resolveReady));
const dnsPort = dns.address().port;
const reserve = () => new Promise(resolvePort => { const server = netServer(); server.listen(0, '127.0.0.1', () => { const port = server.address().port; server.close(() => resolvePort(port)); }); });
const publicPort = await reserve(), httpPort = await reserve(), upstreamPort = await reserve();
const checks = [], upstreamRequests = [];
const check = (name, action) => { action(); checks.push(name); };
const credential = 'Bearer isolated-native-proxy-fixture';
const config = template.replace('worker_processes auto;', 'worker_processes 1;\ndaemon off;')
  .replace('pid /tmp/nginx.pid;', 'pid ' + path('nginx.pid') + ';')
  .replace('error_log /dev/stderr warn;', 'error_log ' + path('error.log') + ' warn;')
  .replace('access_log /dev/stdout safe;', 'access_log ' + path('access.log') + ' safe;')
  .replace('/etc/nginx/mime.types', path('mime.types'))
  .replaceAll('/tmp/', run + '/tmp/').replaceAll('/run/proxy/', run + '/')
  .replace('resolver 127.0.0.11 ', 'resolver 127.0.0.1:' + dnsPort + ' ')
  .replace('listen 80;', 'listen 127.0.0.1:' + httpPort + ';')
  .replace('listen @HTTPS_PORT@ ssl;', 'listen 127.0.0.1:@HTTPS_PORT@ ssl;')
  .replaceAll('api:8787', 'api:' + upstreamPort)
  .replaceAll('@HOST@', 'native-proxy.fixture').replaceAll('@HTTPS_PORT@', String(publicPort))
  .replaceAll('@PUBLIC_ORIGIN@', 'https://native-proxy.fixture:' + publicPort)
  .replaceAll('@GATEWAY_ID@', 'fixture-gateway').replaceAll('@GATEWAY_FINGERPRINT@', fingerprint).replaceAll('@MAP_ORIGINS@', 'https://tiles.example.invalid');
for (const directory of ['client', 'proxy', 'fastcgi', 'uwsgi', 'scgi']) mkdirSync(path('tmp/' + directory), { recursive: true, mode: 0o700 });
writeFileSync(path('mime.types'), 'types { application/json json; text/html html; }\n');
writeFileSync(path('nginx.conf'), config);
async function startUpstream(name, requireCertificate = false) {
  upstream = httpsServer({ cert: readFileSync(path(name + '.pem')), key: readFileSync(path(name + '.key')), ca,
    requestCert: true, rejectUnauthorized: requireCertificate, minVersion: 'TLSv1.3', maxVersion: 'TLSv1.3' }, (incoming, response) => {
    let body = 0; incoming.on('data', chunk => body += chunk.length);
    incoming.on('end', () => {
      upstreamRequests.push({ url: incoming.url, headers: incoming.headers, body,
        clientCertificate: Object.keys(incoming.socket.getPeerCertificate()).length !== 0, protocol: incoming.socket.getProtocol() });
      const allowed = incoming.headers.authorization === credential;
      response.writeHead(allowed ? 200 : 401, { 'content-type': 'application/json', 'cache-control': 'no-store', 'x-privacy-bubble-contract': 'privacy-bubble.integration.v1' });
      response.end(JSON.stringify(allowed ? { fixture: true, simulation: true, readExpiresAt: new Date(Date.now() + 5000).toISOString() } : { error: 'fixture_credential_required' }));
    });
  });
  await new Promise((resolveStarted, reject) => { upstream.once('error', reject); upstream.listen(upstreamPort, '127.0.0.1', resolveStarted); });
}
function exchange({ method = 'GET', route = '/v1/portal/locations', headers = {}, body, clientCertificate = false } = {}) {
  return new Promise((resolveResponse, reject) => {
    const call = request({ host: '127.0.0.1', port: publicPort, servername: 'native-proxy.fixture', ca,
      minVersion: 'TLSv1.3', maxVersion: 'TLSv1.3', rejectUnauthorized: true, agent: false,
      ...(clientCertificate ? gateway : {}), method, path: route, headers }, response => {
      response.resume(); response.once('end', () => resolveResponse({ status: response.statusCode, headers: response.headers }));
      response.once('error', reject);
    });
    call.setTimeout(6000, () => call.destroy(new Error('Local proxy fixture timeout'))); call.once('error', reject); call.end(body);
  });
}
async function startNginx() {
  nginx = spawn(binary, ['-p', run + '/', '-c', path('nginx.conf')], { stdio: 'ignore' });
  let launchError; nginx.once('error', failure => { launchError = failure; });
  for (let attempt = 0; attempt < 100; attempt++) {
    if (launchError) throw launchError;
    if (nginx.exitCode !== null) throw new Error('Nginx exited: ' + readFileSync(path('error.log'), 'utf8'));
    try { const ready = await exchange(); if (ready.status === 401) return; } catch { }
    await new Promise(resolveLater => setTimeout(resolveLater, 30));
  }
  throw new Error('Local proxy did not start');
}
  execFileSync(binary, ['-t', '-p', run + '/', '-c', path('nginx.conf')], { stdio: 'pipe' }); checks.push('full-template-nginx-syntax');
  await startUpstream('api-good'); await startNginx();
  const spoofed = { Authorization: credential, Cookie: 'fixture-cookie=never-forward', 'X-Forwarded-For': '203.0.113.44', 'X-Forwarded-Host': 'spoof.fixture',
    'X-Forwarded-Proto': 'http', 'X-Forwarded-Port': '80', 'X-Forwarded-Client-Cert': 'fixture-certificate-spoof',
    'X-SSL-CERT': 'fixture-certificate-spoof', 'Proxy-Authorization': 'Basic fixture-only', 'X-Fixture-Marker': 'never-forward' };
  const response = await exchange({ headers: spoofed, clientCertificate: true });
  check('native-get-with-validated-client-tls-is-bearer-only', () => assert.equal(response.status, 200));
  const received = upstreamRequests.at(-1);
  check('upstream-exact-path-and-no-body', () => { assert.equal(received.url, '/v1/portal/locations'); assert.equal(received.body, 0); });
  check('upstream-only-host-authorization-and-accept', () => {
    assert.deepEqual(Object.keys(received.headers).sort(), ['accept', 'authorization', 'host']);
    assert.equal(received.headers.host, 'api:' + upstreamPort); assert.equal(received.headers.authorization, credential); assert.equal(received.headers.accept, 'application/json');
  });
  check('no-gateway-client-certificate-on-upstream', () => assert.equal(received.clientCertificate, false));
  check('upstream-real-tls13-ca-and-api-name', () => assert.equal(received.protocol, 'TLSv1.3'));
  check('upstream-no-store-and-contract-survive', () => {
    assert.equal(response.headers['cache-control'], 'no-store'); assert.equal(response.headers['x-privacy-bubble-contract'], 'privacy-bubble.integration.v1');
  });
  const rejected = [
    ['missing-authorization', {}, 401],
    ['cookie-cannot-replace-bearer', { headers: { Cookie: 'fixture-cookie=never-authorize' } }, 401],
    ['query-blocked', { route: '/v1/portal/locations?fixture=1', headers: spoofed }, 400],
    ['get-content-length-body-blocked', { headers: { Authorization: credential, 'Content-Length': '1' }, body: 'x' }, 400],
    ['get-chunked-body-blocked', { headers: { Authorization: credential, 'Transfer-Encoding': 'chunked' }, body: 'x' }, 400],
    ['post-blocked', { method: 'POST', headers: { Authorization: credential, 'Content-Length': '0' } }, 405],
    ['head-blocked', { method: 'HEAD', headers: spoofed }, 405],
    ['adjacent-v1-path-blocked', { route: '/v1/portal/other', headers: spoofed }, 403],
    ['trailing-slash-path-blocked', { route: '/v1/portal/locations/', headers: spoofed }, 403],
  ];
  for (const [name, options, expected] of rejected) {
    const before = upstreamRequests.length; const denied = await exchange(options);
    check(name, () => { assert.equal(denied.status, expected); assert.equal(upstreamRequests.length, before); });
  }
  assert.equal((await exchange({ headers: { Authorization: 'Bearer isolated-wrong-fixture' } })).status, 401);
  checks.push('upstream-rejects-a-different-bearer');
  await stopNginx(); await stopUpstream();
  for (const [name, certificate, requireCertificate] of [
    ['untrusted-upstream-ca-rejected', 'api-untrusted', false],
    ['wrong-upstream-api-name-rejected', 'api-wrong-name', false],
    ['no-lent-gateway-identity-when-upstream-demands-mtls', 'api-good', true],
  ]) {
    await startUpstream(certificate, requireCertificate); await startNginx();
    const before = upstreamRequests.length; const denied = await exchange({ headers: spoofed, clientCertificate: true });
    check(name, () => { assert.equal(denied.status, 502); assert.equal(upstreamRequests.length, before); });
    await stopNginx(); await stopUpstream();
  }
  console.log(JSON.stringify({ passed: true, nginxVersion: '1.30.6', checks, productionEndpointsUsed: false,
    runtimeCredentialsUsed: false, templateSha256: createHash('sha256').update(template).digest('hex'),
    testSourceSha256: createHash('sha256').update(readFileSync(sourcePath)).digest('hex'),
    infrastructureAdaptations: ['workspace paths and fixture MIME include', 'temporary loopback listener/upstream ports', 'local DNS resolver', 'single foreground worker'],
    cleanup: 'owned child processes, sockets and private runtime directory removed in finally' }, null, 2));
} finally {
  try { await stopNginx(); }
  finally {
    try { await stopUpstream(); }
    finally {
      if (dns) { try { dns.close(); } catch { } }
      rmSync(run, { recursive: true, force: true });
    }
  }
}
