import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createVerifiedObservation } from './observation-bridge.mjs';

function fixture(mode) {
  const rqi = 'kr-test-123', aeId = 'Ctest', aeName = 'app-test';
  const cin = {rn:`gw-${createHash('sha256').update(rqi).digest('hex')}`, cnf:'application/json:0', con:{state:true, sequence:7}};
  const request = {op:1,ty:4,to:`/Mobius/${aeName}/observations`,fr:aeId,rqi,rvi:'4',pc:{'m2m:cin':cin}};
  let calls = 0;
  const fetchImpl = async (url, opts) => {
    calls++;
    const id = opts.headers['X-M2M-RI'];
    if (calls === 1) return new Response('{}', {status:201,headers:{'X-M2M-RI':mode === 'create-id' ? 'other' : id, 'X-M2M-RSC':mode === 'create-rsc' ? '4000' : '2001'}});
    const doc = {'m2m:cin':{rn:cin.rn,con:mode === 'content' ? {state:false} : cin.con}};
    return new Response(JSON.stringify(doc), {status:200,headers:{'X-M2M-RI':mode === 'read-id' ? 'other' : id, 'X-M2M-RSC':mode === 'read-rsc' ? '4000' : '2000'}});
  };
  return {aeId,aeName,request,fetchImpl,calls:() => calls};
}
test('only reports oneM2M success after correlated create and equal readback', async () => {
  const f = fixture();
  const result = await createVerifiedObservation({...f, origin:'http://127.0.0.1', wire:JSON.stringify(f.request)});
  assert.deepEqual(result.reply,{rqi:f.request.rqi,rsc:2001,fr:'Mobius4',to:f.aeId});
  assert.deepEqual(result.evidence,{createHttp:201,createRsc:2001,createRiMatched:true,readHttp:200,readRsc:2000,readRiMatched:true,contentMatched:true});
  assert.equal(f.calls(),2);
});
for (const mode of ['create-id','create-rsc','read-id','read-rsc','content']) {
  test(`does not acknowledge an unverified ${mode}`, async () => {
    const f = fixture(mode);
    await assert.rejects(createVerifiedObservation({...f, origin:'http://127.0.0.1', wire:JSON.stringify(f.request)}));
    assert.equal(f.calls(),mode.startsWith('create') ? 1 : 2);
  });
}
test('rejects request for a different resource before contacting CSE', async () => {
  const f = fixture(); f.request.to = '/Mobius/another/observations';
  await assert.rejects(createVerifiedObservation({...f, origin:'http://127.0.0.1', wire:JSON.stringify(f.request)}));
  assert.equal(f.calls(),0);
});
