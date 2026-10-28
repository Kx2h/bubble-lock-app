import {test} from 'node:test';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdir, mkdtemp, readFile, rm} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import https from 'node:https';
import {createMockIdp} from '../oidc-mock/server.mjs';
import {createAuthenticatedConsent} from './server.mjs';

const app=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
const secret='synthetic-introspection-secret-00000000001';
async function fixture(run, testAccessClaims, {dropFirstMutationResponse=false, badIssuerCa=false, resourceSecret=secret}={}) {
  await mkdir(resolve(app,'build'),{recursive:true});
  const temp=await mkdtemp(resolve(app,'build/auth-consent-'));
  let idp, consent;
  try {
    execFileSync('openssl',['req','-x509','-newkey','rsa:2048','-nodes','-days','1','-keyout','s.key','-out','s.pem','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost'],{cwd:temp,stdio:'ignore'});
    const [cert,key]=await Promise.all(['s.pem','s.key'].map(x=>readFile(resolve(temp,x))));
    let issuerCa=cert;
    if (badIssuerCa) {
      execFileSync('openssl',['req','-x509','-newkey','rsa:2048','-nodes','-days','1','-keyout','other.key','-out','other.pem','-subj','/CN=other'],{cwd:temp,stdio:'ignore'});
      issuerCa=await readFile(resolve(temp,'other.pem'));
    }
    const clock=Math.floor(Date.now()/1000);
    // Listen before creating the mock to pick a free port without replacing live services.
    const net=await import('node:net'); const reservation=net.createServer();
    await new Promise(r=>reservation.listen(0,'127.0.0.1',r)); const idpPort=reservation.address().port;
    await new Promise(r=>reservation.close(r));
    const issuer=`https://localhost:${idpPort}`;
    idp=createMockIdp({issuer,cert,key,introspectionSecret:secret,now:()=>clock,testAccessClaims});
    await new Promise((r,j)=>idp.once('error',j).listen(idpPort,'127.0.0.1',r));
    consent=createAuthenticatedConsent({issuer,cert,key,ca:issuerCa,introspectionSecret:resourceSecret,dropFirstMutationResponse,now:()=>clock});
    await new Promise((r,j)=>consent.once('error',j).listen(0,'127.0.0.1',r));
    const call=(port,path,{method='GET',body,headers={},trust=true}={})=>new Promise((done,reject)=>{
      const req=https.request({hostname:'127.0.0.1',servername:'localhost',port,path,method,...(trust?{ca:cert}:{}),headers},res=>{
        const chunks=[];res.on('data',x=>chunks.push(x));res.on('end',()=>done({status:res.statusCode,body:Buffer.concat(chunks).toString()}));
      });req.on('error',reject);req.end(body);
    });
    const form=(path,values)=>call(idpPort,path,{method:'POST',body:new URLSearchParams(values).toString(),headers:{'Content-Type':'application/x-www-form-urlencoded'}});
    const verifier='v'.repeat(64),challenge=createHash('sha256').update(verifier).digest('base64url');
    const postCode=()=>new Promise((done,reject)=>{
      const body=new URLSearchParams({response_type:'code',client_id:'privacy-bubble-android-test',redirect_uri:'org.privacybubble.app:/oauth2redirect',scope:'openid offline_access test.consent.read test.consent.write',prompt:'consent',state:'synthetic-state-12345',nonce:'synthetic-nonce-12345',code_challenge:challenge,code_challenge_method:'S256'}).toString();
      const req=https.request({hostname:'127.0.0.1',servername:'localhost',port:idpPort,path:'/authorize',method:'POST',ca:cert,headers:{'Content-Type':'application/x-www-form-urlencoded'}},res=>{res.resume();res.on('end',()=>done({status:res.statusCode,location:res.headers.location}));});req.on('error',reject);req.end(body);
    });
    const callback=await postCode(); assert.equal(callback.status,303);
    const code=new URL(callback.location).searchParams.get('code');
    const tokenResult=await form('/token',{grant_type:'authorization_code',client_id:'privacy-bubble-android-test',redirect_uri:'org.privacybubble.app:/oauth2redirect',code,code_verifier:verifier});
    assert.equal(tokenResult.status,200);const tokens=JSON.parse(tokenResult.body);const token=tokens.access_token;
    const callConsent=(path,options={})=>call(consent.address().port,`/api/mock-consent/v1${path}`,options);
    await run({token,tokens,issuer,callConsent,form,closeIdp:()=>new Promise(r=>idp.close(r))});
  } finally {
    if (consent?.listening) await new Promise(r=>consent.close(r));
    if (idp?.listening) await new Promise(r=>idp.close(r));
    await rm(temp,{recursive:true,force:true});
  }
}
const bearer=token=>({Authorization:`Bearer ${token}`});
const valid={requestId:'synthetic-request-1',revision:1,purpose:'synthetic-location-check',scopes:['test.location.read'],ttlSeconds:120};

test('real synthetic PKCE bearer protects read, approve, and revocation', async()=>fixture(async ({token,callConsent,form})=>{
  assert.equal((await callConsent('/request')).status,401);
  await assert.rejects(callConsent('/request',{headers:bearer(token),trust:false}));
  const request=await callConsent('/request',{headers:bearer(token)}); assert.equal(request.status,200);
  const instance=JSON.parse(request.body).serverInstanceId;
  const post=()=>callConsent('/approve',{method:'POST',headers:{...bearer(token),'Content-Type':'application/json','Idempotency-Key':'synthetic-key-00000001','X-Correlation-ID':'synthetic-correlation-1','X-Mock-Instance-ID':instance},body:JSON.stringify(valid)});
  const result=await post(); assert.equal(result.status,200); assert.equal(JSON.parse(result.body).status,'active');
  const revoked=await form('/revoke',{client_id:'privacy-bubble-android-test',token}); assert.equal(revoked.status,200);
  assert.equal((await callConsent('/grant',{headers:bearer(token)})).status,401);
  assert.equal((await post()).status,401);
}));



test('lost authorized response replays exactly once with the same key', async()=>fixture(async ({token,callConsent})=>{
  const request=await callConsent('/request',{headers:bearer(token)}); const instance=JSON.parse(request.body).serverInstanceId;
  const write=()=>callConsent('/approve',{method:'POST',headers:{...bearer(token),'Content-Type':'application/json','Idempotency-Key':'synthetic-key-00000001','X-Correlation-ID':'synthetic-correlation-1','X-Mock-Instance-ID':instance},body:JSON.stringify(valid)});
  await assert.rejects(write());
  const observed=JSON.parse((await callConsent('/grant',{headers:bearer(token)})).body);
  assert.equal(observed.status,'active');
  const replay=await write(); assert.equal(replay.status,200);
  assert.deepEqual(JSON.parse(replay.body).grant,observed.grant);
},undefined,{dropFirstMutationResponse:true}));
test('refresh replay revokes the access-token family', async()=>fixture(async ({token,tokens,callConsent,form})=>{
  const rotated=await form('/token',{grant_type:'refresh_token',client_id:'privacy-bubble-android-test',refresh_token:tokens.refresh_token});
  assert.equal(rotated.status,200); const next=JSON.parse(rotated.body);
  assert.equal((await callConsent('/grant',{headers:bearer(token)})).status,200);
  assert.equal((await callConsent('/grant',{headers:bearer(next.access_token)})).status,200);
  const replay=await form('/token',{grant_type:'refresh_token',client_id:'privacy-bubble-android-test',refresh_token:tokens.refresh_token});
  assert.equal(replay.status,400);
  assert.equal((await callConsent('/grant',{headers:bearer(token)})).status,401);
  assert.equal((await callConsent('/grant',{headers:bearer(next.access_token)})).status,401);
}));

test('refuses an issuer signed by an untrusted CA', async()=>fixture(async ({token,callConsent})=>{
  assert.equal((await callConsent('/grant',{headers:bearer(token)})).status,503);
},undefined,{badIssuerCa:true}));

test('refuses when the introspection service credential is invalid', async()=>fixture(async ({token,callConsent})=>{
  assert.equal((await callConsent('/grant',{headers:bearer(token)})).status,503);
},undefined,{resourceSecret:'synthetic-wrong-secret-00000000000000001'}));
test('issuer outage fails closed instead of accepting stale credentials', async()=>fixture(async ({token,callConsent,closeIdp})=>{
  assert.equal((await callConsent('/grant',{headers:bearer(token)})).status,200);
  await closeIdp();
  assert.equal((await callConsent('/grant',{headers:bearer(token)})).status,503);
}));

test('rejects malformed, ID-token substitution, and forged signatures', async()=>fixture(async ({token,tokens,callConsent})=>{
  const parts=token.split('.'); parts[2]=(parts[2][0]==='A'?'B':'A')+parts[2].slice(1);
  assert.equal((await callConsent('/grant',{headers:bearer(parts.join('.'))})).status,401);
  const unsignedClaims=token.split('.'); const payload=JSON.parse(Buffer.from(unsignedClaims[1],'base64url'));
  payload.scope='test.consent.read'; unsignedClaims[1]=Buffer.from(JSON.stringify(payload)).toString('base64url');
  assert.equal((await callConsent('/approve',{method:'POST',headers:bearer(unsignedClaims.join('.'))})).status,401);
  assert.equal((await callConsent('/grant',{headers:bearer('bad.token')})).status,401);
  assert.equal((await callConsent('/grant',{headers:bearer(tokens.id_token)})).status,401);
  assert.equal((await callConsent('/grant',{headers:{Authorization:'Basic abc'}})).status,401);
}));

for (const [name,claims,expected] of [
  ['wrong issuer',{iss:'https://wrong.invalid'},401],
  ['wrong audience',{aud:'other'},401],
  ['wrong subject',{sub:'synthetic-other'},401],
  ['expired',{exp:1},401],
  ['future issuance',{iat:4102444800,exp:4102444900},401],
  ['read-only access',{scope:'test.consent.read'},403]
]) test(name,async()=>fixture(async ({token,callConsent})=>{
  const headers={...bearer(token),'Content-Type':'application/json','Idempotency-Key':'synthetic-key-00000001','X-Correlation-ID':'synthetic-correlation-1','X-Mock-Instance-ID':'00000000-0000-4000-8000-000000000000'};
  const response=await callConsent('/approve',{method:'POST',headers,body:JSON.stringify(valid)});
  assert.equal(response.status,expected);
},claims));
