import http from 'node:http';
let requests = 0;
const server = http.createServer((req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('Content-Type', 'application/json');
  if (req.method !== 'GET' || req.url !== '/api/test/bubble-state') {
    res.writeHead(404).end(JSON.stringify({error:'not_found'}));
    return;
  }
  requests++;
  const status = requests === 1 ? 503 : 200;
  console.log(JSON.stringify({mode:'mock', requestNumber:requests, status}));
  res.writeHead(status).end(JSON.stringify(status === 503 ? {mode:'mock',error:'simulated_unavailable'} : {mode:'test', state:false, observation:'unobserved',requestId:'mock-retry-device-test'}));
});
server.listen(8787,'127.0.0.1',() => console.log(JSON.stringify({mode:'mock',event:'ready'})));
