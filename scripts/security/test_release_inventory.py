import argparse
import contextlib
import io
import pathlib
import tempfile
import tarfile
import unittest
from unittest.mock import patch

import release_inventory as inventory


class SecurityInventoryTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.bom = pathlib.Path(self.directory.name) / "bom.json"
        self.output = pathlib.Path(self.directory.name) / "sca.json"
        self.document = {"metadata": {"component": {"bom-ref": "root"}}, "components": [
            {"bom-ref": "a", "group": "example", "name": "a", "version": "1.0", "purl": "pkg:maven/example/a@1.0"},
            {"bom-ref": "b", "group": "example", "name": "b", "version": "2.0", "purl": "pkg:maven/example/b@2.0"}],
            "dependencies": [{"ref": "root", "dependsOn": ["a", "b"]}, {"ref": "a", "dependsOn": []}, {"ref": "b", "dependsOn": []}]}
        inventory.write_json(self.bom, self.document)
        self.arguments = argparse.Namespace(sbom=self.bom, output=self.output, fail_on_findings=True)

    def run_scan(self, side_effect):
        with patch.object(inventory, "request_json", side_effect=side_effect), contextlib.redirect_stdout(io.StringIO()):
            return inventory.scan(self.arguments)

    def test_independent_package_pagination_is_fully_collected(self):
        calls = []

        def respond(url, body=None):
            calls.append((url, body))
            if url.endswith("querybatch") and len(calls) == 1:
                return {"results": [{"vulns": [{"id": "OSV-FIRST"}], "next_page_token": "more-a"}, {}]}
            if url.endswith("querybatch"):
                self.assertEqual(body["queries"], [{"package": {"ecosystem": "Maven", "name": "example:a"}, "version": "1.0", "page_token": "more-a"}])
                return {"results": [{"vulns": [{"id": "OSV-SECOND"}]}]}
            return {"id": url.rsplit("/", 1)[-1]}

        self.assertEqual(self.run_scan(respond), 1)
        report = inventory.read_json(self.output)
        self.assertEqual(report["unique_advisories"], 2)
        self.assertEqual(report["querybatch_requests"], 2)
        self.assertNotIn("page_token", report["request_response_evidence"][0]["request"]["queries"][0])
        self.assertEqual(report["packages"][0]["vulnerability_ids"], ["OSV-FIRST", "OSV-SECOND"])

    def test_incomplete_batch_cannot_report_no_known_findings(self):
        with self.assertRaisesRegex(ValueError, "Incomplete OSV"):
            self.run_scan(lambda *_: {"results": [{}]})
        self.assertFalse(self.output.exists())

    def test_repeated_pagination_token_cannot_loop_or_hide_findings(self):
        with self.assertRaisesRegex(ValueError, "Repeated/excessive"):
            self.run_scan(lambda *_: {"results": [{"next_page_token": "again"}, {"next_page_token": "again"}]})

    def test_dangling_or_missing_graph_node_is_rejected(self):
        self.document["dependencies"][1]["dependsOn"] = ["unknown"]
        with self.assertRaisesRegex(ValueError, "Dangling"):
            inventory.verify_graph(self.document)
        self.document["dependencies"] = self.document["dependencies"][:1]
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            inventory.verify_graph(self.document)

    def test_nested_npm_resolution_uses_nearest_locked_version(self):
        packages = {"node_modules/a": {}, "node_modules/shared": {}, "node_modules/a/node_modules/shared": {}}
        self.assertEqual(inventory.resolve_npm("node_modules/a", "shared", packages), "node_modules/a/node_modules/shared")
        self.assertEqual(inventory.resolve_npm("node_modules/b", "shared", packages), "node_modules/shared")

    def write_lock(self, name, packages):
        path = pathlib.Path(self.directory.name) / (name + ".json")
        inventory.write_json(path, {"lockfileVersion": 3, "packages": packages})
        return path

    def test_two_applications_keep_colliding_install_paths_and_dev_edges_separate(self):
        pc = self.write_lock("pc", {"": {"name": "pc", "version": "1", "dependencies": {"shared": "1"}}, "node_modules/shared": {"version": "1.0.0"}})
        web = self.write_lock("web", {"": {"name": "web", "version": "1", "dependencies": {"shared": "2"}, "devDependencies": {"builder": "1"}},
                                      "node_modules/shared": {"version": "2.0.0"}, "node_modules/builder": {"version": "1.0.0", "dev": True, "peerDependencies": {"shared": "*"}}})
        args = argparse.Namespace(npm_lock=pc, npm_application=["web=" + str(web)])
        components, graph = [], {"root": set()}
        for spec in inventory.npm_inputs(args):
            inventory.add_npm_application(spec, components, graph, "root", {})
        bom = inventory.assemble_bom("root", "test", "1", components, graph, "test only")
        inventory.verify_graph(bom)
        self.assertIn("npm-location:node_modules/shared", graph["application:pc-mobius"])
        self.assertIn("npm-location:web:node_modules/shared", graph["application:web"])
        self.assertIn("npm-location:web:node_modules/builder", graph["application:web"])
        builder = next(item for item in components if item["name"] == "builder")
        self.assertEqual(builder["scope"], "excluded")
        self.assertEqual(inventory.property_value(builder, "privacybubble:dependency-kind"), "development")
        self.assertEqual(graph[builder["bom-ref"]], {"npm-location:web:node_modules/shared"})

    def test_duplicate_application_identity_or_lockfile_cannot_be_silently_merged(self):
        pc, web = self.write_lock("pc", {}), self.write_lock("web", {})
        for value in ["pc-mobius=" + str(web), "web=" + str(pc), "bad/id=" + str(web)]:
            with self.assertRaises(ValueError):
                inventory.npm_inputs(argparse.Namespace(npm_lock=pc, npm_application=[value]))

    def test_nested_runtime_lock_keeps_its_actual_repository_source(self):
        pc = self.write_lock("pc", {})
        repository = pathlib.Path(inventory.__file__).resolve().parents[2]
        source = "deployment/cse/artifacts/dependencies/package-lock.json"
        specs = inventory.npm_inputs(argparse.Namespace(npm_lock=pc, npm_application=["cse-runtime=" + str(repository / source)]))
        self.assertEqual(specs[1]["source"], source)

    def test_optional_peer_absence_is_allowed_but_required_build_dependency_is_not(self):
        path = self.write_lock("web", {"": {"name": "web", "version": "1", "peerDependencies": {"optional": "*"}, "peerDependenciesMeta": {"optional": {"optional": True}}}})
        spec = {"id": "web", "path": path, "source": "web/package-lock.json", "legacy": False}
        inventory.add_npm_application(spec, [], {"root": set()}, "root", {})
        data = inventory.read_json(path)
        data["packages"][""]["devDependencies"] = {"missing-build-tool": "1"}
        inventory.write_json(path, data)
        with self.assertRaisesRegex(ValueError, "Dependency missing"):
            inventory.add_npm_application(spec, [], {"root": set()}, "root", {})

    def test_python_runtime_closure_evaluates_target_markers_and_excludes_unrelated_tools(self):
        from packaging.markers import default_environment
        packages = [{"name": "gateway", "version": "1.0", "requires": ["network>=2", "linux-only; sys_platform == 'linux'"]},
                    {"name": "network", "version": "2.0", "requires": []}, {"name": "linux-only", "version": "1", "requires": []},
                    {"name": "pip", "version": "24.0", "requires": []}]
        runtime, graph = inventory.python_runtime_closure(packages, "gateway", {**default_environment(), "sys_platform": "win32"})
        self.assertEqual(runtime, ["gateway", "network"])
        self.assertEqual(graph["gateway"], ["network"])
        packages[1]["version"] = "1.0"
        with self.assertRaisesRegex(ValueError, "violates"):
            inventory.python_runtime_closure(packages, "gateway", {**default_environment(), "sys_platform": "win32"})

    def test_python_extras_expand_only_requested_runtime_dependencies(self):
        from packaging.markers import default_environment
        packages = [{"name": "gateway", "version": "1", "requires": ["network[secure]"]},
                    {"name": "network", "version": "1", "requires": ["tls; extra == 'secure'", "test-helper; extra == 'test'"]},
                    {"name": "tls", "version": "1", "requires": []}]
        runtime, graph = inventory.python_runtime_closure(packages, "gateway", default_environment())
        self.assertEqual(runtime, ["gateway", "network", "tls"])
        self.assertEqual(graph["network"], ["tls"])

    def test_pypi_scan_is_classified_as_pypi_with_application_scope(self):
        self.document["components"][0].update({"name": "network", "purl": "pkg:pypi/network@1.0", "scope": "required",
                                               "properties": inventory.properties(environment_id="gateway-validation", deployment_scope="local validation only")})
        inventory.write_json(self.bom, self.document)
        queries = []
        def respond(url, body=None):
            queries.extend(body["queries"])
            return {"results": [{} for _ in body["queries"]]}
        self.assertEqual(self.run_scan(respond), 0)
        self.assertIn({"package": {"ecosystem": "PyPI", "name": "network"}, "version": "1.0"}, queries)
        record = next(item for item in inventory.read_json(self.output)["packages"] if item["ecosystem"] == "PyPI")
        self.assertEqual(record["component_contexts"][0]["application"], "gateway-validation")

    def cse_source_fixture(self, unsafe=False):
        root = pathlib.Path(self.directory.name)
        lock = self.write_lock("cse-lock", {"": {"name": "cse", "version": "1.0"}})
        archive = root / "cse.tar.gz"
        source = b"module.exports = 'reviewed fixture';\n"
        with tarfile.open(archive, "w:gz") as output:
            for name, data in [("package-lock.json", lock.read_bytes()), ("cse/handler.js", source)]:
                entry = tarfile.TarInfo(name); entry.size = len(data); output.addfile(entry, io.BytesIO(data))
            if unsafe:
                entry = tarfile.TarInfo("../outside"); entry.size = 0; output.addfile(entry, io.BytesIO())
        manifest = root / "manifest.json"
        inventory.write_json(manifest, {"artifact": archive.name, "artifactSHA256": inventory.sha256(archive.read_bytes()),
                                       "lockSHA256": inventory.sha256(lock.read_bytes()), "repository": "https://example.test/cse", "revision": "a" * 40, "version": "1.0",
                                       "securityPatch": {"files": [{"file": "cse/handler.js", "sha256": inventory.sha256(source)}]}})
        return {"id": "cse-runtime", "path": lock, "source_manifest": manifest, "source": "deployment/cse/package-lock.json", "legacy": False}, archive

    def test_cse_source_manifest_binds_archive_patches_and_external_lock_to_graph(self):
        spec, _ = self.cse_source_fixture()
        evidence = inventory.verify_npm_source_manifest(spec)
        self.assertEqual(evidence["verified_patch_files"], ["cse/handler.js", "package-lock.json"])
        components, graph = [], {"root": set()}
        inventory.add_npm_application(spec, components, graph, "root", {})
        inventory.verify_graph(inventory.assemble_bom("root", "test", "1", components, graph, "test"))
        self.assertIn("source-archive:cse-runtime", graph["application:cse-runtime"])

    def test_cse_source_archive_and_lock_tampering_fail_closed(self):
        spec, archive = self.cse_source_fixture()
        archive.write_bytes(archive.read_bytes() + b"tampered")
        with self.assertRaisesRegex(ValueError, "differs"):
            inventory.verify_npm_source_manifest(spec)
        spec, _ = self.cse_source_fixture()
        spec["path"].write_text("{}")
        with self.assertRaisesRegex(ValueError, "differs"):
            inventory.verify_npm_source_manifest(spec)

    def test_cse_source_path_traversal_is_rejected_without_extraction(self):
        spec, _ = self.cse_source_fixture(unsafe=True)
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            inventory.verify_npm_source_manifest(spec)
        spec, _ = self.cse_source_fixture()
        manifest = inventory.read_json(spec["source_manifest"]); manifest["artifact"] = "../outside.tar.gz"
        inventory.write_json(spec["source_manifest"], manifest)
        with self.assertRaisesRegex(ValueError, "single manifest-relative"):
            inventory.verify_npm_source_manifest(spec)


if __name__ == "__main__":
    unittest.main()
