# Mobile API security tests

Scope: the APP-owned `GET /v1/keyrings` adapter, its locally provisioned signing keys/bindings, and the IntegrationStore read projection. All network probes target ephemeral loopback listeners created by the tests. Tests generate RSA-2048 key pairs in memory; they never read production credentials, contact an identity provider, scan a public host or change system trust.

## Attack surface and vectors

| Surface | Tested vectors | Required result |
| --- | --- | --- |
| JWT protected header/signature | `none`/HMAC/PSS confusion, remote/embedded key directives, JOSE `crit`/`b64`, unknown/path/prototype-looking `kid`, duplicate/escaped duplicate JSON keys, malformed UTF-8, invalid base64url, nonzero unused encoding bits, altered signed bytes | 401; no protected state reads |
| JWT claims | Wrong issuer/audience, missing/expired/future times, numeric strings/booleans/fractions/unsafe integers, malformed subject, lone surrogates, incorrect scope types and conflicting `scope`/`scp` | 401/403; no caller-supplied principal coercion |
| Local trusted JWKS | Duplicate/unknown IDs, malformed `key_ops`, private parameters, disallowed algorithms/use, weak keys | Configuration rejection |
| Identity and grant projection | Cross-user bindings/events, wrong grant principal/device/ID, consent ID/scope/revision corruption, revocation/expiry, inherited map entries, prototype-looking own keys | Only explicitly bound devices; denied metadata cleared |
| HTTP parser and route | Duplicate Authorization/Host, CL/TE combinations, repeated Content-Length, chunked/fixed/huge-declared bodies, unsupported methods, encoded/traversal/query/absolute paths, excessive headers/target, injected control octets, incomplete headers | Bounded error response/connection closure; no protected reads or process crash |
| JSON files | Partial/lenient JSON, duplicate/escaped keys, malformed UTF-8, lone surrogates/BOM, nesting, missing/nonregular/oversized files, valid multichunk reads | Generic service failure; no content/path disclosure or file mutation |
| Resources | 20,000 events/100 visible rings, 20,001-event/101-ring overflow, UTF-8 response byte overflow, blocked reads above concurrency bound, slow header sockets, health after parser rejection | Bounded work, fail closed, service remains usable |

The deterministic corpus is [security-corpus.json](security-corpus.json). [security-fuzz.test.mjs](security-fuzz.test.mjs) adds seeded JWT wire mutation, strict-JSON grammar/differential mutation, location/tenant mutation, malformed HTTP syntax and raw byte fuzzing. Expected failures assert an actual security property, including zero protected reader calls, coordinate clearing and unchanged file bytes/mtime. Production state is never used.

## Confirmed findings and changes

1. A grant and consent both missing `revision`, with no consent scopes, produced an `available` coordinate in the baseline. The reader now requires matching positive safe-integer revisions, actual record IDs and a current consent location scope. The original reproduction and permanent corruption corpus cover the omission.
2. The baseline HTTP handler returned 200 for two Authorization headers when the first carried a valid generated test JWT and the second carried an invalid token. Node exposes the first value in `request.headers`; the handler now rejects duplicate headers in either order before reading any state. Duplicate Host is also rejected. This removes a proxy/parser ambiguity; it does not demonstrate signature forgery.
3. The baseline accepted a validly signed header containing duplicate `alg` members. JWT/file parsing now rejects literal and escaped duplicate keys, malformed Unicode and excessive nesting. Token signature encoding is also canonicalized, removing alternate encodings of identical signature bytes. These checks harden ambiguous representations; the tests do not claim an unsigned-token bypass existed.
4. A present `null` location expiry silently fell back to the default freshness window. Present malformed expiry now clears the fix. This was discovered by the seeded location mutation stage and remains covered there.
5. File reads formerly checked size and then called unbounded `readFile`, allowing a concurrent grow between those operations to defeat the allocation bound. Descriptor-based bounded reads now stop at `limit + 1`, reject nonregular files without blocking on a FIFO open, and close descriptors on all outcomes. This is a reviewed resource-bound correction; no production race was exercised.
6. Repeated keyrings for one UE caused the same 20,000 timestamps to be parsed up to 100 times. A baseline local measurement was 370.850 / 363.992 / 364.561 ms for three queries. One event scan now builds the visible-device index. Post-change measurements and runtime/seed metadata are in the receipts; timing is observational, with a generous two-second regression ceiling.

The concurrency guard also waits for both state and binding reads to settle when either fails. A regression combines an immediately throwing binding reader with a blocked state reader to ensure rejected requests cannot release slots while file work remains pending.

The fixture used by the original grant test now contains actual IntegrationStore grant/consent IDs, revisions and scopes. Existing administrator owner bindings without a `grantId` retain their explicit provisioning semantics.

## Local execution evidence

Node v22.23.4, 4,000 iterations per JWT/JSON/location mutation stage:

| Seed | Existing + security tests | Adversarial/property cases | Failures | 20k-event / 100-ring query range |
| --- | --- | --- | --- | --- |
| `0x5ec0a11` | 30/30 | 12,986 | 0 | 4.352–8.896 ms |
| `0xc0ffee` | 30/30 | 12,986 | 0 | 4.228–8.677 ms |
| `0xdeadbeef` | 30/30 | 12,986 | 0 | 4.720–12.621 ms |

Total: 38,958 cases, no observed crash or unexpected acceptance. Slow incomplete headers received 408 after approximately five seconds; saturated reads remained capped at two in the deliberately configured two-slot test. Measurement ranges are local observations, not a production latency promise. The [summary](security-evidence/summary.json), three seed receipts and matching TAP output are checked in alongside the corpus. Receipts identify the exact tested source by SHA-256; rerun after any source edit to refresh that association.

These receipts were refreshed after production source comment removal and the test-only `TMPDIR` selection fix. Seed `0x5ec0a11` used a separately configured temporary directory below workspace `.local/tmp/security/relocated-clone-check`, verifying that explicit `TMPDIR` controls fixture and receipt placement. Production API hashes and the prior `node --test pc-mobius/test/*.test.mjs` CORE/INTEGRATION 21/21 result were preserved, recorded in [core-final.tap](security-evidence/core-final.tap). Combined coverage is 51 distinct tests, with 111 successful test executions across the three API seed runs and the CORE/INTEGRATION run.

## Reproduce

Run from the repository root with the workspace Node installation:

```sh
export WORKSPACE=/home/kx2h/sharedir/mobius4
mkdir -p "$WORKSPACE/.local/tmp/security"
export TMPDIR="$WORKSPACE/.local/tmp/security"
MOBILE_FUZZ_SEED=0x5ec0a11 \
MOBILE_FUZZ_ITERATIONS=4000 \
MOBILE_FUZZ_RECEIPT="$TMPDIR/mobile-api-fuzz-5ec0a11.json" \
"$WORKSPACE/.local/node/bin/node" --test \
  pc-mobius/mobile-api/mobile-api.test.mjs \
  pc-mobius/mobile-api/security-fuzz.test.mjs
```

`MOBILE_FUZZ_SEED` accepts a nonzero 32-bit integer; `MOBILE_FUZZ_ITERATIONS` accepts 1–100,000 and defaults to 2,000 per JWT/JSON/location stage. HTTP mutation stages cap at 256 and 512 cases to keep CI bounded. Fresh test keys vary between runs; the seed fixes the mutation sequence and case IDs. Every receipt records source SHA-256 values, cases, test outcomes, failures and resource measurements. Temporary files and receipts follow the explicit `TMPDIR`; when it is unset they use workspace `.local/tmp/security`. Receipts must remain inside that selected temporary root and contain no tokens or private keys. The release gate verifies that `TMPDIR` is contained by `WORKSPACE_ROOT`. The checked-in [security-evidence](security-evidence/) contains the final local run receipts and TAP summaries.

## Practical limits

These local tests establish the stated regressions and invariants for the checked source. They do not establish security of the upstream decode-only mutation runner, the deployment reverse proxy, operating identity provider, MEC ingress, physical keyring, signing-key rotation or all possible traffic/interleavings. The service remains loopback-only and requires a production HTTPS proxy with deployment-specific connection/request limits and rate limiting. IntegrationStore writes are still nontransactional; a partial file fails with 503. The read-only projection cannot authenticate the provenance of state already written by an upstream actor.
