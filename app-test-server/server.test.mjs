import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from './server.mjs';

async function withServer(fetchImpl, run) {
  const server = createServer({ fetchImpl });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try { await run(`http://127.0.0.1:${server.address().port}`); }
  finally { await new Promise(resolve => server.close(resolve)); }
}

test('maps CORE contract and creates independent request identifiers', async () => {
  const calls = [];
  await withServer(async (url, options) => {
    calls.push({ url, options });
    return new Response(JSON.stringify({ 'pb:binarySwitch': { state: false } }));
  }, async base => {
    const first = await (await fetch(`${base}/api/test/bubble-state`)).json();
    const second = await (await fetch(`${base}/api/test/bubble-state`)).json();
    assert.equal(first.state, false); assert.equal(first.mode, 'test'); assert.equal(first.observation, 'unknown');
    assert.notEqual(first.requestId, second.requestId);
    assert.equal(calls[0].url, 'http://127.0.0.1:7599/Mobius/privacy-bubble-test-ae/bubbleState');
    assert.equal(calls[0].options.headers['X-M2M-RVI'], '4');
    assert.equal(calls[0].options.headers['X-M2M-RI'], first.requestId);
  });
});
test('preserves bootstrap unobserved label and never infers observation', async () => {
  for (const [labels, expected] of [
    [['dev-bootstrap', 'unobserved'], 'unobserved'],
    [['observed'], 'unknown'],
    ['unobserved', 'unknown'],
    [null, 'unknown'],
  ]) {
    await withServer(async () => new Response(JSON.stringify({ 'pb:binarySwitch': { state: false, lbl: labels } })), async base => {
      const response = await fetch(`${base}/api/test/bubble-state`);
      assert.equal(response.status, 200);
      assert.equal((await response.json()).observation, expected);
    });
  }
});
test('reports malformed and upstream errors without claiming a state', async () => {
  for (const upstream of [new Response('{}'), new Response('', { status: 403 })]) {
    await withServer(async () => upstream, async base => {
      const response = await fetch(`${base}/api/test/bubble-state`);
      assert.equal(response.status, 502); assert.equal((await response.json()).state, undefined);
    });
  }
});
test('does not expose a general proxy or write endpoint', async () => {
  await withServer(async () => { throw new Error('unexpected'); }, async base => {
    assert.equal((await fetch(`${base}/api/test/bubble-state`, { method: 'POST' })).status, 404);
    assert.equal((await fetch(`${base}/api/test/bubble-state?url=example`)).status, 404);
  });
});
test('reports an unavailable upstream', async () => {
  await withServer(async () => { throw new Error('offline'); }, async base => {
    const response = await fetch(`${base}/api/test/bubble-state`);
    assert.equal(response.status, 502);
    assert.equal((await response.json()).error, 'mobius_unavailable');
  });
});

test('bounds HTTP upstream responses, including chunked bodies', async () => {
  const oversized = JSON.stringify({ 'pb:binarySwitch': { state: true }, padding: 'x'.repeat(65536) });
  await withServer(async () => new Response(oversized), async base => {
    const response = await fetch(`${base}/api/test/bubble-state`);
    assert.equal(response.status, 502);
    assert.equal((await response.json()).state, undefined);
  });
});

test('HTTPS requires all client credentials and forbids them with HTTP', () => {
  assert.throws(() => createServer({ mobiusUrl: 'https://localhost/Mobius' }), /requires CA/);
  assert.throws(() => createServer({ tls: { ca: 'x', cert: 'x', key: 'x' } }), /require HTTPS/);
});

test('mTLS verifies the server and presents client credentials; oversized responses fail', async () => {
  const { mkdtemp, readFile, writeFile, rm } = await import('node:fs/promises');
  const { execFileSync } = await import('node:child_process');
  const { dirname, join } = await import('node:path');
  const { fileURLToPath } = await import('node:url');
  const https = await import('node:https');
  const dir = await mkdtemp(join(dirname(fileURLToPath(import.meta.url)), '.tls-test-'));
  const openssl = (...args) => execFileSync('openssl', args, { cwd: dir, stdio: 'ignore' });
  let upstream;
  try {
    openssl('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-keyout', 'ca.key', '-out', 'ca.pem', '-subj', '/CN=Test CA', '-addext', 'basicConstraints=critical,CA:TRUE');
    for (const name of ['server', 'client']) {
      openssl('req', '-new', '-newkey', 'rsa:2048', '-nodes', '-keyout', `${name}.key`, '-out', `${name}.csr`, '-subj', `/CN=${name}`);
      await writeFile(join(dir, `${name}.ext`), name === 'server' ? 'subjectAltName=IP:127.0.0.1\nextendedKeyUsage=serverAuth\n' : 'extendedKeyUsage=clientAuth\n');
      openssl('x509', '-req', '-in', `${name}.csr`, '-CA', 'ca.pem', '-CAkey', 'ca.key', '-CAcreateserial', '-days', '1', '-out', `${name}.pem`, '-extfile', `${name}.ext`);
    }
    const load = name => readFile(join(dir, name));
    const [ca, cert, key, serverCert, serverKey] = await Promise.all(['ca.pem', 'client.pem', 'client.key', 'server.pem', 'server.key'].map(load));
    let receivedHeaders;
    upstream = https.createServer({ ca, cert: serverCert, key: serverKey, requestCert: true, rejectUnauthorized: true, minVersion: 'TLSv1.3' }, (req, res) => {
      receivedHeaders = req.headers;
      if (req.url.includes('bubbleState')) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        if (req.headers['x-m2m-origin'] === 'oversized') res.end('x'.repeat(65537));
        else res.end(JSON.stringify({ 'pb:binarySwitch': { state: true } }));
      } else res.writeHead(404).end();
    });
    await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve));
    const mobiusUrl = `https://127.0.0.1:${upstream.address().port}/Mobius`;
    async function query(options) {
      const adapter = createServer({ mobiusUrl, ...options });
      await new Promise(resolve => adapter.listen(0, '127.0.0.1', resolve));
      try {
        const response = await fetch(`http://127.0.0.1:${adapter.address().port}/api/test/bubble-state`);
        return { status: response.status, body: await response.json() };
      } finally { await new Promise(resolve => adapter.close(resolve)); }
    }
    const good = await query({ tls: { ca, cert, key }, originator: 'test' });
    assert.equal(good.status, 200); assert.equal(good.body.state, true);
    assert.equal(receivedHeaders['x-m2m-rvi'], '4');
    assert.equal(receivedHeaders['x-m2m-ri'], good.body.requestId);
    assert.equal(receivedHeaders['x-m2m-origin'], 'test');
    const untrusted = await query({ tls: { ca: serverCert, cert, key } });
    assert.equal(untrusted.status, 502); assert.equal(untrusted.body.error, 'mobius_unavailable');
    const invalidClient = await query({ tls: { ca, cert: serverCert, key: serverKey } });
    assert.equal(invalidClient.status, 502); assert.equal(invalidClient.body.error, 'mobius_unavailable');
    const large = await query({ tls: { ca, cert, key }, originator: 'oversized' });
    assert.equal(large.status, 502); assert.equal(large.body.state, undefined);
  } finally {
    if (upstream?.listening) await new Promise(resolve => upstream.close(resolve));
    await rm(dir, { recursive: true, force: true });
  }
});
