import { createHash, randomUUID } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';

export const SYNTHETIC_RECEIPT_RQI = `kr-${createHash('sha256').update('receipt:synthetic-command-1').digest('hex').slice(0, 48)}`;
export const SYNTHETIC_RECEIPT_RN = `gw-${createHash('sha256').update(SYNTHETIC_RECEIPT_RQI).digest('hex')}`;

async function jsonBounded(response) {
  const chunks = []; let size = 0;
  for await (const chunk of response.body ?? []) {
    size += chunk.length;
    if (size > 65536) throw Error('CSE response too large');
    chunks.push(Buffer.from(chunk));
  }
  return JSON.parse(Buffer.concat(chunks, size).toString('utf8'));
}

/** Test-only confirmation for one terminal synthetic receipt; no command is dispatched. */
export async function createVerifiedReceipt({ origin, aeId, aeName, wire, fetchImpl = fetch }) {
  if (!/^app-bridge-[a-f0-9]{16}$/.test(aeName) || !/^C[A-Za-z0-9_-]{1,128}$/.test(aeId)
      || typeof wire !== 'string' || Buffer.byteLength(wire) > 65536) throw Error('Invalid receipt request');
  const req = JSON.parse(wire), cin = req?.pc?.['m2m:cin'], receipt = cin?.con;
  const target = `/Mobius/${aeName}/receipts`;
  const exactKeys = (value, keys) => value && typeof value === 'object' && !Array.isArray(value)
    && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value,key));
  const expectedFields = ['version','gatewayId','deviceId','commandId','payloadHash','status','ackCode',
    'acceptedAt','appliedAt','receivedAt','readback','firmwareRevision','ownershipEpoch','grantRevision',
    'timestampSource','detail','bootId'];
  const validReceived = typeof receipt?.receivedAt === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(receipt.receivedAt)
    && Number.isFinite(Date.parse(receipt.receivedAt)) && new Date(receipt.receivedAt).toISOString() === receipt.receivedAt;
  if (!exactKeys(req,['op','to','fr','rqi','ty','rvi','pc']) || !exactKeys(req.pc,['m2m:cin'])
      || !exactKeys(cin,['rn','cnf','con']) || req.op !== 1 || req.ty !== 4 || req.to !== target || req.fr !== aeId || req.rvi !== '4'
      || req.rqi !== SYNTHETIC_RECEIPT_RQI || cin?.rn !== SYNTHETIC_RECEIPT_RN || cin?.cnf !== 'application/json:0'
      || !receipt || typeof receipt !== 'object' || Array.isArray(receipt)
      || Object.keys(receipt).length !== expectedFields.length || !expectedFields.every(key => Object.hasOwn(receipt, key))
      || receipt.version !== 1 || receipt.gatewayId !== 'app-isolated' || receipt.deviceId !== 'synthetic-keyring'
      || receipt.commandId !== 'synthetic-command-1' || receipt.payloadHash !== '0'.repeat(64)
      || receipt.status !== 'INDETERMINATE' || receipt.ackCode !== 'INVALID_RECEIPT'
      || receipt.acceptedAt !== null || receipt.appliedAt !== null || receipt.readback !== null
      || receipt.firmwareRevision !== null || receipt.bootId !== null || receipt.timestampSource !== 'unavailable'
      || receipt.ownershipEpoch !== 1 || receipt.grantRevision !== 1 || !validReceived
      || receipt.detail !== 'Synthetic test; no device command was sent') throw Error('Invalid receipt request');
  const headers = {Accept:'application/json','X-M2M-RVI':'4','X-M2M-Origin':aeId,'X-M2M-RI':req.rqi,'Content-Type':'application/json;ty=4'};
  const created = await fetchImpl(`${origin}${target}`, {method:'POST',headers,body:JSON.stringify({'m2m:cin':cin}),signal:AbortSignal.timeout(5000),redirect:'error'});
  if (created.status !== 201 || created.headers.get('x-m2m-rsc') !== '2001' || created.headers.get('x-m2m-ri') !== req.rqi) throw Error('Receipt create not verified');
  if (created.body) await created.body.cancel();
  const readId = `app-read-${randomUUID()}`;
  const retrieved = await fetchImpl(`${origin}${target}/${cin.rn}`, {headers:{Accept:'application/json','X-M2M-RVI':'4','X-M2M-Origin':aeId,'X-M2M-RI':readId},signal:AbortSignal.timeout(5000),redirect:'error'});
  if (retrieved.status !== 200 || retrieved.headers.get('x-m2m-rsc') !== '2000' || retrieved.headers.get('x-m2m-ri') !== readId) throw Error('Receipt readback not verified');
  const data = await jsonBounded(retrieved);
  if (data?.['m2m:cin']?.rn !== cin.rn || !isDeepStrictEqual(data?.['m2m:cin']?.con,receipt)) throw Error('Receipt content mismatch');
  return {reply:{rqi:req.rqi,rsc:2001,fr:'Mobius4',to:aeId},
    evidence:{createHttp:201,createRsc:2001,createRiMatched:true,readHttp:200,readRsc:2000,readRiMatched:true,contentMatched:true}};
}
