import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createReceiptMonitorFixture } from './receipt-monitor.mjs';

test('receipt monitor fixture repeats content, changes it and recovers from failure', async () => {
  let mode = 'baseline';
  const counts = [];
  const server = createReceiptMonitorFixture({ readMode: () => mode, recordCount: item => counts.push(item) });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}/api/test/receipt`;
  try {
    const first = await (await fetch(url)).json();
    const same = await (await fetch(url)).json();
    assert.notEqual(first.requestId, same.requestId);
    delete first.requestId; delete same.requestId;
    assert.deepEqual(same, first);
    assert.equal(first.status, 'INDETERMINATE');
    mode = 'changed';
    const changed = await (await fetch(url)).json();
    assert.equal(changed.status, 'REJECTED');
    assert.equal(changed.ackCode, 'SYNTHETIC_REJECTED');
    assert.equal(changed.readback, null);
    mode = 'failure'; assert.equal((await fetch(url)).status, 503);
    mode = 'changed'; assert.equal((await fetch(url)).status, 200);
    mode = 'unsupported'; assert.equal((await fetch(url)).status, 503);
    assert.equal((await fetch(url, { method: 'POST' })).status, 404);
    assert.equal((await fetch(`${url}?mode=changed`)).status, 404);
    assert.deepEqual(counts.map(item => item.count), [1, 2, 3, 4, 5, 6]);
  } finally { await new Promise(resolve => server.close(resolve)); }
});
