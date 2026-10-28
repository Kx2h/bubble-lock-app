// Synthetic loopback fixture for manual Android request-lifecycle checks.
// It does not call Mobius, a Gateway, or a device.
import http from 'node:http';

let observationRequests = 0;
const payload = JSON.stringify({mode:'synthetic',gatewayId:'app-isolated',deviceId:'synthetic-keyring',sequence:1,
  state:true,observedAt:'2026-01-01T00:00:00Z',receivedAt:'2026-01-01T00:00:00Z',timestampSource:'gateway-received',battery:90,rssi:-50});
const server = http.createServer((req,res) => {
  if (req.method === 'GET' && req.url === '/count') {
    res.writeHead(200,{'Content-Type':'application/json','Cache-Control':'no-store'});
    return res.end(JSON.stringify({observationRequests}));
  }
  if (req.method !== 'GET' || req.url !== '/api/test/observation') {res.writeHead(404);return res.end();}
  observationRequests++;
  res.writeHead(200,{'Content-Type':'application/json','Content-Length':Buffer.byteLength(payload),'Cache-Control':'no-store'});
  res.flushHeaders();
  setTimeout(() => res.end(payload), 7000);
});
server.listen(8787,'127.0.0.1',()=>console.log('Observation lifecycle fixture listening on loopback 8787'));
