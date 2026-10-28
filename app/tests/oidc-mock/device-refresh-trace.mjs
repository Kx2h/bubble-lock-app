import { createMockIdp } from './server.mjs';
import { readFileSync, openSync, writeSync, closeSync } from 'node:fs';
const trace = openSync(process.env.REFRESH_TRACE_FILE, 'wx', 0o600);
const server = createMockIdp({issuer: 'https://localhost:18443', cert: readFileSync(process.env.MOCK_OIDC_CERT), key: readFileSync(process.env.MOCK_OIDC_KEY), refreshIdToken: process.env.MOCK_REFRESH_ID_TOKEN === 'wrong-sub' ? 'wrong-sub' : process.env.MOCK_REFRESH_ID_TOKEN === '1', accessHash: process.env.MOCK_ACCESS_HASH ?? 'none', introspectionSecret: process.env.MOCK_INTROSPECTION_SECRET});
server.on('request', req => {
  const pathname = new URL(req.url, 'https://localhost:18443').pathname;
  const permitted = ['/.well-known/openid-configuration', '/authorize', '/token', '/jwks'];
  const label = permitted.includes(pathname) ? pathname : '/other';
  const method = req.method === 'GET' || req.method === 'POST' ? req.method : 'OTHER';
  writeSync(trace, `${method} ${label}\n`);
});
server.on('error', error => { closeSync(trace); console.error(`Fixture server error: ${error.code ?? 'unknown'}`); });
server.listen(18443, '127.0.0.1', () => console.log('Synthetic OIDC fixture started (method/path only).'));
