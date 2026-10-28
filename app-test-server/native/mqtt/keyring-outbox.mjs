import tls from 'node:tls';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { access, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { once } from 'node:events';
import { setTimeout as delay } from 'node:timers/promises';
import { Aedes } from 'aedes';
import mqtt from 'mqtt';
import { GatewayBridge } from '../../../pc-mobius/src/gateway-bridge.mjs';
import { createVerifiedObservation } from './observation-bridge.mjs';
import { createVerifiedReceipt } from './receipt-bridge.mjs';

const exec = promisify(execFile);
const temporaryRoot = fileURLToPath(new URL('../../../app/build/', import.meta.url));
const python = fileURLToPath(new URL('../../../../../.local/keyring-gateway-venv/bin/python', import.meta.url));
const helper = fileURLToPath(new URL('../keyring-outbox-check.py', import.meta.url));

export async function checkOutboxDependencies() { await access(python); }

/** Real KEYRING outbox/transport and CORE on an ephemeral mTLS broker. */
export async function runKeyringOutbox({ origin, aeId, aeName, source, verifyObservation = false, verifyReceipt = false }) {
  if (verifyReceipt && !verifyObservation) throw Error('Receipt outbox check requires observation confirmation');
  await mkdir(temporaryRoot, { recursive: true });
  const dir = await mkdtemp(join(temporaryRoot, 'keyring-outbox-'));
  let broker, server, subscriber;
  const p = name => join(dir, name);
  const run = async args => exec('openssl', args, { timeout: 10000, maxBuffer: 65536 });
  try {
    // Dedicated short-lived fixture certificates; nothing enters a device trust store.
    await run(['req','-x509','-newkey','rsa:2048','-nodes','-days','1','-subj','/CN=APP local CA','-keyout',p('ca.key'),'-out',p('ca.pem')]);
    await run(['req','-newkey','rsa:2048','-nodes','-subj','/CN=localhost','-keyout',p('server.key'),'-out',p('server.csr')]);
    await writeFile(p('server.ext'), 'subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n');
    await run(['x509','-req','-in',p('server.csr'),'-CA',p('ca.pem'),'-CAkey',p('ca.key'),'-CAcreateserial','-days','1','-extfile',p('server.ext'),'-out',p('server.pem')]);
    await run(['req','-newkey','rsa:2048','-nodes','-subj','/CN=APP fixture client','-keyout',p('client.key'),'-out',p('client.csr')]);
    await writeFile(p('client.ext'), 'extendedKeyUsage=clientAuth\n');
    await run(['x509','-req','-in',p('client.csr'),'-CA',p('ca.pem'),'-CAkey',p('ca.key'),'-CAcreateserial','-days','1','-extfile',p('client.ext'),'-out',p('client.pem')]);
    const [ca, cert, key, clientCert, clientKey] = await Promise.all(['ca.pem','server.pem','server.key','client.pem','client.key'].map(name => readFile(p(name))));
    broker = await Aedes.createBroker();
    server = tls.createServer({ca, cert, key, requestCert:true, rejectUnauthorized:true, minVersion:'TLSv1.3'}, broker.handle);
    let ingress = 0, subscribed = false, mqttError, authorizedTls13 = 0, observationVerified = 0, observationEvidence;
    let receiptVerified = 0, receiptEvidence, commandIngress = 0;
    server.on('secureConnection', socket => { if (socket.authorized && socket.getProtocol() === 'TLSv1.3') authorizedTls13++; });
    broker.on('subscribe', (subs, client) => {
      if (client?.id === 'app-core-outbox' && subs.some(sub => sub.topic === 'privacy-bubble/gateway/app-isolated/telemetry' && sub.qos === 1)) subscribed = true;
    });
    broker.on('publish', (packet, client) => {
      if (client?.id === 'pb-gateway-app-isolated' && packet.topic === 'privacy-bubble/gateway/app-isolated/telemetry' && packet.qos === 1) ingress++;
      if (packet.topic === 'privacy-bubble/gateway/app-isolated/command') commandIngress++;
    });
    await new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve));
    const port = server.address().port;
    // Confirm that the broker rejects a connection without a client certificate.
    const rejected = once(server, 'tlsClientError', {signal:AbortSignal.timeout(8000)});
    const noCert = tls.connect({host:'127.0.0.1', port, ca, servername:'localhost', minVersion:'TLSv1.3', rejectUnauthorized:true});
    noCert.on('error', () => {});
    try { await rejected; } finally { noCert.destroy(); }
    subscriber = mqtt.connect(`mqtts://127.0.0.1:${port}`, {ca, cert:clientCert, key:clientKey, servername:'localhost', rejectUnauthorized:true, minVersion:'TLSv1.3', clientId:'app-core-outbox', reconnectPeriod:0});
    subscriber.on('error', error => { mqttError = error; });
    await once(subscriber, 'connect', {signal:AbortSignal.timeout(8000)});
    if (verifyObservation) {
      const requestTopic = `/oneM2M/req/${aeId}/Mobius4/json`;
      const responseTopic = `/oneM2M/resp/${aeId}/Mobius4/json`;
      const pending = new Set();
      subscriber.on('message', (topic, payload) => {
        if (topic !== requestTopic) return;
        const wire = payload.toString('utf8');
        let target;
        try { target = JSON.parse(wire)?.to; } catch { return; }
        const observationTarget = `/Mobius/${aeName}/observations`, receiptTarget = `/Mobius/${aeName}/receipts`;
        const isReceipt = verifyReceipt && target === receiptTarget;
        if ((!isReceipt && target !== observationTarget) || pending.has(target)
            || (isReceipt ? receiptVerified : observationVerified)) return;
        pending.add(target);
        (isReceipt ? createVerifiedReceipt : createVerifiedObservation)({origin, aeId, aeName, wire})
          .then(async result => {
            await subscriber.publishAsync(responseTopic, JSON.stringify(result.reply), {qos:1, retain:false});
            if (isReceipt) { receiptEvidence = result.evidence; receiptVerified++; }
            else { observationEvidence = result.evidence; observationVerified++; }
          }).catch(() => { /* No success response when CSE confirmation is unavailable. */ })
          .finally(() => { pending.delete(target); });
      });
      await subscriber.subscribeAsync(requestTopic, {qos:1});
    }
    const updates = [];
    const bridge = new GatewayBridge({mqttClient:subscriber, baseUrl:origin, originator:aeId,
      resourcePath:`/Mobius/${aeName}/bubbleState`, gatewayId:'app-isolated',
      fetchImpl:async (url, options) => {
        const response = await fetch(url, {...options, signal:AbortSignal.timeout(8000), redirect:'error'});
        updates.push({status:response.status,rsc:response.headers.get('x-m2m-rsc'),correlated:response.headers.get('x-m2m-ri') === options.headers['X-M2M-RI']});
        return response;
      }});
    await bridge.start();
    const subscribedUntil = performance.now() + 8000;
    while (!subscribed) {
      if (mqttError) throw mqttError;
      if (performance.now() >= subscribedUntil) throw Error('CORE subscription timeout');
      await delay(25);
    }
    const result = await exec(python, ['-I','-B',helper,source,dir,aeName,aeId,String(port), ...(verifyReceipt ? ['verified-receipt'] : verifyObservation ? ['verified-observation'] : [])], {timeout:30000,maxBuffer:65536});
    const outbox = JSON.parse(result.stdout);
    const until = performance.now() + 8000;
    while (bridge.receipts.length === 0 && performance.now() < until) await delay(25);
    if (ingress !== 1 || authorizedTls13 < 2 || bridge.receipts.length !== 1 || bridge.receipts[0].status !== 'APPLIED' || updates.length !== 1
        || (verifyObservation && observationVerified !== 1) || (verifyReceipt && (receiptVerified !== 1 || commandIngress !== 0))) throw Error('Gateway delivery did not reach CORE');
    return {receipt:bridge.receipts[0], updates, outbox:{...outbox, brokerIngress:ingress, mtls:true, missingClientCertRejected:true,
      ...(verifyObservation ? {observationReadbackVerified:true,observationEvidence} : {}),
      ...(verifyReceipt ? {receiptReadbackVerified:true,receiptEvidence,commandIngress} : {})}};
  } finally {
    if (subscriber) await subscriber.endAsync(true);
    if (broker) await new Promise(resolve => broker.close(resolve));
    if (server?.listening) await new Promise(resolve => server.close(resolve));
    await rm(dir, {recursive:true, force:true});
  }
}
