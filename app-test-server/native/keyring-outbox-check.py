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
verify_observation = len(sys.argv) > 6 and sys.argv[6] in {'verified-observation', 'verified-receipt'}
verify_receipt = len(sys.argv) > 6 and sys.argv[6] == 'verified-receipt'
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
    mqtt_keepalive=10, mqtt_wrapped_primitive=False, request_timeout=4.0 if verify_observation else 1.0,
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
        if verify_receipt:
            # Record a fixed terminal synthetic result without calling dispatch,
            # authorization, BLE or any command transport. The real outbox loop
            # stages this journal result through Store.stage_receipts.
            command = {'deviceId': 'synthetic-keyring', 'commandId': 'synthetic-command-1',
                       'ownershipEpoch': 1, 'grantRevision': 1}
            assert gateway.store.accept(command, '0' * 64) == 'NEW'
            gateway._finish(command, '0' * 64, 'INDETERMINATE', 'INVALID_RECEIPT',
                            'Synthetic test; no device command was sent')
        # All entries must remain durable when no transport session is ready.
        task = asyncio.create_task(gateway._outbox_loop())
        expected = 3 if verify_receipt else 2
        await until(lambda: all(row['attempts'] >= 1 for row in rows()) and len(rows()) == expected)
        gateway.stop.set()
        await asyncio.wait_for(task, 5)
        task = None
        if verify_receipt:
            gateway.store.close()
            gateway.store = Store(directory/'outbox.sqlite', 100, 1000000, 100, configured_binding(config))
            journal = gateway.store.db.execute('SELECT status,receipt_delivered FROM commands WHERE command_id=?',
                                              ('synthetic-command-1',)).fetchone()
            assert journal['status'] == 'INDETERMINATE' and journal['receipt_delivered'] == 0
            assert len(rows()) == 3 and all(row['attempts'] >= 1 for row in rows())
        gateway.stop = asyncio.Event()
        await gateway.transport.start()
        await asyncio.wait_for(gateway.transport._ready.wait(), 8)
        task = asyncio.create_task(gateway._outbox_loop())
        await until(lambda: all(row['container'] != '@core/telemetry' for row in rows()))
        # The unrelated oneM2M observation remains pending: the test has no
        # subscription response or HTTPS endpoint for that container.
        if verify_observation:
            await until(lambda: not rows())
        else:
            pending = rows()
            assert len(pending) == 1 and pending[0]['container'] == config.observation_container
            assert pending[0]['attempts'] >= 1
        gateway.stop.set()
        task.cancel()
        try: await task
        except asyncio.CancelledError: pass
        task = None
        summary = {'offlineRetained': True, 'corePubackRemoved': True,
                   'otherObservationPending': not verify_observation,
                   'observationsQueued': gateway.counters['observations_queued']}
        if verify_observation:
            summary['observationSettled'] = True
        if verify_receipt:
            journal = gateway.store.db.execute('SELECT status,receipt,receipt_delivered FROM commands WHERE command_id=?',
                                              ('synthetic-command-1',)).fetchone()
            assert journal['status'] == 'INDETERMINATE' and journal['receipt_delivered'] == 1
            assert json.loads(journal['receipt'])['commandId'] == 'synthetic-command-1'
            assert not gateway.store.pending() and not gateway.store.interrupted()
            summary.update(receiptOfflineRetained=True, receiptJournalPreserved=True,
                           receiptSettled=True, receiptDeliveryRecorded=True, commandDispatched=False)
        print(json.dumps(summary))
    finally:
        if task is not None:
            task.cancel()
            try: await task
            except asyncio.CancelledError: pass
        await gateway.transport.close()
        gateway.store.close()

asyncio.run(main())
