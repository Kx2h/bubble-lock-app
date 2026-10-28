"""Run the pinned gateway's real observation, outbox and mTLS MQTT transport.

This invokes only the selected gateway methods, not the full daemon or BLE.
All inputs, credentials and the database are temporary synthetic test data.
"""
import asyncio
import json
import sys
from collections import Counter
from pathlib import Path
from types import SimpleNamespace

source, directory = map(lambda p: Path(p).resolve(), sys.argv[1:3])
ae_name, ae_id, broker_port = sys.argv[3], sys.argv[4], int(sys.argv[5])
sys.path.insert(0, str(source / 'keyring/raspberry-pi-gateway/src'))
from bubble_gateway.service import Gateway
from bubble_gateway.state_identity import configured_binding
from bubble_gateway.store import Store
from bubble_gateway.transport import TransportHub

config = SimpleNamespace(
    gateway_id='app-isolated', originator=ae_id, cse_id='Mobius4', rvi='4',
    observation_container=f'/Mobius/{ae_name}/observations',
    receipt_container=f'/Mobius/{ae_name}/receipts',
    health_container=f'/Mobius/{ae_name}/health',
    api_url='https://127.0.0.1:1', cse_url='https://127.0.0.1:1',
    ca_file=directory/'ca.pem', cert_file=directory/'client.pem', key_file=directory/'client.key',
    mqtt_enabled=True, mqtt_host='127.0.0.1', mqtt_port=broker_port,
    mqtt_keepalive=10, mqtt_wrapped_primitive=False, request_timeout=1.0,
    core_bridge_enabled=True, ble_devices={'synthetic-keyring': 'synthetic'},
)
observation = {
    'version': 1, 'gatewayId': 'app-isolated', 'deviceId': 'synthetic-keyring',
    'bootId': 'synthetic-boot', 'sequence': 1,
    'observedAt': '2026-01-01T00:00:00Z', 'receivedAt': '2026-01-01T00:00:00Z',
    'rssi': -50, 'battery': 90.0, 'buttonState': 'released', 'sensorValues': {},
    'state': True, 'firmwareRevision': 'synthetic-0', 'timestampSource': 'gateway-received',
}

async def until(predicate, seconds=15):
    async with asyncio.timeout(seconds):
        while not predicate():
            await asyncio.sleep(0.025)

async def main():
    gateway = Gateway.__new__(Gateway)
    gateway.config = config
    gateway.store = Store(directory/'outbox.sqlite', 100, 1000000, 100, configured_binding(config))
    gateway.transport = TransportHub(config)
    gateway.stop = asyncio.Event()
    gateway.counters = Counter()
    task = None
    try:
        await gateway.observation(observation)
        rows = lambda: list(gateway.store.db.execute('SELECT container,attempts FROM outbox'))
        assert len(rows()) == 2
        # Both entries must remain durable when no transport session is ready.
        task = asyncio.create_task(gateway._outbox_loop())
        await until(lambda: all(row['attempts'] >= 1 for row in rows()) and len(rows()) == 2)
        gateway.stop.set()
        await asyncio.wait_for(task, 5)
        task = None
        gateway.stop = asyncio.Event()
        await gateway.transport.start()
        await asyncio.wait_for(gateway.transport._ready.wait(), 8)
        task = asyncio.create_task(gateway._outbox_loop())
        await until(lambda: all(row['container'] != '@core/telemetry' for row in rows()))
        # The unrelated oneM2M observation remains pending: the test has no
        # subscription response or HTTPS endpoint for that container.
        pending = rows()
        assert len(pending) == 1 and pending[0]['container'] == config.observation_container
        assert pending[0]['attempts'] >= 1
        gateway.stop.set()
        task.cancel()
        try: await task
        except asyncio.CancelledError: pass
        task = None
        print(json.dumps({'offlineRetained': True, 'corePubackRemoved': True,
                          'otherObservationPending': True, 'observationsQueued': gateway.counters['observations_queued']}))
    finally:
        if task is not None:
            task.cancel()
            try: await task
            except asyncio.CancelledError: pass
        await gateway.transport.close()
        gateway.store.close()

asyncio.run(main())
