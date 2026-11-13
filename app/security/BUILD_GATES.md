# Reproduce the host release gates

[`../scripts/verify-release.sh`](../scripts/verify-release.sh) connects the existing checks and stops on the first failing command. It does not sign, install or launch an app, invoke ADB, change device state, install dependencies, regenerate legal assets, or approve new dependency hashes. A full invocation builds the default debug APK and optimized release APK/AAB, runs both JVM variants and release lint, exports the resolved release inventory, inspects the APK and build isolation, runs Node CORE/mobile API and Python security-tool tests, then generates, validates and scans the SBOM. The default SBOM includes the PC, web and CSE npm lock trees, the verified patched CSE source archive, and the reviewed local Gateway Python runtime snapshot.

**Both commands are required for the integrated release:** run the repository-root `npm run verify` and this release gate. The root command covers its integrated web/Gateway/local TLS/contract stages; this gate does not execute or replace those stages. A gate success alone must not be reported as a fresh pass of the root verification workflow.

Use the reviewed Gradle **8.13**, JDK **17**, Node **22 or newer** (the integrated web workflow requires **22.19 or newer**), Android platform **36**, Android build-tools **35.0.0**, and a Python environment containing the exact pins in [`../../scripts/security/requirements.txt`](../../scripts/security/requirements.txt). The hash-checked bootstrap is documented in [SUPPLY_CHAIN.md](../../docs/security/SUPPLY_CHAIN.md) using `requirements.lock`. Prepare dependencies and SDK packages explicitly before running the gate. `NODE_BIN`, `GRADLE_BIN` and `PYTHON_BIN` select the tool executables; they are each one executable path, rather than a shell command with arguments.

From the workspace that contains the checkout and tool installations, adapt `REPO` to the checkout:

```sh
export WORKSPACE_ROOT="$(pwd -P)"
export REPO="$WORKSPACE_ROOT/github_upload/bubble_lock"
export TMPDIR="$WORKSPACE_ROOT/.local/tmp/release-gate"
mkdir -p "$TMPDIR"
export JAVA_HOME="$WORKSPACE_ROOT/.local/jdk17"
export ANDROID_HOME="$WORKSPACE_ROOT/.local/android"
export ANDROID_USER_HOME="$WORKSPACE_ROOT/.local/android-user"
export GRADLE_USER_HOME="$WORKSPACE_ROOT/.local/gradle-home"
export GRADLE_BIN="$WORKSPACE_ROOT/.local/gradle-8.13/bin/gradle"
export NODE_BIN="$WORKSPACE_ROOT/.local/node/bin/node"
export PYTHON_BIN="$WORKSPACE_ROOT/.local/security-tools/venv/bin/python"

"$REPO/app/scripts/verify-release.sh" --check
"$REPO/app/scripts/verify-release.sh" --dry-run

cd "$REPO"
PATH="$WORKSPACE_ROOT/.local/node/bin:$PATH" npm run verify
"$REPO/app/scripts/verify-release.sh"
```

`--help` works without a configured environment. `--check` checks executable versions, required files, exact Python pins, and coverage plus exact selected-lock hashes of the reviewed npm license snapshots. It verifies the CSE archive, its external/internal lock and listed source patch hashes without extracting it. It also recalculates the selected Gateway snapshot's runtime closure and checks its first-party version against current source. Missing snapshots, incomplete/mismatched lock coverage, altered recorded lock or archive hashes, unsafe archive paths, unresolved npm declarations, invalid Python closure, or a stale Gateway source version fail preflight. `--dry-run` additionally prints the commands; neither runs tests, builds packages or performs a vulnerability query. Tool version checks can initialize tool caches. `TMPDIR` must already exist, be writable and resolve inside `WORKSPACE_ROOT`; `GRADLE_USER_HOME` and `ANDROID_USER_HOME` must also be existing directories inside the workspace. Java receives the temporary directory through `java.io.tmpdir` and the explicit Android user directory through `user.home`, so JVM tools keep their user state inside the workspace. The script defaults `WORKSPACE_ROOT` to the checkout root if it is omitted. Explicitly set a larger workspace when tools and temporary files live beside the checkout.

The default profile explicitly passes empty issuer, client and API properties to Gradle. This produces an unprovisioned release whose manifest omits the AppAuth callback receiver. For an operator-provisioned candidate, supply the three public build profile values and enable the matching manifest expectation:

```sh
export EXPECT_PRODUCTION_AUTH=1
export MOBILE_API_BASE_URL='https://api.example.org'
export OIDC_ISSUER='https://identity.example.org/realm'
export OIDC_CLIENT_ID='mobile-client'
export MOBILE_API_CONTRACT='portal-central-v1'
"$REPO/app/scripts/verify-release.sh"
```

The configured run uses `check-release-security.py --configured-auth` and `EXPECT_PRODUCTION_AUTH=1` for `check-build-isolation.sh`. The default uses neither expectation. Gradle validates HTTPS origins and complete profiles; build isolation also rejects a synthetic CA in the default debug build. Do not supply `oidcTestCa` through user Gradle properties to this gate.

Both Gradle invocations enforce `--dependency-verification=strict`. The second invocation exports inventory after packaging so its local AppAuth AAR describes the final build. The gate never passes `--write-locks`, `--write-verification-metadata`, or a verification-disable flag. A new dependency, changed checksum, changed official schema or changed legal text requires explicit review and a separate update of the corresponding tracked inputs. A missing or mismatched npm license snapshot also fails: update it deliberately using the exact-version metadata procedure in [SUPPLY_CHAIN.md](../../docs/security/SUPPLY_CHAIN.md), review it, and prepare legal assets before packaging when dependencies change.

Reports and stage logs default to `app/app/build/reports/release-gate/`; `GATE_OUTPUT_DIR` can select another dedicated directory inside the workspace. Historical 0.3.0 evidence remains unchanged. The reviewed inputs default to:

| Environment variable | Default reviewed input / behavior |
| --- | --- |
| `NPM_LICENSES_JSON` | `app/security/deployment-20261114/pc-npm-licenses.json`, matching only the PC lock |
| `NPM_ALL_LICENSES_JSON` | `app/security/deployment-20261114/npm-all-licenses.json`, covering PC/web/CSE exact versions and reviewed lock hashes |
| `INCLUDE_WEB_INVENTORY` | `1`; adds the separate web application and its runtime/development/optional lock entries |
| `INCLUDE_CSE_INVENTORY` | Defaults to `INCLUDE_WEB_INVENTORY`, normally `1`; adds the CSE runtime and reviewed patched source archive |
| `CSE_NPM_LOCK` | `deployment/cse/artifacts/dependencies/package-lock.json` |
| `CSE_SOURCE_MANIFEST` | `deployment/cse/artifacts/manifest.json`, binding the archive, lock and listed patched files |
| `GATEWAY_PYTHON_SNAPSHOT` | `app/security/deployment-20261114/gateway-python-environment.json`, a recorded local validation interpreter snapshot |
| `INCLUDE_GATEWAY_PYTHON` | `1`; adds only the Gateway's declared runtime closure from the reviewed snapshot |

Set an `INCLUDE_*` variable to `0` only for an explicitly narrower inventory. Setting `INCLUDE_WEB_INVENTORY=0` and `INCLUDE_GATEWAY_PYTHON=0` retains the earlier PC-only mode because the CSE default follows the web flag; `INCLUDE_CSE_INVENTORY=1` can explicitly retain CSE in that case. A combined reviewed license snapshot may cover more applications than the selected subset, but every selected exact version and selected lock hash must match. Only selected applications enter the generated BOM/SCA. The PC-only snapshot remains an exact PC-lock check. Narrowing inventory does not disable strict metadata checks or the separate required root verification command. The tool logs the selected flags and keeps local Gateway validation, web development tooling, CSE/PC host runtime and Android packaging distinct.

The gate consumes reviewed snapshots; it does not refresh them or install the Python environment. After a reviewed dependency/source change, regenerate the corresponding npm/Python snapshots deliberately using [SUPPLY_CHAIN.md](../../docs/security/SUPPLY_CHAIN.md), review them, then rerun preflight. A local Gateway snapshot is not proof of the physical Pi's or remote server's installed packages. Validator tooling is outside that product runtime closure.

Schema validation can download the official schema snapshots into the gate's schema cache and checks their tracked hashes. OSV receives exact package/version queries; errors, incomplete responses, and known findings with `--fail-on-findings` fail the gate. Development packages are queried while retaining their excluded/runtime scope. A successful scan reports the database state at scan time and does not establish absence of vulnerabilities.

Node tests create temporary fixtures and ephemeral loopback listeners. They do not query a production IdP or device. Host logs can contain build paths; review them before publishing. Physical-device instrumentation, real IdP lifecycle tests, production signing, deployment authentication/rate limits, and any final signed/provisioned artifact inventory remain separate release checks. A changed APK/AAB requires a fresh inventory/SBOM. Test scope and actual recorded results are in [ANDROID_TESTS.md](ANDROID_TESTS.md) and [README.md](README.md).

For the deployed portal, select `portal-central-v1` only after registering the native public client and exposing its authenticated read route. `portal-web-v1` selects the normalized WEB wire shape; it does not convert browser session cookies into a native bearer. The default `keyrings-v1` remains the older standalone mobile contract. See [deployment preparation](../deployment/README.md).
