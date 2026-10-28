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
  assert.equal(response.status,200); const token = JSON.parse(response.body).id_token;
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
