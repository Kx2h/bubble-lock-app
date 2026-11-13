# 2026-11-14 integration supply-chain evidence

This directory records the 0.4.0 integration review and final unsigned, unprovisioned Android package inventory. The historical 0.3.0 evidence under `app/security/evidence/` is preserved. Native client registration, deployment TLS identity confirmation and real-account connection remain pending. No operating credentials or Gateway private keys are embedded in these reports or asserted to be in the Android build.

| Final release check | Result |
| --- | --- |
| [APK/AAB inventory and profile](release-artifacts.json) | 0.4.0 / versionCode 4, default `keyrings-v1`, unsigned/unprovisioned, not debuggable, callback absent |
| [Final integrated CycloneDX 1.6 BOM](release.cdx.json) | 258 components / 259 graph nodes; 228 library components with license declarations |
| [Official schema validation](release-schema-validation.json) | PASS, pinned official schemas, complete graph coverage and local-path checks |
| [Final exact-version OSV scan](release-osv.json) | 222 unique package/version queries in 3 batches; 0 known matching advisories at the recorded time |
| [Supply-chain tool source](security-tool-source-hashes.json) | Relative source paths and SHA256 at final artifact-inventory time |

The 222 queries comprise 72 Maven coordinates, the upstream version of the patched AppAuth fork, 139 unique npm versions across the PC/web lock trees and 10 external Python packages from the local Gateway runtime closure. The separate seven-package validator-tool scan is not added to those product counts. R8 reachability, exact browser bundle contents, native static internals and deployed server/Pi package state are not inferred from these package-version matches.

| Artifact | SHA-256 |
| --- | --- |
| `app-release-unsigned.apk` | `efb7f288f4653996d6ba78cadb6cc3e886715192e8efc0387dfa084edb0d42b6` |
| `app-release.aab` | `dd79c603a48ef24fc7a770c512ff300063180d58146c588ab8be33a6b560a3b3` |
| `release.cdx.json` | `0ee9b80d5bb34dec30f79742306481386c192806f85f773ef39426c2c8d184e6` |

A QA-signed copy or future provisioned/production-signed package has different bytes and needs its own artifact inventory. [SHA256SUMS](SHA256SUMS) binds the reports below; it does not establish deployment authorization or independent publisher authenticity.

| Input / check | Recorded result |
| --- | --- |
| PC Mobius npm lock | 63 locked entries: 62 runtime, 1 runtime optional |
| Web dashboard npm lock | 82 locked entries: 10 runtime, 72 development/build |
| [Gateway Python validation snapshot](gateway-python-environment.json) | 13 installed distributions, 11 in the declared runtime closure; pip and lxml excluded |
| [Host preflight SBOM](host-preflight.cdx.json) | 158 components, distinct PC/web application roots and local Gateway runtime graph |
| [Official CycloneDX validation](host-preflight-schema.json) | PASS against the pinned 1.6 schemas, graph coverage and local-path checks |
| [Exact-version host OSV lookup](host-preflight-osv.json) | 149 exact package/version queries in 2 batch requests; 0 known matching advisories at the recorded query time |
| [npm license metadata](npm-all-licenses.json) | 139 unique exact npm versions; every queried version declared a license |
| [Tool bootstrap](security-tool-bootstrap.json) | 7 selected-platform wheels matched official PyPI SHA256 metadata and installed offline with hash checking in a new local venv |
| [Separate tooling SCA](validation-tooling-osv.json) | 7 exact PyPI versions, 1 batch, 0 known matches; tooling-only BOM is kept separate from all product-runtime counts |
| [Tool tests](security-tool-tests.txt) | 11 passed, including application-path collisions, development/peer graph edges, Python marker/extras closure and independent OSV pagination |
| [Release-gate preflight](release-gate-preflight.json) | 9 shell preflight/dry-run checks passed, including stale/missing snapshots and altered lock-hash rejection; no full gate build was run in these checks |

The [PC-only license snapshot](pc-npm-licenses.json) is available for the legacy single-lock release gate. The combined snapshot is used with the additional web application option. Development packages remain in the inventory and SCA with explicit scope; no browser-bundle reachability claim is made. Platform-optional npm entries can be present in the lock without being installed on the current host.

The Python snapshot describes this workspace's Gateway validation interpreter, not a physical Pi or remote server. Exact installed metadata is the evidence; wheel bytes and Linux platform libraries are outside that snapshot. Its first-party Gateway package is represented as an application, and its ten external runtime packages receive PyPI queries. Validator tooling has separate [official PyPI release provenance](security-tool-pypi-provenance.json), [tool-only BOM](validation-tooling.cdx.json) and [schema validation](validation-tooling-schema.json), and is excluded from the product runtime graph. The installer/bootstrap Python/pip layers are outside the seven-package tooling scan.

Known-match count zero is a database result at query time. Static native internals, remote deployment state, external IdP/browser/OS, and service-side configuration require separate evidence. The preflight host-only files remain as intermediate scope evidence; the final APK/AAB-linked result is `release.cdx.json`.
