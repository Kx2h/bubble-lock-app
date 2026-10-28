# Synthetic external OIDC test agent

ROLE: APP · DOC-REV: 2.0.0 · R10

This is a **temporary, debug-only external test surrogate**, not Samsung Internet, Chrome, a general browser, or production authentication. It is a distinct Android package and UID (`org.privacybubble.oidcsurrogate`) with an explicit synthetic identity button. It requests no password or personal data, does not store cookies or tokens, and rejects every destination except the exact HTTPS local mock authorization endpoint. The OIDC request parser requires the fixed test client and redirect, `openid`, S256, state and nonce; unknown fields, including user or account hints, are rejected. The separately installed app's debug network configuration trusts the workspace test CA only within this APK. No device, system, browser or production app trust store is modified.

The scheme filter advertises HTTP and HTTPS without an authority because AppAuth 0.11.1 discovers external agents this way. This is strictly a short-lived test package; do not set it as the device's default browser, use it for real users, or treat the result as a real browser or production IdP login. Runtime validation rejects HTTP and every non-local destination. The debug caller must explicitly select the exact test package **and** verify its signer matches the test app. Actual sender identification and AppAuth callback/state validation remain the caller's responsibility. Release builds of this test APK are disabled.

Prepare the local mock CA using `app/tests/oidc-mock/create-local-certs.sh` if it does not already exist. Do not copy its private key to the phone or commit generated CA files. Set `WORKSPACE` and isolated Android/JDK/Gradle variables as described in `app/README.md`:

```sh
"$WORKSPACE/.local/gradle-8.7/bin/gradle" --no-daemon -p app/tests/oidc-external-agent :assembleDebug
JAVAC="$WORKSPACE/.local/jdk17/bin/javac" JAVA="$WORKSPACE/.local/jdk17/bin/java" app/tests/oidc-external-agent/tests/run-host-tests.sh
MOCK_OIDC_CERT="$WORKSPACE/.local/oidc-mock/server.pem" MOCK_OIDC_KEY="$WORKSPACE/.local/oidc-mock/server.key" "$WORKSPACE/.local/node/bin/node" app/tests/oidc-mock/server.mjs
```

Before use select the authorized device explicitly, confirm `pm path --user 0 org.privacybubble.oidcsurrogate` returns no package and `reverse --list` has no `tcp:18443` mapping. Never overwrite another package or mapping. For WSL, use `ADB="$PWD/app/scripts/adb-windows.sh"` to convert the local APK path for Windows ADB.

```sh
"$ADB" -s "$SERIAL" shell pm path --user 0 org.privacybubble.oidcsurrogate
"$ADB" -s "$SERIAL" reverse --list
"$ADB" -s "$SERIAL" install "$PWD/app/tests/oidc-external-agent/build/outputs/apk/debug/PrivacyBubbleSyntheticExternalAgent-debug.apk"
"$ADB" -s "$SERIAL" reverse --no-rebind tcp:18443 tcp:18443
# Start the opt-in debug test flow from the APP and tap the synthetic identity button.
```

After the test, verify the installed package and the `tcp:18443` mapping belong to this run; then explicitly run `"$ADB" -s "$SERIAL" uninstall org.privacybubble.oidcsurrogate` and `"$ADB" -s "$SERIAL" reverse --remove tcp:18443`. Stop only the mock server process started by this test. Do not remove the main APP, the pre-existing `tcp:8787` reverse, or phone-wide trust settings. Do not copy the CA private key or credentials to the phone.

Standalone device smoke test: with an isolated mock server and selected device, the synthetic authorization URL reached a ready screen over the scoped CA; an `example.org` URL was rejected before connection. The screen captures and SHA-256 sums are in `evidence/`. This smoke test alone did **not** execute AppAuth, prove browser authentication, or create a user session.
