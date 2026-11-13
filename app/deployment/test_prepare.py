import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("native_deployment_prepare", ROOT / "prepare.py")
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


def profile():
    return {
        "schemaVersion": "privacy-bubble.native-deployment.v1",
        "publicOrigin": prepare.ORIGIN,
        "issuer": prepare.ISSUER,
        "nativeClientId": "bubble-android-validation-fixture",
        "apiAudience": "urn:privacy-bubble:validation-fixture",
        "keycloakMajorVersion": 26,
        "publicCertificateSha256": "ab" * 32,
        "publicCertificateConfirmed": True,
        "upstreamOrigin": "https://127.0.0.1:8787",
        "upstreamTlsName": "api.validation.invalid",
        "upstreamCaFile": "/etc/bubblelock/validation-api-ca.pem",
        "upstreamVerifiesOidcAndEnrollment": True,
    }


class DeploymentPreparationTest(unittest.TestCase):
    def test_unresolved_templates_cannot_be_prepared(self):
        value = json.loads((ROOT / "deployment.template.json").read_text())
        with self.assertRaisesRegex(ValueError, "Unresolved deployment value"):
            prepare.validate_profile(value)

    def test_missing_fields_and_secret_fields_are_rejected(self):
        for key in list(profile()):
            value = profile()
            del value[key]
            with self.assertRaises(ValueError):
                prepare.validate_profile(value)
        for key in ["password", "clientSecret", "gatewayPrivateKey", "guardianToken"]:
            value = profile()
            value[key] = "isolated-invalid-fixture"
            with self.assertRaises(ValueError):
                prepare.validate_profile(value)

    def test_deployment_facts_must_be_explicitly_confirmed(self):
        for key in ["publicCertificateConfirmed", "upstreamVerifiesOidcAndEnrollment"]:
            for value in [False, None, 1, "true"]:
                bad = profile()
                bad[key] = value
                with self.assertRaises(ValueError):
                    prepare.validate_profile(bad)

    def test_native_client_is_separate_from_the_browser_bff(self):
        for value in ["bubble-lock-dashboard", "client*", "client\nother", "client secret", "__REQUIRED_CLIENT__"]:
            bad = profile()
            bad["nativeClientId"] = value
            with self.assertRaises(ValueError):
                prepare.validate_profile(bad)

    def test_origin_credentials_downgrade_and_nginx_injection_are_rejected(self):
        for key, values in {
            "upstreamOrigin": ["http://127.0.0.1:8787", "https://user:password@upstream", "https://upstream/path", "https://upstream?secret=1", "https://upstream:0", "https://upstream:", "https://upstream:65536"],
            "upstreamTlsName": ["*.example.test", "server; proxy_ssl_verify off;", "bad/name", "server\nother"],
            "upstreamCaFile": ["relative-ca.pem", "/etc/../private/ca.pem", "/etc/gateway-1.key", "/etc/ca.pem;bad", "/etc/ca file.pem"],
        }.items():
            for value in values:
                bad = profile()
                bad[key] = value
                with self.assertRaises(ValueError):
                    prepare.validate_profile(bad)

    def test_public_origin_issuer_audience_and_certificate_are_validated(self):
        for key, value in [("publicOrigin", "https://other.example"), ("issuer", prepare.ISSUER + "/"),
                           ("apiAudience", "__REQUIRED_API_AUDIENCE__"), ("apiAudience", "audience with spaces"),
                           ("publicCertificateSha256", "a" * 63), ("keycloakMajorVersion", True), ("keycloakMajorVersion", 24)]:
            bad = profile()
            bad[key] = value
            with self.assertRaises(ValueError):
                prepare.validate_profile(bad)

    def test_client_grant_redirect_and_scope_weakening_are_rejected(self):
        checked = prepare.validate_profile(profile())
        base = prepare.render(checked)
        for key, value in [("publicClient", False), ("implicitFlowEnabled", True), ("directAccessGrantsEnabled", True),
                           ("serviceAccountsEnabled", True), ("fullScopeAllowed", True), ("secret", "not-allowed"),
                           ("redirectUris", [prepare.REDIRECT + "*"]), ("optionalClientScopes", ["offline_access", "portal:read"]),
                           ("protocolMappers", [])]:
            artifacts = dict(base)
            client = json.loads(artifacts["native-client.json"])
            client[key] = value
            artifacts["native-client.json"] = json.dumps(client)
            with self.assertRaises(ValueError):
                prepare.validate_artifacts(artifacts, checked)

    def test_proxy_tls_cache_and_authorization_weakening_are_rejected(self):
        checked = prepare.validate_profile(profile())
        base = prepare.render(checked)
        for original, changed in [("proxy_ssl_verify on;", "proxy_ssl_verify off;"),
                                  ("proxy_cache off;", "proxy_cache private_cache;"),
                                  ("proxy_set_header Authorization $http_authorization;", "proxy_set_header Authorization \"\";"),
                                  ("if ($request_method != GET)", "if ($request_method != POST)"),
                                  ("proxy_ssl_certificate \"\";", "proxy_ssl_certificate /etc/gateway.pem;")]:
            artifacts = dict(base)
            artifacts["nginx-server.conf"] = artifacts["nginx-server.conf"].replace(original, changed)
            with self.assertRaises(ValueError):
                prepare.validate_artifacts(artifacts, checked)

    def test_synthetic_profile_renders_expected_read_only_artifacts_locally(self):
        checked = prepare.validate_profile(profile())
        artifacts = prepare.render(checked)
        self.assertEqual(len(artifacts), 5)
        self.assertEqual(artifacts["android-profile.properties"].splitlines()[-1], "mobileApiContract=portal-central-v1")
        self.assertEqual(json.loads(artifacts["portal-read-scope.json"])["protocolMappers"][0]["config"]["included.custom.audience"], checked["apiAudience"])
        temporary = os.environ.get("TMPDIR")
        self.assertTrue(temporary and Path(temporary).is_dir(), "Set a workspace-local TMPDIR for the test")
        with tempfile.TemporaryDirectory(dir=temporary, prefix="native-deployment-") as directory:
            workspace = Path(directory)
            output = workspace / "prepared"
            prepare.write_artifacts(artifacts, checked, output, workspace)
            self.assertEqual(len(list(output.iterdir())), 6)
            report = json.loads((output / "preparation.json").read_text())
            self.assertFalse(report["remote_actions"])
            self.assertEqual(report["status"], "locally-validated-not-applied")
            self.assertEqual(output.stat().st_mode & 0o777, 0o700)
            self.assertTrue(all(path.stat().st_mode & 0o777 == 0o600 for path in output.iterdir()))
            with self.assertRaises(ValueError):
                prepare.write_artifacts(artifacts, checked, output, workspace)
            with self.assertRaises(ValueError):
                prepare.write_artifacts(artifacts, checked, workspace.parent / "outside-output", workspace)


if __name__ == "__main__":
    unittest.main()
