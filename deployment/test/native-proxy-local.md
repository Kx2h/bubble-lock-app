# Native Portal proxy fixture

This opt-in check reads the current `deployment/nginx.conf.template` and runs a workspace-local Nginx 1.30.6 against an isolated TLS 1.3 API fixture. It is deliberately named `.mjs`, so the normal `*.test.mjs` test glob does not start Nginx or build dependencies.

From the repository root, with Node 22+, OpenSSL and an explicitly selected Nginx binary already built inside the workspace:

```sh
NGINX_BIN="$WORKSPACE/.local/tools/nginx-validation/install/sbin/nginx" \
  node deployment/test/native-proxy-local.mjs
```

`WORKSPACE` is the `mobius4` directory containing `github_upload/bubble_lock`. The executable's real path must remain inside that workspace. The script requires version 1.30.6, creates its own CA/certificates, dummy bearer and Nginx runtime below `.local`, and binds temporary loopback listener, DNS and upstream ports. Only filesystem paths, MIME fixture include, ports, resolver and foreground worker count are adapted; the native proxy policy is read from the template. Its 20 checks cover syntax, the allowed GET, body/query/method/path rejection, the precise upstream header allowlist, incoming versus upstream client-certificate separation, and failure on a wrong upstream CA/name or a demand for mTLS.

All generated private material, owned child processes and sockets are cleaned in `finally`. The JSON result contains hashes and check names, with no tokens or private paths. No production endpoint, identity provider, account, system trust store or device is involved. This validates the local proxy policy; it does not validate a deployed stack or a Keycloak login. [Recorded result and build provenance](../../app/evidence/deployment-20261114/nginx-native-proxy.json).
