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
    assert.equal(first.state, false); assert.equal(first.mode, 'test');
    assert.notEqual(first.requestId, second.requestId);
    assert.equal(calls[0].url, 'http://127.0.0.1:7599/Mobius/privacy-bubble-test-ae/bubbleState');
    assert.equal(calls[0].options.headers['X-M2M-RVI'], '4');
    assert.equal(calls[0].options.headers['X-M2M-RI'], first.requestId);
  });
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
