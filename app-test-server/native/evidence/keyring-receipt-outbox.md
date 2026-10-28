# KEYRING receipt outbox host integration

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10. Checked on 2026-10-29 (Asia/Seoul) using the pinned KEYRING commit `d233c0f7c445be7b7505a69582a8fb0f471c57c2`, its actual SQLite journal/outbox and MQTT transport, a temporary loopback TLS 1.3 mTLS broker, actual CORE `GatewayBridge`, and the existing loopback Mobius4 CSE. All inputs and certificates were synthetic. This record covers host execution, not Android UI or a physical device.

```sh
"$WORKSPACE/.local/node/bin/node" app-test-server/native/core-app-bridge-check.mjs --mqtt --keyring-outbox --observation-cin --receipt-outbox
```

The run created only `app-bridge-a51a3e46b26a4469` and its test children. The test recorded a fixed terminal `INDETERMINATE` result through `Store.accept` and `Gateway._finish`; no dispatch, authorization, BLE or device-command method was invoked. The real `_outbox_loop` staged the receipt. Before transport was ready, all three queued items remained pending after failed attempts. Closing and reopening SQLite preserved the receipt journal and pending rows with `receipt_delivered=0`.

After the real transport connected to the mTLS broker, the test adapter accepted only the fixed synthetic receipt request. CSE CREATE returned HTTP 201 / RSC 2001 with a matching request ID; an independent RETRIEVE returned HTTP 200 / RSC 2000 with its own matching request ID and equal CIN name/content. Only then did the adapter send the matching MQTT success response. The team outbox loop removed the receipt row and marked the still-preserved journal `receipt_delivered=1`. Broker command-topic ingress was zero. The APP test adapter then independently read the exact outbox-generated CIN name and returned the synthetic receipt with HTTP 200.

CORE telemetry and the original observation path also completed their independent checks. Cleanup returned DELETE HTTP 200 / RSC 2002 followed by GET HTTP 404 / RSC 4004, each with matching request ID. The temporary broker, clients, SQLite and test certificates were cleaned up; the bootstrap resource, phone mapping and device trust stores were not modified.

Host regressions cover wrong create/readback RSC or request ID, unequal content, and non-synthetic request identity without a success reply. They also cover the original direct receipt mode and exact source-specific adapter names. This does not establish production principal/ACP, command authorization, real device execution, physical vibration, or recovery from a lost response and subsequent CIN name conflict.
