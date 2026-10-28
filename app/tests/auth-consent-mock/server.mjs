// Opt-in synthetic identity / consent boundary, never a production API.
import https from 'node:https';
import { createPublicKey, verify } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { createConsentMock } from '../consent-mock/server.mjs';

function endpointRead(url, {ca, method = 'GET', body, headers = {}}) {
  return new Promise((resolve, reject) => {
    const request = https.request(url, {ca, method, headers, minVersion:'TLSv1.3', rejectUnauthorized:true, signal:AbortSignal.timeout(5000)}, async response => {
      try {
        if (response.statusCode !== 200) throw Error('upstream status');
        let size=0; const chunks=[];
        for await (const chunk of response) { size+=chunk.length; if (size>65536) throw Error('upstream size'); chunks.push(chunk); }
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch (e) { response.destroy(); reject(e); }
    });
    request.on('error',reject); request.end(body);
  });
}
function part(raw) {
  if (!/^[A-Za-z0-9_-]+$/.test(raw)) throw Error('invalid jwt');
  const bytes=Buffer.from(raw,'base64url');
  if (bytes.toString('base64url') !== raw) throw Error('invalid jwt');
  return JSON.parse(bytes.toString('utf8'));
}
export function createAuthenticatedConsent({cert,key,ca,issuer='https://localhost:18443',introspectionSecret,dropFirstMutationResponse=false,now=()=>Math.floor(Date.now()/1000)}) {
  const base=new URL(issuer);
  if (base.protocol !== 'https:' || base.hostname !== 'localhost' || base.pathname !== '/' || base.search || base.hash || base.username || base.password || !base.port || !cert || !key || !ca || typeof introspectionSecret !== 'string' || introspectionSecret.length < 32 || introspectionSecret.length > 256 || /[^\x21-\x7e]/.test(introspectionSecret)) throw Error('Invalid synthetic server configuration');
  const canonical=issuer.replace(/\/$/,'');
  async function authorize(req) {
    const path = req.url;
    const read = req.method === 'GET' && ['/api/mock-consent/v1/request','/api/mock-consent/v1/grant'].includes(path);
    const write = req.method === 'POST' && ['/api/mock-consent/v1/approve','/api/mock-consent/v1/revoke'].includes(path);
    if (!read && !write) return 403;
    if (req.rawHeaders.filter((v,i) => i%2===0 && v.toLowerCase()==='authorization').length !== 1) return 401;
    const auth=req.headers.authorization;
    if (typeof auth !== 'string' || !/^Bearer [A-Za-z0-9_.-]{1,16384}$/.test(auth)) return 401;
    const token=auth.slice(7), pieces=token.split('.');
    if (pieces.length !== 3) return 401;
    let h,c,scopes;
    try {
      h=part(pieces[0]); c=part(pieces[1]);
      if (!h || h.alg !== 'RS256' || h.typ !== 'at+jwt' || h.kid !== 'mock-signing-key' || h.jku || h.jwk || h.x5u || h.crit) return 401;
      if (!c || c.iss !== canonical || c.aud !== 'consent-test-api' || c.sub !== 'synthetic-test-user' || !Number.isSafeInteger(c.iat) || !Number.isSafeInteger(c.exp) || c.iat > now() || c.exp <= now() || c.exp <= c.iat || c.exp-c.iat > 120 || typeof c.jti !== 'string' || !/^[A-Za-z0-9_-]{22}$/.test(c.jti) || typeof c.scope !== 'string') return 401;
      scopes=c.scope.split(' ');
      if (scopes.some(s => !['test.consent.read','test.consent.write'].includes(s)) || new Set(scopes).size !== scopes.length) return 401;
    } catch { return 401; }
    // Obtain the public key and live-token status solely from the pinned TLS issuer.
    const jwks=await endpointRead(`${canonical}/jwks`,{ca});
    const matches=Array.isArray(jwks?.keys)?jwks.keys.filter(k=>k.kid===h.kid):[];
    if (matches.length!==1 || matches[0].kty!=='RSA' || matches[0].alg!=='RS256' || matches[0].use!=='sig' || matches[0].d) return 503;
    let publicKey;
    try { publicKey=createPublicKey({key:matches[0],format:'jwk'}); } catch { return 503; }
    if (publicKey.asymmetricKeyDetails?.modulusLength < 2048) return 503;
    const signature=Buffer.from(pieces[2],'base64url');
    if (signature.toString('base64url') !== pieces[2] || !verify('RSA-SHA256',Buffer.from(`${pieces[0]}.${pieces[1]}`),publicKey,signature)) return 401;
    const form=new URLSearchParams({token}).toString();
    const introspection=await endpointRead(`${canonical}/introspect`,{ca,method:'POST',body:form,headers:{'Content-Type':'application/x-www-form-urlencoded','X-Mock-Introspection-Key':introspectionSecret}});
    if (introspection?.active !== true) return 401;
    // Re-check time after network; a token can expire while fetching the key or introspection.
    if (c.exp <= now()) return 401;
    return scopes.includes(read ? 'test.consent.read' : 'test.consent.write') ? 200 : 403;
  }
  return createConsentMock({authorize,dropFirstMutationResponse,serverFactory:handler=>https.createServer({cert,key,minVersion:'TLSv1.3'},handler)});
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port=Number(process.env.AUTH_CONSENT_PORT || 18788);
  if (!Number.isInteger(port) || port<1024 || port>65535 || !process.env.MOCK_OIDC_CERT || !process.env.MOCK_OIDC_KEY || !process.env.MOCK_OIDC_CA || !process.env.MOCK_INTROSPECTION_SECRET) throw Error('Configure local test certificate, key, CA, and introspection secret');
  createAuthenticatedConsent({cert:readFileSync(process.env.MOCK_OIDC_CERT),key:readFileSync(process.env.MOCK_OIDC_KEY),ca:readFileSync(process.env.MOCK_OIDC_CA),introspectionSecret:process.env.MOCK_INTROSPECTION_SECRET,dropFirstMutationResponse:process.argv.includes('--drop-first-response')}).listen(port,'127.0.0.1',()=>console.log('Synthetic authenticated consent fixture ready on loopback'));
}
