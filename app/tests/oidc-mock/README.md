# Synthetic OIDC test provider

ROLE: APP · DOC-REV: 2.0.0 · R10. This is a local, synthetic identity provider for protocol testing, not a user authentication service. It has no passwords or real users. It listens only on `127.0.0.1` over HTTPS. The sole test identity is `synthetic-test-user`; it is never a Guardian or Visitor authorization grant.

From the repository root, run the host tests with Node 22 and OpenSSL:

```sh
node --test app/tests/oidc-mock/server.test.mjs
```

Tests create ephemeral keys in the ignored `app/build` directory and remove their own temporary files. They verify trusted HTTPS and rejection of an untrusted certificate, discovery, S256 code exchange, RS256 signature and nonce, code replay and expiry, and rejection of an unregistered redirect and wrong verifier. These tests do not exercise Android or a browser.

To prepare a reusable local server certificate and a private test CA, run the following from the repository root. `WORKSPACE` is the absolute path of the `mobius4` workspace. The certificate script refuses to overwrite an existing `.local/oidc-mock` directory and never installs a CA into a trust store. Protect and do not commit the private keys.

```sh
app/tests/oidc-mock/create-local-certs.sh
MOCK_OIDC_CERT="$WORKSPACE/.local/oidc-mock/server.pem" \
MOCK_OIDC_KEY="$WORKSPACE/.local/oidc-mock/server.key" \
node app/tests/oidc-mock/server.mjs
```

Issuer: `https://localhost:18443`; client ID: `privacy-bubble-android-test`; redirect URI: `org.privacybubble.app:/oauth2redirect`. The server intentionally accepts only this registered redirect and uses one-time, two-minute codes. It is designed for a debug test application and issues short-lived synthetic tokens; it does not provide real authentication or authorization. Stopping the server invalidates pending codes and its signing keys.

An Android phone can reach a running server using `adb reverse tcp:18443 tcp:18443`, but reachability alone does **not** establish trust. Android's app HTTP client and the external browser each need an independently valid TLS trust path for the issuer. A private local CA is not generally trusted by either, and Android app trust configuration does not automatically change browser trust. No device trust store is changed by these scripts, and certificate validation must not be bypassed. Use an appropriately trusted test environment or a legitimate test endpoint with a certificate trusted by both before running a phone login. Remove the specific ADB reverse mapping after testing; never copy `ca.key` or `server.key` to the device. Actual issuer/client/redirect and access policy remain subject to PC-MOBIUS-INTEGRATION's contract.

For a separate host integration check of the app's actual `OidcProvider`, `OidcRedirect` and `IdTokenVerifier`, first build the Android project so the pinned Nimbus 9.47 JAR is present in the workspace Gradle cache, then run:

```sh
app/tests/oidc-mock/test-java-integration.sh
```

The script compiles the actual Java classes and launches separate JVM processes. A throwaway per-process PKCS12 truststore trusts the test certificate for the positive exchange; another JVM with the normal truststore must reject the same certificate. It uses the real HTTPS mock discovery, S256 token exchange and JWKS and verifies a synthetic signed ID token, then rejects a wrong nonce. It does not use an Android browser, AppAuth or the device, and changes no system trust store. Source and generated keys/truststore are test-only. The shell defaults to the workspace JDK, Node and Gradle cache; `HOST_JDK_HOME` and `NIMBUS_JAR` can override the JDK and JAR.

The mock discovery advertises RFC 9207 issuer identification and includes `iss` in its authorization response; clients must check it against the expected issuer. This test does not model real user authentication or production consent.

## Synthetic refresh-token rotation

This test IdP issues a refresh token only after a synthetic consent POST for `scope=openid offline_access` and `prompt=consent`. The token endpoint accepts `grant_type=refresh_token`, the registered test `client_id` and exactly one `refresh_token`. A successful response contains a new access token and a rotated refresh token, `token_type=Bearer` and `expires_in=120`; it deliberately omits an ID token and does not support a refresh `scope` parameter. No refresh token is provided for an `openid`-only grant.

Refresh tokens and families exist solely in memory. Each successful rotation makes the previous token spent. Reuse of a spent token revokes every token in that family, while an unrelated family remains valid. The family expires after 30 minutes and is limited to 64 issued refresh tokens. An invalid client does not revoke a family. Server restart removes all synthetic tokens. The fixture has no resource server and does not validate or revoke access tokens; family revocation here applies only to refresh tokens. Do not record or print any token. This fixture models one conservative test contract; rotation, consent, session lifetime, revocation and account policy for a real issuer remain subject to the integration team.

The host tests cover explicit synthetic consent, rotation, replay and family revocation, unrelated-family isolation, concurrent reuse, expiry and the family limit. These are local synthetic tests; they do not show a user's approval or prove production issuer behavior.

For the opt-in Android refresh PoC, the same mock server can record a **method and fixed endpoint path only** trace. It never records query strings, request bodies, state, codes or tokens. Select a new trace filename under the workspace; the script uses exclusive creation and will refuse to overwrite an existing file. Stop any other server on port 18443 first.

```sh
MOCK_OIDC_CERT="$WORKSPACE/.local/oidc-mock/server.pem" \
MOCK_OIDC_KEY="$WORKSPACE/.local/oidc-mock/server.key" \
REFRESH_TRACE_FILE="$WORKSPACE/.local/oidc-mock/device-refresh-trace.txt" \
"$WORKSPACE/.local/node/bin/node" app/tests/oidc-mock/device-refresh-trace.mjs
# After the test, stop this process with Ctrl-C. Preserve or remove the trace explicitly.
```

The fixture is local and synthetic. A pair of `POST /token` lines corresponds to two token requests in the controlled test; the trace alone does not prove the response payload or a production authentication outcome. See the Android result and its limitations in `app/evidence/oidc-refresh-device.md`.
