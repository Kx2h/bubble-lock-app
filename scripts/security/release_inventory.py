#!/usr/bin/env python3
"""Generate a resolved release SBOM, validate it, and query public OSV records."""

import argparse
import base64
import datetime
import hashlib
import importlib.metadata
import json
import pathlib
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid
import zipfile
from concurrent.futures import ThreadPoolExecutor


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
    specs = npm_inputs(args)
    records = []
    selected = sorted({(npm_name(location), item["version"]) for spec in specs for location, item in read_json(spec["path"])["packages"].items() if location})
    def collect(package):
        name, version = package
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
        return {"name": name, "version": version, "source": source, "licenses": choices,
                "metadata_sha256": sha256(json.dumps(metadata, sort_keys=True).encode())}
    with ThreadPoolExecutor(max_workers=8) as pool:
        records = list(pool.map(collect, selected))
    write_json(args.output, {"source": "npm exact-version registry metadata", "checked_at": stamp(), "packages": records,
                            "applications": [{"id": spec["id"], "lock_sha256": sha256(pathlib.Path(spec["path"]).read_bytes())} for spec in specs]})
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


def named_input(value):
    identifier, separator, path = value.partition("=")
    if not separator or not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", identifier) or not path:
        raise ValueError("Expected a unique application-id=path input")
    return identifier, path


def npm_inputs(args):
    result = [{"id": "pc-mobius", "path": args.npm_lock, "source": "pc-mobius/package-lock.json", "legacy": True}]
    names, paths = {"pc-mobius"}, {pathlib.Path(args.npm_lock).resolve()}
    for identifier, path in sorted(named_input(value) for value in getattr(args, "npm_application", []) or []):
        if identifier in names or pathlib.Path(path).resolve() in paths:
            raise ValueError("Duplicate npm application identity or lockfile")
        names.add(identifier); paths.add(pathlib.Path(path).resolve())
        result.append({"id": identifier, "path": path, "source": identifier + "/package-lock.json", "legacy": False})
    return result


def add_npm_application(spec, components, graph, parent_ref, license_records):
    lock = read_json(spec["path"])
    if lock.get("lockfileVersion") != 3:
        raise ValueError("Expected npm lockfile version 3")
    packages = lock["packages"]
    npm_root = "application:" + spec["id"]
    namespace = "npm-location:" if spec["legacy"] else "npm-location:" + spec["id"] + ":"
    deployment = "PC Mobius host" if spec["legacy"] else spec["id"] + " host/browser build"
    components.append({"bom-ref": npm_root, "type": "application", "name": packages[""]["name"], "version": packages[""]["version"],
                       "hashes": [{"alg": "SHA-256", "content": sha256(pathlib.Path(spec["path"]).read_bytes())}],
                       "properties": properties(inventory_source=spec["source"], deployment_scope=deployment + "; not embedded in Android APK", hash_scope="package-lock.json", dependency_coverage="complete lock tree including development, peer and optional packages; not a bundle reachability analysis")})
    graph[parent_ref].add(npm_root)
    npm_refs = {location: namespace + location for location in packages if location}
    counts = {"runtime": 0, "development": 0, "runtime-optional": 0}
    for location, item in sorted(packages.items()):
        reference = npm_root if not location else npm_refs[location]
        graph[reference] = set()
        dependencies = {**item.get("dependencies", {}), **item.get("optionalDependencies", {}), **item.get("peerDependencies", {})}
        if not location:
            dependencies.update(item.get("devDependencies", {}))
        for name in sorted(dependencies):
            try:
                graph[reference].add(npm_refs[resolve_npm(location, name, packages)])
            except ValueError:
                optional = name in item.get("optionalDependencies", {}) or item.get("peerDependenciesMeta", {}).get(name, {}).get("optional", False)
                if not optional:
                    raise
        if not location:
            continue
        if item.get("link") or not item.get("version"):
            raise ValueError("Unsupported unversioned/link dependency in npm lockfile")
        name = npm_name(location)
        kind = "development" if item.get("dev") else "runtime-optional" if item.get("optional") else "runtime"
        counts[kind] += 1
        component = {"bom-ref": reference, "type": "library", "name": name, "version": item["version"],
                     "purl": "pkg:npm/" + urllib.parse.quote(name, safe="/") + "@" + urllib.parse.quote(item["version"]),
                     "scope": "excluded" if kind == "development" else "optional" if kind == "runtime-optional" else "required",
                     "properties": properties(inventory_source=spec["source"], application_id=spec["id"], deployment_scope=deployment,
                                               dependency_kind=kind, npm_dev_optional=bool(item.get("devOptional")),
                                               integrity_scope="lockfile registry tarball integrity; tarball not re-downloaded")}
        license_record = license_records.get((name, item["version"]))
        if license_record:
            if license_record["licenses"]:
                component["licenses"] = license_record["licenses"]
            component["properties"] += properties(license_metadata_source=license_record["source"], license_metadata_sha256=license_record["metadata_sha256"])
        hashes = []
        for value in item.get("integrity", "").split():
            algorithm, encoded = value.split("-", 1)
            if algorithm in ("sha256", "sha384", "sha512", "sha1"):
                hashes.append({"alg": {"sha1": "SHA-1", "sha256": "SHA-256", "sha384": "SHA-384", "sha512": "SHA-512"}[algorithm], "content": base64.b64decode(encoded, validate=True).hex()})
        if hashes:
            component["hashes"] = hashes
        components.append(component)
    return {"application": spec["id"], "lock_source": spec["source"], "lock_sha256": sha256(pathlib.Path(spec["path"]).read_bytes()), "packages": sum(counts.values()), "kinds": counts}


def assemble_bom(root_ref, name, version, components, graph, coverage):
    metadata = {"bom-ref": root_ref, "type": "application", "name": name, "version": version}
    bom = {"$schema": "http://cyclonedx.org/schema/bom-1.6.schema.json", "bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
           "metadata": {"component": metadata, "properties": properties(coverage=coverage)},
           "components": sorted(components, key=lambda c: c["bom-ref"]), "dependencies": [{"ref": ref, "dependsOn": sorted(deps)} for ref, deps in sorted(graph.items())],
           "compositions": [{"aggregate": "incomplete", "assemblies": [root_ref]}]}
    encoded = json.dumps(bom, sort_keys=True).encode()
    bom["serialNumber"] = "urn:uuid:" + str(uuid.uuid5(uuid.NAMESPACE_URL, sha256(encoded)))
    verify_graph(bom)
    return bom


def generate_npm(args):
    components, root_ref = [], "release:bubble-lock-host-dependencies"
    graph = {root_ref: set()}
    licenses = read_json(args.npm_licenses) if args.npm_licenses else {"packages": []}
    records = {(item["name"], item["version"]): item for item in licenses["packages"]}
    scopes = [add_npm_application(spec, components, graph, root_ref, records) for spec in npm_inputs(args)]
    python_snapshots = getattr(args, "python_environment", []) or []
    for value in python_snapshots:
        identifier, path = named_input(value)
        add_python_environment(identifier, path, components, graph, root_ref)
    bom = assemble_bom(root_ref, "bubble-lock-host-dependencies", "inventory-snapshot", components, graph,
                       "Complete selected npm lock trees including development/peer/optional dependencies and optionally selected local Gateway Python runtime snapshots; excludes Android; not evidence of deployed server/Pi package versions or browser bundle reachability")
    write_json(args.output, bom)
    print(json.dumps({"result": "generated", "components": len(components), "applications": scopes}))


def python_runtime_closure(packages, root_name, environment):
    from packaging.requirements import Requirement
    from packaging.utils import canonicalize_name
    from packaging.version import Version
    by_name = {canonicalize_name(item["name"]): item for item in packages}
    if len(by_name) != len(packages) or root_name not in by_name:
        raise ValueError("Ambiguous Python inventory or missing Gateway root distribution")
    active = {root_name: set()}
    pending, graph = [root_name], {}
    while pending:
        name = pending.pop(0)
        graph[name] = set()
        for expression in by_name[name]["requires"]:
            requirement = Requirement(expression)
            if requirement.url:
                raise ValueError("Direct URL Python dependency needs separate provenance")
            if requirement.marker and not any(requirement.marker.evaluate({**environment, "extra": extra}) for extra in {"", *active[name]}):
                continue
            selected = canonicalize_name(requirement.name)
            if selected not in by_name:
                raise ValueError("Installed Python runtime dependency is missing: " + selected)
            if requirement.specifier and not requirement.specifier.contains(Version(by_name[selected]["version"]), prereleases=True):
                raise ValueError("Installed Python version violates declared dependency: " + selected)
            graph[name].add(selected)
            previous = active.get(selected)
            extras = set(requirement.extras)
            if previous is None or extras - previous:
                active.setdefault(selected, set()).update(extras)
                pending.append(selected)
    return sorted(active), {name: sorted(values) for name, values in graph.items()}


def collect_python_environment(args):
    from packaging.utils import canonicalize_name
    collector = '''import hashlib,importlib.metadata as m,json,os,platform,sys
rows=[]
for d in m.distributions():
 raw=d.read_text("METADATA") or d.read_text("PKG-INFO") or ""
 rows.append({"name":d.metadata["Name"],"version":d.version,"requires":d.requires or [],"metadata_sha256":hashlib.sha256(raw.encode()).hexdigest(),"license_expression":d.metadata.get("License-Expression"),"license_name":(d.metadata.get("License") or "").splitlines()[0][:256] if d.metadata.get("License") else None})
v=sys.implementation.version
iv="%s.%s.%s"%(v.major,v.minor,v.micro)
if v.releaselevel!="final":iv+={"alpha":"a","beta":"b","candidate":"rc"}[v.releaselevel]+str(v.serial)
env={"implementation_name":sys.implementation.name,"implementation_version":iv,"os_name":os.name,"platform_machine":platform.machine(),"platform_python_implementation":platform.python_implementation(),"platform_release":platform.release(),"platform_system":platform.system(),"platform_version":platform.version(),"python_full_version":platform.python_version(),"python_version":"%s.%s"%sys.version_info[:2],"sys_platform":sys.platform}
print(json.dumps({"environment":env,"packages":rows}))'''
    result = subprocess.run([args.python, "-I", "-c", collector], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
    if len(result.stdout) > LIMIT:
        raise ValueError("Python environment metadata exceeds limit")
    document = json.loads(result.stdout)
    selected = {}
    for item in document["packages"]:
        from packaging.requirements import Requirement
        from packaging.version import Version
        name = canonicalize_name(item["name"], validate=True)
        Version(item["version"])
        if len(item["requires"]) > 512:
            raise ValueError("Python dependency metadata exceeds bound")
        for value in item["requires"]:
            if len(value) > 2048 or Requirement(value).url:
                raise ValueError("Unsupported Python requirement URL or oversized metadata")
        if name in selected and selected[name] != item:
            raise ValueError("Multiple conflicting Python distributions: " + name)
        selected[name] = item
    packages = [selected[name] for name in sorted(selected)]
    root_name = canonicalize_name(args.root_distribution)
    runtime, graph = python_runtime_closure(packages, root_name, document["environment"])
    snapshot = {"format": "privacy-bubble.python-environment.v1", "checked_at": stamp(), "root_distribution": root_name,
                "environment": document["environment"], "packages": packages, "runtime_packages": runtime, "runtime_graph": graph,
                "excluded_installed_packages": sorted(set(selected) - set(runtime)),
                "scope": "Local Gateway validation interpreter snapshot; not a lockfile, physical Pi environment, or deployed server inventory. Only the declared runtime closure enters the product BOM."}
    write_json(args.output, snapshot)
    print(json.dumps({"result": "python-environment-collected", "installed_packages": len(packages), "runtime_packages": len(runtime), "excluded_installed_packages": len(snapshot["excluded_installed_packages"])}))


def add_python_environment(identifier, path, components, graph, parent_ref):
    from packaging.utils import canonicalize_name
    snapshot = read_json(path)
    if snapshot.get("format") != "privacy-bubble.python-environment.v1":
        raise ValueError("Expected a recorded Gateway Python environment")
    runtime, runtime_graph = python_runtime_closure(snapshot["packages"], snapshot["root_distribution"], snapshot["environment"])
    if runtime != snapshot["runtime_packages"] or runtime_graph != snapshot["runtime_graph"]:
        raise ValueError("Recorded Python runtime closure differs from metadata")
    packages = {canonicalize_name(item["name"]): item for item in snapshot["packages"]}
    refs = {name: "python-environment:" + identifier + ":" + name for name in runtime}
    root = snapshot["root_distribution"]
    graph[parent_ref].add(refs[root])
    for name in runtime:
        item = packages[name]
        component = {"bom-ref": refs[name], "type": "application" if name == root else "library", "name": name, "version": item["version"], "scope": "required",
                     "properties": properties(inventory_source="installed Python distribution METADATA", environment_id=identifier,
                                               deployment_scope="local Gateway validation environment; not physical Pi or deployed server",
                                               dependency_kind="first-party application" if name == root else "runtime",
                                               metadata_sha256=item["metadata_sha256"], environment_snapshot_sha256=sha256(pathlib.Path(path).read_bytes()),
                                               artifact_hash_scope="metadata only; installed wheel/archive bytes not independently reconstructed")}
        if name != root:
            component["purl"] = "pkg:pypi/" + urllib.parse.quote(name) + "@" + urllib.parse.quote(item["version"])
        license_name = item.get("license_expression") or item.get("license_name")
        if license_name:
            component["licenses"] = [license_choice(license_name)]
        components.append(component)
        graph[refs[name]] = {refs[dependency] for dependency in runtime_graph[name]}


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
    for spec in npm_inputs(args):
        add_npm_application(spec, components, graph, root_ref, license_records)
    for value in getattr(args, "python_environment", []) or []:
        identifier, path = named_input(value)
        add_python_environment(identifier, path, components, graph, root_ref)
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
    bom = assemble_bom(root_ref, "bubble-lock-release", inventory["version"], components, graph,
                       "Resolved Android release runtime + patched AppAuth AAR + native files + selected npm lock trees + optional installed Gateway Python runtime snapshot; excludes OS/browser/build toolchains and unknown native static internals")
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
        elif purl.startswith("pkg:pypi/"):
            ecosystem, name, version = "PyPI", component["name"], component["version"]
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
    contexts = {component["bom-ref"]: {"ref": component["bom-ref"], "scope": component.get("scope", "unspecified"),
                 "application": property_value(component, "privacybubble:application-id") or property_value(component, "privacybubble:environment-id"),
                 "dependency_kind": property_value(component, "privacybubble:dependency-kind"),
                 "deployment_scope": property_value(component, "privacybubble:deployment-scope")} for component in bom["components"]}
    for key in package_keys:
        records.append({"ecosystem": key[0], "name": key[1], "version": key[2], "component_refs": sorted(packages[key]),
                        "component_contexts": [contexts[reference] for reference in sorted(packages[key])], "vulnerability_ids": sorted(findings.get(key, set()))})
    report = {"format": "privacy-bubble.osv-sca.v1", "checked_at": stamp(), "sbom_sha256": sha256(pathlib.Path(args.sbom).read_bytes()),
              "status": "findings" if findings else "no-known-findings", "exact_package_versions": len(records), "affected_package_versions": len(findings),
              "unique_advisories": len(advisories), "querybatch_requests": len(trace), "packages": records, "advisories": advisories, "request_response_evidence": trace,
              "excluded_components": excluded, "limitations": ["OSV database matches are not proof of runtime exploitability or absence of vulnerabilities.", "Patched AppAuth is queried using upstream 0.11.1; local patches require separate review.", "Native file hashes do not identify statically bundled third-party versions; those internals are not scanned.", "Selected npm lock trees include development/build dependencies; they are separate applications, not Android APK contents or exact browser bundle reachability.", "Optional Python package queries describe the declared runtime closure of a local validation interpreter; they do not inventory a physical Pi or deployed server."]}
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
    generate_parser.add_argument("--npm-application", action="append", default=[], metavar="ID=LOCKFILE")
    generate_parser.add_argument("--npm-licenses")
    generate_parser.add_argument("--python-environment", action="append", default=[], metavar="ID=SNAPSHOT")
    generate_parser.add_argument("--android-notices", default="app/app/src/main/assets/licenses/manifest.json")
    generate_parser.add_argument("--apk")
    generate_parser.add_argument("--aab")
    generate_parser.add_argument("--output", required=True)
    generate_parser.set_defaults(function=generate)
    licenses_parser = commands.add_parser("licenses")
    licenses_parser.add_argument("--npm-lock", default="pc-mobius/package-lock.json")
    licenses_parser.add_argument("--npm-application", action="append", default=[], metavar="ID=LOCKFILE")
    licenses_parser.add_argument("--output", required=True)
    licenses_parser.set_defaults(function=collect_licenses)
    npm_parser = commands.add_parser("npm-bom")
    npm_parser.add_argument("--npm-lock", default="pc-mobius/package-lock.json")
    npm_parser.add_argument("--npm-application", action="append", default=[], metavar="ID=LOCKFILE")
    npm_parser.add_argument("--npm-licenses")
    npm_parser.add_argument("--python-environment", action="append", default=[], metavar="ID=SNAPSHOT")
    npm_parser.add_argument("--output", required=True)
    npm_parser.set_defaults(function=generate_npm)
    python_parser = commands.add_parser("python-environment")
    python_parser.add_argument("--python", required=True)
    python_parser.add_argument("--root-distribution", default="privacy-bubble-gateway")
    python_parser.add_argument("--output", required=True)
    python_parser.set_defaults(function=collect_python_environment)
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
