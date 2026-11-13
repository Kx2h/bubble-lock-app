# Isolated Portal HTTPS and lease fixture

ROLE: APP · DOC-REV: 2.0.0 · R10, R14. This is a privileged debug fixture for the native Portal read contract. It uses one synthetic RSA JWT, synthetic coordinates and a loopback HTTPS server. It sends no request to a production endpoint and uses no real account or physical keyring.

The server accepts only its own short-lived signed JWT with the fixed issuer `https://localhost:9443/fixture-idp`, audience `portal-network-native` and scope `portal:read`. `GET /v1/portal/locations` returns one explicitly simulated GPS observation, a five-second `readExpiresAt`, HTTP `Date`, `Cache-Control: no-store` and the central integration contract header. An authenticated empty `POST /_fixture/slow-deny` makes the next location GET wait seven seconds before returning 403. Later reads remain denied. This control route exists only in this fixture.

The Android helper exercises the actual HTTPS transport, Portal parser and `MainActivity` state. During the pending renewal it checks independent expiry of coordinates, selection and camera cache, and checks that the delayed 403 cannot restore them. It also checks `FLAG_SECURE` and the native simulation label. It bypasses OIDC login, drives foreground state while the phone can remain locked and injects a synthetic camera cache with SDK networking paused. The result does not establish browser login, a production IdP, physical location, visible touch, rotation or TalkBack behavior.

## Host self-test

Run from the repository root with Node 22+ and OpenSSL:

```sh
node --test app/tests/portal-network/server.test.mjs
```

The self-test creates a private directory and throwaway CA/leaf keys below workspace `.local`, starts an ephemeral loopback port and removes its own directory in `finally`. It checks TLS 1.3, CA and hostname validation, denial of absent/changed credentials, response metadata, the lease, the delayed denial, redacted events and owned-token cleanup. It changes no trust store and does not use ADB. Paths follow this workspace layout: `$WORKSPACE/github_upload/bubble_lock`; the fixture resolves `$WORKSPACE/.local` from its source location. Certificate inputs and runtime state must resolve inside that directory.

## Opt-in Android QA profile

`WORKSPACE` is the absolute path of the `mobius4` workspace. Prepare the local certificate with [the existing certificate script](../oidc-mock/create-local-certs.sh). It refuses to overwrite `.local/oidc-mock`; inspect validity and preserve an existing directory before explicitly regenerating an expired fixture. Never copy a CA private key or server private key to a phone. This profile trusts the test CA only for the `localhost` domain inside this debug app. It does not change Android, browser or production release trust.

From repository `app/`, using the workspace JDK/SDK/cache configuration documented in [the app README](../../README.md):

```sh
"$WORKSPACE/.local/gradle-8.13/bin/gradle" --no-daemon --max-workers=2 \
  --dependency-verification=strict \
  -PmobileApiBaseUrl=https://localhost:9443 \
  -PmobileApiContract=portal-central-v1 \
  -PoidcIssuer=https://localhost:9443/fixture-idp \
  -PoidcClientId=portal-network-native \
  -PoidcTestCa="$WORKSPACE/.local/oidc-mock/ca.pem" \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

The helper refuses any other build profile, a build without the explicit debug test CA, or pre-existing encrypted credentials. Use an isolated QA installation. Preserve the current APK/data and verify the test target before installing the debug APK and its instrumentation APK; this fixture does not authorize deleting or replacing another user's app data. The QA profile is separate from a production artifact.

## Server and private token transfer

After the build, from the repository root, start a fresh dedicated state directory and leave this server in the foreground. Its JWT expires ten minutes after startup. Only the state path and ready metadata are printed; do not print or place the token in a shell argument.

```sh
PORTAL_QA_STATE=$(mktemp -d "$WORKSPACE/.local/portal-network-device.XXXXXX")
"$WORKSPACE/.local/node/bin/node" app/tests/portal-network/server.mjs \
  --cert "$WORKSPACE/.local/oidc-mock/server.pem" \
  --key "$WORKSPACE/.local/oidc-mock/server.key" \
  --private-dir "$PORTAL_QA_STATE" --port 9443
```

In a second terminal, set `PORTAL_QA_STATE` to the same private directory path, `SERIAL` to the chosen QA device and `ADB` to an ADB executable or [the WSL wrapper](../../scripts/adb-windows.sh). Inspect `reverse --list` first and leave any pre-existing `tcp:9443` mapping untouched. When the port is free, create this test's mapping. Copy the dummy JWT through standard input directly into private app storage; no `echo`, token command argument, shared storage or external file is used. `run-as` must target the installed debuggable QA app.

```sh
"$ADB" -s "$SERIAL" reverse --list
"$ADB" -s "$SERIAL" reverse tcp:9443 tcp:9443
"$ADB" -s "$SERIAL" shell -T 'run-as org.privacybubble.app sh -c "umask 077; test ! -e files/portal-network-token && cat > files/portal-network-token"' \
  < "$PORTAL_QA_STATE/portal-network-token"
"$ADB" -s "$SERIAL" shell am instrument -w -r -e mode portal-network \
  org.privacybubble.app.test/org.privacybubble.app.security.SessionSecurityInstrumentation
```

Use the explicit `portal-network` mode. The default instrumentation path runs the separate generic session tests and has different setup behavior. The dedicated result reports assertion count and expiry/denial/total timings. Successful device execution is designed to finish within 15 seconds. The host self-test alone does not prove that Android execution succeeded.

## Owned cleanup

The helper's `finally` finishes its Activity, clears the test encrypted session, removes its private JWT file and restores the saved API setting and the exact nullable MapLibre connectivity override. The override pauses SDK networking only; Wi-Fi, device connectivity and system trust settings are unchanged. It refuses existing credentials before taking session ownership. If instrumentation never starts, its cleanup cannot run: remove only the dummy token file this procedure created, after verifying that the isolated QA installation is still the target.

Stop this server with Ctrl-C. It closes its sockets and delayed response timer, and removes the host token file only while its bytes still match the token it generated. The state directory and capped, redacted `portal-network-events.jsonl` remain for a receipt; they contain synthetic route/timing metadata, not credentials. Delete only this procedure's directory when it is no longer needed. Existing `.local/oidc-mock` certificate files are shared fixture inputs and are not removed by the server. Remove only the `tcp:9443` reverse mapping created above, after confirming that it has not changed:

```sh
"$ADB" -s "$SERIAL" reverse --list
"$ADB" -s "$SERIAL" reverse --remove tcp:9443
```

Restore the previously recorded QA application artifact and remove only this test's instrumentation package when required by the device work record. Never add test CA material, generated tokens, private runtime files or a QA-signed build to production deployment inputs.
