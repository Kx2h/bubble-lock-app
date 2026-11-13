# Release dependency inventory and SCA

The release inventory comes from Gradle's resolved `releaseRuntimeClasspath`, not a hand-written list of declared versions. It records the selected dependency graph, SHA-256 of each resolved Maven AAR/JAR, the built local AppAuth AAR, and native `.so` entries in AARs. The CycloneDX 1.6 generator also inventories the supplied APK/AAB and their packaged native files after Android processing. Artifact signing and production provisioning are separate release checks.

The exporter omits constraint-only edges on the application root, including Gradle's synthetic dependency-lock constraints, so lock-writing and strict lock-reading retain the same direct dependency edges; resolved components and library BOM constraints remain in the inventory.

The resolved graph can include libraries or metadata components whose code is partly or fully removed by R8. This inventory makes no class-level reachability claim. The APK/AAB and native-file hashes identify actual package bytes separately.

The default BOM contains the complete `pc-mobius/package-lock.json` v3 tree. Additional `--npm-application ID=LOCKFILE` inputs add independent application roots and namespaced installation references; the same node_modules location in two projects never collides. Development dependencies remain explicit `scope: excluded` components and are still scanned; root development and peer edges are included. Optional absent peers/platform dependencies are distinguished from missing required dependencies. Lock flags classify runtime/development/optional roles, without claiming exact browser bundle reachability. These npm dependencies belong to the PC Mobius host and are not asserted to be embedded in the Android APK. Their tarball integrity hashes come from the lockfile; this tool does not re-download every npm tarball. The lockfile itself has a SHA-256 entry. Dependency edges follow the nearest locked npm installation location, including nested versions.

Maven license names and URLs come from the resolved POMs, with a POM SHA-256 provenance field. Exact npm-version registry metadata supplies npm license declarations and a metadata hash. Recognized license names use SPDX identifiers; unresolved names remain explicit names rather than guessed SPDX identifiers. This is an inventory of declarations, not a license compliance opinion. Local AppAuth is represented as a fork of upstream 0.11.1, with its own built artifact hash and Apache-2.0 declaration.

## Reproduce

Set `WORKSPACE` to the workspace containing the tool installations and `REPO` to this checkout. Commands below write no credentials or absolute cache paths into the distributable reports. The Gradle exporter is applied from `app/app/build.gradle`.

```sh
export JAVA_HOME="$WORKSPACE/.local/jdk17"
export ANDROID_HOME="$WORKSPACE/.local/android"
export GRADLE_USER_HOME="$WORKSPACE/.local/gradle-home"
export ANDROID_USER_HOME="$WORKSPACE/.local/android-user"
export TMPDIR="$WORKSPACE/.local/tmp/security"
export PIP_CACHE_DIR="$WORKSPACE/.local/security-tools/pip-cache"
mkdir -p "$TMPDIR" "$ANDROID_USER_HOME"
export JAVA_TOOL_OPTIONS="-Duser.home=$ANDROID_USER_HOME -Djava.io.tmpdir=$TMPDIR"
python3 -m venv "$WORKSPACE/.local/security-tools/venv"
"$WORKSPACE/.local/security-tools/venv/bin/python" -m pip install --index-url https://pypi.org/simple --only-binary=:all: --require-hashes -r "$REPO/scripts/security/requirements.lock"

cd "$REPO/app"
"$WORKSPACE/.local/gradle-8.13/bin/gradle" --no-daemon --max-workers=2 :app:exportReleaseSecurityInventory

cd "$REPO"
export REPORT_DIR="$WORKSPACE/.local/release-inventory"
mkdir -p "$REPORT_DIR"
cp app/app/build/reports/security/release-inventory.json "$REPORT_DIR/release-inventory.json"
python3 scripts/security/release_inventory.py licenses --output "$REPORT_DIR/npm-licenses.json"
python3 scripts/security/prepare_notices.py \
  --inventory "$REPORT_DIR/release-inventory.json" \
  --gradle-cache "$GRADLE_USER_HOME/caches/modules-2/files-2.1"
python3 scripts/security/release_inventory.py generate \
  --inventory "$REPORT_DIR/release-inventory.json" \
  --npm-licenses "$REPORT_DIR/npm-licenses.json" \
  --apk app/app/build/outputs/apk/release/app-release-unsigned.apk \
  --aab app/app/build/outputs/bundle/release/app-release.aab \
  --output "$REPORT_DIR/release.cdx.json"
"$WORKSPACE/.local/security-tools/venv/bin/python" scripts/security/release_inventory.py validate \
  --sbom "$REPORT_DIR/release.cdx.json" \
  --schema-cache "$WORKSPACE/.local/security-tools/schemas" \
  --output "$REPORT_DIR/release-schema-validation.json"
python3 scripts/security/release_inventory.py scan \
  --sbom "$REPORT_DIR/release.cdx.json" \
  --output "$REPORT_DIR/release-osv.json" --fail-on-findings
PYTHONDONTWRITEBYTECODE=1 "$WORKSPACE/.local/security-tools/venv/bin/python" -m unittest discover -s scripts/security -p 'test_*.py' -v
```

The historical `app/security/evidence/` directory records 0.3.0; write new inventories to a separate report directory instead of replacing it. Run the export after the final release build so local AAR and package hashes describe the delivered output. A signed or otherwise changed artifact requires regenerating the BOM. Given identical inventory, npm license snapshot, lockfile and package bytes, BOM generation is deterministic. This does not claim that the Android build itself is byte-reproducible.

Prepare `assets/licenses/open_source_licenses.txt` before the final Android package build. `prepare_notices.py` checks the exact resolved Maven artifact hashes and follows real cached parent POMs when a license is inherited. It preserves original official Apache/MPL texts, the local AppAuth LICENSE, MapLibre's release-pinned Android/core notices and legal files found inside the resolved archives. Its manifest records source URLs and SHA-256. Downloaded official texts must match `app/security/legal-source-lock.json`. The app reads this notice index offline. When dependency versions change, refresh these notices and rebuild; the generator rejects a mismatch between their coordinate list and the final release graph. Official MapLibre notice URLs are deliberately pinned to 13.7.1 and require an explicit update for a new SDK release.

Validation uses the official CycloneDX 1.6 JSON schema and its SPDX/JSF references from the specification's `1.6` tag. Canonical schema snapshot hashes are pinned in [schema-lock.json](../../app/security/schema-lock.json); a changed cache fails validation. The validator also checks unique component references, complete graph-node coverage, no dangling dependency references, and absence of local absolute/cache paths. Validation downloads only these explicitly selected official schemas into workspace-local tooling storage.

OSV receives exact ecosystem/package/version queries in batches of at most 100. Each package's `next_page_token` is followed independently, and repeated tokens, incomplete results and network failures prevent a clean result. The report preserves request/response evidence, all matched advisory records and the checked BOM's SHA-256. Exit status is 0 for completed scans, 1 for findings with `--fail-on-findings`, and 2 for errors. Test cases exercise pagination, incomplete responses, looping tokens, missing graph edges and nested npm resolution.

## Coverage limits

- OSV matches known package versions. Zero matches does not prove the absence of vulnerabilities or establish runtime exploitability.
- AppAuth's local patch does not change its upstream identity for OSV queries. Its modified source and binary require independent review.
- Native files are hashed before and after packaging, but their statically bundled third-party internals are not decomposed into verified names/versions. No native CVE-clean claim is made. ABI copies are separate file components.
- Android OS, external browser, IdP, map servers, host operating system and build-tool dependencies are outside this product-runtime BOM. The pinned Python requirements describe the validation tool environment separately.
- First-party code needs the threat review and attack/fuzz tests. SCA does not replace them.

## Integrated PC, web, CSE and Gateway inventory

The original `--npm-lock pc-mobius/package-lock.json` interface remains supported. Add the web project with `--npm-application web-dashboard=web-dashboard/package-lock.json` and CSE with `--npm-application cse-runtime=deployment/cse/artifacts/dependencies/package-lock.json` to both `licenses` and `generate`. Each project is a separate application with its own lock SHA256 and installation-path references; nested lock sources retain their actual repository-relative paths. The scanner deduplicates equal ecosystem/package/version queries while retaining every application context, scope and dependency kind in the result. Thus a development-only finding remains visible without being described as a shipped browser or Android vulnerability.

```sh
python3 scripts/security/release_inventory.py licenses \
  --npm-application web-dashboard=web-dashboard/package-lock.json \
  --npm-application cse-runtime=deployment/cse/artifacts/dependencies/package-lock.json \
  --output "$REPORT_DIR/npm-all-licenses.json"

"$WORKSPACE/.local/security-tools/venv/bin/python" scripts/security/release_inventory.py python-environment \
  --python "$REPO/.venv/bin/python" \
  --output "$REPORT_DIR/gateway-python-environment.json"

"$WORKSPACE/.local/security-tools/venv/bin/python" scripts/security/release_inventory.py generate \
  --inventory "$REPORT_DIR/release-inventory.json" \
  --npm-application web-dashboard=web-dashboard/package-lock.json \
  --npm-application cse-runtime=deployment/cse/artifacts/dependencies/package-lock.json \
  --npm-source-manifest cse-runtime=deployment/cse/artifacts/manifest.json \
  --npm-licenses "$REPORT_DIR/npm-all-licenses.json" \
  --python-environment "gateway-validation=$REPORT_DIR/gateway-python-environment.json" \
  --apk app/app/build/outputs/apk/release/app-release-unsigned.apk \
  --aab app/app/build/outputs/bundle/release/app-release.aab \
  --output "$REPORT_DIR/release.cdx.json"
```

The optional Python snapshot reads installed distribution metadata from an explicitly selected local interpreter. It records exact versions, metadata SHA256 and target marker environment, follows only the Gateway's declared runtime dependencies using PEP 508 markers/extras and verifies installed versions against their specifiers. Duplicate conflicting distributions, missing runtime dependencies and direct-URL requirements fail collection. Unrelated installed packages such as pip and local test helpers stay outside the product BOM. The snapshot is not a Python lockfile, verified wheel archive inventory, or evidence of the physical Pi/server installation. The separate SBOM validator environment is never added to the Gateway product closure.

`--npm-source-manifest cse-runtime=deployment/cse/artifacts/manifest.json` additionally checks the tracked patched Mobius4 archive SHA256, the external and archive-internal npm lock hash, and every listed security/dependency patch target hash. It rejects archive path traversal, links, duplicate entries, missing targets and excessive content without extracting source files. The verified archive becomes a file component linked to the CSE application, with upstream revision/repository provenance. This identifies reviewed source bytes; it is not a deployed container image inventory, independent upstream signature verification, or a claim that the custom source patch removes every vulnerability. Container base images, Node runtime binaries and OS packages remain outside the npm package-version SCA.

Before a new Android artifact exists, `npm-bom` accepts the same npm and optional Python inputs and produces a clearly labelled host-only preflight BOM. Run its `validate` and `scan --fail-on-findings` checks separately. Do not describe this host-only result as the final APK/AAB inventory. When artifacts are ready, export their final Android graph and use `generate`, then validate and scan that exact BOM again.

`scripts/security/requirements.txt` lists the seven pinned validator/inventory-tool packages. `requirements.lock` contains the official PyPI SHA256 values for their published wheels/sdists and supports pip's `--require-hashes` mode. The 2026-11-14 provenance records verified the exact release/version JSON endpoints and freshly downloaded seven matching platform wheels, followed by offline installation and the tool tests in a new local venv. Other platform artifact hashes were recorded from official metadata; their bytes were not all downloaded. These bootstrap checks use HTTPS metadata and hashes, not independent publisher-signature authentication. The Python installer bootstrap supplied by `ensurepip` is a separate tool layer.

Current integration evidence is under [deployment-20261114](../../app/security/deployment-20261114/README.md). Final Android evidence is generated only after the final package build; 0.3.0 evidence is preserved.

Additional primary references: [Python installed metadata](https://packaging.python.org/en/latest/specifications/recording-installed-packages/), [packaging requirement and marker parsing](https://packaging.pypa.io/en/stable/requirements.html), [pip hash-checking mode](https://pip.pypa.io/en/stable/topics/secure-installs/), [official PyPI JSON API](https://docs.pypi.org/api/json/).

Official references: [CycloneDX 1.6 JSON schema](https://github.com/CycloneDX/specification/blob/1.6/schema/bom-1.6.schema.json), [Gradle resolved dependency result](https://docs.gradle.org/8.13/javadoc/org/gradle/api/artifacts/result/ResolutionResult.html), [Gradle artifact resolution query](https://docs.gradle.org/8.13/javadoc/org/gradle/api/artifacts/query/ArtifactResolutionQuery.html), [npm lockfile format](https://docs.npmjs.com/cli/v11/configuring-npm/package-lock-json/), [OSV batch API and pagination](https://google.github.io/osv.dev/post-v1-querybatch/).
