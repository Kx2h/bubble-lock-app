# 0.5.0 Calm Console release evidence

This evidence identifies the optimized Server Portal profile, versionCode 6.
The live first-party web application remains hosted by the existing BFF;
the Android package contains its native shell and one inert mobile stylesheet.
It does not bundle the web JavaScript application or introduce a credential bridge.

| Check | Recorded evidence |
|---|---|
| Version, QA signer and package hashes | [release-artifacts.json](release-artifacts.json) |
| Debug97 / Release88 JVM tests, lint0, complete gates | [build-validation.json](build-validation.json) |
| 42 packaged security boundaries | [release-inspection.json](release-inspection.json) |
| Resolved Android dependencies and binary hashes | [release-inventory.json](release-inventory.json) |
| CycloneDX 1.6, 492 components / 493 nodes | [release.cdx.json](release.cdx.json) |
| Official schema and graph validation | [release-schema-validation.json](release-schema-validation.json) |
| Exact-version OSV lookup: 417 versions, no known matches | [release-osv.json](release-osv.json) |
| Source binding | [source-sha256.json](source-sha256.json) |

The inventory and OSV scope follows the unchanged reviewed inputs in
[the preceding supply-chain evidence](../deployment-20261114/README.md).
Database matches are time-bounded; this is not an exhaustive exploit/fuzz campaign,
remote installation inventory, WebView/OS audit, or commercial certification.

The app retains exact-origin certificate trust and SPKI pinning, disabled backup,
`FLAG_SECURE`, disabled WebView saved state, file/content/cleartext restrictions,
and server-confirmed logout before clearing the owned web session. Resume hints
contain only allowlisted routes in RAM; authentication callbacks and query values
are excluded. The exact local stylesheet resource is handled only as a same-origin
subresource so the server's CSP does not need to allow inline styles. API and
identity responses are never replaced. Cookie flushes persist the WebView's own
jar without reading cookie values into native code.

The installed APK uses the existing workspace QA signer so it can update the
previous test installation without clearing data. It is separate from an
organization/store-signed release. No native OIDC client registration, server
configuration change, system CA installation or firmware security provisioning
is implied by this UI release.

[Design and phone evidence](../../evidence/calm-console-20261119/README.md) ·
[Change report](../../docs/CALM-CONSOLE-20261119.md)
