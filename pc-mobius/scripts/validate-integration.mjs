import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const contract = JSON.parse(await readFile(resolve(root, 'contracts/integration-contract.v1.json'), 'utf8'));
const required = ['R06', 'R10', 'R12', 'R13', 'R14', 'R15', 'R16', 'R17'];
for (const key of required) if (!contract.rubricMapping[key]) throw new Error(`Integration rubric mapping is required: ${key}`);
if (contract.owner !== 'PC-MOBIUS-INTEGRATION') throw new Error('Integration contract owner is required');
if (!contract.surfaces.auth.mtls || !contract.surfaces.mcp.checkpoint || !contract.surfaces.mcp.resume) throw new Error('Integration security and continuity flags are required');
console.log(JSON.stringify({ contractId: contract.contractId, docRevision: contract.docRevision, owner: contract.owner, rubric: Object.keys(contract.rubricMapping), status: 'PASS' }, null, 2));
