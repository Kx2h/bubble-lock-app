# Server Portal application profile

APP 0.4.1 / versionCode 5. This optional profile opens the deployed first-party web product directly inside `ServerPortalActivity`. The existing BFF handles its browser OIDC login, refresh and server-side API calls. This profile does not register an Android public OIDC client or populate the native app's token store.

The launcher opens the configured origin at `/#/user/map`; the web home also provides the operator entry. Native back, home, reload, close and logout controls remain available. The native `MainActivity` retains its own API/login behavior and shows a web-service entry only when this profile is present. Default builds continue to launch `MainActivity`.

## Explicit build inputs

From repository `app/`, with the workspace JDK/SDK/cache environment already configured:

```sh
"$WORKSPACE/.local/gradle-8.13/bin/gradle" --no-daemon --dependency-verification=strict \
  -PserverPortalOrigin=https://bubblelock.iptime.org \
  -PserverPortalCertificate="$WORKSPACE/.local/deployment-connect-20261114/server.pem" \
  :app:assembleDebug :app:assembleRelease
```

Both properties are required. The certificate input must resolve to that fixed ignored workspace file and contain one public X.509 certificate. It must be in date, cover the exact hostname and permit server authentication. Native OIDC profile inputs and `oidcTestCa` are separate modes and cannot be combined with the Server Portal profile. No client secret, account password, bearer or private key belongs in these properties.

The generated network security configuration uses the explicitly approved captured HTTPS leaf as the trust anchor for the exact configured DNS hostname, with `includeSubdomains="false"` and a SHA-256 SPKI pin. Other hosts retain system CA validation. This trust applies only inside this app, including its WebView; Android and external browsers are unchanged. The normal certificate lifetime, hostname and pin checks remain active. A server certificate/key change requires an updated approved profile and application build. TLS errors are cancelled rather than bypassed.

## Session and navigation behavior

The BFF's `Secure`, `HttpOnly`, same-origin cookies remain in the app WebView's cookie store. The app does not extract cookies or copy passwords, access tokens or refresh tokens into Java, a JavaScript bridge, native preferences or saved Activity state. External browser sessions are separate.

Only the exact configured HTTPS origin can navigate the main frame. External HTTPS links require a main-frame user gesture and open through an external browser intent. Scripted external redirects, other schemes, file/content access, mixed content, file upload, geolocation and WebView debugging are disabled. The Activity uses `FLAG_SECURE` and disables recents screenshots where supported.

Logout sends the BFF's same-origin `POST /api/auth/logout` from the trusted portal document. Only after a 200 response does it clear this app's WebView cookies, including the Keycloak SSO cookies, WebStorage and cache so a different account can sign in. Other browser and system data are unaffected. Failure keeps the session visible and offers another attempt.

When the Activity stops, it blanks the page and clears navigation history; returning loads a new portal view using the retained web session. WebView history/forms are not written into Activity saved state. The native map model and its independent read lease are not used for this mode: the deployed web product owns its map, permission checks, simulation labels and read-expiry behavior.

Tests distinguish this BFF web-session mode from native OIDC, a deployed native API proxy, visible/manual UI inspection and physical keyring data. An empty live device list remains empty; this application profile does not invent location observations.
