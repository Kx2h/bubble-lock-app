# 0.4.1 provisioned Server Portal security evidence

This inventory identifies the actual user-approved private-server BFF profile, separately from the historical 0.4.0 unprovisioned native build. The optimized package uses exact-host app-only certificate trust and SPKI pinning. Operating account passwords, client secrets and Gateway private keys are absent from the source and package.

| Check | Result |
|---|---|
| [Artifacts and profile](release-artifacts.json) | 0.4.1/code5; BFF web-session profile; unsigned APK/AAB plus the QA-signed copy installed on the phone |
| [Packaged boundaries](release-inspection.json) | 42 checks PASS, including host/pin/public certificate, launcher, HTTPS, backup/test isolation and native binary boundaries |
| [Resolved release dependencies](release-inventory.json) | Current Gradle runtime graph and exact binary hashes |
| [CycloneDX1.6](release.cdx.json) | 492 components /493 dependency nodes; 460 library components with declared licenses |
| [Official schema](release-schema-validation.json) | PASS |
| [Exact-version OSV lookup](release-osv.json) | 417 package versions in five batches, zero known matches at recorded query time |
| [Source hashes](source-sha256.json) | Relative source paths binding the portal implementation and release inspection tooling |

Unsigned APK SHA256: `7b9adc66b0a542499842226f1b028ff39700d8d9201c2da2fb20e38d6db789d9`.

Validated AAB SHA256: `254afdc538564820b692a2e85f74f146c20634e7e82544c5f7ca29c9ebbc6df6`.

Installed QA APK SHA256: `fd9e91807a69555d1bd0fe9208844de25b36116241d8d060bdebf3622570c29d`.

BOM SHA256: `af4da423dfb46e36ce890c5793830719baacf1dad23189db303c8ab23584b9da`.

The complete release gate executed its build, tests, isolation, inventory, schema and OSV stages. The default native profile was also built independently and passed its original system-only trust checks. Rebuilding the portal reproduced the APK byte-for-byte; AAB runtime entries were identical while R8 elapsed-time telemetry changed. The original gate-validated AAB is retained. [Build/device evidence](../../evidence/server-connect-20261114/README.md)

Package selection, npm source/lock provenance, Gateway snapshot, license metadata and validation-tool evidence use the unchanged reviewed inputs in [the preceding supply-chain directory](../deployment-20261114/README.md). Counts include the selected PC/web/CSE lock trees and local Gateway validation runtime closure. They do not assert the versions installed on the remote server or a physical Pi, or browser bundle reachability.

Native statically bundled internals, platform WebView/OS, container base images and remote installation state are outside the exact package-version SCA. Zero database matches is a time-bounded lookup result. Neither that lookup nor deterministic mutation testing establishes a completed 1-day exploit regression matrix or coverage-guided native fuzz campaign.

The profile reuses the deployed web BFF and its existing OIDC client. It does not claim a new Android public-client registration or remote server configuration change. A store/organization signing key and full commercial acceptance are separate from this verified QA-signed private-server connection. [SHA256SUMS](SHA256SUMS)
