import { test } from 'node:test';
import assert from 'node:assert/strict';
import https from 'node:https';
import net from 'node:net';
import { execFileSync } from 'node:child_process';
import { mkdtemp, mkdir, readFile, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';
import { createHash, createPublicKey, verify } from 'node:crypto';
import { createMockIdp } from './server.mjs';

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
async function listenPort() {
  const s = net.createServer(); await new Promise(r => s.listen(0, '127.0.0.1', r));
  const port = s.address().port; await new Promise(r => s.close(r)); return port;
}
async function fixture(run) {
  await mkdir(resolve(appRoot, 'build'), {recursive:true});
  const dir = await mkdtemp(resolve(appRoot, 'build/oidc-mock-'));
  let server;
  try {
    execFileSync('openssl', ['req','-x509','-newkey','rsa:2048','-nodes','-days','1','-keyout','server.key','-out','server.pem','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost'], {cwd:dir,stdio:'ignore'});
    const [cert,key] = await Promise.all(['server.pem','server.key'].map(p => readFile(resolve(dir,p))));
    const port = await listenPort(), issuer = `https://localhost:${port}`;
    let clock = 1800000000;
    server = createMockIdp({issuer, cert, key, now:() => clock});
    await new Promise((r,j) => server.once('error',j).listen(port,'127.0.0.1',r));
    const request = (path, {method='GET', body, trust=true}={}) => new Promise((done, reject) => {
      const req = https.request({hostname:'127.0.0.1', servername:'localhost', port, path, method, ...(trust ? {ca:cert} : {}), headers:body ? {'Content-Type':'application/x-www-form-urlencoded'} : {}}, res => {
        const chunks=[]; res.on('data', c => chunks.push(c)); res.on('end', () => done({status:res.statusCode, headers:res.headers, body:Buffer.concat(chunks).toString()}));
      }); req.on('error',reject); req.end(body);
    });
    await run({request,issuer,advance:seconds => {clock+=seconds;}});
  } finally { if (server?.listening) await new Promise(r => server.close(r)); await rm(dir,{recursive:true,force:true}); }
}
const verifier = 'v'.repeat(64);
function params(challenge = createHash('sha256').update(verifier).digest('base64url')) {
  return new URLSearchParams({response_type:'code',client_id:'privacy-bubble-android-test',redirect_uri:'org.privacybubble.app:/oauth2redirect',scope:'openid',state:'synthetic-state-12345',nonce:'synthetic-nonce-12345',code_challenge:challenge,code_challenge_method:'S256'});
}
async function codeFor(request, p, expectedIssuer) {
  const auth = await request('/authorize', {method:'POST',body:p.toString()});
  assert.equal(auth.status,303); const uri = new URL(auth.headers.location);
  assert.equal(uri.searchParams.get('state'), p.get('state')); assert.equal(uri.searchParams.get('iss'), expectedIssuer); return uri.searchParams.get('code');
}
function tokenForm(code, codeVerifier = verifier) {
  return new URLSearchParams({grant_type:'authorization_code',client_id:'privacy-bubble-android-test',redirect_uri:'org.privacybubble.app:/oauth2redirect',code,code_verifier:codeVerifier}).toString();
}

test('HTTPS discovery, synthetic consent, S256 exchange and signed ID token', async () => fixture(async ({request,issuer}) => {
  await assert.rejects(request('/jwks', {trust:false}));
  const discovery = JSON.parse((await request('/.well-known/openid-configuration')).body);
  assert.equal(discovery.issuer,issuer); assert.equal(discovery.authorization_response_iss_parameter_supported,true); assert.deepEqual(discovery.code_challenge_methods_supported,['S256']);
  const p = params(), page = await request(`/authorize?${p}`);
  assert.equal(page.status,200); assert.match(page.body,/synthetic account/i); assert.match(page.body,/Continue with test identity/);
  const code = await codeFor(request,p,issuer);
  const response = await request('/token',{method:'POST',body:tokenForm(code)});
  assert.equal(response.status,200); const initial = JSON.parse(response.body); assert.equal(initial.refresh_token, undefined); const token = initial.id_token;
  const [header,payload,signature] = token.split('.');
  const claims = JSON.parse(Buffer.from(payload,'base64url'));
  assert.equal(claims.iss,issuer); assert.equal(claims.aud,'privacy-bubble-android-test'); assert.equal(claims.nonce,p.get('nonce')); assert.equal(claims.sub,'synthetic-test-user');
  const jwks = JSON.parse((await request('/jwks')).body), publicKey=createPublicKey({key:jwks.keys[0],format:'jwk'});
  assert.ok(verify('RSA-SHA256',Buffer.from(`${header}.${payload}`),publicKey,Buffer.from(signature,'base64url')));
  assert.equal((await request('/token',{method:'POST',body:tokenForm(code)})).status,400);
}));

test('rejects incorrect verifier, replay, expired code and unregistered redirects', async () => fixture(async ({request,advance,issuer}) => {
  const p=params(); p.set('redirect_uri','org.attacker:/callback');
  assert.equal((await request(`/authorize?${p}`)).status,400);
  const duplicate=params(); duplicate.append('state','injected');
  assert.equal((await request(`/authorize?${duplicate}`)).status,400);
  const bad = await codeFor(request,params(),issuer);
  assert.equal((await request('/token',{method:'POST',body:tokenForm(bad,'w'.repeat(64))})).status,400);
  assert.equal((await request('/token',{method:'POST',body:tokenForm(bad)})).status,400);
  const expired = await codeFor(request,params(),issuer); advance(120);
  assert.equal((await request('/token',{method:'POST',body:tokenForm(expired)})).status,400);
}));


function offlineParams() {
  const p = params(); p.set('scope', 'openid offline_access'); p.set('prompt', 'consent'); return p;
}
function refreshForm(token, clientId = 'privacy-bubble-android-test') {
  return new URLSearchParams({grant_type:'refresh_token',client_id:clientId,refresh_token:token});
}
async function offlineGrant(request, issuer) {
  const code = await codeFor(request, offlineParams(), issuer);
  const result = await request('/token', {method:'POST', body:tokenForm(code)});
  assert.equal(result.status, 200);
  const body = JSON.parse(result.body);
  assert.equal(typeof body.refresh_token, 'string');
  return body;
}

test('offline access requires explicit synthetic consent and only registered scope', async () => fixture(async ({request,issuer}) => {
  const absent = offlineParams(); absent.delete('prompt');
  assert.equal((await request(`/authorize?${absent}`)).status, 400);
  assert.equal((await request('/authorize', {method:'POST', body:absent.toString()})).status, 400);
  const duplicate = offlineParams(); duplicate.append('prompt','consent');
  assert.equal((await request(`/authorize?${duplicate}`)).status, 400);
  const extra = offlineParams(); extra.set('scope', 'openid offline_access profile');
  assert.equal((await request(`/authorize?${extra}`)).status, 400);
  const duplicated = offlineParams(); duplicated.set('scope', 'openid openid offline_access');
  assert.equal((await request(`/authorize?${duplicated}`)).status, 400);
  const unexpectedPrompt = params(); unexpectedPrompt.set('prompt','consent');
  assert.equal((await request(`/authorize?${unexpectedPrompt}`)).status, 400);
  const valid = offlineParams();
  const page = await request(`/authorize?${valid}`);
  assert.equal(page.status, 200); assert.match(page.body, /consent for this synthetic test only/i);
  assert.match(page.body, /name="prompt" value="consent"/);
  await offlineGrant(request,issuer);
}));

test('refresh rotates once and replay revokes that entire token family', async () => fixture(async ({request,issuer}) => {
  const first = await offlineGrant(request,issuer);
  const other = await offlineGrant(request,issuer);
  const wrongClient = await request('/token',{method:'POST',body:refreshForm(first.refresh_token,'other-client').toString()});
  assert.equal(wrongClient.status,400);
  const scoped = refreshForm(first.refresh_token); scoped.set('scope','openid');
  const wrongScope = await request('/token',{method:'POST',body:scoped.toString()});
  assert.equal(wrongScope.status,400); assert.equal(JSON.parse(wrongScope.body).error,'invalid_scope');
  const rotated = await request('/token',{method:'POST',body:refreshForm(first.refresh_token).toString()});
  assert.equal(rotated.status,200);
  const second = JSON.parse(rotated.body);
  assert.equal(second.token_type,'Bearer'); assert.equal(second.expires_in,120);
  assert.equal(typeof second.access_token,'string'); assert.equal(typeof second.refresh_token,'string');
  assert.ok(second.refresh_token !== first.refresh_token, 'rotation must issue a different refresh token'); assert.equal(second.id_token,undefined);
  const replay = await request('/token',{method:'POST',body:refreshForm(first.refresh_token).toString()});
  assert.equal(replay.status,400); assert.equal(JSON.parse(replay.body).error,'invalid_grant');
  const revoked = await request('/token',{method:'POST',body:refreshForm(second.refresh_token).toString()});
  assert.equal(revoked.status,400); assert.equal(JSON.parse(revoked.body).error,'invalid_grant');
  assert.equal((await request('/token',{method:'POST',body:refreshForm(other.refresh_token).toString()})).status,200);
}));

test('concurrent replay and family expiry fail closed', async () => fixture(async ({request,issuer,advance}) => {
  const first = await offlineGrant(request,issuer);
  const [a,b] = await Promise.all([
    request('/token',{method:'POST',body:refreshForm(first.refresh_token).toString()}),
    request('/token',{method:'POST',body:refreshForm(first.refresh_token).toString()})
  ]);
  assert.deepEqual([a.status,b.status].sort(),[200,400]);
  const next = JSON.parse((a.status === 200 ? a : b).body).refresh_token;
  assert.equal((await request('/token',{method:'POST',body:refreshForm(next).toString()})).status,400);
  const expires = await offlineGrant(request,issuer); advance(1800);
  assert.equal((await request('/token',{method:'POST',body:refreshForm(expires.refresh_token).toString()})).status,400);
}));

test('rotation limit revokes the family rather than retaining a usable token', async () => fixture(async ({request,issuer}) => {
  let current = (await offlineGrant(request,issuer)).refresh_token;
  for (let i = 0; i < 63; i++) {
    const result = await request('/token',{method:'POST',body:refreshForm(current).toString()});
    assert.equal(result.status,200); current = JSON.parse(result.body).refresh_token;
  }
  const limited = await request('/token',{method:'POST',body:refreshForm(current).toString()});
  assert.equal(limited.status,400); assert.equal(JSON.parse(limited.body).error,'invalid_grant');
  assert.equal((await request('/token',{method:'POST',body:refreshForm(current).toString()})).status,400);
}));
