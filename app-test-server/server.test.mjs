import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from './server.mjs';

function switchResource(overrides = {}) {
  return { rn: 'bubbleState', cnd: 'org.onem2m.common.moduleclass.binarySwitch', state: false, ...overrides };
}
function validResponse(body, options) {
  return new Response(body, { headers: { 'X-M2M-RI': options.headers['X-M2M-RI'], 'X-M2M-RSC': '2000' } });
}

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
    return validResponse(JSON.stringify({ 'pb:binarySwitch': switchResource() }), options);
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
test('selects only a simple test AE name without permitting path injection', async () => {
  for (const name of ['', '../other', 'a/b', 'a?x', 'a#b', '%2f', 'a'.repeat(65), 42]) {
    assert.throws(() => createServer({ testAeName: name }), /Invalid test AE name/);
  }
  let requested;
  const server = createServer({testAeName: 'app-test_01', fetchImpl: async (url, options) => {
    requested = url;
    return validResponse(JSON.stringify({ 'pb:binarySwitch': switchResource() }), options);
  }});
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const response = await fetch(`http://127.0.0.1:${server.address().port}/api/test/bubble-state`);
    assert.equal(response.status, 200);
    assert.equal(requested, 'http://127.0.0.1:7599/Mobius/app-test_01/bubbleState');
  } finally { await new Promise(resolve => server.close(resolve)); }
});
test('reports the bootstrap label separately and never infers observation', async () => {
  for (const [labels, expected] of [
    [['dev-bootstrap', 'unobserved'], true],
    [['observed'], false],
    ['unobserved', false],
    [null, false],
  ]) {
    for (const state of [true, false]) {
      await withServer(async (_, options) => validResponse(JSON.stringify({ 'pb:binarySwitch': switchResource({ lbl: labels, state }) }), options), async base => {
        const response = await fetch(`${base}/api/test/bubble-state`);
        assert.equal(response.status, 200);
        const body = await response.json();
        assert.equal(body.observation, 'unknown');
        assert.equal(body.bootstrapLabelPresent, expected);
        assert.equal(body.state, state);
      });
    }
  }
});
test('only forwards valid resource modification timestamps without timezone conversion', async () => {
  const cases = [
    ['20261028T102434', '20261028T102434'], ['20240229T235959', '20240229T235959'],
    ['20230229T000000', null], ['20261301T000000', null], ['20260101T240000', null],
    ['00000101T000000', null], ['20261028T102434Z', null], ['20261028T102434\n', null],
    ['', null], [null, null], [123, null], [undefined, null]
  ];
  for (const [lt, expected] of cases) {
    await withServer(async (_, options) => validResponse(JSON.stringify({ 'pb:binarySwitch': switchResource({ lt }) }), options), async base => {
      const response = await fetch(`${base}/api/test/bubble-state`);
      assert.equal(response.status, 200);
      assert.equal((await response.json()).resourceLastModifiedRaw, expected);
    });
  }
});
test('rejects state from a resource with missing or mismatched contract identity', async () => {
  for (const change of [{rn: undefined}, {rn: 'another'}, {cnd: undefined}, {cnd: 'other'}, {state: 'false'}]) {
    await withServer(async (_, options) => validResponse(JSON.stringify({ 'pb:binarySwitch': switchResource(change) }), options), async base => {
      const response = await fetch(`${base}/api/test/bubble-state`);
      assert.equal(response.status, 502);
      const body = await response.json();
      assert.equal(body.error, 'invalid_mobius_response');
      assert.equal(body.state, undefined);
    });
  }
});
test('reports malformed and upstream errors without claiming a state', async () => {
  for (const [upstream, error] of [[(_, options) => validResponse('{}', options), 'invalid_mobius_response'], [() => new Response('', { status: 403 }), 'mobius_http_error']]) {
    await withServer(upstream, async base => {
      const response = await fetch(`${base}/api/test/bubble-state`);
      assert.equal(response.status, 502); const body = await response.json(); assert.equal(body.state, undefined); assert.equal(body.error, error);
    });
  }
});

test('rejects wrong or absent oneM2M response identifiers and result codes', async () => {
  for (const scenario of ['missing-id', 'wrong-id', 'missing-code', 'wrong-code']) {
    await withServer(async (_, options) => {
      const headers = { 'X-M2M-RI': options.headers['X-M2M-RI'], 'X-M2M-RSC': '2000' };
      if (scenario === 'missing-id') delete headers['X-M2M-RI'];
      if (scenario === 'wrong-id') headers['X-M2M-RI'] = 'other-request';
      if (scenario === 'missing-code') delete headers['X-M2M-RSC'];
      if (scenario === 'wrong-code') headers['X-M2M-RSC'] = '4000';
      return new Response(JSON.stringify({ 'pb:binarySwitch': switchResource({ state: true }) }), { headers });
    }, async base => {
      const response = await fetch(`${base}/api/test/bubble-state`);
      assert.equal(response.status, 502);
      const body = await response.json(); assert.equal(body.error, 'invalid_mobius_headers'); assert.equal(body.state, undefined);
    });
  }
});

test('does not treat other 2xx HTTP results as a successful RETRIEVE', async () => {
  await withServer(async (_, options) => new Response('{}', { status: 201, headers: { 'X-M2M-RI': options.headers['X-M2M-RI'], 'X-M2M-RSC': '2000' } }), async base => {
    const response = await fetch(`${base}/api/test/bubble-state`);
    assert.equal(response.status, 502); assert.equal((await response.json()).error, 'mobius_http_error');
  });
});
test('does not expose a general proxy or write endpoint', async () => {
  await withServer(async () => { throw new Error('unexpected'); }, async base => {
    assert.equal((await fetch(`${base}/api/test/bubble-state`, { method: 'POST' })).status, 404);
    assert.equal((await fetch(`${base}/api/test/bubble-state?url=example`)).status, 404);
    assert.equal((await fetch(`${base}/api/test/observation`)).status, 404);
    assert.equal((await fetch(`${base}/api/test/receipt`)).status, 404);
  });
});
test('synthetic receipt requires an isolated AE and preserves distinct outcomes', async () => {
  assert.throws(() => createServer({enableSyntheticReceipt:true}), /isolated test AE/);
  let mode = 'valid', status = 'INDETERMINATE';
  const expected = {version:1,gatewayId:'app-isolated',deviceId:'synthetic-keyring',commandId:'synthetic-command-1',
    payloadHash:'0'.repeat(64),status,ackCode:'INVALID_RECEIPT',grantRevision:1,ownershipEpoch:1,
    timestampSource:'unavailable',acceptedAt:null,appliedAt:null,receivedAt:'2026-10-28T12:00:00Z',readback:null};
  const server = createServer({testAeName:'app-bridge-0123456789abcdef',enableSyntheticReceipt:true,fetchImpl:async (url,options) => {
    assert.equal(url,'http://127.0.0.1:7599/Mobius/app-bridge-0123456789abcdef/receipts/la');
    if (mode === 'missing') return new Response('',{status:404});
    const headers = {'X-M2M-RI':mode === 'wrong-ri' ? 'wrong' : options.headers['X-M2M-RI'],'X-M2M-RSC':mode === 'wrong-rsc' ? '4000' : '2000'};
    const receipt = {...expected,status,...(mode === 'wrong-device' ? {deviceId:'real-device'} : {}),
      ...(mode === 'bad-status' ? {status:'UNKNOWN'} : {}), ...(mode === 'bad-time' ? {receivedAt:'2026-02-30T00:00:00Z'} : {}),
      ...(mode === 'bad-source' ? {acceptedAt:'2026-10-28T12:00:00Z'} : {}), ...(mode === 'bad-readback' ? {readback:{state:'true'}} : {}),
      ...(mode === 'estimated' ? {timestampSource:'gateway-estimated',acceptedAt:'2026-10-28T11:59:59Z',appliedAt:'2026-10-28T12:00:00Z',readback:{state:true}} : {})};
    return new Response(JSON.stringify({'m2m:cin':{rn:mode === 'wrong-rn' ? 'other' : 'synthetic-receipt',con:receipt}}),{headers});
  }});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const base=`http://127.0.0.1:${server.address().port}`;
  try {
    for (status of ['APPLIED','REJECTED','TIMEOUT','INDETERMINATE']) {
      const response=await fetch(`${base}/api/test/receipt`); assert.equal(response.status,200);
      const body=await response.json(); assert.equal(body.mode,'synthetic'); assert.equal(body.status,status); assert.equal(body.readback,null);
    }
    mode='estimated'; { const response=await fetch(`${base}/api/test/receipt`); assert.equal(response.status,200);
      const body=await response.json(); assert.equal(body.timestampSource,'gateway-estimated'); assert.equal(body.readback.state,true); }
    for (mode of ['missing','wrong-ri','wrong-rsc','wrong-device','wrong-rn','bad-status','bad-time','bad-source','bad-readback']) {
      const response=await fetch(`${base}/api/test/receipt`); assert.equal(response.status,502); const body=await response.json(); assert.equal(body.status,undefined);
    }
    assert.equal((await fetch(`${base}/api/test/receipt?x=1`)).status,404);
    assert.equal((await fetch(`${base}/api/test/receipt`,{method:'POST'})).status,404);
  } finally { await new Promise(resolve=>server.close(resolve)); }
});
test('synthetic observation is isolated, read-only and fails closed', async () => {
  assert.throws(() => createServer({enableSyntheticObservation:true}), /isolated test AE/);
  let mode = 'valid';
  const expected = {version:1,gatewayId:'app-isolated',deviceId:'synthetic-keyring',bootId:'synthetic-boot',sequence:1,
    observedAt:'2026-01-01T00:00:00Z',receivedAt:'2026-01-01T00:00:00Z',timestampSource:'gateway-received',state:true,battery:null,rssi:null};
  const server = createServer({testAeName:'app-bridge-0123456789abcdef',enableSyntheticObservation:true,fetchImpl:async (url,options) => {
    assert.equal(url,'http://127.0.0.1:7599/Mobius/app-bridge-0123456789abcdef/observations/la');
    if (mode === 'missing') return new Response('',{status:404});
    const headers = {'X-M2M-RI':mode === 'wrong-ri' ? 'wrong' : options.headers['X-M2M-RI'],'X-M2M-RSC':mode === 'wrong-rsc' ? '4000' : '2000'};
    const observation = {...expected,...(mode === 'wrong-device' ? {deviceId:'real-device'} : {}),...(mode === 'bad-rssi' ? {rssi:200} : {}),...(mode === 'bad-time' ? {observedAt:'2026-02-30T00:00:00Z'} : {})};
    return new Response(JSON.stringify({'m2m:cin':{rn:mode === 'wrong-rn' ? 'other' : 'gw-'+'a'.repeat(64),con:observation}}),{headers});
  }});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const base=`http://127.0.0.1:${server.address().port}`;
  try {
    let response=await fetch(`${base}/api/test/observation`); assert.equal(response.status,200);
    let body=await response.json(); assert.equal(body.mode,'synthetic'); assert.equal(body.battery,null); assert.equal(body.rssi,null);
    for (mode of ['missing','wrong-ri','wrong-rsc','wrong-device','wrong-rn','bad-rssi','bad-time']) { response=await fetch(`${base}/api/test/observation`); assert.equal(response.status,502); body=await response.json(); assert.equal(body.state,undefined); }
    assert.equal((await fetch(`${base}/api/test/observation?x=1`)).status,404);
    assert.equal((await fetch(`${base}/api/test/observation`,{method:'POST'})).status,404);
  } finally { await new Promise(resolve=>server.close(resolve)); }
});
test('reports an unavailable upstream', async () => {
  await withServer(async () => { throw new Error('offline'); }, async base => {
    const response = await fetch(`${base}/api/test/bubble-state`);
    assert.equal(response.status, 502);
    assert.equal((await response.json()).error, 'mobius_unavailable');
  });
});

test('bounds HTTP upstream responses, including chunked bodies', async () => {
  const oversized = JSON.stringify({ 'pb:binarySwitch': switchResource({ state: true }), padding: 'x'.repeat(65536) });
  await withServer(async (_, options) => validResponse(oversized, options), async base => {
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
        const id = req.headers['x-m2m-origin'] === 'wrong-id' ? 'other' : req.headers['x-m2m-ri'];
        const code = req.headers['x-m2m-origin'] === 'wrong-code' ? '4000' : '2000';
        res.writeHead(200, { 'Content-Type': 'application/json', 'X-M2M-RI': id, 'X-M2M-RSC': code });
        if (req.headers['x-m2m-origin'] === 'oversized') res.end('x'.repeat(65537));
        else if (req.headers['x-m2m-origin'] === 'wrong-cnd') res.end(JSON.stringify({ 'pb:binarySwitch': switchResource({ cnd: 'other' }) }));
        else if (req.headers['x-m2m-origin'] === 'wrong-rn') res.end(JSON.stringify({ 'pb:binarySwitch': switchResource({ rn: 'other' }) }));
        else res.end(JSON.stringify({ 'pb:binarySwitch': switchResource({ state: true }) }));
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
    for (const originator of ['wrong-id', 'wrong-code']) {
      const invalidHeaders = await query({ tls: { ca, cert, key }, originator });
      assert.equal(invalidHeaders.status, 502); assert.equal(invalidHeaders.body.error, 'invalid_mobius_headers'); assert.equal(invalidHeaders.body.state, undefined);
    }
    for (const originator of ['wrong-cnd', 'wrong-rn']) {
      const invalidIdentity = await query({ tls: { ca, cert, key }, originator });
      assert.equal(invalidIdentity.status, 502); assert.equal(invalidIdentity.body.error, 'invalid_mobius_response'); assert.equal(invalidIdentity.body.state, undefined);
    }
    const large = await query({ tls: { ca, cert, key }, originator: 'oversized' });
    assert.equal(large.status, 502); assert.equal(large.body.state, undefined);
  } finally {
    if (upstream?.listening) await new Promise(resolve => upstream.close(resolve));
    await rm(dir, { recursive: true, force: true });
  }
});
