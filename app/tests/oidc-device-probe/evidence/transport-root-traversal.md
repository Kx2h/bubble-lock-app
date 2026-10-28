# Android HTTPS transport root traversal check

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R10. On 2026-10-28 the Android 14 spare phone ran the separate synthetic probe with its app-scoped test CA. Device and browser trust stores were not changed. No real account, token, operational API or permission was used.

The APP transport source and the source staged into the probe both had SHA-256 `6ed7df710a014d25e37f0de4cac5b831a5881a620a364efc556968bd30d71e35`. The debug probe APK SHA-256 was `bc737ade675a1673dd08a9244138246c160da78ee1c8a2a11d08a91d9ea5395b`.

The probe rejected `/../admin`, `/..`, `/../../admin` and three double-encoded traversal or separator paths before making a network request. It also observed TLS 1.3 success, expected HTTP responses, redirect not followed, a lost synthetic POST response, and rejection of a TLS 1.2-only server and a server certificate for the wrong DNS name. [Screen](transport-root-traversal.png). This is an aggregate application result against local fixtures, not a production security proof; a lost POST response remains an unknown outcome.

The test-only probe APK and its app data, reverse mappings `18791`–`18793`, UI dump and fixture process were removed. The main app was not replaced and its existing `8787` reverse mapping was retained. Private fixture keys stayed in the workspace.
