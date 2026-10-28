import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createConsentMock } from './server.mjs';

const basePath = '/api/mock-consent/v1';
const valid = {requestId:'synthetic-request-1',revision:1,purpose:'synthetic-location-check',scopes:['test.location.read'],ttlSeconds:120};
const key = n => `synthetic-key-${String(n).padStart(8,'0')}`;
async function fixture(options, run) {
  const server = createConsentMock(options);
  await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
  const url = `http://127.0.0.1:${server.address().port}${basePath}`;
  const get = async path => { const res=await fetch(url+path); return {code:res.status,body:await res.json()}; };
  const post = async (path, body, n = 1, headers = {}) => {
    const res=await fetch(url+path,{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':key(n),'X-Correlation-ID':'synthetic-correlation-1',...headers},body:JSON.stringify(body)});
    return {code:res.status,body:await res.json()};
  };
  try { await run({url,get,post}); } finally { await new Promise(resolve => server.close(resolve)); }
}

test('request, approve and revoke are explicitly synthetic and revisioned', async () => fixture({}, async ({get,post}) => {
  const request=await get('/request'); assert.equal(request.body.mode,'synthetic'); assert.deepEqual(request.body.request.scopes,['test.location.read']);
  assert.equal((await get('/grant')).body.status,'none');
  const approved=await post('/approve',valid); assert.equal(approved.code,200); assert.equal(approved.body.mode,'synthetic'); assert.equal(approved.body.status,'active');
  assert.equal(approved.body.grant.consentRevision,1); assert.equal(approved.body.grant.grantRevision,1); assert.equal(approved.body.correlationId,'synthetic-correlation-1');
  const revoked=await post('/revoke',{requestId:valid.requestId,grantRevision:1},2); assert.equal(revoked.body.status,'revoked'); assert.equal(revoked.body.grant.grantRevision,2);
  assert.equal((await get('/grant')).body.status,'revoked');
}));

test('rejects mismatched purpose, scopes, TTL, revision and unrecognized fields', async () => fixture({}, async ({post,get}) => {
  const invalid=[{...valid,purpose:'other'}, {...valid,scopes:['test.location.read','extra']}, {...valid,ttlSeconds:121}, {...valid,extra:true}];
  for (const [i, body] of invalid.entries()) assert.equal((await post('/approve',body,10+i)).code,400);
  assert.equal((await post('/approve',{...valid,revision:2},30)).body.error,'stale_revision');
  assert.equal((await get('/grant')).body.status,'none');
}));

test('replays identical mutation without duplicating, conflicting key is rejected', async () => fixture({}, async ({post}) => {
  const a=await post('/approve',valid,1); const replay=await post('/approve',{ttlSeconds:120,scopes:['test.location.read'],purpose:valid.purpose,revision:1,requestId:valid.requestId},1);
  assert.deepEqual(replay,a);
  const conflict=await post('/approve',{...valid,ttlSeconds:121},1); assert.equal(conflict.code,409); assert.equal(conflict.body.error,'idempotency_conflict');
  assert.equal((await post('/revoke',{requestId:valid.requestId,grantRevision:1},1)).body.error,'idempotency_conflict');
}));

test('expiry and revocation reject stale or inactive use', async () => {
  let current=Date.parse('2026-01-01T00:00:00Z');
  await fixture({now:()=>current}, async ({get,post}) => {
    const a=await post('/approve',valid,1); current+=120000;
    const expired=await get('/grant'); assert.equal(expired.body.status,'expired'); assert.equal(expired.body.grant.grantRevision,2);
    assert.equal((await post('/revoke',{requestId:valid.requestId,grantRevision:a.body.grant.grantRevision},2)).body.error,'stale_revision');
    assert.equal((await post('/revoke',{requestId:valid.requestId,grantRevision:2},3)).body.error,'grant_not_active');
    const b=await post('/approve',valid,4); assert.equal(b.body.grant.grantRevision,3);
    const revoked=await post('/revoke',{requestId:valid.requestId,grantRevision:3},5); assert.equal(revoked.body.status,'revoked');
    assert.equal((await post('/revoke',{requestId:valid.requestId,grantRevision:4},6)).body.error,'grant_not_active');
  });
});

test('lost mutation response is uncertain, same-key retry returns original receipt', async () => fixture({dropFirstMutationResponse:true}, async ({url,get,post}) => {
  await assert.rejects(fetch(`${url}/approve`,{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':key(1),'X-Correlation-ID':'synthetic-correlation-1'},body:JSON.stringify(valid)}));
  const observed=await get('/grant'); assert.equal(observed.body.status,'active');
  const retry=await post('/approve',valid,1); assert.equal(retry.code,200); assert.deepEqual(retry.body.grant,observed.body.grant);
  assert.equal(retry.body.grant.grantRevision,1);
}));

test('requires bounded identifiers and does not offer a general proxy', async () => fixture({}, async ({url,post}) => {
  assert.equal((await post('/approve',valid,1,{'Idempotency-Key':'short'})).body.error,'invalid_headers');
  assert.equal((await post('/approve',valid,2,{'X-Correlation-ID':'x'})).body.error,'invalid_headers');
  assert.equal((await fetch(`${url}/request?url=https://example.test`)).status,404);
}));

test('concurrent duplicate submissions settle once; different keys cannot both grant', async () => fixture({}, async ({post,get}) => {
  const simultaneous=await Promise.all(Array.from({length:12}, () => post('/approve',valid,50)));
  for (const item of simultaneous) assert.deepEqual(item,simultaneous[0]);
  assert.equal(simultaneous[0].body.grant.grantRevision,1);
  assert.equal((await get('/grant')).body.grant.grantRevision,1);
  const other=await post('/approve',valid,51); assert.equal(other.code,409); assert.equal(other.body.error,'already_active');
}));

test('replayed receipt is historical; GET gives current expiration', async () => {
  let current=Date.parse('2026-01-01T00:00:00Z');
  await fixture({now:()=>current}, async ({get,post}) => {
    const original=await post('/approve',valid,1); current+=121000;
    assert.deepEqual(await post('/approve',valid,1),original);
    const state=await get('/grant'); assert.equal(state.body.status,'expired'); assert.equal(state.body.grant.grantRevision,2);
  });
});
