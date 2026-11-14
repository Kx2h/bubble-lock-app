# 0.4.1 real-server and phone evidence

2026-11-14 Asia/Seoul. This run uses the user's explicitly trusted private deployment at `https://bubblelock.iptime.org` and the supplied guardian/operator accounts. It is separate from historical synthetic and unprovisioned evidence.

| Evidence | Result |
|---|---|
| [Host login](host-live-login.json) | Both real OIDC/BFF logins, authenticated session, locations/snapshot 200, logout 200 and session cleared |
| [Debug phone](device-debug.txt) | 45 assertions against the actual deployed server |
| [Optimized release phone](device-release.txt) | 46 assertions; actual target debuggable=false; real role-specific dashboard DOM, API and logout |
| [Touch smoke](release-touch-smoke.json) | Native home/back/reload taps, role entry screen and WebView retained |
| [Launcher event](launcher-smoke.txt) | Normal launcher-category event targets the app; final foreground Activity checked separately |
| [Final installed state](device-final-state.json) | Installed QA-signed 0.4.1 matches handoff APK hash; test package removed for user 0, QA credentials/session cleared, display settings restored |
| [Build and variant validation](build-validation.json) | Debug92/Release83, lint0, actual full gate and default/profile trust separation PASS |
| [Workspace workflow](workspace-verification.json) | All 13 required module/contract/real-local-TLS/demo/web stages PASS |
| [AAB rebuild comparison](aab-rebuild-comparison.json) | Runtime entries identical; only R8 compiler elapsed-time metadata differs; validated original AAB retained |
| [APK/AAB SBOM/SCA](../../security/server-connect-20261114/README.md) | Package hashes, schema, exact-version scan and profile boundaries |

The phone was connected and unlocked for the UI run. Login automation loads the actual app Activity/WebView and submits one real login form per account. It returns only fixed checks, booleans and counts, with no passwords, cookies, bearer tokens, OAuth query or private response DTO. It checks scope separation and each role's map DOM; it does not execute device/consent/grant/command mutations or certify every permission boundary.

The real deployment returned zero devices, positions and BLE records to both accounts. No coordinates or telemetry were invented. Actual app/server integration does not establish physical Pi/BLE/keyring location accuracy. UI automation and a three-control touch smoke do not substitute for a full manual accessibility, device/GPU, long-duration or battery acceptance matrix.

The server certificate is trusted only inside this explicitly configured app. No system/browser trust was changed. BFF session cookies remain within the WebView, and the app does not register a new native OIDC client or copy a server client secret. The final installed package is a QA-signed optimized release, not a store/organization-signed release.

[Connection report](../../docs/SERVER-CONNECTION-20261114.md) · [Checksums](SHA256SUMS).
