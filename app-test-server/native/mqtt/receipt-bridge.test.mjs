import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createVerifiedReceipt, SYNTHETIC_RECEIPT_RN, SYNTHETIC_RECEIPT_RQI } from './receipt-bridge.mjs';

function fixture(mode) {
  const aeId = 'Ctest', aeName = 'app-bridge-0123456789abcdef';
  const receipt = {version:1,gatewayId:'app-isolated',deviceId:'synthetic-keyring',commandId:'synthetic-command-1',
    payloadHash:'0'.repeat(64),status:'INDETERMINATE',ackCode:'INVALID_RECEIPT',acceptedAt:null,appliedAt:null,
    receivedAt:'2026-10-29T01:02:03.004Z',readback:null,firmwareRevision:null,ownershipEpoch:1,grantRevision:1,
    timestampSource:'unavailable',detail:'Synthetic test; no device command was sent',bootId:null};
  const cin = {rn:SYNTHETIC_RECEIPT_RN,cnf:'application/json:0',con:receipt};
  const request = {op:1,ty:4,to:`/Mobius/${aeName}/receipts`,fr:aeId,rqi:SYNTHETIC_RECEIPT_RQI,rvi:'4',pc:{'m2m:cin':cin}};
  let calls = 0;
  const fetchImpl = async (url, options) => {
    calls++;
    assert.equal(url, calls === 1 ? `http://127.0.0.1/Mobius/${aeName}/receipts` : `http://127.0.0.1/Mobius/${aeName}/receipts/${cin.rn}`);
    const id = options.headers['X-M2M-RI'];
    if (calls === 1) return new Response('{}',{status:201,headers:{'X-M2M-RI':mode === 'create-id' ? 'other' : id,'X-M2M-RSC':mode === 'create-rsc' ? '4000' : '2001'}});
    return new Response(JSON.stringify({'m2m:cin':{rn:cin.rn,con:mode === 'content' ? {...receipt,status:'APPLIED'} : receipt}}),
      {status:200,headers:{'X-M2M-RI':mode === 'read-id' ? 'other' : id,'X-M2M-RSC':mode === 'read-rsc' ? '4000' : '2000'}});
  };
  return {aeId,aeName,request,fetchImpl,calls:() => calls};
}
test('only acknowledges a fixed synthetic receipt after correlated create and equal readback',async () => {
  const f = fixture();
  const result = await createVerifiedReceipt({...f,origin:'http://127.0.0.1',wire:JSON.stringify(f.request)});
  assert.equal(f.calls(),2); assert.equal(result.reply.rqi,SYNTHETIC_RECEIPT_RQI); assert.equal(result.reply.rsc,2001);
  assert.equal(result.evidence.contentMatched,true);
});
for (const mode of ['create-id','create-rsc','read-id','read-rsc','content']) test(`no receipt acknowledgement for ${mode}`,async () => {
  const f = fixture(mode);
  await assert.rejects(createVerifiedReceipt({...f,origin:'http://127.0.0.1',wire:JSON.stringify(f.request)}));
});
test('rejects another target, request identity or non-synthetic result before contacting CSE',async () => {
  for (const mutate of [r=>{r.to='/Mobius/other/receipts';},r=>{r.rqi='other';},r=>{r.pc['m2m:cin'].con.commandId='real-command';},
    r=>{r.pc['m2m:cin'].con.status='APPLIED';},r=>{r.pc['m2m:cin'].con.receivedAt='2026-02-30T01:02:03.004Z';},
    r=>{r.pc['m2m:cin'].acpi=['other'];},r=>{r.extra='unexpected';}]) {
    const f=fixture(); mutate(f.request);
    await assert.rejects(createVerifiedReceipt({...f,origin:'http://127.0.0.1',wire:JSON.stringify(f.request)}));
    assert.equal(f.calls(),0);
  }
});
