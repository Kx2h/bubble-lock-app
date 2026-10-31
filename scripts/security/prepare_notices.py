#!/usr/bin/env python3
"""Bundle resolved Android license declarations and unchanged official legal texts."""

import argparse
import hashlib
import json
import pathlib
import re
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
OFFICIAL_TEXTS = {
    "Apache-2.0.txt": "https://www.apache.org/licenses/LICENSE-2.0.txt",
    "MPL-2.0.txt": "https://www.mozilla.org/media/MPL/2.0/index.txt",
    "MapLibre-13.7.1-LICENSE.md": "https://raw.githubusercontent.com/maplibre/maplibre-native/android-v13.7.1/LICENSE.md",
    "MapLibre-13.7.1-Android-LICENSE.md": "https://raw.githubusercontent.com/maplibre/maplibre-native/android-v13.7.1/platform/android/LICENSE.md",
    "MapLibre-13.7.1-core-LICENSES.md": "https://raw.githubusercontent.com/maplibre/maplibre-native/android-v13.7.1/LICENSES.core.md",
}


def digest(value):
    return hashlib.sha256(value).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def cached_pom(cache, group, name, version, parents=()):
    coordinate = group + ":" + name + ":" + version
    if coordinate in parents or len(parents) > 8:
        raise ValueError("Cyclic/excessive POM parent inheritance")
    paths = list((cache / group / name / version).glob("*/*.pom"))
    if len(paths) != 1:
        raise ValueError("Expected exactly one cached resolved POM: " + coordinate)
    content = paths[0].read_bytes()
    if b"<!DOCTYPE" in content or b"<!ENTITY" in content:
        raise ValueError("DTD/entity declarations prohibited in legal metadata")
    root = ET.fromstring(content)
    values = [{"name": item.findtext("m:name", default="", namespaces=NAMESPACE), "url": item.findtext("m:url", default="", namespaces=NAMESPACE)}
              for item in root.findall("m:licenses/m:license", NAMESPACE)]
    sources = [{"coordinate": coordinate, "pom_sha256": digest(content)}]
    if not values:
        parent = root.find("m:parent", NAMESPACE)
        if parent is not None:
            identity = [parent.findtext("m:" + key, default="", namespaces=NAMESPACE) for key in ("groupId", "artifactId", "version")]
            if any(not value or "${" in value for value in identity):
                raise ValueError("Unresolved parent POM license identity")
            inherited, previous = cached_pom(cache, *identity, parents=(*parents, coordinate))
            values, sources = inherited, sources + previous
    return values, sources


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", required=True)
    parser.add_argument("--gradle-cache", required=True)
    parser.add_argument("--output", default="app/app/src/main/assets/licenses")
    args = parser.parse_args()
    inventory = json.loads(pathlib.Path(args.inventory).read_text())
    maplibre = [component for component in inventory["components"] if component.get("group") == "org.maplibre.gl" and component.get("name") == "android-sdk-vulkan-opengl"]
    if len(maplibre) != 1 or maplibre[0]["version"] != "13.7.1":
        raise ValueError("Update the pinned official MapLibre legal sources for the resolved SDK version")
    cache = pathlib.Path(args.gradle_cache)
    output = pathlib.Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    source_lock = json.loads((pathlib.Path(__file__).resolve().parents[2] / "app/security/legal-source-lock.json").read_text())
    expected_sources = {item["file"]: item for item in source_lock["legal_texts"]}
    files, declarations = [], []
    for filename, source in OFFICIAL_TEXTS.items():
        request = urllib.request.Request(source, headers={"User-Agent": "PrivacyBubble-license-notices/1"})
        with urllib.request.urlopen(request, timeout=40) as response:
            content = response.read(2_000_001)
        if len(content) > 2_000_000:
            raise ValueError("Legal text exceeds bound")
        if expected_sources[filename]["source"] != source or expected_sources[filename]["sha256"] != digest(content):
            raise ValueError("Official legal source checksum mismatch: " + filename)
        content.decode("utf-8", errors="strict")
        (output / filename).write_bytes(content)
        files.append({"file": filename, "source": source, "sha256": digest(content)})
    appauth = pathlib.Path(__file__).resolve().parents[2] / "app/vendor/appauth/LICENSE"
    content = appauth.read_bytes()
    (output / "AppAuth-0.11.1-LICENSE.txt").write_bytes(content)
    files.append({"file": "AppAuth-0.11.1-LICENSE.txt", "source": "vendored app/vendor/appauth/LICENSE; upstream AppAuth-Android 0.11.1", "sha256": digest(content)})
    for component in inventory["components"]:
        if component["kind"] != "maven":
            continue
        licenses, poms = cached_pom(cache, component["group"], component["name"], component["version"])
        if not licenses:
            raise ValueError("Missing resolved Maven license declaration: " + component["id"])
        coordinate = component["group"] + ":" + component["name"] + ":" + component["version"]
        declarations.append({"coordinate": coordinate, "licenses": licenses, "pom_provenance": poms})
        for artifact in component["artifacts"]:
            paths = list((cache / component["group"] / component["name"] / component["version"]).glob("*/" + artifact["name"]))
            if len(paths) != 1 or digest(paths[0].read_bytes()) != artifact["sha256"]:
                raise ValueError("Resolved artifact hash differs during notice extraction")
            with zipfile.ZipFile(paths[0]) as archive:
                for entry in sorted(archive.namelist()):
                    if not re.search(r"(^|/)(LICENSE|NOTICE|COPYING)([./_-]|$)", entry, re.IGNORECASE) or entry.endswith("/"):
                        continue
                    content = archive.read(entry)
                    filename = "artifact-" + hashlib.sha256((coordinate + ":" + entry).encode()).hexdigest()[:16] + ".txt"
                    (output / filename).write_bytes(content)
                    files.append({"file": filename, "source": coordinate + "!/" + entry, "sha256": digest(content)})
    manifest = {"format": "privacy-bubble.offline-notices.v1", "resolved_configuration": "releaseRuntimeClasspath", "maven_components": sorted(declarations, key=lambda c: c["coordinate"]),
                "local_fork": "net.openid:appauth:0.11.1-privacybubble.1", "legal_texts": sorted(files, key=lambda item: item["file"]),
                "limitations": "Upstream MapLibre Android/core legal notices are preserved verbatim. They are not an independently verified inventory of every static native component/version. npm host dependencies are catalogued in the separate supply-chain BOM, not bundled into Android."}
    write_json(output / "manifest.json", manifest)
    parts = ["Bubble 오픈소스 라이선스\n\n이 앱에서 사용하는 오픈소스 소프트웨어의 라이선스와 저작권 고지입니다.\n\n오픈소스 소프트웨어 목록\n"]
    for declaration in manifest["maven_components"]:
        parts.append("\n" + declaration["coordinate"] + "\n" + "\n".join("  " + license["name"] + " — " + license["url"] for license in declaration["licenses"]) + "\n")
    parts.append("\nLocal AppAuth source fork: upstream 0.11.1, Apache License 2.0. The source retains the documented privacy state-log modification.\n")
    for item in manifest["legal_texts"]:
        parts.append("\n\n" + "=" * 72 + "\n" + item["file"] + "\nSource: " + item["source"] + "\nSHA-256: " + item["sha256"] + "\n" + "=" * 72 + "\n\n")
        parts.append((output / item["file"]).read_text(encoding="utf-8"))
    index = "".join(parts).encode("utf-8")
    (output / "open_source_licenses.txt").write_bytes(index)
    manifest["index_sha256"] = digest(index)
    write_json(output / "manifest.json", manifest)
    print(json.dumps({"result": "offline-notices-ready", "resolved_maven_components": len(declarations), "original_legal_texts": len(files), "index_bytes": (output / "open_source_licenses.txt").stat().st_size}))


if __name__ == "__main__":
    main()
