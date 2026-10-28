# Synthetic OIDC access-token hash device check

Date: 2026-10-28 (Asia/Seoul), Android 14 spare phone. This test used the opt-in debug app and a separately installed synthetic external agent, not a real browser, user, IdP, or permission. The test CA was scoped to those two APKs; system and browser trust stores were not changed. Opt-in app SHA-256: `af24bc91d29f94a11f4b87c47e986ae7475780a34269a6c36975c1638b665acb`. Synthetic agent SHA-256: `b6f38f01db845eaa51a2ef700b87109a4e88b0106a251eafe7f8c472dda08b7c`.

The localhost synthetic issuer was restarted for each mode, with `MOCK_REFRESH_ID_TOKEN=1`. The host fixture tests independently checked that each ID token was signed and its `at_hash` matched or differed as configured. After the synthetic agent's explicit continue action:

- `MOCK_ACCESS_HASH=valid`: the app reported synthetic authentication and refresh PASS ([screen](oidc-at-hash-valid.png)).
- `MOCK_ACCESS_HASH=wrong-initial`: the app reported authentication cancelled or validation failed ([screen](oidc-at-hash-wrong-initial.png)).
- `MOCK_ACCESS_HASH=wrong-refresh`: the app reported authentication or refresh failure ([screen](oidc-at-hash-wrong-refresh.png)).

The UI reports generic errors; screenshots do not establish the exact exception. The configured fault, host fixture tests and JVM verifier tests provide complementary evidence, not a production IdP or ACP test. No token, authorization code, state or request body was captured in this evidence.

The test server was stopped, the test-only `tcp:18443` reverse and synthetic agent removed, and the prior main app was reinstalled without clearing its data. Its restored APK SHA-256 was `bb9dc64aebefbc89c9812b7954181d88e00432ee844e5b8610ba50b463d5ee24`. The existing `tcp:8787` reverse remained in place.
