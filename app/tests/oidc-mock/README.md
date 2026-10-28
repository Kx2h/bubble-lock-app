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

Issuer: `https://localhost:18443`; client ID: `privacy-bubble-android-test`; redirect URI: `org.privacybubble.app:/oauth2redirect`. The server intentionally accepts only this registered redirect and uses one-time, two-minute codes. It is designed for a debug test application and issues short-lived synthetic tokens; it does not support refresh or real authorization. Stopping the server invalidates pending codes and its signing keys.

An Android phone can reach a running server using `adb reverse tcp:18443 tcp:18443`, but reachability alone does **not** establish trust. Android's app HTTP client and the external browser each need an independently valid TLS trust path for the issuer. A private local CA is not generally trusted by either, and Android app trust configuration does not automatically change browser trust. No device trust store is changed by these scripts, and certificate validation must not be bypassed. Use an appropriately trusted test environment or a legitimate test endpoint with a certificate trusted by both before running a phone login. Remove the specific ADB reverse mapping after testing; never copy `ca.key` or `server.key` to the device. Actual issuer/client/redirect and access policy remain subject to PC-MOBIUS-INTEGRATION's contract.

For a separate host integration check of the app's actual `OidcProvider`, `OidcRedirect` and `IdTokenVerifier`, first build the Android project so the pinned Nimbus 9.47 JAR is present in the workspace Gradle cache, then run:

```sh
app/tests/oidc-mock/test-java-integration.sh
```

The script compiles the actual Java classes and launches separate JVM processes. A throwaway per-process PKCS12 truststore trusts the test certificate for the positive exchange; another JVM with the normal truststore must reject the same certificate. It uses the real HTTPS mock discovery, S256 token exchange and JWKS and verifies a synthetic signed ID token, then rejects a wrong nonce. It does not use an Android browser, AppAuth or the device, and changes no system trust store. Source and generated keys/truststore are test-only. The shell defaults to the workspace JDK, Node and Gradle cache; `HOST_JDK_HOME` and `NIMBUS_JAR` can override the JDK and JAR.

The mock discovery advertises RFC 9207 issuer identification and includes `iss` in its authorization response; clients must check it against the expected issuer. This test does not model real user authentication or production consent.
