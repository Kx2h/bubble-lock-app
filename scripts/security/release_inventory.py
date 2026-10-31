#!/usr/bin/env python3
"""Generate a resolved release SBOM, validate it, and query public OSV records."""

import argparse
import base64
import datetime
import hashlib
import importlib.metadata
import json
import pathlib
import sys
import time
import urllib.parse
import urllib.request
import uuid
import zipfile


OSV = "https://api.osv.dev/v1/"
SCHEMA_BASE = "https://raw.githubusercontent.com/CycloneDX/specification/1.6/schema/"
LIMIT = 16 * 1024 * 1024


def read_json(path):
    return json.loads(pathlib.Path(path).read_text(encoding="utf-8"))


def write_json(path, value):
    target = pathlib.Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def stamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def property_value(component, name):
    return next((p["value"] for p in component.get("properties", []) if p["name"] == name), None)


def request_json(url, value=None):
    data = None if value is None else json.dumps(value).encode()
    request = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json", "User-Agent": "PrivacyBubble-release-inventory/1"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                content = response.read(LIMIT + 1)
                if len(content) > LIMIT:
                    raise ValueError("Public metadata response exceeds limit")
                return json.loads(content)
        except (urllib.error.URLError, TimeoutError):
            if attempt == 2:
                raise
            time.sleep(attempt + 1)


def properties(**values):
    return [{"name": "privacybubble:" + name.replace("_", "-"), "value": str(value)} for name, value in values.items()]


def license_choice(name, url=None):
    aliases = {"the apache software license, version 2.0": "Apache-2.0", "the apache license, version 2.0": "Apache-2.0",
               "apache license, version 2.0": "Apache-2.0", "apache 2.0": "Apache-2.0", "bsd 2-clause": "BSD-2-Clause",
               "the mit license": "MIT", "mit license": "MIT"}
    identifier = aliases.get(name.lower(), name)
    if url and "bsd-2-clause" in url.lower():
        identifier = "BSD-2-Clause"
    if " OR " in identifier or " AND " in identifier or " WITH " in identifier:
        return {"expression": identifier}
    license_value = {"id": identifier} if identifier in {"Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "MIT", "ISC", "0BSD", "CC0-1.0", "Unlicense"} else {"name": name}
    if url and urllib.parse.urlparse(url).scheme in ("http", "https"):
        license_value["url"] = url
    return {"license": license_value}


def collect_licenses(args):
    lock = read_json(args.npm_lock)
    records = []
    for location, item in sorted(lock["packages"].items()):
        if not location:
            continue
        name, version = npm_name(location), item["version"]
        source = "https://registry.npmjs.org/" + urllib.parse.quote(name, safe="") + "/" + urllib.parse.quote(version, safe="")
        metadata = request_json(source)
        if metadata.get("name") != name or metadata.get("version") != version:
            raise ValueError("Registry license metadata does not match locked package/version")
        value = metadata.get("license")
        if isinstance(value, str):
            choices = [license_choice(value)]
        elif isinstance(value, dict):
            choices = [license_choice(value.get("type", "Unspecified"), value.get("url"))]
        else:
            choices = []
        records.append({"name": name, "version": version, "source": source, "licenses": choices,
                        "metadata_sha256": sha256(json.dumps(metadata, sort_keys=True).encode())})
    write_json(args.output, {"source": "npm exact-version registry metadata", "checked_at": stamp(), "packages": records})
    print(json.dumps({"result": "license-metadata-collected", "npm_packages": len(records), "without_declared_license": sum(not r["licenses"] for r in records)}))


def npm_name(location):
    return location.rsplit("node_modules/", 1)[-1]


def resolve_npm(location, name, packages):
    parent = pathlib.PurePosixPath(location)
    for folder in (parent, *parent.parents):
        if folder.name == "node_modules":
            continue
        candidate = str(folder / "node_modules" / name)
        if candidate in packages:
            return candidate
    raise ValueError("Dependency missing from lockfile: " + name)


def generate(args):
    inventory = read_json(args.inventory)
    if inventory.get("format") != "privacy-bubble.security-inventory.v1" or inventory.get("configuration") != "releaseRuntimeClasspath":
        raise ValueError("Expected the resolved releaseRuntimeClasspath export")
    components = []
    graph = {}
    npm_licenses = read_json(args.npm_licenses) if args.npm_licenses else {"packages": []}
    license_records = {(item["name"], item["version"]): item for item in npm_licenses["packages"]}
    notices = read_json(args.android_notices)
    maven_notices = {item["coordinate"]: item for item in notices["maven_components"]}
    expected_coordinates = {item["group"] + ":" + item["name"] + ":" + item["version"] for item in inventory["components"] if item["kind"] == "maven"}
    if set(maven_notices) != expected_coordinates:
        raise ValueError("Offline legal notices do not match the resolved release dependency versions")
    for item in inventory["components"]:
        component = {"bom-ref": item["id"], "type": "application" if item["kind"] == "application" else "library",
                     "group": item["group"], "name": item["name"], "version": item["version"], "scope": "required"}
        component["properties"] = properties(inventory_source="Gradle releaseRuntimeClasspath", dependency_kind=item["kind"])
        declared_licenses = item.get("licenses", [])
        if item["kind"] == "maven":
            notice = maven_notices[item["group"] + ":" + item["name"] + ":" + item["version"]]
            declared_licenses = declared_licenses or notice["licenses"]
            component["properties"] += properties(license_pom_provenance=json.dumps(notice["pom_provenance"], sort_keys=True))
        if declared_licenses:
            component["licenses"] = [license_choice(license["name"], license.get("url")) for license in declared_licenses]
        if item.get("pomSha256"):
            component["properties"] += properties(pom_sha256=item["pomSha256"])
        if item["kind"] == "maven":
            component["purl"] = "pkg:maven/" + urllib.parse.quote(item["group"], safe=".") + "/" + urllib.parse.quote(item["name"]) + "@" + urllib.parse.quote(item["version"])
        elif item["kind"] == "local-fork":
            component["purl"] = "pkg:generic/privacybubble/appauth@" + item["version"]
            component["properties"] += properties(upstream_maven=item["group"] + ":" + item["name"], upstream_version=item["upstreamVersion"], fork="vendored source with documented state-log patch")
            if "licenses" not in component:
                component["licenses"] = [license_choice("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0")]
        artifacts = sorted(item["artifacts"], key=lambda a: a["name"])
        if len(artifacts) == 1:
            component["hashes"] = [{"alg": "SHA-256", "content": artifacts[0]["sha256"]}]
        graph[item["id"]] = set(item["dependencies"])
        for artifact in artifacts:
            component["properties"] += properties(**{"artifact:" + artifact["name"]: "sha256:" + artifact["sha256"]})
        components.append(component)
        for native in item["nativeFiles"]:
            reference = "native-aar:" + item["id"] + ":" + native["name"]
            components.append({"bom-ref": reference, "type": "file", "name": native["name"],
                               "hashes": [{"alg": "SHA-256", "content": native["sha256"]}],
                               "properties": properties(container_component=item["id"], native_stage="resolved AAR before Android stripping", native_internal_dependencies="not decomposed")})
            graph[item["id"]].add(reference)
            graph[reference] = set()
    root_ref = "release:bubble-lock"
    graph[root_ref] = {inventory["root"]}
    lock = read_json(args.npm_lock)
    if lock.get("lockfileVersion") != 3:
        raise ValueError("Expected npm lockfile version 3")
    packages = lock["packages"]
    npm_root = "application:pc-mobius"
    components.append({"bom-ref": npm_root, "type": "application", "name": packages[""]["name"], "version": packages[""]["version"],
                       "hashes": [{"alg": "SHA-256", "content": sha256(pathlib.Path(args.npm_lock).read_bytes())}],
                       "properties": properties(inventory_source="pc-mobius/package-lock.json", deployment_scope="PC Mobius host; not embedded in Android APK", hash_scope="package-lock.json")})
    graph[root_ref].add(npm_root)
    npm_refs = {location: "npm-location:" + location for location in packages if location}
    for location, item in sorted(packages.items()):
        reference = npm_root if not location else npm_refs[location]
        graph[reference] = set()
        for name in sorted({**item.get("dependencies", {}), **item.get("optionalDependencies", {})}):
            try:
                graph[reference].add(npm_refs[resolve_npm(location, name, packages)])
            except ValueError:
                if name not in item.get("optionalDependencies", {}):
                    raise
        if not location:
            continue
        if item.get("link") or not item.get("version"):
            raise ValueError("Unsupported unversioned/link dependency in npm lockfile")
        name = npm_name(location)
        component = {"bom-ref": reference, "type": "library", "name": name, "version": item["version"],
                     "purl": "pkg:npm/" + urllib.parse.quote(name, safe="/") + "@" + urllib.parse.quote(item["version"]),
                     "scope": "excluded" if item.get("dev") else "optional" if item.get("optional") else "required",
                     "properties": properties(inventory_source="pc-mobius/package-lock.json", deployment_scope="PC Mobius host", integrity_scope="lockfile registry tarball integrity; tarball not re-downloaded")}
        license_record = license_records.get((name, item["version"]))
        if license_record:
            if license_record["licenses"]:
                component["licenses"] = license_record["licenses"]
            component["properties"] += properties(license_metadata_source=license_record["source"], license_metadata_sha256=license_record["metadata_sha256"])
        integrity = item.get("integrity", "").split()
        hashes = []
        for value in integrity:
            algorithm, encoded = value.split("-", 1)
            if algorithm in ("sha256", "sha384", "sha512", "sha1"):
                hashes.append({"alg": {"sha1": "SHA-1", "sha256": "SHA-256", "sha384": "SHA-384", "sha512": "SHA-512"}[algorithm], "content": base64.b64decode(encoded, validate=True).hex()})
        if hashes:
            component["hashes"] = hashes
        components.append(component)
    for supplied, kind in ((args.apk, "apk"), (args.aab, "aab")):
        if not supplied:
            continue
        archive_path = pathlib.Path(supplied)
        artifact_ref = "artifact:android-release-" + kind
        components.append({"bom-ref": artifact_ref, "type": "file", "name": archive_path.name, "hashes": [{"alg": "SHA-256", "content": sha256(archive_path.read_bytes())}],
                           "properties": properties(artifact_stage=kind.upper() + " supplied to generator; signing/provisioning status is separately verified")})
        graph[inventory["root"]].add(artifact_ref)
        graph[artifact_ref] = set()
        with zipfile.ZipFile(archive_path) as archive:
            asset_prefix = "base/assets/licenses/" if kind == "aab" else "assets/licenses/"
            packaged_notices = json.loads(archive.read(asset_prefix + "manifest.json"))
            if packaged_notices.get("index_sha256") != sha256(archive.read(asset_prefix + "open_source_licenses.txt")):
                raise ValueError("Packaged offline notice index checksum differs")
            if {item["coordinate"] for item in packaged_notices["maven_components"]} != expected_coordinates:
                raise ValueError("Packaged offline notices do not match the resolved release graph")
            for entry in sorted(archive.namelist()):
                if (entry.startswith("lib/") or entry.startswith("base/lib/")) and entry.endswith(".so"):
                    reference = "native-" + kind + ":" + entry
                    components.append({"bom-ref": reference, "type": "file", "name": entry,
                                       "hashes": [{"alg": "SHA-256", "content": sha256(archive.read(entry))}],
                                       "properties": properties(native_stage="packaged " + kind.upper() + " after Android processing", native_internal_dependencies="not decomposed; no native-version SCA claim")})
                    graph[artifact_ref].add(reference)
                    graph[reference] = set()
    metadata = {"bom-ref": root_ref, "type": "application", "name": "bubble-lock-release", "version": inventory["version"]}
    bom = {"$schema": "http://cyclonedx.org/schema/bom-1.6.schema.json", "bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
           "metadata": {"component": metadata, "properties": properties(coverage="Resolved Android release runtime + patched AppAuth AAR + native files + pc-mobius npm lock; excludes OS/browser/build toolchains and unknown native static internals")},
           "components": sorted(components, key=lambda c: c["bom-ref"]), "dependencies": [{"ref": ref, "dependsOn": sorted(deps)} for ref, deps in sorted(graph.items())],
           "compositions": [{"aggregate": "incomplete", "assemblies": [root_ref]}]}
    encoded = json.dumps(bom, sort_keys=True).encode()
    bom["serialNumber"] = "urn:uuid:" + str(uuid.uuid5(uuid.NAMESPACE_URL, sha256(encoded)))
    verify_graph(bom)
    write_json(args.output, bom)
    print(json.dumps({"result": "generated", "components": len(components), "dependency_nodes": len(graph), "sha256": sha256(pathlib.Path(args.output).read_bytes())}))


def verify_graph(bom):
    refs = [component["bom-ref"] for component in bom["components"]] + [bom["metadata"]["component"]["bom-ref"]]
    if len(refs) != len(set(refs)):
        raise ValueError("Duplicate BOM reference")
    known = set(refs)
    graph_refs = [dependency["ref"] for dependency in bom["dependencies"]]
    if len(graph_refs) != len(set(graph_refs)) or set(graph_refs) != known:
        raise ValueError("Incomplete dependency graph coverage")
    if any(set(dependency["dependsOn"]) - known for dependency in bom["dependencies"]):
        raise ValueError("Dangling dependency graph reference")


def validate(args):
    import jsonschema
    from referencing import Registry, Resource
    cache = pathlib.Path(args.schema_cache)
    cache.mkdir(parents=True, exist_ok=True)
    names = ["bom-1.6.schema.json", "spdx.schema.json", "jsf-0.82.schema.json"]
    lock = read_json(pathlib.Path(__file__).resolve().parents[2] / "app/security/schema-lock.json")
    expected = {item["name"]: item["sha256"] for item in lock["schemas"]}
    store, hashes = {}, []
    for name in names:
        path = cache / name
        if not path.exists():
            schema = request_json(SCHEMA_BASE + name)
            write_json(path, schema)
        if sha256(path.read_bytes()) != expected[name]:
            raise ValueError("Official schema snapshot checksum mismatch: " + name)
        schema = read_json(path)
        hashes.append({"source": SCHEMA_BASE + name, "sha256": sha256(path.read_bytes())})
        for base in (SCHEMA_BASE, "http://cyclonedx.org/schema/", "https://cyclonedx.org/schema/"):
            store[base + name] = schema
        if "$id" in schema:
            store[schema["$id"]] = schema
    schema = store[SCHEMA_BASE + names[0]]
    jsonschema.Draft7Validator.check_schema(schema)
    registry = Registry().with_resources((uri, Resource.from_contents(value)) for uri, value in store.items())
    validator = jsonschema.Draft7Validator(schema, registry=registry, format_checker=jsonschema.FormatChecker())
    bom = read_json(args.sbom)
    validator.validate(bom)
    verify_graph(bom)
    encoded = pathlib.Path(args.sbom).read_bytes()
    if b"/home/" in encoded or b"/root/" in encoded or b".gradle/caches" in encoded:
        raise ValueError("Absolute workspace/cache path in distributable SBOM")
    report = {"result": "PASS", "checked_at": stamp(), "sbom_sha256": sha256(encoded), "specification": "CycloneDX 1.6 JSON Draft-07",
              "validator": "jsonschema " + importlib.metadata.version("jsonschema"), "official_schemas": hashes,
              "checks": ["official schema including external references and formats", "unique graph references", "all component graph nodes present", "no dangling references", "no local absolute/cache paths"]}
    write_json(args.output, report)
    print(json.dumps({"result": "schema-valid", "components": len(bom["components"])}))


def scan(args):
    bom = read_json(args.sbom)
    verify_graph(bom)
    packages = {}
    excluded = []
    for component in bom["components"]:
        purl = component.get("purl", "")
        if purl.startswith("pkg:maven/"):
            ecosystem, name, version = "Maven", component["group"] + ":" + component["name"], component["version"]
        elif purl.startswith("pkg:npm/"):
            ecosystem, name, version = "npm", component["name"], component["version"]
        elif property_value(component, "privacybubble:upstream-maven"):
            ecosystem, name, version = "Maven", property_value(component, "privacybubble:upstream-maven"), property_value(component, "privacybubble:upstream-version")
        else:
            excluded.append({"ref": component["bom-ref"], "reason": "first-party application, archive, or native file without verified package/version decomposition"})
            continue
        packages.setdefault((ecosystem, name, version), []).append(component["bom-ref"])
    package_keys = sorted(packages)
    records = []
    findings = {}
    trace = []
    for start in range(0, len(package_keys), 100):
        active = [{"key": key, "query": {"package": {"ecosystem": key[0], "name": key[1]}, "version": key[2]}, "tokens": set()} for key in package_keys[start:start + 100]]
        while active:
            request = {"queries": [item["query"] for item in active]}
            response = request_json(OSV + "querybatch", request)
            trace.append({"request": json.loads(json.dumps(request)), "response": response})
            results = response.get("results")
            if not isinstance(results, list) or len(results) != len(active):
                raise ValueError("Incomplete OSV batch result")
            continuation = []
            for item, result in zip(active, results):
                if result.get("error"):
                    raise ValueError("OSV query failed")
                key = item["key"]
                for vulnerability in result.get("vulns", []):
                    findings.setdefault(key, set()).add(vulnerability["id"])
                token = result.get("next_page_token")
                if token:
                    if token in item["tokens"] or len(item["tokens"]) >= 100:
                        raise ValueError("Repeated/excessive OSV pagination token")
                    item["tokens"].add(token)
                    item["query"]["page_token"] = token
                    continuation.append(item)
            active = continuation
    advisory_ids = sorted(set().union(*findings.values())) if findings else []
    advisories = {identifier: request_json(OSV + "vulns/" + urllib.parse.quote(identifier, safe="")) for identifier in advisory_ids}
    for key in package_keys:
        records.append({"ecosystem": key[0], "name": key[1], "version": key[2], "component_refs": sorted(packages[key]), "vulnerability_ids": sorted(findings.get(key, set()))})
    report = {"format": "privacy-bubble.osv-sca.v1", "checked_at": stamp(), "sbom_sha256": sha256(pathlib.Path(args.sbom).read_bytes()),
              "status": "findings" if findings else "no-known-findings", "exact_package_versions": len(records), "affected_package_versions": len(findings),
              "unique_advisories": len(advisories), "querybatch_requests": len(trace), "packages": records, "advisories": advisories, "request_response_evidence": trace,
              "excluded_components": excluded, "limitations": ["OSV database matches are not proof of runtime exploitability or absence of vulnerabilities.", "Patched AppAuth is queried using upstream 0.11.1; local patches require separate review.", "Native file hashes do not identify statically bundled third-party versions; those internals are not scanned.", "Node coverage is the complete checked-in pc-mobius package-lock tree, not an assertion that dependencies are embedded in the Android APK."]}
    write_json(args.output, report)
    print(json.dumps({name: report[name] for name in ("status", "exact_package_versions", "affected_package_versions", "unique_advisories", "querybatch_requests")}))
    if findings and args.fail_on_findings:
        return 1
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    generate_parser = commands.add_parser("generate")
    generate_parser.add_argument("--inventory", required=True)
    generate_parser.add_argument("--npm-lock", default="pc-mobius/package-lock.json")
    generate_parser.add_argument("--npm-licenses")
    generate_parser.add_argument("--android-notices", default="app/app/src/main/assets/licenses/manifest.json")
    generate_parser.add_argument("--apk")
    generate_parser.add_argument("--aab")
    generate_parser.add_argument("--output", required=True)
    generate_parser.set_defaults(function=generate)
    licenses_parser = commands.add_parser("licenses")
    licenses_parser.add_argument("--npm-lock", default="pc-mobius/package-lock.json")
    licenses_parser.add_argument("--output", required=True)
    licenses_parser.set_defaults(function=collect_licenses)
    validate_parser = commands.add_parser("validate")
    validate_parser.add_argument("--sbom", required=True)
    validate_parser.add_argument("--schema-cache", required=True)
    validate_parser.add_argument("--output", required=True)
    validate_parser.set_defaults(function=validate)
    scan_parser = commands.add_parser("scan")
    scan_parser.add_argument("--sbom", required=True)
    scan_parser.add_argument("--output", required=True)
    scan_parser.add_argument("--fail-on-findings", action="store_true")
    scan_parser.set_defaults(function=scan)
    args = parser.parse_args()
    try:
        return args.function(args) or 0
    except Exception as failure:
        print("Security inventory failed: " + type(failure).__name__ + ": " + str(failure), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
