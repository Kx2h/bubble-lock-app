#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: app/scripts/verify-release.sh [--check | --dry-run | --help]

Run the existing host release checks without installing or operating the app.
  --check     Validate tool versions, pinned Python packages and input paths only.
  --dry-run   Validate prerequisites and print the exact commands without running them.
  --help      Show this help without requiring the build environment.

Required environment:
  TMPDIR            Existing writable temporary directory inside WORKSPACE_ROOT.
  JAVA_HOME         JDK 17 installation.
  ANDROID_HOME      SDK with platform android-36 and build-tools/35.0.0.
  ANDROID_USER_HOME  Existing Android/JVM user directory inside WORKSPACE_ROOT.
  GRADLE_USER_HOME   Existing Gradle cache directory inside WORKSPACE_ROOT.

Tool selection:
  GRADLE_BIN        Gradle 8.13 executable (default: gradle).
  NODE_BIN          Node >=22 executable (default: node).
  PYTHON_BIN        Python with scripts/security/requirements.txt installed
                    (default: python3; no packages are installed by this script).

Paths and profile:
  WORKSPACE_ROOT    Workspace containing this checkout (default: checkout root).
  GATE_OUTPUT_DIR   Generated reports (default: app/app/build/reports/release-gate).
  NPM_LICENSES_JSON Reviewed exact-version license snapshot
                    (default: app/security/deployment-20261114/pc-npm-licenses.json).
  INCLUDE_WEB_INVENTORY=1 (default) adds the web lock and requires the reviewed
  NPM_ALL_LICENSES_JSON (default: deployment-20261114/npm-all-licenses.json).
  INCLUDE_GATEWAY_PYTHON=1 (default) adds the reviewed local Gateway runtime
  GATEWAY_PYTHON_SNAPSHOT (default: deployment-20261114/gateway-python-environment.json).
  INCLUDE_CSE_INVENTORY defaults to INCLUDE_WEB_INVENTORY (normally 1).
  CSE_NPM_LOCK defaults to deployment/cse/artifacts/dependencies/package-lock.json.
  CSE_SOURCE_MANIFEST defaults to deployment/cse/artifacts/manifest.json and
  binds the patched source archive, dependency lock and listed source patches.
  Set either INCLUDE_* value to 0 only for an explicitly narrower inventory.
  EXPECT_PRODUCTION_AUTH=1 requires MOBILE_API_BASE_URL, OIDC_ISSUER and
  OIDC_CLIENT_ID. Default 0 explicitly builds an unprovisioned profile.
  MOBILE_API_CONTRACT keyrings-v1 (default), portal-central-v1 or portal-web-v1.
  SERVER_PORTAL_ORIGIN with SERVER_PORTAL_CERTIFICATE builds the private-server
  BFF web-session profile using an exact app-only trust anchor and SPKI pin.
  This mode is separate from native OIDC provisioning and synthetic QA trust.

Dependency lockfiles, verification metadata, source legal notices and signing
keys are never updated. Gradle uses strict dependency verification. OSV and
missing pinned schema snapshots require network access; scan failures or known
findings fail the gate. Logs may contain local build paths; review before sharing.
Run the root npm run verify separately; this gate does not replace its integrated
web/Gateway/real-local-TLS verification stages.
USAGE
}

mode=run
case ${1:-} in
  '') ;;
  --check) mode=check ;;
  --dry-run) mode=dry ;;
  --help|-h) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac
[[ $# -le 1 ]] || { usage >&2; exit 2; }

fail() { printf 'Release gate: %s\n' "$*" >&2; exit 2; }
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
app_root="$repo_root/app"
workspace_root=$(cd -- "${WORKSPACE_ROOT:-$repo_root}" && pwd -P)
[[ $workspace_root != / ]] || fail 'WORKSPACE_ROOT must be a specific workspace.'
[[ $repo_root == "$workspace_root" || $repo_root == "$workspace_root/"* ]] || fail 'Checkout must be inside WORKSPACE_ROOT.'
: "${TMPDIR:?Set TMPDIR to an existing workspace-local temporary directory}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
: "${ANDROID_USER_HOME:?Set ANDROID_USER_HOME to an existing workspace-local directory}"
: "${GRADLE_USER_HOME:?Set GRADLE_USER_HOME to an existing workspace-local cache}"
[[ $TMPDIR == /* && -d $TMPDIR && -w $TMPDIR ]] || fail 'TMPDIR must be an existing writable absolute directory.'
temporary_root=$(cd -- "$TMPDIR" && pwd -P)
cache_root=$(cd -- "$GRADLE_USER_HOME" && pwd -P)
android_user_root=$(cd -- "$ANDROID_USER_HOME" && pwd -P)
for directory in "$temporary_root" "$cache_root" "$android_user_root"; do
  [[ $directory == "$workspace_root/"* ]] || fail 'TMPDIR, GRADLE_USER_HOME and ANDROID_USER_HOME must resolve inside WORKSPACE_ROOT.'
done
for directory in "$temporary_root" "$android_user_root"; do
  [[ $directory != *'"'* && $directory != *'\'* && $directory != *$'\n'* ]] || fail 'TMPDIR and ANDROID_USER_HOME must not contain quotes, backslashes or newlines.'
done
export TMPDIR="$temporary_root" GRADLE_USER_HOME="$cache_root" ANDROID_USER_HOME="$android_user_root" PYTHONDONTWRITEBYTECODE=1
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Djava.io.tmpdir=\"$TMPDIR\" -Duser.home=\"$ANDROID_USER_HOME\""
GRADLE_BIN=${GRADLE_BIN:-gradle}
NODE_BIN=${NODE_BIN:-node}
PYTHON_BIN=${PYTHON_BIN:-python3}
for executable in "$GRADLE_BIN" "$NODE_BIN" "$PYTHON_BIN" "$JAVA_HOME/bin/java" "$JAVA_HOME/bin/javac" \
    "$ANDROID_HOME/build-tools/35.0.0/aapt" "$ANDROID_HOME/build-tools/35.0.0/zipalign"; do
  command -v -- "$executable" >/dev/null 2>&1 || fail "Executable not found: $executable"
done
java_version=$("$JAVA_HOME/bin/java" -version 2>&1)
[[ $java_version == *'version "17.'* || $java_version == *'version "17"'* ]] || fail 'JDK 17 is required.'
gradle_version=$("$GRADLE_BIN" --version)
[[ $gradle_version == *$'\nGradle 8.13\n'* ]] || fail 'Gradle 8.13 is required.'
node_major=$("$NODE_BIN" -p 'process.versions.node.split(".")[0]')
[[ $node_major =~ ^[0-9]+$ ]] && ((node_major >= 22)) || fail 'Node >=22 is required.'

cd -- "$repo_root"
output_dir=${GATE_OUTPUT_DIR:-"$app_root/app/build/reports/release-gate"}
npm_licenses=${NPM_LICENSES_JSON:-"$app_root/security/deployment-20261114/pc-npm-licenses.json"}
all_npm_licenses=${NPM_ALL_LICENSES_JSON:-"$app_root/security/deployment-20261114/npm-all-licenses.json"}
gateway_snapshot=${GATEWAY_PYTHON_SNAPSHOT:-"$app_root/security/deployment-20261114/gateway-python-environment.json"}
include_web=${INCLUDE_WEB_INVENTORY:-1}
include_gateway=${INCLUDE_GATEWAY_PYTHON:-1}
include_cse=${INCLUDE_CSE_INVENTORY:-$include_web}
cse_lock=${CSE_NPM_LOCK:-"$repo_root/deployment/cse/artifacts/dependencies/package-lock.json"}
cse_manifest=${CSE_SOURCE_MANIFEST:-"$repo_root/deployment/cse/artifacts/manifest.json"}
for value in "$include_web" "$include_gateway" "$include_cse"; do
  [[ $value == 0 || $value == 1 ]] || fail 'INCLUDE_WEB_INVENTORY, INCLUDE_GATEWAY_PYTHON and INCLUDE_CSE_INVENTORY must be 0 or 1.'
done
output_dir=$("$PYTHON_BIN" - "$workspace_root" "$output_dir" "$npm_licenses" "$all_npm_licenses" "$include_web" "$gateway_snapshot" "$include_gateway" "$include_cse" "$cse_lock" "$cse_manifest" <<'PY'
import hashlib
import importlib.metadata
import json
from pathlib import Path
import re
import sys

workspace, output, licenses, combined = map(Path, sys.argv[1:5])
include_web, gateway, include_gateway = sys.argv[5:8]
include_cse, cse_lock, cse_manifest = sys.argv[8:11]
output = output.resolve()
output.relative_to(workspace)
if output == workspace:
    raise SystemExit('GATE_OUTPUT_DIR must be a dedicated directory inside WORKSPACE_ROOT')
for line in Path('scripts/security/requirements.txt').read_text().splitlines():
    if not line.strip() or line.lstrip().startswith('#'):
        continue
    package, version = line.strip().split('==')
    if importlib.metadata.version(package) != version:
        raise SystemExit('Install the reviewed Python requirements before running the gate: ' + package)
def verify_licenses(path, applications, allow_extra=False):
    expected, lock_hashes = set(), {}
    for identifier, lock_path in applications:
        raw = Path(lock_path).read_bytes()
        lock = json.loads(raw)
        if lock.get('lockfileVersion') != 3:
            raise SystemExit('Release gate requires npm lockfile version 3')
        expected.update((location.rsplit('node_modules/', 1)[-1], item['version']) for location, item in lock['packages'].items() if location)
        lock_hashes[identifier] = hashlib.sha256(raw).hexdigest()
    snapshot = json.loads(path.read_text())
    actual = {(item['name'], item['version']) for item in snapshot['packages']}
    if (not expected.issubset(actual) if allow_extra else expected != actual) or len(actual) != len(snapshot['packages']):
        raise SystemExit('Reviewed npm license snapshot coverage differs from the selected lockfiles; update explicitly after review')
    recorded = {item['id']: item['lock_sha256'] for item in snapshot.get('applications', [])}
    hashes_match = all(recorded.get(name) == digest for name, digest in lock_hashes.items()) if allow_extra else recorded == lock_hashes
    if not hashes_match or len(recorded) != len(snapshot.get('applications', [])):
        raise SystemExit('Reviewed npm license snapshot lock hashes differ from the selected inputs')
    if any(not item.get('licenses') for item in snapshot['packages']):
        raise SystemExit('Selected npm license snapshot has unresolved declarations requiring review')
pc = [('pc-mobius', 'pc-mobius/package-lock.json')]
verify_licenses(licenses, pc)
selected = list(pc)
if include_web == '1':
    selected.append(('web-dashboard', 'web-dashboard/package-lock.json'))
if include_cse == '1':
    for path in (Path(cse_lock), Path(cse_manifest)):
        path.resolve().relative_to(workspace)
    selected.append(('cse-runtime', cse_lock))
    sys.path.insert(0, str(Path('scripts/security').resolve()))
    import release_inventory
    release_inventory.verify_npm_source_manifest({'path': cse_lock, 'source_manifest': cse_manifest})
if include_web == '1' or include_cse == '1':
    verify_licenses(combined, selected, allow_extra=True)
if include_gateway == '1':
    sys.path.insert(0, str(Path('scripts/security').resolve()))
    import release_inventory
    snapshot = json.loads(Path(gateway).read_text())
    if snapshot.get('root_distribution') != 'privacy-bubble-gateway':
        raise SystemExit('Expected the reviewed Gateway distribution snapshot')
    release_inventory.add_python_environment('gateway-validation', gateway, [], {'root': set()}, 'root')
    source = Path('keyring/raspberry-pi-gateway/src/bubble_gateway/__init__.py').read_text()
    version = re.search(r'^__version__\s*=\s*[\'"]([^\'"]+)[\'"]', source, re.M)
    package = next(item for item in snapshot['packages'] if item['name'] == 'privacy-bubble-gateway')
    if version is None or version.group(1) != package['version']:
        raise SystemExit('Reviewed Gateway snapshot version differs from the current first-party source')
print(output)
PY
)
for file in app/gradle/verification-metadata.xml app/app/gradle.lockfile app/vendor/appauth/gradle.lockfile \
    app/app/src/main/assets/licenses/manifest.json app/scripts/check-release-security.py app/scripts/check-build-isolation.sh \
    scripts/security/release_inventory.py scripts/security/test_release_inventory.py \
    scripts/security/requirements.lock app/security/schema-lock.json app/security/legal-source-lock.json; do
  [[ -f $file ]] || fail "Required reviewed input is missing: $file"
done
[[ -f "$ANDROID_HOME/platforms/android-36/android.jar" ]] || fail 'Install Android SDK platform 36 before running the gate.'
profile=${EXPECT_PRODUCTION_AUTH:-0}
portal_origin=${SERVER_PORTAL_ORIGIN:-}
portal_certificate=${SERVER_PORTAL_CERTIFICATE:-}
api_contract=${MOBILE_API_CONTRACT:-keyrings-v1}
case $api_contract in
  keyrings-v1|portal-central-v1|portal-web-v1) ;;
  *) fail 'Unsupported MOBILE_API_CONTRACT.' ;;
esac
case $profile in
  0) [[ -z ${MOBILE_API_BASE_URL:-}${OIDC_ISSUER:-}${OIDC_CLIENT_ID:-} ]] || fail 'Set EXPECT_PRODUCTION_AUTH=1 to use a provisioned profile.' ;;
  1) [[ -n ${MOBILE_API_BASE_URL:-} && -n ${OIDC_ISSUER:-} && -n ${OIDC_CLIENT_ID:-} ]] || fail 'Provisioned profile requires all three public profile variables.' ;;
  *) fail 'EXPECT_PRODUCTION_AUTH must be 0 or 1.' ;;
esac
gradle=("$GRADLE_BIN" --no-daemon --max-workers=2 --dependency-verification=strict \
    "-PmobileApiBaseUrl=${MOBILE_API_BASE_URL:-}" "-PoidcIssuer=${OIDC_ISSUER:-}" "-PoidcClientId=${OIDC_CLIENT_ID:-}" "-PmobileApiContract=$api_contract")
inspection=()
[[ $profile == 0 ]] || inspection+=(--configured-auth)
if [[ -n $portal_origin$portal_certificate ]]; then
  [[ -n $portal_origin && -f $portal_certificate ]] || fail 'Portal profile requires SERVER_PORTAL_ORIGIN and SERVER_PORTAL_CERTIFICATE together.'
  [[ $profile == 0 && -z ${MOBILE_API_BASE_URL:-}${OIDC_ISSUER:-}${OIDC_CLIENT_ID:-} ]] || fail 'The BFF Portal profile is separate from native OIDC provisioning.'
  gradle+=("-PserverPortalOrigin=$portal_origin" "-PserverPortalCertificate=$portal_certificate")
  inspection+=(--portal-origin "$portal_origin" --portal-certificate "$portal_certificate")
fi
bom_inputs=(--npm-licenses "$npm_licenses")
if [[ $include_web == 1 || $include_cse == 1 ]]; then
  bom_inputs=(--npm-licenses "$all_npm_licenses")
fi
if [[ $include_web == 1 ]]; then
  bom_inputs+=(--npm-application web-dashboard=web-dashboard/package-lock.json)
fi
if [[ $include_cse == 1 ]]; then
  bom_inputs+=(--npm-application "cse-runtime=$cse_lock" --npm-source-manifest "cse-runtime=$cse_manifest")
fi
if [[ $include_gateway == 1 ]]; then
  bom_inputs+=(--python-environment "gateway-validation=$gateway_snapshot")
fi
printf 'PASS: release-gate prerequisites; callback profile=%s; API contract=%s; Portal=%s; web inventory=%s; CSE inventory=%s; Gateway snapshot=%s\n' "$profile" "$api_contract" "${portal_origin:-disabled}" "$include_web" "$include_cse" "$include_gateway"
[[ $mode != check ]] || exit 0
[[ $mode == dry ]] || mkdir -p -- "$output_dir"

run() {
  local label=$1
  shift
  printf '\n[%s]' "$label"
  printf ' %q' "$@"
  printf '\n'
  [[ $mode != dry ]] || return 0
  "$@" 2>&1 | tee "$output_dir/$label.log"
}

(
  cd -- "$app_root"
  run android "${gradle[@]}" :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintRelease \
      :app:assembleDebug :app:assembleRelease :app:bundleRelease
  run inventory "${gradle[@]}" :app:exportReleaseSecurityInventory "-PsecurityInventory=$output_dir/release-inventory.json"
)
run apk-inspection "$PYTHON_BIN" app/scripts/check-release-security.py \
    app/app/build/outputs/apk/release/app-release-unsigned.apk "${inspection[@]}" --output "$output_dir/release-inspection.json"
run build-isolation env "EXPECT_PRODUCTION_AUTH=$profile" "AAPT=$ANDROID_HOME/build-tools/35.0.0/aapt" bash app/scripts/check-build-isolation.sh
(
  cd -- "$repo_root/pc-mobius"
  run node-core "$NODE_BIN" --test test/*.test.mjs
  run node-mobile-api "$NODE_BIN" --test mobile-api/*.test.mjs
)
run security-tool-tests "$PYTHON_BIN" -m unittest discover -s scripts/security -p 'test_*.py' -v
run sbom "$PYTHON_BIN" scripts/security/release_inventory.py generate --inventory "$output_dir/release-inventory.json" \
    --npm-lock pc-mobius/package-lock.json "${bom_inputs[@]}" \
    --apk app/app/build/outputs/apk/release/app-release-unsigned.apk --aab app/app/build/outputs/bundle/release/app-release.aab \
    --output "$output_dir/release.cdx.json"
run sbom-schema "$PYTHON_BIN" scripts/security/release_inventory.py validate --sbom "$output_dir/release.cdx.json" \
    --schema-cache "$output_dir/schema-cache" --output "$output_dir/release-schema-validation.json"
run sbom-osv "$PYTHON_BIN" scripts/security/release_inventory.py scan --sbom "$output_dir/release.cdx.json" \
    --output "$output_dir/release-osv.json" --fail-on-findings
if [[ $mode == dry ]]; then
  printf '\nDry run complete; no gate stages executed.\n'
else
  printf '\nPASS: release gates complete. Generated reports: %s\n' "$output_dir"
fi
