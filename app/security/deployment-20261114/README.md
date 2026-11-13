# 2026-11-14 integration supply-chain evidence

This directory records the 0.4.0 integration review and final unsigned, unprovisioned Android package inventory. The historical 0.3.0 evidence under `app/security/evidence/` is preserved. Native client registration, deployment TLS identity confirmation and real-account connection remain pending. No operating credentials or Gateway private keys are embedded in these reports or asserted to be in the Android build.

| Final release check | Result |
| --- | --- |
| [APK/AAB inventory and profile](release-artifacts.json) | 0.4.0 / versionCode 4, default `keyrings-v1`, unsigned/unprovisioned, not debuggable, callback absent |
| [Final integrated CycloneDX 1.6 BOM](release.cdx.json) | 492 components / 493 graph nodes; 460 library components with license declarations |
| [Official schema validation](release-schema-validation.json) | PASS, pinned official schemas, complete graph coverage and local-path checks |
| [Final exact-version OSV scan](release-osv.json) | 417 unique package/version queries in 5 batches; 0 known matching advisories at the recorded time |
| [Supply-chain tool source](security-tool-source-hashes.json) | Relative source paths and SHA256 at final artifact-inventory time |

The 417 queries comprise 72 Maven coordinates, the upstream version of the patched AppAuth fork, 334 unique npm versions across the PC/web/CSE lock trees and 10 external Python packages from the local Gateway runtime closure. The separate seven-package validator-tool scan is not added to those product counts. R8 reachability, exact browser bundle contents, native static internals and deployed server/Pi package state are not inferred from these package-version matches.

| Artifact | SHA-256 |
| --- | --- |
| `app-release-unsigned.apk` | `5cbdadd2543bc5581ffe9c8cbe1bd8aa757aa21f2de68a3d13c3db7677613ed9` |
| `app-release.aab` | `a64e7c3bbc8c2954c7b5fb585c0d0199ffc1182e41e5f151e828f7c1937a3f01` |
| `release.cdx.json` | `d04baad920ee8a50d6411ffbc8ce725e50b55c642eea8a5e1f17cafadfe51c53` |

A QA-signed copy or future provisioned/production-signed package has different bytes and needs its own artifact inventory. [SHA256SUMS](SHA256SUMS) binds the reports below; it does not establish deployment authorization or independent publisher authenticity.

| Input / check | Recorded result |
| --- | --- |
| PC Mobius npm lock | 63 locked entries: 62 runtime, 1 runtime optional |
| Web dashboard npm lock | 82 locked entries: 10 runtime, 72 development/build |
| CSE runtime npm lock | 232 locked entries: 231 runtime, 1 runtime optional |
| [Patched CSE source verification](cse-source-verification.json) | Archive, internal/external lock and seven listed source/lock patch hashes match; no extraction or deployed-image claim |
| [Gateway Python validation snapshot](gateway-python-environment.json) | 13 installed distributions, 11 in the declared runtime closure; pip and lxml excluded |
| [Host preflight SBOM](host-preflight.cdx.json) | 392 components, distinct PC/web/CSE roots, patched CSE source archive and local Gateway runtime graph |
| [Official CycloneDX validation](host-preflight-schema.json) | PASS against the pinned 1.6 schemas, graph coverage and local-path checks |
| [Exact-version host OSV lookup](host-preflight-osv.json) | 344 exact package/version queries in 4 batch requests; 0 known matching advisories at the recorded query time |
| [npm license metadata](npm-all-licenses.json) | 334 unique exact npm versions; every queried version declared a license |
| [Tool bootstrap](security-tool-bootstrap.json) | 7 selected-platform wheels matched official PyPI SHA256 metadata and installed offline with hash checking in a new local venv |
| [Separate tooling SCA](validation-tooling-osv.json) | 7 exact PyPI versions, 1 batch, 0 known matches; tooling-only BOM is kept separate from all product-runtime counts |
| [Tool tests](security-tool-tests.txt) | 15 passed, including application-path collisions, development/peer graph edges, Python marker/extras closure and independent OSV pagination |
| [Release-gate preflight](release-gate-preflight.json) | 13 shell preflight/dry-run checks passed, including stale/missing snapshots and altered lock-hash rejection; no full gate build was run in these checks |

The [PC-only license snapshot](pc-npm-licenses.json) is available for the legacy single-lock release gate. The combined snapshot is used with the additional web application option. Development packages remain in the inventory and SCA with explicit scope; no browser-bundle reachability claim is made. Platform-optional npm entries can be present in the lock without being installed on the current host.

The Python snapshot records Gateway **0.2.122** in this workspace's validation interpreter, not a physical Pi or remote server. Exact installed metadata is the evidence; wheel bytes and Linux platform libraries are outside that snapshot. Its first-party Gateway package is represented as an application, and its ten external runtime packages receive PyPI queries. Validator tooling has separate [official PyPI release provenance](security-tool-pypi-provenance.json), [tool-only BOM](validation-tooling.cdx.json) and [schema validation](validation-tooling-schema.json), and is excluded from the product runtime graph. The installer/bootstrap Python/pip layers are outside the seven-package tooling scan.

Known-match count zero is a database result at query time. Static native internals, remote deployment state, external IdP/browser/OS, container base images and service-side configuration require separate evidence. The preflight host-only files remain as intermediate scope evidence; the final APK/AAB-linked result is `release.cdx.json`.
