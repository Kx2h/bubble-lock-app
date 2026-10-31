import { createPublicKey, verify } from 'node:crypto';
import { decodeBase64Url, parseStrictJson } from './strict-json.mjs';

export class AccessError extends Error {
  constructor(code = 'invalid_token', status = 401) { super(code); this.code = code; this.status = status; }
}

function object(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }
function jsonPart(part) {
  try {
    const value = parseStrictJson(decodeBase64Url(part), 16);
    if (!object(value)) throw new Error();
    return value;
  } catch { throw new AccessError(); }
}
function rsaKey(value) {
  const key = createPublicKey(value);
  const bits = key.asymmetricKeyDetails?.modulusLength ?? 0;
  if (key.asymmetricKeyType !== 'rsa' || bits < 2048 || bits > 8192) throw new Error('An RSA public key between 2048 and 8192 bits is required');
  return key;
}

export function createJwtVerifier({ issuer, audience, publicKey = null, jwks = null, clock = () => Date.now() } = {}) {
  const origin = new URL(issuer);
  if (origin.protocol !== 'https:' || origin.username || origin.password || origin.search || origin.hash || !audience || typeof audience !== 'string') throw new Error('A trusted HTTPS issuer and audience are required');
  if (Boolean(publicKey) === Boolean(jwks)) throw new Error('Configure exactly one trusted public key or JWKS');
  let keys;
  if (publicKey) keys = [{ kid: null, key: rsaKey(publicKey) }];
  else {
    if (!object(jwks) || !Array.isArray(jwks.keys) || jwks.keys.length === 0 || jwks.keys.length > 8) throw new Error('Invalid trusted JWKS');
    const ids = new Set();
    keys = jwks.keys.map(jwk => {
      if (!object(jwk) || jwk.kty !== 'RSA' || typeof jwk.kid !== 'string' || !jwk.kid || jwk.kid.length > 128 || ids.has(jwk.kid)
          || (jwk.alg !== undefined && jwk.alg !== 'RS256') || (jwk.use !== undefined && jwk.use !== 'sig')
          || ['d', 'p', 'q', 'dp', 'dq', 'qi', 'oth'].some(field => Object.hasOwn(jwk, field))
          || (jwk.key_ops !== undefined && (!Array.isArray(jwk.key_ops) || jwk.key_ops.length !== 1 || jwk.key_ops[0] !== 'verify'))) throw new Error('Invalid trusted RSA signing key');
      ids.add(jwk.kid);
      return { kid: jwk.kid, key: rsaKey({ key: jwk, format: 'jwk' }) };
    });
  }
  return authorization => {
    if (typeof authorization !== 'string' || authorization.length > 8192 || !/^Bearer [A-Za-z0-9_.-]+$/.test(authorization)) throw new AccessError();
    const token = authorization.slice(7), chunks = token.split('.');
    if (chunks.length !== 3 || !chunks.every(Boolean)) throw new AccessError();
    const header = jsonPart(chunks[0]), claims = jsonPart(chunks[1]);
    if (header.alg !== 'RS256' || header.crit !== undefined || header.b64 !== undefined
        || (header.typ !== undefined && header.typ !== 'JWT' && header.typ !== 'at+jwt')
        || (header.kid !== undefined && (typeof header.kid !== 'string' || !header.kid || header.kid.length > 128 || /[\x00-\x1f\x7f]/.test(header.kid)))
        || ['jku', 'jwk', 'x5u'].some(field => Object.hasOwn(header, field))) throw new AccessError();
    const key = keys.find(k => k.kid === null || k.kid === header.kid);
    let signature;
    try { signature = decodeBase64Url(chunks[2]); } catch { throw new AccessError(); }
    if (!key || !verify('RSA-SHA256', Buffer.from(`${chunks[0]}.${chunks[1]}`), key.key, signature)) throw new AccessError();
    const now = Math.floor(clock() / 1000);
    const audiences = typeof claims.aud === 'string' ? [claims.aud] : claims.aud;
    if (!Number.isSafeInteger(now) || claims.iss !== issuer || !Array.isArray(audiences) || !audiences.every(a => typeof a === 'string') || !audiences.includes(audience)
        || typeof claims.sub !== 'string' || !claims.sub.trim() || claims.sub.length > 256 || /[\x00-\x1f\x7f]/.test(claims.sub)
        || !Number.isSafeInteger(claims.exp) || claims.exp <= now
        || (claims.nbf !== undefined && (!Number.isSafeInteger(claims.nbf) || claims.nbf > now))
        || (claims.iat !== undefined && (!Number.isSafeInteger(claims.iat) || claims.iat > now + 30))) throw new AccessError();
    const scopes = claims.scope !== undefined ? (typeof claims.scope === 'string' ? claims.scope.split(' ') : null) : claims.scp;
    if (!Array.isArray(scopes) || !scopes.every(scope => typeof scope === 'string') || !scopes.includes('location:read')) throw new AccessError('insufficient_scope', 403);
    return Object.freeze({ subject: claims.sub });
  };
}
