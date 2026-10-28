// Synthetic local OIDC provider. No production identities or credentials.
import https from 'node:https';
import { readFileSync } from 'node:fs';
import { createHash, generateKeyPairSync, randomBytes, sign, timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';

const defaultClient = 'privacy-bubble-android-test';
const defaultRedirect = 'org.privacybubble.app:/oauth2redirect';
const b64 = value => Buffer.from(value).toString('base64url');
const escape = value => value.replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
const text = (v, max = 512) => typeof v === 'string' && v.length > 0 && v.length <= max;
const required = (params, key) => params.getAll(key).length === 1 ? params.get(key) : null;

export function createMockIdp({ issuer, cert, key, clientId = defaultClient, redirectUri = defaultRedirect, now = () => Math.floor(Date.now() / 1000) }) {
  const issuerUrl = new URL(issuer);
  if (issuerUrl.protocol !== 'https:' || issuerUrl.hostname !== 'localhost' || issuerUrl.username || issuerUrl.password || issuerUrl.pathname !== '/' || issuerUrl.search || issuerUrl.hash || !issuerUrl.port) throw Error('Mock issuer must be https://localhost:<port>');
  if (!cert || !key || !text(clientId) || redirectUri !== defaultRedirect) throw Error('Invalid mock configuration');
  issuer = issuer.replace(/\/$/, '');
  const keys = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const jwk = { ...keys.publicKey.export({ format: 'jwk' }), kid: 'mock-signing-key', alg: 'RS256', use: 'sig' };
  const codes = new Map();
  const json = (res, status, data) => { res.writeHead(status, {'Content-Type':'application/json'}); res.end(JSON.stringify(data)); };
  const fail = res => json(res, 400, { error: 'invalid_request' });
  function validate(p) {
    const responseType = required(p, 'response_type'), client = required(p, 'client_id'), redirect = required(p, 'redirect_uri');
    const scope = required(p, 'scope'), state = required(p, 'state'), nonce = required(p, 'nonce');
    const challenge = required(p, 'code_challenge'), method = required(p, 'code_challenge_method');
    if (responseType !== 'code' || client !== clientId || redirect !== redirectUri || !text(scope) || !scope.split(' ').includes('openid') || !text(state) || state.length < 16 || !text(nonce) || nonce.length < 16 || method !== 'S256' || !/^[A-Za-z0-9_-]{43}$/.test(challenge ?? '')) return null;
    return { state, nonce, challenge };
  }
  async function form(req) {
    let size = 0; const chunks = [];
    for await (const chunk of req) { size += chunk.length; if (size > 8192) throw Error('large'); chunks.push(chunk); }
    if (!(req.headers['content-type'] ?? '').toLowerCase().startsWith('application/x-www-form-urlencoded')) throw Error('content type');
    return new URLSearchParams(Buffer.concat(chunks).toString('utf8'));
  }
  function issueToken(nonce) {
    const t = now(), header = b64(JSON.stringify({alg:'RS256',kid:jwk.kid,typ:'JWT'}));
    const payload = b64(JSON.stringify({iss:issuer,sub:'synthetic-test-user',aud:clientId,iat:t,exp:t+120,nonce}));
    const message = `${header}.${payload}`;
    return `${message}.${sign('RSA-SHA256', Buffer.from(message), keys.privateKey).toString('base64url')}`;
  }
  return https.createServer({cert, key, minVersion:'TLSv1.3'}, async (req, res) => {
    res.setHeader('Cache-Control', 'no-store'); res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    try {
      const u = new URL(req.url, issuer);
      if (u.pathname === '/.well-known/openid-configuration' && req.method === 'GET') return json(res, 200, { issuer, authorization_endpoint:`${issuer}/authorize`, token_endpoint:`${issuer}/token`, jwks_uri:`${issuer}/jwks`, response_types_supported:['code'], code_challenge_methods_supported:['S256'], id_token_signing_alg_values_supported:['RS256'], subject_types_supported:['public'], grant_types_supported:['authorization_code'], authorization_response_iss_parameter_supported:true });
      if (u.pathname === '/jwks' && req.method === 'GET') return json(res, 200, {keys:[jwk]});
      if (u.pathname === '/authorize' && req.method === 'GET') {
        const auth = validate(u.searchParams); if (!auth) return fail(res);
        const fields = ['response_type','client_id','redirect_uri','scope','state','nonce','code_challenge','code_challenge_method'];
        const hidden = fields.map(name => `<input type="hidden" name="${name}" value="${escape(required(u.searchParams,name))}">`).join('');
        res.writeHead(200, {'Content-Type':'text/html; charset=utf-8', 'Content-Security-Policy':"default-src 'none'; form-action 'self'; base-uri 'none'"});
        return res.end(`<!doctype html><html lang="en"><title>Local mock sign-in</title><h1>Local test identity</h1><p>This is a synthetic account. Do not enter a password or personal information.</p><form method="post" action="/authorize">${hidden}<button type="submit">Continue with test identity</button></form></html>`);
      }
      if (u.pathname === '/authorize' && req.method === 'POST') {
        const p = await form(req), auth = validate(p); if (!auth) return fail(res);
        for (const [code, item] of codes) if (item.expires <= now()) codes.delete(code);
        if (codes.size >= 100) return json(res, 503, {error:'temporarily_unavailable'});
        const code = randomBytes(32).toString('base64url'); codes.set(code, {...auth, expires:now()+120});
        const callback = new URL(redirectUri); callback.searchParams.set('code', code); callback.searchParams.set('state', auth.state); callback.searchParams.set('iss', issuer);
        res.writeHead(303, {Location:callback.href}); return res.end();
      }
      if (u.pathname === '/token' && req.method === 'POST') {
        const p = await form(req), code = required(p,'code'), verifier = required(p,'code_verifier');
        const grant = codes.get(code); if (code) codes.delete(code);
        if (required(p,'grant_type') !== 'authorization_code' || required(p,'client_id') !== clientId || required(p,'redirect_uri') !== redirectUri || !grant || grant.expires <= now() || !/^[A-Za-z0-9._~-]{43,128}$/.test(verifier ?? '')) return json(res, 400, {error:'invalid_grant'});
        const actual = createHash('sha256').update(verifier).digest(), expected = Buffer.from(grant.challenge, 'base64url');
        if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) return json(res, 400, {error:'invalid_grant'});
        return json(res, 200, {token_type:'Bearer', expires_in:120, access_token:randomBytes(32).toString('base64url'), id_token:issueToken(grant.nonce)});
      }
      return json(res, 404, {error:'not_found'});
    } catch { if (!res.headersSent) return fail(res); res.destroy(); }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = Number(process.env.MOCK_OIDC_PORT || 18443);
  if (!Number.isInteger(port) || port < 1024 || port > 65535 || !process.env.MOCK_OIDC_CERT || !process.env.MOCK_OIDC_KEY) throw Error('Set MOCK_OIDC_CERT and MOCK_OIDC_KEY; use a valid unprivileged port');
  createMockIdp({issuer:`https://localhost:${port}`, cert:readFileSync(process.env.MOCK_OIDC_CERT), key:readFileSync(process.env.MOCK_OIDC_KEY)}).listen(port, '127.0.0.1', () => console.log(`Synthetic local IdP listening on 127.0.0.1:${port}`));
}
