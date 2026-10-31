# Release dependency inventory and SCA

The release inventory comes from Gradle's resolved `releaseRuntimeClasspath`, not a hand-written list of declared versions. It records the selected dependency graph, SHA-256 of each resolved Maven AAR/JAR, the built local AppAuth AAR, and native `.so` entries in AARs. The CycloneDX 1.6 generator also inventories the supplied APK/AAB and their packaged native files after Android processing. Artifact signing and production provisioning are separate release checks.

The exporter omits constraint-only edges on the application root, including Gradle's synthetic dependency-lock constraints, so lock-writing and strict lock-reading retain the same direct dependency edges; resolved components and library BOM constraints remain in the inventory.

The resolved graph can include libraries or metadata components whose code is partly or fully removed by R8. This inventory makes no class-level reachability claim. The APK/AAB and native-file hashes identify actual package bytes separately.

The same BOM contains the complete `pc-mobius/package-lock.json` v3 tree. These npm dependencies belong to the PC Mobius host and are not asserted to be embedded in the Android APK. Their tarball integrity hashes come from the lockfile; this tool does not re-download every npm tarball. The lockfile itself has a SHA-256 entry. Dependency edges follow the nearest locked npm installation location, including nested versions.

Maven license names and URLs come from the resolved POMs, with a POM SHA-256 provenance field. Exact npm-version registry metadata supplies npm license declarations and a metadata hash. Recognized license names use SPDX identifiers; unresolved names remain explicit names rather than guessed SPDX identifiers. This is an inventory of declarations, not a license compliance opinion. Local AppAuth is represented as a fork of upstream 0.11.1, with its own built artifact hash and Apache-2.0 declaration.

## Reproduce

Set `WORKSPACE` to the workspace containing the tool installations and `REPO` to this checkout. Commands below write no credentials or absolute cache paths into the distributable reports. The Gradle exporter is applied from `app/app/build.gradle`.

```sh
export JAVA_HOME="$WORKSPACE/.local/jdk17"
export ANDROID_HOME="$WORKSPACE/.local/android"
export GRADLE_USER_HOME="$WORKSPACE/.local/gradle-home"
python3 -m venv "$WORKSPACE/.local/security-tools/venv"
"$WORKSPACE/.local/security-tools/venv/bin/python" -m pip install -r "$REPO/scripts/security/requirements.txt"

cd "$REPO/app"
"$WORKSPACE/.local/gradle-8.13/bin/gradle" --no-daemon --max-workers=2 :app:exportReleaseSecurityInventory

cd "$REPO"
mkdir -p app/security/evidence
cp app/app/build/reports/security/release-inventory.json app/security/evidence/release-inventory.json
python3 scripts/security/release_inventory.py licenses --output app/security/evidence/npm-licenses.json
python3 scripts/security/prepare_notices.py \
  --inventory app/security/evidence/release-inventory.json \
  --gradle-cache "$GRADLE_USER_HOME/caches/modules-2/files-2.1"
python3 scripts/security/release_inventory.py generate \
  --inventory app/security/evidence/release-inventory.json \
  --npm-licenses app/security/evidence/npm-licenses.json \
  --apk app/app/build/outputs/apk/release/app-release-unsigned.apk \
  --aab app/app/build/outputs/bundle/release/app-release.aab \
  --output app/security/evidence/release.cdx.json
"$WORKSPACE/.local/security-tools/venv/bin/python" scripts/security/release_inventory.py validate \
  --sbom app/security/evidence/release.cdx.json \
  --schema-cache "$WORKSPACE/.local/security-tools/schemas" \
  --output app/security/evidence/release-schema-validation.json
python3 scripts/security/release_inventory.py scan \
  --sbom app/security/evidence/release.cdx.json \
  --output app/security/evidence/release-osv.json --fail-on-findings
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/security -p 'test_*.py' -v
```

Run the export after the final release build so local AAR and package hashes describe the delivered output. A signed or otherwise changed artifact requires regenerating the BOM. Given identical inventory, npm license snapshot, lockfile and package bytes, BOM generation is deterministic. This does not claim that the Android build itself is byte-reproducible.

Prepare `assets/licenses/open_source_licenses.txt` before the final Android package build. `prepare_notices.py` checks the exact resolved Maven artifact hashes and follows real cached parent POMs when a license is inherited. It preserves original official Apache/MPL texts, the local AppAuth LICENSE, MapLibre's release-pinned Android/core notices and legal files found inside the resolved archives. Its manifest records source URLs and SHA-256. Downloaded official texts must match `app/security/legal-source-lock.json`. The app reads this notice index offline. When dependency versions change, refresh these notices and rebuild; the generator rejects a mismatch between their coordinate list and the final release graph. Official MapLibre notice URLs are deliberately pinned to 13.7.1 and require an explicit update for a new SDK release.

Validation uses the official CycloneDX 1.6 JSON schema and its SPDX/JSF references from the specification's `1.6` tag. Canonical schema snapshot hashes are pinned in [schema-lock.json](../../app/security/schema-lock.json); a changed cache fails validation. The validator also checks unique component references, complete graph-node coverage, no dangling dependency references, and absence of local absolute/cache paths. Validation downloads only these explicitly selected official schemas into workspace-local tooling storage.

OSV receives exact ecosystem/package/version queries in batches of at most 100. Each package's `next_page_token` is followed independently, and repeated tokens, incomplete results and network failures prevent a clean result. The report preserves request/response evidence, all matched advisory records and the checked BOM's SHA-256. Exit status is 0 for completed scans, 1 for findings with `--fail-on-findings`, and 2 for errors. Test cases exercise pagination, incomplete responses, looping tokens, missing graph edges and nested npm resolution.

## Coverage limits

- OSV matches known package versions. Zero matches does not prove the absence of vulnerabilities or establish runtime exploitability.
- AppAuth's local patch does not change its upstream identity for OSV queries. Its modified source and binary require independent review.
- Native files are hashed before and after packaging, but their statically bundled third-party internals are not decomposed into verified names/versions. No native CVE-clean claim is made. ABI copies are separate file components.
- Android OS, external browser, IdP, map servers, host operating system and build-tool dependencies are outside this product-runtime BOM. The pinned Python requirements describe the validation tool environment separately.
- First-party code needs the threat review and attack/fuzz tests. SCA does not replace them.

Official references: [CycloneDX 1.6 JSON schema](https://github.com/CycloneDX/specification/blob/1.6/schema/bom-1.6.schema.json), [Gradle resolved dependency result](https://docs.gradle.org/8.13/javadoc/org/gradle/api/artifacts/result/ResolutionResult.html), [Gradle artifact resolution query](https://docs.gradle.org/8.13/javadoc/org/gradle/api/artifacts/query/ArtifactResolutionQuery.html), [npm lockfile format](https://docs.npmjs.com/cli/v11/configuring-npm/package-lock-json/), [OSV batch API and pagination](https://google.github.io/osv.dev/post-v1-querybatch/).
