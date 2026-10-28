import { randomUUID } from 'node:crypto';
import { GatewayBridge } from '../../pc-mobius/src/gateway-bridge.mjs';
import { createServer } from '../server.mjs';

if (process.argv.slice(2).some(arg => !['--phone', '--mqtt', '--mqtt-reconnect', '--keyring', '--keyring-outbox', '--observation-cin'].includes(arg)) || new Set(process.argv.slice(2)).size !== process.argv.slice(2).length || ((process.argv.includes('--mqtt-reconnect') || process.argv.includes('--keyring') || process.argv.includes('--keyring-outbox')) && !process.argv.includes('--mqtt')) || ['--mqtt-reconnect', '--keyring', '--keyring-outbox'].filter(arg => process.argv.includes(arg)).length > 1 || (process.argv.includes('--observation-cin') && !process.argv.includes('--keyring-outbox'))) throw Error('Usage: node core-app-bridge-check.mjs [--phone] [--mqtt [--mqtt-reconnect | --keyring | --keyring-outbox [--observation-cin]]]');
const phone = process.argv.includes('--phone');
const useMqtt = process.argv.includes('--mqtt');
const reconnect = process.argv.includes('--mqtt-reconnect');
const useKeyring = process.argv.includes('--keyring');
const useOutbox = process.argv.includes('--keyring-outbox');
const verifyObservation = process.argv.includes('--observation-cin');
const cse = new URL(process.env.APP_CORE_TEST_CSE_URL ?? 'http://127.0.0.1:50545/Mobius');
if (cse.protocol !== 'http:' || cse.hostname !== '127.0.0.1' || !cse.port || cse.pathname !== '/Mobius'
    || cse.username || cse.password || cse.search || cse.hash) throw Error('Test CSE must be HTTP loopback with an explicit port and /Mobius path');
const base = cse.href;
const aeName = `app-bridge-${randomUUID().replaceAll('-', '').slice(0, 16)}`;
const resource = `${base}/${aeName}`;
let aeId;
let failed = false;
let creationAttempted = false;
const results = [];
async function req(method, path, origin, body, ty) {
  const id = `app-bridge-${randomUUID()}`;
  const headers = { Accept: 'application/json', 'X-M2M-RVI': '4', 'X-M2M-Origin': origin, 'X-M2M-RI': id };
  if (body) headers['Content-Type'] = ty ? `application/json;ty=${ty}` : 'application/json';
  const res = await fetch(path, { method, headers, ...(body ? {body: JSON.stringify(body)} : {}), signal: AbortSignal.timeout(8000), redirect: 'error' });
  const text = await res.text(); let parsed;
  try { parsed = JSON.parse(text); } catch { parsed = null; }
  const item = { method, status: res.status, rsc: res.headers.get('x-m2m-rsc'), correlated: res.headers.get('x-m2m-ri') === id };
  results.push(item);
  return { ...item, parsed };
}
function ok(res, status, rsc) { if (res.status !== status || res.rsc !== rsc || !res.correlated) throw Error('Unexpected oneM2M result'); }
let proxy;
try {
  const keyring = useKeyring || useOutbox ? await import('./keyring-projection.mjs') : null;
  const keyringSource = keyring ? await keyring.checkKeyringSource() : null;
  const outboxModule = useOutbox ? await import('./mqtt/keyring-outbox.mjs') : null;
  if (outboxModule) await outboxModule.checkOutboxDependencies();
  console.log(JSON.stringify({testAeName:aeName, note:'Retain this name if the create result is uncertain.'}));
  creationAttempted = true;
  const created = await req('POST', base, '', {'m2m:ae': {rn:aeName,api:'Nprivacy-bubble.app-isolated',rr:true}}, 2);
  ok(created,201,'2001');
  const candidateId = created.parsed?.['m2m:ae']?.aei;
  if (typeof candidateId !== 'string' || !/^C[A-Za-z0-9_-]{1,128}$/.test(candidateId)
      || created.parsed?.['m2m:ae']?.rn !== aeName || created.parsed?.['m2m:ae']?.ri !== candidateId) throw Error('AE ownership not established');
  aeId = candidateId;
  const child = await req('POST', resource, aeId, {'pb:binarySwitch': {rn:'bubbleState',cnd:'org.onem2m.common.moduleclass.binarySwitch',state:false,lbl:['dev-bootstrap','unobserved']}}, 28);
  ok(child,201,'2001');
  if (verifyObservation) {
    const container = await req('POST', resource, aeId, {'m2m:cnt':{rn:'observations'}}, 3);
    ok(container,201,'2001');
  }
  const before = await req('GET', `${resource}/bubbleState`, aeId);
  ok(before,200,'2000');
  if (before.parsed?.['pb:binarySwitch']?.state !== false) throw Error('Unexpected initial state');
  const projection = useKeyring ? await keyring.keyringProjection(keyringSource, aeName, aeId) : null;
  const telemetry = projection ? projection.telemetry : {deviceId:'synthetic-keyring',sequence:1,timestamp:new Date().toISOString(),state:true,batteryPct:90,rssiDbm:-50,schemaVersion:'1.0.0'};
  let receipt, updates, mqttEvidence, outboxEvidence;
  if (useOutbox) {
    ({receipt, updates, outbox:outboxEvidence} = await outboxModule.runKeyringOutbox({origin:cse.origin,aeId,aeName,source:keyringSource,verifyObservation}));
  } else if (useMqtt) {
    const { runMqttUpdate } = await import('./mqtt/run.mjs');
    ({receipt, updates, ...mqttEvidence} = await runMqttUpdate({origin:cse.origin,aeId,aeName,telemetry,reconnect}));
  } else {
    const mqttClient = {async subscribe(){},async publish(){},on(){}};
    updates = [];
    const bridge = new GatewayBridge({mqttClient,baseUrl:cse.origin,originator:aeId,resourcePath:`/Mobius/${aeName}/bubbleState`,gatewayId:'app-isolated',fetchImpl:async (url, options) => {
      const response = await fetch(url, {...options, signal:AbortSignal.timeout(8000), redirect:'error'});
      updates.push({status:response.status,rsc:response.headers.get('x-m2m-rsc'),correlated:response.headers.get('x-m2m-ri') === options.headers['X-M2M-RI']});
      return response;
    }});
    receipt = await bridge.handleTelemetry(telemetry);
  }
  if (receipt.status !== 'APPLIED' || updates.length !== (reconnect ? 2 : 1) || updates.some(update => update.status !== 200 || update.rsc !== '2004' || !update.correlated)) throw Error('Bridge update not independently verified');
  const after = await req('GET', `${resource}/bubbleState`, aeId);
  ok(after,200,'2000');
  const expectedState = !reconnect;
  if (after.parsed?.['pb:binarySwitch']?.state !== expectedState) throw Error('Updated state absent');
  proxy = createServer({mobiusUrl:base,originator:aeId,testAeName:aeName});
  await new Promise((resolve,reject)=>proxy.once('error',reject).listen(phone ? 8787 : 0,'127.0.0.1',resolve));
  const answer = await fetch(`http://127.0.0.1:${proxy.address().port}/api/test/bubble-state`);
  const value = await answer.json();
  if (answer.status !== 200 || value.state !== expectedState || value.observation !== 'unknown') throw Error('APP adapter did not observe the CSE state');
  results.push({bridge:receipt.status, update:updates[0], ...(reconnect ? {reconnectUpdate:updates[1]} : {}), stateAfter:expectedState, bootstrapLabelPresent:value.bootstrapLabelPresent, observation:value.observation, adapterStatus:answer.status, ...(mqttEvidence ? {mqtt:mqttEvidence} : {}), ...(outboxEvidence ? {outbox:outboxEvidence} : {}), ...(projection ? {keyring:{nullMeasurementsSkipped:projection.nullMeasurementsSkipped,duplicateSkipped:projection.duplicateSkipped,reopenPreserved:projection.reopenPreserved,wireSequences:projection.wireSequences}} : {})});
  if (phone) {
    console.log(JSON.stringify({ready:true, aeName, port:8787, note:'Press Enter to clean up the test AE.'}));
    await new Promise(resolve=>process.stdin.once('data',resolve));
    process.stdin.pause();
  }
} catch (error) {
  failed = true;
  results.push({error:error.message});
} finally {
  if (proxy?.listening) await new Promise(resolve=>proxy.close(resolve));
  if (aeId) {
    try {
      const found = await req('GET', resource, aeId);
      ok(found,200,'2000');
      if (found.parsed?.['m2m:ae']?.rn !== aeName || found.parsed?.['m2m:ae']?.ri !== aeId) throw Error('AE identity changed; not deleting');
      const removed = await req('DELETE', resource, aeId);
      ok(removed,200,'2002');
      const gone = await req('GET', resource, aeId);
      ok(gone,404,'4004');
      results.push({cleanup:'verified'});
    } catch (error) { failed = true; results.push({cleanup:'manual-review',error:error.message}); }
  } else if (creationAttempted) {
    failed = true;
    results.push({cleanup:'manual-review',error:'AE creation outcome or ownership uncertain; no deletion attempted'});
  }
  console.log(JSON.stringify({aeName,results}));
  if (failed) process.exitCode = 1;
}
