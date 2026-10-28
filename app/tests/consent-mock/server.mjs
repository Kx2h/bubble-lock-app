import http from 'node:http';
import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';

// Test-only process-local simulator. It grants no actual access or command authority.
const BASE = '/api/mock-consent/v1';
const REQUEST = Object.freeze({requestId:'synthetic-request-1', principal:'synthetic-visitor', purpose:'synthetic-location-check', scopes:['test.location.read'], ttlSeconds:120, revision:1});
const HEADER = /^[A-Za-z0-9-]{16,64}$/;
const keysMatch = (object, keys) => object && typeof object === 'object' && !Array.isArray(object) && Object.keys(object).sort().join(',') === [...keys].sort().join(',');
const stable = object => JSON.stringify(object, (_key, value) => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return value;
  return Object.fromEntries(Object.keys(value).sort().map(key => [key, value[key]]));
});

export function createConsentMock({ now = () => Date.now(), dropFirstMutationResponse = false } = {}) {
  let grant = null, status = 'none', revision = 0, drop = dropFirstMutationResponse;
  const operations = new Map();
  const refreshExpiry = () => {
    if (status === 'active' && now() >= Date.parse(grant.expiresAt)) {
      status = 'expired'; grant = {...grant, grantRevision: ++revision};
    }
  };
  const response = (res, code, body) => { res.writeHead(code); res.end(JSON.stringify(body)); };
  const error = name => ({mode:'synthetic', error:name});
  async function bodyJson(req) {
    let size = 0; const chunks = [];
    for await (const chunk of req) { size += chunk.length; if (size > 4096) throw Error('too_large'); chunks.push(chunk); }
    const text = Buffer.concat(chunks).toString('utf8');
    return JSON.parse(text);
  }
  return http.createServer(async (req, res) => {
    res.setHeader('Content-Type','application/json; charset=utf-8');
    res.setHeader('Cache-Control','no-store');
    if (req.method === 'GET' && req.url === `${BASE}/request`) return response(res, 200, {mode:'synthetic',request:REQUEST});
    if (req.method === 'GET' && req.url === `${BASE}/grant`) { refreshExpiry(); return response(res, 200, {mode:'synthetic',status,grant}); }
    if (req.method !== 'POST' || ![`${BASE}/approve`,`${BASE}/revoke`].includes(req.url)) return response(res,404,error('not_found'));
    const id = req.headers['idempotency-key'], correlation = req.headers['x-correlation-id'];
    if (typeof id !== 'string' || !HEADER.test(id) || typeof correlation !== 'string' || !HEADER.test(correlation)) return response(res,400,error('invalid_headers'));
    if (!/^application\/json(?:\s*;|$)/i.test(req.headers['content-type'] ?? '')) return response(res,415,error('invalid_content_type'));
    let input;
    try { input = await bodyJson(req); } catch { return response(res,400,error('invalid_body')); }
    if (!input || typeof input !== 'object' || Array.isArray(input)) return response(res,400,error('invalid_body'));
    const fingerprint = `${req.url}\n${stable(input)}`;
    const prior = operations.get(id);
    if (prior) {
      if (prior.fingerprint !== fingerprint) return response(res,409,error('idempotency_conflict'));
      return response(res,prior.code,prior.body);
    }
    if (operations.size >= 128) return response(res,503,error('capacity'));
    refreshExpiry();
    let code, result;
    if (req.url === `${BASE}/approve`) {
      if (!keysMatch(input,['requestId','revision','purpose','scopes','ttlSeconds']) || input.requestId !== REQUEST.requestId || input.purpose !== REQUEST.purpose || input.ttlSeconds !== REQUEST.ttlSeconds || !Array.isArray(input.scopes) || input.scopes.length !== 1 || input.scopes[0] !== REQUEST.scopes[0]) { code = 400; result = error('invalid_request'); }
      else if (input.revision !== REQUEST.revision) { code = 409; result = error('stale_revision'); }
      else if (status === 'active') { code = 409; result = error('already_active'); }
      else {
        status = 'active';
        grant = {requestId:REQUEST.requestId,consentRevision:REQUEST.revision,grantRevision:++revision,expiresAt:new Date(now()+REQUEST.ttlSeconds*1000).toISOString(),receiptId:`synthetic-${randomUUID()}`};
        code = 200; result = {mode:'synthetic',status,grant,correlationId:correlation};
      }
    } else {
      if (!keysMatch(input,['requestId','grantRevision']) || input.requestId !== REQUEST.requestId || !Number.isSafeInteger(input.grantRevision)) { code = 400; result = error('invalid_request'); }
      else if (!grant || input.grantRevision !== grant.grantRevision) { code = 409; result = error('stale_revision'); }
      else if (status !== 'active') { code = 409; result = error('grant_not_active'); }
      else { status = 'revoked'; grant = {...grant,grantRevision:++revision}; code = 200; result = {mode:'synthetic',status,grant,correlationId:correlation}; }
    }
    operations.set(id, {fingerprint,code,body:result});
    if (drop && code === 200) { drop = false; res.destroy(); return; }
    return response(res,code,result);
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = Number(process.env.CONSENT_MOCK_PORT ?? 18787);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw Error('Invalid port');
  createConsentMock({dropFirstMutationResponse:process.argv.includes('--drop-first-response')}).listen(port,'127.0.0.1',() => console.log(`Synthetic consent mock listening on 127.0.0.1:${port}`));
}
