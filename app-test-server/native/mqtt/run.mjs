import net from 'node:net';
import { setTimeout as delay } from 'node:timers/promises';
import { once } from 'node:events';
import { Aedes } from 'aedes';
import mqtt from 'mqtt';
import { GatewayBridge } from '../../../pc-mobius/src/gateway-bridge.mjs';

// Real MQTT 3.1.1 broker and clients, bound only to an ephemeral loopback port.
// Inputs remain synthetic and the MQTT hop is not authenticated or encrypted.
export async function runMqttUpdate({ origin, aeId, aeName, telemetry }) {
  const broker = await Aedes.createBroker();
  const server = net.createServer(broker.handle);
  let subscriber, publisher;
  let mqttError;
  const updates = [], subscriptions = [], messages = [], deliveries = [];
  const onSubscribe = (subs, client) => {
    if (client?.id === 'app-core-bridge') subscriptions.push(...subs.map(({ topic, qos }) => ({ topic, qos })));
  };
  broker.on('subscribe', onSubscribe);
  broker.on('publish', (packet, client) => {
    if (client?.id === 'app-test-gateway') messages.push({ topic: packet.topic, qos: packet.qos, retain: packet.retain });
  });
  try {
    await new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve));
    const url = `mqtt://127.0.0.1:${server.address().port}`;
    subscriber = mqtt.connect(url, { clientId: 'app-core-bridge', protocolVersion: 4, clean: true, reconnectPeriod: 0 });
    subscriber.on('error', error => { mqttError = error; });
    subscriber.on('message', (topic, _payload, packet) => deliveries.push({ topic, qos: packet.qos }));
    await once(subscriber, 'connect', { signal: AbortSignal.timeout(8000) });
    const bridge = new GatewayBridge({ mqttClient: subscriber, baseUrl: origin, originator: aeId,
      resourcePath: `/Mobius/${aeName}/bubbleState`, gatewayId: 'app-isolated',
      fetchImpl: async (url, options) => {
        const response = await fetch(url, { ...options, signal: AbortSignal.timeout(8000), redirect: 'error' });
        updates.push({ status: response.status, rsc: response.headers.get('x-m2m-rsc'), correlated: response.headers.get('x-m2m-ri') === options.headers['X-M2M-RI'] });
        return response;
      } });
    await bridge.start();
    const subscribedUntil = Date.now() + 8000;
    while (!subscriptions.some(sub => sub.topic === bridge.telemetryTopic && sub.qos === 1)) {
      if (mqttError) throw mqttError;
      if (Date.now() >= subscribedUntil) throw Error('MQTT telemetry subscription timeout');
      await delay(25);
    }
    publisher = mqtt.connect(url, { clientId: 'app-test-gateway', protocolVersion: 4, clean: true, reconnectPeriod: 0 });
    publisher.on('error', error => { mqttError = error; });
    await once(publisher, 'connect', { signal: AbortSignal.timeout(8000) });
    await publisher.publishAsync(bridge.telemetryTopic, JSON.stringify(telemetry), { qos: 1, retain: false });
    const receiptUntil = Date.now() + 12000;
    while (bridge.receipts.length === 0) {
      if (mqttError) throw mqttError;
      if (Date.now() >= receiptUntil) throw Error('CORE bridge receipt timeout');
      await delay(25);
    }
    if (!messages.some(message => message.topic === bridge.telemetryTopic && message.qos === 1 && message.retain === false)
        || !deliveries.some(message => message.topic === bridge.telemetryTopic && message.qos === 1)) throw Error('MQTT QoS 1 delivery not observed');
    if (bridge.receipts.length !== 1) throw Error('Unexpected bridge receipt count');
    await publisher.publishAsync(bridge.telemetryTopic, JSON.stringify(telemetry), { qos: 1, retain: false });
    const duplicateUntil = Date.now() + 8000;
    while (bridge.receipts.length < 2) {
      if (mqttError) throw mqttError;
      if (Date.now() >= duplicateUntil) throw Error('Duplicate telemetry receipt timeout');
      await delay(25);
    }
    if (bridge.receipts.length !== 2 || bridge.receipts[1].status !== 'DUPLICATE' || updates.length !== 1
        || deliveries.filter(message => message.topic === bridge.telemetryTopic && message.qos === 1).length !== 2) throw Error('Duplicate telemetry was not safely deduplicated');
    return { receipt: bridge.receipts[0], updates, topic: bridge.telemetryTopic, qos: 1, retained: false, duplicate: 'DUPLICATE', updateCount: updates.length };
  } finally {
    if (publisher) await publisher.endAsync(true);
    if (subscriber) await subscriber.endAsync(true);
    await new Promise(resolve => broker.close(resolve));
    if (server.listening) await new Promise(resolve => server.close(resolve));
  }
}
