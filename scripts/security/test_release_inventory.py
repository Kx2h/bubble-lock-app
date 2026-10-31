import argparse
import contextlib
import io
import pathlib
import tempfile
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


if __name__ == "__main__":
    unittest.main()
