import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const input = process.argv.slice(2).find((value) => value.startsWith('--input='))?.split('=')[1] ?? 'receipts/integration-demo.json';
const report = JSON.parse(await readFile(resolve(root, input), 'utf8'));
const required = ['contractId', 'docRevision', 'owner', 'status'];
const missing = required.filter((field) => report[field] === undefined);
if (missing.length > 0) throw new Error(`Integration replay fields are required: ${missing.join(', ')}`);
console.log(JSON.stringify({ input, contractId: report.contractId, docRevision: report.docRevision, owner: report.owner, status: report.status, replay: 'PASS' }, null, 2));
