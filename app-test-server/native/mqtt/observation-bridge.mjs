import { createHash, randomUUID } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';

async function jsonBounded(response) {
  const chunks = []; let size = 0;
  for await (const chunk of response.body ?? []) {
    size += chunk.length;
    if (size > 65536) throw Error('CSE response too large');
    chunks.push(Buffer.from(chunk));
  }
  return JSON.parse(Buffer.concat(chunks, size).toString('utf8'));
}

/** Test-only oneM2M MQTT-to-HTTP bridge for one isolated observation container. */
export async function createVerifiedObservation({ origin, aeId, aeName, wire, fetchImpl = fetch }) {
  if (typeof wire !== 'string' || Buffer.byteLength(wire) > 65536) throw Error('Invalid request');
  const req = JSON.parse(wire);
  const cin = req?.pc?.['m2m:cin'];
  const target = `/Mobius/${aeName}/observations`;
  if (req?.op !== 1 || req.ty !== 4 || req.to !== target || req.fr !== aeId || req.rvi !== '4'
      || typeof req.rqi !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(req.rqi)
      || !cin || typeof cin !== 'object' || !cin.con || typeof cin.con !== 'object'
      || cin.cnf !== 'application/json:0'
      || cin.rn !== `gw-${createHash('sha256').update(req.rqi).digest('hex')}`) throw Error('Invalid request');
  const headers = { Accept:'application/json', 'X-M2M-RVI':'4', 'X-M2M-Origin':aeId, 'X-M2M-RI':req.rqi,
    'Content-Type':'application/json;ty=4' };
  const created = await fetchImpl(`${origin}${target}`, {method:'POST', headers, body:JSON.stringify({'m2m:cin':cin}), signal:AbortSignal.timeout(5000), redirect:'error'});
  if (created.status !== 201 || created.headers.get('x-m2m-rsc') !== '2001' || created.headers.get('x-m2m-ri') !== req.rqi) throw Error('CSE create not verified');
  if (created.body) await created.body.cancel();
  const readId = `app-read-${randomUUID()}`;
  const retrieved = await fetchImpl(`${origin}${target}/${cin.rn}`, {headers:{Accept:'application/json','X-M2M-RVI':'4','X-M2M-Origin':aeId,'X-M2M-RI':readId}, signal:AbortSignal.timeout(5000), redirect:'error'});
  if (retrieved.status !== 200 || retrieved.headers.get('x-m2m-rsc') !== '2000' || retrieved.headers.get('x-m2m-ri') !== readId) throw Error('CSE readback not verified');
  const data = await jsonBounded(retrieved);
  if (data?.['m2m:cin']?.rn !== cin.rn || !isDeepStrictEqual(data?.['m2m:cin']?.con, cin.con)) throw Error('CSE content mismatch');
  return {reply:{rqi:req.rqi, rsc:2001, fr:'Mobius4', to:aeId},
    evidence:{createHttp:201,createRsc:2001,createRiMatched:true,readHttp:200,readRsc:2000,readRiMatched:true,contentMatched:true}};
}
