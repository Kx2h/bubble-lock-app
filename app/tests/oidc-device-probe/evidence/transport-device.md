# Android synthetic HTTPS transport check

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R10. 2026-10-28 on the Android 14 spare phone, the separate debug probe used an app-scoped test CA; the device and browser trust stores were not modified. No real account, token, server API or permission was used.

The final `HttpsApiTransport.java` and the source staged into the probe each had SHA-256 `63e1f87f789d81ff5a79e6990ab7d712924fa6d0052b0b3c4af930b98bb9a1f8`. The probe APK SHA-256 was `9e0621d6d106df1c05020317381be1991ac62d611c6dfac1db4e2d209d33590b`. The final version includes the additional rejection of `%25` in paths.

Against three loopback HTTPS fixtures, the probe rejected three double-encoded traversal or separator paths, observed TLS 1.3 success, HTTP 201 and 403 responses, no redirect follow, an IOException after the server consumed a synthetic POST and dropped its response, and a fixture count of one POST for that lost response. It rejected a TLS 1.2-only server and a server certificate signed by the same test CA but issued for a different DNS name. [Screen](transport-probe.png). The lost POST result remains unknown to the client; a single observed attempt in this test does not prove that lower layers never retry in every circumstance. The screenshot is the probe's aggregate result, not a packet capture or production security proof.

After the test, the probe APK and its app data, three test reverse mappings (`18791`, `18792`, `18793`), local fixture process and UI dump were removed. The main app was not replaced and its existing `8787` mapping remained. The test CA's private key stayed in the workspace and was not copied to the phone.
