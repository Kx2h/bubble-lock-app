import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, rm } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import https from 'node:https';

const app = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const javaHome = process.env.JAVA_HOME;
if (!javaHome) throw new Error('Set JAVA_HOME to workspace JDK 17');
function run(cmd, args, options = {}) {
  return new Promise((resolveRun, reject) => {
    const child = spawn(cmd, args, { ...options, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '', stderr = '';
    child.stdout.on('data', data => { stdout += data; });
    child.stderr.on('data', data => { stderr += data; });
    child.on('error', reject);
    child.on('close', code => code === 0 ? resolveRun(stdout) : reject(new Error(`${cmd} failed (${code}): ${stderr}`)));
  });
}

test('HTTPS verifies trust, hostname, TLS version, bounded responses and does not follow redirects', async () => {
  await mkdir(resolve(app, 'build'), {recursive:true});
  const dir = await mkdtemp(resolve(app, 'build/api-transport-'));
  const certPath = resolve(dir, 'server.pem'), keyPath = resolve(dir, 'server.key'), trust = resolve(dir, 'trust.p12');
  let server, oldServer;
  try {
    await run('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', keyPath, '-out', certPath, '-days', '1', '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost']);
    await run(resolve(javaHome, 'bin/keytool'), ['-importcert', '-noprompt', '-alias', 'test', '-file', certPath, '-keystore', trust, '-storetype', 'PKCS12', '-storepass', 'test-only']);
    await run(resolve(javaHome, 'bin/javac'), ['-d', dir, resolve(app, 'app/src/main/java/org/privacybubble/app/net/Tls13SocketFactory.java'), resolve(app, 'app/src/main/java/org/privacybubble/app/api/HttpsApiTransport.java'), resolve(app, 'tests/api-transport/HttpsApiTransportTest.java')]);
    const cert = await readFile(certPath), key = await readFile(keyPath);
    let writes = 0, redirected = 0;
    const listener = (req, res) => {
      if (req.url === '/redirect') { res.writeHead(302, {location:'/should-not-follow'}); return res.end(); }
      if (req.url === '/should-not-follow') { redirected++; return res.end('bad'); }
      if (req.url === '/denied') { res.writeHead(403); return res.end('denied'); }
      if (req.url === '/duplicate') { res.writeHead(200, ['X-Duplicate', 'one', 'x-duplicate', 'two']); return res.end('ok'); }
      if (req.url === '/oversize') return res.end(Buffer.alloc(65537));
      if (req.url === '/slow') {
        res.writeHead(200);
        const timer = setInterval(() => res.write('x'), 250);
        res.on('close', () => clearInterval(timer));
        return;
      }
      if (req.url === '/write?choice=test') {
        writes++;
        const chunks = [];
        req.on('data', chunk => chunks.push(chunk));
        return req.on('end', () => {
          assert.equal(req.method, 'POST'); assert.equal(req.headers.authorization, 'Bearer synthetic-test');
          assert.equal(req.headers['x-test-request'], 'fixture'); assert.equal(Buffer.concat(chunks).toString(), 'example');
          res.writeHead(201, {'X-Fixture':'fixture-response'}); res.end('stored');
        });
      }
      res.end('ok');
    };
    server = https.createServer({cert,key,minVersion:'TLSv1.3'}, listener);
    oldServer = https.createServer({cert,key,minVersion:'TLSv1.2',maxVersion:'TLSv1.2'}, listener);
    for (const s of [server, oldServer]) await new Promise((ok, fail) => s.once('error', fail).listen(0, '127.0.0.1', ok));
    const java = resolve(javaHome, 'bin/java');
    const trustedArgs = [`-Djavax.net.ssl.trustStore=${trust}`, '-Djavax.net.ssl.trustStoreType=PKCS12', '-Djavax.net.ssl.trustStorePassword=test-only', '-cp', dir, 'HttpsApiTransportTest'];
    const url = `https://localhost:${server.address().port}`;
    assert.match(await run(java, [...trustedArgs, 'trusted', url]), /HTTPS transport tests passed/);
    assert.equal(writes, 1); assert.equal(redirected, 0);
    assert.match(await run(java, ['-cp', dir, 'HttpsApiTransportTest', 'untrusted', url]), /TLS rejected/);
    assert.match(await run(java, [...trustedArgs, 'wronghost', `https://127.0.0.1:${server.address().port}`]), /TLS rejected/);
    assert.match(await run(java, [...trustedArgs, 'tls12', `https://localhost:${oldServer.address().port}`]), /TLS rejected/);
  } finally {
    for (const s of [server, oldServer]) if (s?.listening) await new Promise(ok => s.close(ok));
    await rm(dir, {recursive:true,force:true});
  }
});
