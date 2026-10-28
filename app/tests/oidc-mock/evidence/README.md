# Host protocol test receipt

ROLE: APP · DOC-REV: 2.0.0 · R10 · Test ID: APP-OIDC-MOCK-HOST-01 · 2026-10-28.

`node --test app/tests/oidc-mock/server.test.mjs` completed with two passing test cases. The suite creates throwaway certificates under `app/build`, runs an HTTPS mock identity provider, checks certificate trust, tests discovery, simulated consent and a valid S256 exchange, verifies the token signature and nonce, and rejects code replay, expiry, incorrect verifier and an unregistered redirect.

`app/tests/oidc-mock/test-java-integration.sh` completed with one passing test. A separate JVM with a throwaway per-process truststore connected the actual `OidcProvider`, `OidcRedirect` and `IdTokenVerifier` to the mock HTTPS provider. It exercised discovery, synthetic PKCE code exchange, JWKS-backed signature and claim validation, and rejection of a wrong nonce. A JVM using its normal truststore rejected the untrusted certificate. This is manual host code exchange using production Java verification and redirect classes; it is not an AppAuth, Android browser, mobile device or real identity provider test. The system and device trust stores were not changed.

`SHA256SUMS` records the captured TAP outputs.
