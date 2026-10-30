import { createServer } from 'node:http';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createIntegrationRuntime } from '../integration/src/integration-runtime.mjs';

const root = fileURLToPath(new URL('..', import.meta.url));
const port = Number(process.env.PRIVACY_BUBBLE_INTEGRATION_PORT ?? 8787);
const runtime = await createIntegrationRuntime({ statePath: resolve(root, 'integration/state/runtime.json'), receiptPath: resolve(root, 'receipts/integration-api.jsonl') });

const server = createServer(async (request, response) => {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  const raw = Buffer.concat(chunks).toString('utf8');
  let body = {};
  if (raw) {
    try { body = JSON.parse(raw); } catch { body = { raw }; }
  }
  const result = await runtime.api.handle({ method: request.method, path: request.url?.split('?')[0], headers: Object.fromEntries(Object.entries(request.headers).map(([key, value]) => [key.toLowerCase(), Array.isArray(value) ? value[0] : value])), body });
  response.writeHead(result.status, result.headers);
  response.end(JSON.stringify(result.body));
});

server.listen(port, '127.0.0.1', () => console.log(JSON.stringify({ role: 'PC-MOBIUS-INTEGRATION', endpoint: `http://127.0.0.1:${port}/v1`, status: 'READY' })));
