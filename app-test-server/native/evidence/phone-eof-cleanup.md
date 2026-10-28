# Phone harness stdin EOF cleanup

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10. Checked on 2026-10-29 (Asia/Seoul) against the existing loopback Mobius4 CSE. This host check did not install or operate a phone application.

Command from the repository root:

```sh
APP_CORE_TEST_PROXY_PORT=0 "$WORKSPACE/.local/node/bin/node" app-test-server/native/core-app-bridge-check.mjs --phone < /dev/null
```

The harness created only `app-bridge-2d24cfb57809464e` and its test resource. It completed the CORE HTTP update and independent CSE/adapter readback. With stdin already closed, it returned after the ready message instead of waiting indefinitely. Cleanup returned HTTP 200 / oneM2M RSC 2002, followed by HTTP 404 / RSC 4004; both response request IDs matched. The final result was `cleanup: verified` and process exit code 0. The proxy used ephemeral port 42621 and closed on exit; the existing phone reverse mapping and bootstrap resource were unchanged.

Host regression tests independently cover EOF and Enter against isolated fake CSE resources. Abrupt process termination can still bypass cleanup; the printed unique AE name remains necessary for manual ownership checks in that case.
