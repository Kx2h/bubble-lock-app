import { readFile, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
if (!process.argv[2] || !process.argv[3]) throw new Error('Usage: bootstrap.mjs <config.json> <receipt.json>');
const config = JSON.parse(await readFile(process.argv[2]));
const base = config.cse.poa[0] + '/Mobius';
const resources = [
  { path: '', ty: 2, body: { 'm2m:ae': { rn: 'privacy-bubble-test-ae', api: 'Nprivacy-bubble', rr: true } } },
  { path: '/privacy-bubble-test-ae', ty: 28, body: { 'pb:binarySwitch': { rn: 'bubbleState', cnd: 'org.onem2m.common.moduleclass.binarySwitch', state: false, lbl: ['dev-bootstrap', 'unobserved'] } } },
];
const receipts = [];
for (const r of resources) {
  const id = `app-setup-${randomUUID()}`;
  const response = await fetch(base + r.path, { method:'POST', headers: { 'Content-Type': `application/json;ty=${r.ty}`, Accept:'application/json', 'X-M2M-Origin':config.cse.admin, 'X-M2M-RVI':'4', 'X-M2M-RI':id }, body:JSON.stringify(r.body), signal:AbortSignal.timeout(10000) });
  const oneM2M = response.headers.get('x-m2m-rsc');
  const record = {ty:r.ty, status:response.status, rsc:oneM2M, requestId:id, responseRequestId:response.headers.get('x-m2m-ri')};
  receipts.push(record);
  if (record.responseRequestId !== id || !((record.status===201 && oneM2M==='2001') || (record.status===409 && oneM2M==='4105'))) { console.log(JSON.stringify({records:receipts, reason:'provisioning_failed'})); process.exit(1); }
}
await writeFile(process.argv[3], JSON.stringify({scope:'empty-resource-provisioning', receipts}, null, 2));
console.log(JSON.stringify({scope:'empty-resource-provisioning', receipts}));
