# Reproduce the host release gates

[`../scripts/verify-release.sh`](../scripts/verify-release.sh) connects the existing checks and stops on the first failing command. It does not sign, install or launch an app, invoke ADB, change device state, install dependencies, regenerate legal assets, or approve new dependency hashes. A full invocation builds the default debug APK and optimized release APK/AAB, runs both JVM variants and release lint, exports the resolved release inventory, inspects the APK and build isolation, runs Node CORE/mobile API and Python security-tool tests, then generates, validates and scans the SBOM.

Use the reviewed Gradle **8.13**, JDK **17**, Node **22 or newer**, Android platform **36**, Android build-tools **35.0.0**, and a Python environment containing the exact pins in [`../../scripts/security/requirements.txt`](../../scripts/security/requirements.txt). Prepare dependencies and SDK packages explicitly before running the gate. `NODE_BIN`, `GRADLE_BIN` and `PYTHON_BIN` select the tool executables; they are each one executable path, rather than a shell command with arguments.

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
"$REPO/app/scripts/verify-release.sh"
```

`--help` works without a configured environment. `--check` checks executable versions, required files, exact Python pins, and coverage of the reviewed npm license snapshot against the current lockfile. `--dry-run` additionally prints the commands; neither runs tests, builds packages or performs a vulnerability query. Tool version checks can initialize tool caches. `TMPDIR` must already exist, be writable and resolve inside `WORKSPACE_ROOT`; `GRADLE_USER_HOME` and `ANDROID_USER_HOME` must also be existing directories inside the workspace. Java receives the temporary directory through `java.io.tmpdir` and the explicit Android user directory through `user.home`, so JVM tools keep their user state inside the workspace. The script defaults `WORKSPACE_ROOT` to the checkout root if it is omitted. Explicitly set a larger workspace when tools and temporary files live beside the checkout.

The default profile explicitly passes empty issuer, client and API properties to Gradle. This produces an unprovisioned release whose manifest omits the AppAuth callback receiver. For an operator-provisioned candidate, supply the three public build profile values and enable the matching manifest expectation:

```sh
export EXPECT_PRODUCTION_AUTH=1
export MOBILE_API_BASE_URL='https://api.example.org'
export OIDC_ISSUER='https://identity.example.org/realm'
export OIDC_CLIENT_ID='mobile-client'
"$REPO/app/scripts/verify-release.sh"
```

The configured run uses `check-release-security.py --configured-auth` and `EXPECT_PRODUCTION_AUTH=1` for `check-build-isolation.sh`. The default uses neither expectation. Gradle validates HTTPS origins and complete profiles; build isolation also rejects a synthetic CA in the default debug build. Do not supply `oidcTestCa` through user Gradle properties to this gate.

Both Gradle invocations enforce `--dependency-verification=strict`. The second invocation exports inventory after packaging so its local AppAuth AAR describes the final build. The gate never passes `--write-locks`, `--write-verification-metadata`, or a verification-disable flag. A new dependency, changed checksum, changed official schema or changed legal text requires explicit review and a separate update of the corresponding tracked inputs. A missing or mismatched npm license snapshot also fails: update it deliberately using the exact-version metadata procedure in [SUPPLY_CHAIN.md](../../docs/security/SUPPLY_CHAIN.md), review it, and prepare legal assets before packaging when dependencies change.

Reports and stage logs default to `app/app/build/reports/release-gate/`; `GATE_OUTPUT_DIR` can select another dedicated directory inside the workspace. The gate uses the reviewed `app/security/evidence/npm-licenses.json` snapshot unless `NPM_LICENSES_JSON` selects another reviewed snapshot. Schema validation can download the official schema snapshots into the gate's schema cache and checks their tracked hashes. OSV receives exact package/version queries; errors, incomplete responses, and known findings with `--fail-on-findings` fail the gate. A successful scan reports the database state at scan time and does not establish absence of vulnerabilities.

Node tests create temporary fixtures and ephemeral loopback listeners. They do not query a production IdP or device. Host logs can contain build paths; review them before publishing. Physical-device instrumentation, real IdP lifecycle tests, production signing, deployment authentication/rate limits, and any final signed/provisioned artifact inventory remain separate release checks. A changed APK/AAB requires a fresh inventory/SBOM. Test scope and actual recorded results are in [ANDROID_TESTS.md](ANDROID_TESTS.md) and [README.md](README.md).
