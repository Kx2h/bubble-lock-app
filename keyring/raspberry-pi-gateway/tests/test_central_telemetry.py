import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import AsyncMock

from bubble_gateway.store import Store
from bubble_gateway.transport import TransportHub, RemoteResponseError
from bubble_gateway.model import central_observation_id
from helpers import BINDING, config, observation


class CentralTelemetryTests(unittest.IsolatedAsyncioTestCase):
    async def test_native_and_central_routes_share_durable_observation_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "telemetry.sqlite3"
            store = Store(path, 100, 100000, 20, BINDING)
            value = observation(sensorValues={"location": {"lat": 37.5, "lon": 127.0, "source": "gps", "accuracyM": 8}})
            self.assertTrue(store.observation(BINDING["observationContainer"], value, central_enabled=True))
            self.assertFalse(store.observation(BINDING["observationContainer"], value, central_enabled=True))
            store.close()
            restored = Store(path, 100, 100000, 20, BINDING)
            try:
                rows = restored.db.execute("SELECT rqi,container,payload FROM outbox").fetchall()
                self.assertEqual(len(rows), 2)
                self.assertEqual({row["container"] for row in rows}, {BINDING["observationContainer"], "@central/telemetry"})
                self.assertEqual([json.loads(row["payload"]) for row in rows], [value, value])
                identity = central_observation_id(value)
                rqi = Store.request_id("central-telemetry", identity)
                self.assertEqual(next(row["rqi"] for row in rows if row["container"] == "@central/telemetry"), rqi)
                hub = object.__new__(TransportHub)
                hub.config = config()
                hub.config.central_telemetry_enabled = True
                hub._api = AsyncMock(return_value={"status": "ACCEPTED", "deviceId": value["deviceId"], "observationId": identity})
                await hub.publish(rqi, "@central/telemetry", value)
                hub._api.assert_awaited_once_with("POST", "/telemetry", value, rqi, idempotency_key=rqi)
                hub._api.return_value = {"status": "ACCEPTED", "deviceId": "another-device"}
                with self.assertRaises(RemoteResponseError):
                    await hub.publish(rqi, "@central/telemetry", value)
            finally:
                restored.close()


if __name__ == "__main__":
    unittest.main()
