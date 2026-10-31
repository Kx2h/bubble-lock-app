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
                    (default: app/security/evidence/npm-licenses.json).
  EXPECT_PRODUCTION_AUTH=1 requires MOBILE_API_BASE_URL, OIDC_ISSUER and
  OIDC_CLIENT_ID. Default 0 explicitly builds an unprovisioned profile.

Dependency lockfiles, verification metadata, source legal notices and signing
keys are never updated. Gradle uses strict dependency verification. OSV and
missing pinned schema snapshots require network access; scan failures or known
findings fail the gate. Logs may contain local build paths; review before sharing.
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
npm_licenses=${NPM_LICENSES_JSON:-"$app_root/security/evidence/npm-licenses.json"}
output_dir=$("$PYTHON_BIN" - "$workspace_root" "$output_dir" "$npm_licenses" <<'PY'
import importlib.metadata
import json
from pathlib import Path
import sys

workspace, output, licenses = map(Path, sys.argv[1:])
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
lock = json.loads(Path('pc-mobius/package-lock.json').read_text())
expected = {(path.rsplit('node_modules/', 1)[-1], value['version']) for path, value in lock['packages'].items() if path}
snapshot = json.loads(licenses.read_text())
actual = {(item['name'], item['version']) for item in snapshot['packages']}
if expected != actual:
    raise SystemExit('Reviewed npm license snapshot does not match package-lock.json; update it explicitly after review')
print(output)
PY
)
for file in app/gradle/verification-metadata.xml app/app/gradle.lockfile app/vendor/appauth/gradle.lockfile \
    app/app/src/main/assets/licenses/manifest.json app/scripts/check-release-security.py app/scripts/check-build-isolation.sh \
    scripts/security/release_inventory.py scripts/security/test_release_inventory.py \
    app/security/schema-lock.json app/security/legal-source-lock.json; do
  [[ -f $file ]] || fail "Required reviewed input is missing: $file"
done
[[ -f "$ANDROID_HOME/platforms/android-36/android.jar" ]] || fail 'Install Android SDK platform 36 before running the gate.'
profile=${EXPECT_PRODUCTION_AUTH:-0}
case $profile in
  0) [[ -z ${MOBILE_API_BASE_URL:-}${OIDC_ISSUER:-}${OIDC_CLIENT_ID:-} ]] || fail 'Set EXPECT_PRODUCTION_AUTH=1 to use a provisioned profile.' ;;
  1) [[ -n ${MOBILE_API_BASE_URL:-} && -n ${OIDC_ISSUER:-} && -n ${OIDC_CLIENT_ID:-} ]] || fail 'Provisioned profile requires all three public profile variables.' ;;
  *) fail 'EXPECT_PRODUCTION_AUTH must be 0 or 1.' ;;
esac
gradle=("$GRADLE_BIN" --no-daemon --max-workers=2 --dependency-verification=strict \
    "-PmobileApiBaseUrl=${MOBILE_API_BASE_URL:-}" "-PoidcIssuer=${OIDC_ISSUER:-}" "-PoidcClientId=${OIDC_CLIENT_ID:-}")
inspection=()
[[ $profile == 0 ]] || inspection+=(--configured-auth)
printf 'PASS: release-gate prerequisites; callback profile=%s\n' "$profile"
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
    --npm-lock pc-mobius/package-lock.json --npm-licenses "$npm_licenses" \
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
