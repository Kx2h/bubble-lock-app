# Release security inventory

Version 0.3.0. Final unsigned, unprovisioned release packages were inventoried after the clean release build. A QA-signed installation copy or a future production-signed/provisioned build has different package bytes and requires its own package hashes/BOM.

| Evidence | Result |
| --- | --- |
| [CycloneDX 1.6 SBOM](evidence/release.cdx.json) | 145 components, 146 dependency graph nodes |
| [Resolved Android inventory](evidence/release-inventory.json) | 72 Maven coordinates, 69 Maven AAR/JAR archives, local AppAuth AAR; all resolved binary archives have SHA-256 |
| Packaged native files | 8 AAR entries + 8 APK entries + 8 AAB entries, each with SHA-256; these are three packaging stages, not 24 independently identified native libraries |
| License declarations | 117/117 library components: Maven 72, local AppAuth 1, npm 44; inherited Guava parent-POM declaration retained |
| [Official schema validation](evidence/release-schema-validation.json) | PASS, pinned official CycloneDX/SPDX/JSF schemas; graph and local-path checks also pass |
| [Exact-version OSV SCA](evidence/release-osv.json) | 117 package/version queries, 2 batch requests, 0 known matching advisories at 2026-10-31 20:31 UTC |
| [Tool boundary tests](evidence/security-tool-tests.txt) | 5 passed: independent pagination, incomplete response rejection, looping token rejection, graph coverage and nested npm resolution |
| [Offline notices reproduction](evidence/offline-notices-reproduction.json) | 9 files reproduced byte-for-byte, including 7 unchanged original legal texts |

| Package | SHA-256 |
| --- | --- |
| `app-release-unsigned.apk` | `9df451c0814e541f7b3f4fe569d2eb0bedc91b4c271fe6a12fbb2161769ea2d4` |
| `app-release.aab` | `a7d5d85eb90205c6c6febe2835aaf428fe17df069d03914398bc132008fce56e` |
| `release.cdx.json` | `830f2f3135bcdeae3eb8880778166bd8d5d0438b09e6c960ba9cf6af54ceadec` |

The generator checked that the final APK/AAB contain the offline notice index, that its hash matches the packaged manifest, and that all 72 Maven coordinates match the resolved release graph. Original Apache/MPL, local AppAuth, MapLibre 13.7.1 Android/core and bundled OkHttp publicsuffix notice text is retained in [assets/licenses](../app/src/main/assets/licenses/manifest.json).

[Baseline Nimbus 9.47 evidence](evidence/baseline-nimbus-9.47-osv.json) records CVE-2025-53864/GHSA-xwmg-2g98-w7v9. The final graph resolves Nimbus 10.10 and the final OSV lookup has no known match. The first-party strict nesting boundary remains defense in depth.

Zero OSV matches is a database result, not a penetration-test certification. Native static internals are not decomposed into verified package versions; OS/browser/build toolchains and deployment services are outside this runtime inventory. The full npm host lock tree is included separately from Android packaging. Reproduction, scope and license provenance: [SUPPLY_CHAIN.md](../../docs/security/SUPPLY_CHAIN.md). Source attack boundaries and residual deployment requirements: [THREAT_MODEL.md](THREAT_MODEL.md). Evidence checksums: [SHA256SUMS](evidence/SHA256SUMS).
