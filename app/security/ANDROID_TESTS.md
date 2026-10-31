# Android authentication and location boundary tests

The deterministic JVM corpus checks the production authentication and location parsers without production credentials, network calls or device trust changes. It runs against JDK 17, Nimbus 10.10, JUnit 4.13.2 and the actual `org.json:json:20260814` implementation. Location and token lifetime checks consume the original strict JSON `BigDecimal` values before Android can round numbers to `Double`.

Run from `app/` using the configured Android SDK and JDK:

```sh
gradle --no-daemon :app:testDebugUnitTest :app:testReleaseUnitTest
gradle --no-daemon :app:compileDebugAndroidTestJavaWithJavac
```

The final clean Gradle build passed **56 debug tests in 13 suites and 47 release tests in 11 suites**, with zero failures or errors. These totals include three `ServiceOriginTest` methods added by the release work. `lintRelease` reported no issues. A separate JDK 17 compile and JUnitCore run also passed 56 tests after the exact decimal fixes. Debug has nine additional development helper tests; the production boundary corpus runs in both variants. Physical-device instrumentation passed **27 assertions: 15 session checks and 12 authentication checks**. A QA-signed copy of the optimized release also installed successfully, reported `debuggable=false`, and denied `run-as`; this does not validate production signing.

| Corpus | Fixed seed | Executed generated cases |
| --- | --- | --- |
| ID token | `0x425542424C45`, plus recorded XOR seeds | 256 signed claim mutations, 512 altered signatures, 128 altered JWKs, 1,024 arbitrary compact tokens |
| Authorization redirect | `0x5245444952454354` | 512 valid transactions, 512 decoded duplicate parameters, 512 wrong states |
| Discovery | `0x4F4944434D455441` | 512 endpoint/capability changes, including PKCE downgrade |
| Strict JSON | `0x5354524943544A53` | 1,024 valid Unicode documents and 1,024 escaped duplicate documents |
| Location | `0x4C4F434154494F4E` | 1,024 range/type mutations |
| In-memory authorization lifetime | `0x504B434553544154` | 1,024 transactions, each checked for expiry, clock rollback, changed epoch/origin and valid single consumption |

Fixed adversarial cases additionally cover NumericDate overflow and fractional policy, signature encoding pad bits, repeated JWK members, wrong key use/algorithm, array/body/depth budgets, invalid UTF-8 and surrogate escapes, nullable and denied location fields, impossible dates, 24:00/leap-second normalization, sub-double-precision ranges and token lifetimes. Expected failures must be rejected through the public validation error, rather than silently accepted or escaping as unexpected failures.

The application deliberately bounds accepted inputs: 65,536-byte JSON responses, JSON nesting at most 16 levels below the root and 8,192 values, 64-character numeric tokens, 16,384-character ID tokens, at most 64 JWKs, RSA keys from 2,048 to 8,192 bits, 100 keyrings, 8,192-character bearer tokens and redirect strings, and 64 callback parameters. Token lifetimes must be exact integral seconds from 30 through 604,800. Location timestamps use UTC with a four-digit year and at most millisecond precision, matching the API's normalized output.

`AuthorizationAttemptTest` verifies the pure transaction lifetime and binding logic. The activity retains request secrets and the single exchange worker in a `ViewModel`; its own saved-state Bundle contains no serialized authorization request. AppAuth receives a separate public transport request with the real S256 challenge and a placeholder that cannot redeem the code. The real verifier is used only from memory during token exchange. Browser request state, nonce and challenge are public authorization protocol parameters and remain visible to the browser.

`AuthSecurityAssertions` adds twelve Android assertions for the real AppAuth intent serialization, the unchanged browser challenge, inability of the placeholder to satisfy that challenge, and credential/epoch invalidation after model restoration without retained memory, and the terminal failure state delivered to a new observer after browser-launch cancellation. The release instrumentation runner executed all twelve checks successfully on the physical Samsung test phone as part of the 27-assertion run. Model restoration and replacement observers are exercised directly; real browser login, rotation during login and a full process-kill/relaunch cycle still require an operator-configured IdP and device lifecycle verification.

The original `0159653` source with Nimbus 9.47 was compiled in an isolated harness to confirm the recorded regressions. Noncanonical signature bits could make the same initial signed token pass the refresh replay check. Malformed numeric date claims and repeated JWK `kid` members were also accepted. These cases require a token signed by the trusted issuer, or control of an already trusted issuer/JWKS response path. They demonstrate parser and replay-boundary weaknesses; they do not demonstrate signature forgery, certificate forgery or an unauthenticated outside caller signing in. Callback mutations still require the expected transaction state and issuer, followed by all token checks. Location decimal mutations require a malformed authorized API response and do not bypass device authorization or consent.

Publishable machine-readable records: [seeded results](android-evidence/seeded-junit-results.json) and [baseline regressions with attack prerequisites](android-evidence/baseline-regressions.json). They contain no keys, tokens or local absolute paths. Scope and remaining deployment boundaries are documented in [THREAT_MODEL.md](THREAT_MODEL.md). These are deterministic adversarial/property tests, not coverage-guided native fuzzing or an independent penetration-test certification.
