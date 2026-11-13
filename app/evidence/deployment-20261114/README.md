# 0.4.0 integration execution evidence

2026-11-14 Asia/Seoul. Machine timestamps inside JSON use their stated UTC offset. These results describe this integration worktree and the explicit tested targets; historical upstream acceptance evidence is preserved separately.

| Evidence | Scope |
|---|---|
| [Source integration](source-integration.json) | Ref SHA and zero remaining commits from the five fetched team branches |
| [Workspace verification](workspace-verification.json) | All 13 stages passed, including 171 CORE, 87 web, 264 Gateway and 6 local cross-runtime TLS tests |
| [Android](android-verification.json) | Debug 85 / Release 76, lint 0, 36 packaged checks, final unsigned APK/AAB hashes |
| [Actual full release gate](release-gate.json) | All build/test/isolation/SBOM/schema/OSV stages executed; BOM identical to the published evidence |
| [Device transcript](device-validation.txt) | 91 generic and 32 HTTPS/Main assertions, privileged debug fixtures on a locked Samsung Android 14 phone |
| [Final device](device-final-state.json) | QA-signed optimized 0.4.0 installed; test package for user 0, fixture tokens and owned reverse removed; settings restored |
| [Portal host](portal-network-host.json) | TLS/hostname/authentication, no-store and delayed-denial checks using throwaway credentials |
| [Real MQTT read](mqtt-read-result.json) | Provided CA + gateway client certificate, TLS 1.3, matching oneM2M RETRIEVE response `2000`; zero actuation commands |
| [Server observations](server-observations.json) | Separates verified MQTT from unverified HTTPS discovery and pending production access |
| [Nginx](nginx-native-proxy.json) | Official PGP provenance, actual Nginx 1.30.6 syntax and 20 local TLS proxy policy checks |
| [Firmware](firmware-verification.json), [source hashes](firmware-source-sha256.json) | Four compile-only profiles, nine host binaries and eleven configuration cases; no physical flash/GPIO/actuation |
| API fuzz [seed 1](mobile-integrated-0x5ec0a11.json), [seed 2](mobile-integrated-0xc0ffee.json), [seed 3](mobile-integrated-0x19ab73.json) | 30 tests per seed; 38,958 inputs across isolated loopback runs, not a production penetration test |
| [SBOM/SCA](../../security/deployment-20261114/README.md) | 492 components, 460 library license declarations; 417 exact package versions, five OSV batches, zero known matches |

The device fixtures bypass real OIDC login and use simulated positions. The screen remained locked; they do not establish visible touch, rotation, large-font or TalkBack acceptance for this version. The Portal fixture does exercise real Android TLS/HTTP/parser/Main expiry while renewal is pending. The final installed QA copy uses the default unprovisioned profile and is not production-signed.

Only the MQTT RETRIEVE targets the real deployment using its supplied CA and client identity. It does not prove a physical Pi daemon, BLE sensor or keyring position. No account credentials were sent to the unverified HTTPS endpoint, and no remote deployment configuration or system/browser trust was changed.

[Integration report and remaining inputs](../../docs/DEPLOYMENT-20261114.md) · [Checksums](SHA256SUMS). Checksums bind these reports; they do not provide independent publisher authenticity or a production-readiness certification.
