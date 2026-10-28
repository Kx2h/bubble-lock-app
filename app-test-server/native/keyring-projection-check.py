"""Exercise the pinned KEYRING observation validator and persistent CORE outbox.

The inputs are synthetic. This is not the Gateway daemon, BLE or a device test.
The caller creates and removes a fresh test directory under the workspace.
"""
import json
import sys
from pathlib import Path

source = Path(sys.argv[1]).resolve()
database = Path(sys.argv[2]).resolve()
ae_name, ae_id = sys.argv[3:5]
sys.path.insert(0, str(source / "keyring/raspberry-pi-gateway/src"))

from bubble_gateway.model import validate_observation
from bubble_gateway.store import Store

binding = {
    "bindingVersion": 1, "gatewayId": "app-isolated", "originator": ae_id,
    "cseId": "Mobius4", "rvi": "4",
    "observationContainer": f"/Mobius/{ae_name}/observations",
    "receiptContainer": f"/Mobius/{ae_name}/receipts",
    "healthContainer": f"/Mobius/{ae_name}/health",
}
base = {
    "version": 1, "gatewayId": "app-isolated", "deviceId": "synthetic-keyring",
    "bootId": "synthetic-boot", "sequence": 0,
    "observedAt": "2026-01-01T00:00:00Z", "receivedAt": "2026-01-01T00:00:00Z",
    "rssi": -50, "battery": 90.0, "buttonState": "released", "sensorValues": {},
    "state": True, "firmwareRevision": "synthetic-0", "timestampSource": "gateway-received",
}

def rows(store):
    return list(store.db.execute("SELECT container,payload FROM outbox ORDER BY rqi"))

def enqueue(store, sequence, battery, rssi):
    observation = dict(base, sequence=sequence, battery=battery, rssi=rssi)
    validated = validate_observation(observation, "app-isolated", {"synthetic-keyring": "synthetic"})
    assert store.observation(binding["observationContainer"], validated, True)
    return validated

store = Store(database, 100, 1000000, 100, binding)
try:
    enqueue(store, 0, None, -50)
    enqueue(store, 1, 90.0, None)
    assert len(rows(store)) == 2 and all(row["container"] != "@core/telemetry" for row in rows(store))
    observation = enqueue(store, 2, 90.0, -50)
    assert not store.observation(binding["observationContainer"], observation, True)
    assert len(rows(store)) == 4
finally:
    store.close()

# Reopen the same database to verify the pending projection and sequence survive.
store = Store(database, 100, 1000000, 100, binding)
try:
    pending = rows(store)
    projected = [json.loads(row["payload"]) for row in pending if row["container"] == "@core/telemetry"]
    assert len(projected) == 1 and len(pending) == 4
    payload = projected[0]
    assert payload["deviceId"] == "synthetic-keyring" and payload["gatewayId"] == "app-isolated"
    assert payload["sequence"] == 0 and payload["firmwareSequence"] == 2
    assert payload["batteryPct"] == 90 and payload["rssiDbm"] == -50 and payload["state"] is True
    # A new observation gets the next persisted CORE wire sequence.
    enqueue(store, 3, 90.0, -50)
    projected2 = [json.loads(row["payload"]) for row in rows(store) if row["container"] == "@core/telemetry"]
    assert sorted(p["sequence"] for p in projected2) == [0, 1]
    print(json.dumps({"telemetry": payload, "nullMeasurementsSkipped": True,
                      "duplicateSkipped": True, "reopenPreserved": True,
                      "wireSequences": [0, 1]}))
finally:
    store.close()
