# Android local OIDC TLS probe

ROLE: APP · DOC-REV: 2.0.0 · R10

This is a **separate debug-only test APK**, package `org.privacybubble.oidcprobe`, for a synthetic local IdP. It copies only the existing APP `OidcProvider`, `OidcRedirect` and `IdTokenVerifier` sources at build time; it does not modify the normal app. Its own debug network configuration trusts the public test CA generated under the workspace `.local/oidc-mock`. No system or browser trust setting is changed. Release build variants are disabled.

The probe itself makes HTTPS requests: it discovers the mock provider, submits a synthetic consent form directly, exchanges a PKCE S256 code and verifies the ID token and rejection of an incorrect nonce. **This is not an external browser or AppAuth test, actual user authentication, or operational approval.** The production app's TLS or authentication behavior cannot be inferred from this probe's debug trust configuration.

Prepare the mock CA using `app/tests/oidc-mock/create-local-certs.sh`; do not commit or copy the private CA/server keys to the phone. The build reads only the workspace CA certificate and copies it into its ignored `build` directory. Configure the isolated JDK, SDK and Gradle as described in `app/README.md`, then run from the repository root:

```sh
"$WORKSPACE/.local/gradle-8.7/bin/gradle" --no-daemon -p app/tests/oidc-device-probe :assembleDebug
MOCK_OIDC_CERT="$WORKSPACE/.local/oidc-mock/server.pem" \
MOCK_OIDC_KEY="$WORKSPACE/.local/oidc-mock/server.key" \
"$WORKSPACE/.local/node/bin/node" app/tests/oidc-mock/server.mjs
```

In a separate shell select the actual authorized phone explicitly. Before installing, verify `pm path org.privacybubble.oidcprobe` reports no package and `reverse --list` has no `tcp:18443` mapping. Do not replace a pre-existing package or mapping. For WSL, set `ADB="$PWD/app/scripts/adb-windows.sh"` so the APK path is converted for Windows ADB.

```sh
"$ADB" -s "$SERIAL" shell pm path org.privacybubble.oidcprobe
"$ADB" -s "$SERIAL" reverse --list
"$ADB" -s "$SERIAL" install "$PWD/app/tests/oidc-device-probe/build/outputs/apk/debug/OidcDeviceProbe-debug.apk"
"$ADB" -s "$SERIAL" reverse --no-rebind tcp:18443 tcp:18443
"$ADB" -s "$SERIAL" shell am start -n org.privacybubble.oidcprobe/org.privacybubble.app.auth.ProbeActivity
```

After verifying the displayed outcome, confirm the package and port belong to this run, then explicitly run `"$ADB" -s "$SERIAL" uninstall org.privacybubble.oidcprobe` and `"$ADB" -s "$SERIAL" reverse --remove tcp:18443`. The mock server is stopped separately. The original app and its 8787 reverse mapping are not removal targets. Results and limitations are recorded in `evidence/device-poc.md` and checksummed in `evidence/SHA256SUMS`.

The same separate APK has an explicit-only `org.privacybubble.app.api.TransportProbeActivity` for the actual APP HTTPS transport. `stageApiTransport` copies the current APP source into the probe build; compare its SHA-256 to the source before claiming that a build tested a particular revision. The probe uses its own debug CA trust, not the browser or system trust store. It requires loopback TLS fixtures on ports 18791–18793 and never uses a real token or account. See [the device receipt](evidence/transport-device.md) for the tested source and APK hashes, scope, and cleanup. The fixture needs the test server certificate and key, plus a separate server certificate signed by the same test CA for a hostname other than localhost; keys remain in the workspace. After the mock CA has been prepared, the following creates the mismatch certificate and runs the fixture (the wrong certificate is never valid for `localhost`):

```sh
umask 077
mkdir -p "$WORKSPACE/.local/api-transport-device"
openssl req -new -newkey rsa:2048 -nodes -keyout "$WORKSPACE/.local/api-transport-device/wrong.key" -out "$WORKSPACE/.local/api-transport-device/wrong.csr" -subj /CN=wrong.example
printf 'subjectAltName=DNS:wrong.example\n' > "$WORKSPACE/.local/api-transport-device/wrong.ext"
openssl x509 -req -in "$WORKSPACE/.local/api-transport-device/wrong.csr" -CA "$WORKSPACE/.local/oidc-mock/ca.pem" -CAkey "$WORKSPACE/.local/oidc-mock/ca.key" -set_serial 782624 -days 1 -extfile "$WORKSPACE/.local/api-transport-device/wrong.ext" -out "$WORKSPACE/.local/api-transport-device/wrong.pem"
MOCK_OIDC_CERT="$WORKSPACE/.local/oidc-mock/server.pem" MOCK_OIDC_KEY="$WORKSPACE/.local/oidc-mock/server.key" PROBE_WRONG_CERT="$WORKSPACE/.local/api-transport-device/wrong.pem" PROBE_WRONG_KEY="$WORKSPACE/.local/api-transport-device/wrong.key" node app/tests/oidc-device-probe/transport-server.mjs
```

In another shell, verify the probe package and each of the three reverse ports are absent before installation, and verify the fixture bound each port. Use `--no-rebind` to avoid replacing any mapping. After the test, remove only this run's probe and mappings, and stop only the fixture process started above.

```sh
"$ADB" -s "$SERIAL" shell pm path org.privacybubble.oidcprobe
"$ADB" -s "$SERIAL" reverse --list
"$ADB" -s "$SERIAL" install "$PWD/app/tests/oidc-device-probe/build/outputs/apk/debug/OidcDeviceProbe-debug.apk"
for port in 18791 18792 18793; do "$ADB" -s "$SERIAL" reverse --no-rebind "tcp:$port" "tcp:$port"; done
"$ADB" -s "$SERIAL" shell am start -n org.privacybubble.oidcprobe/org.privacybubble.app.api.TransportProbeActivity
"$ADB" -s "$SERIAL" uninstall org.privacybubble.oidcprobe
for port in 18791 18792 18793; do "$ADB" -s "$SERIAL" reverse --remove "tcp:$port"; done
```
