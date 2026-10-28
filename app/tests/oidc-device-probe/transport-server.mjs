import https from 'node:https';
import { readFileSync } from 'node:fs';

const cert = readFileSync(process.env.MOCK_OIDC_CERT), key = readFileSync(process.env.MOCK_OIDC_KEY);
const wrongCert = readFileSync(process.env.PROBE_WRONG_CERT), wrongKey = readFileSync(process.env.PROBE_WRONG_KEY);
let writes = 0, lost = 0, redirected = 0;
function handler(req, res) {
  if (req.url === '/ok' && req.method === 'GET') return res.end('ok');
  if (req.url === '/redirect' && req.method === 'GET') { res.writeHead(302, {location:'/redirect-target'}); return res.end(); }
  if (req.url === '/redirect-target') { redirected++; return res.end(); }
  if (req.url === '/denied' && req.method === 'GET') { res.writeHead(403); return res.end('denied'); }
  if (req.url === '/counts' && req.method === 'GET') return res.end(JSON.stringify({writes,lost,redirected}));
  if (req.url === '/write' && req.method === 'POST') { writes++; req.resume(); req.on('end', () => { res.writeHead(201); res.end('stored'); }); return; }
  if (req.url === '/lost' && req.method === 'POST') { lost++; req.resume(); req.on('end', () => req.socket.destroy()); return; }
  res.writeHead(404); res.end();
}
for (const [port, options] of [
  [18791, {cert,key,minVersion:'TLSv1.3'}],
  [18792, {cert,key,minVersion:'TLSv1.2',maxVersion:'TLSv1.2'}],
  [18793, {cert:wrongCert,key:wrongKey,minVersion:'TLSv1.3'}]
]) https.createServer(options, handler).listen(port, '127.0.0.1', () => console.log(`synthetic TLS fixture ${port} ready`));
