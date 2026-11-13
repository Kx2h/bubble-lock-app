#!/usr/bin/env python3
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent
ORIGIN = "https://bubblelock.iptime.org"
ISSUER = ORIGIN + "/identity/realms/bubble"
REDIRECT = "org.privacybubble.app:/oauth2redirect"
FIELDS = {"schemaVersion", "publicOrigin", "issuer", "nativeClientId", "apiAudience", "keycloakMajorVersion",
          "basicScopeExcludedFromTokenScope", "publicCertificateSha256", "publicCertificateConfirmed",
          "upstreamOrigin", "upstreamTlsName", "upstreamCaFile", "upstreamVerifiesOidcAndEnrollment"}
TEMPLATES = {"native-client.template.json": "native-client.json", "portal-read-scope.template.json": "portal-read-scope.json",
             "android-profile.template.properties": "android-profile.properties", "nginx-http.template.conf": "nginx-http.conf",
             "nginx-server.template.conf": "nginx-server.conf"}


def require(value, message):
    if not value:
        raise ValueError(message)


def plain(value, name, maximum=2048):
    require(isinstance(value, str) and 0 < len(value) <= maximum and value == value.strip()
            and not any(ord(c) < 32 or ord(c) == 127 for c in value), "Invalid value: " + name)
    require("__REQUIRED_" not in value and "{{" not in value and "}}" not in value, "Unresolved deployment value: " + name)
    return value


def hostname(value):
    try:
        ipaddress.ip_address(value)
        return value
    except ValueError:
        require(len(value) <= 253 and all(re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?", part)
                                         for part in value.split(".")), "Invalid upstream TLS name")
        return value.lower()


def https_origin(value):
    plain(value, "HTTPS origin")
    try:
        uri = urlsplit(value)
        port = uri.port
    except ValueError:
        raise ValueError("Invalid HTTPS origin or port") from None
    require(uri.scheme == "https" and uri.hostname and uri.username is None and uri.password is None
            and uri.path in ("", "/") and not uri.query and not uri.fragment, "Expected HTTPS origin only")
    host = hostname(uri.hostname)
    require(port is None or 1 <= port <= 65535, "Invalid HTTPS port")
    authority = ("[" + host + "]") if ":" in host else host
    if port is not None:
        authority += ":" + str(port)
    require(uri.netloc.lower() == authority.lower(), "Ambiguous HTTPS authority")
    return "https://" + authority


def validate_profile(value):
    require(isinstance(value, dict) and set(value) == FIELDS, "Profile fields are missing or unknown")
    require(value["schemaVersion"] == "privacy-bubble.native-deployment.v1", "Unknown deployment schema")
    result = dict(value)
    for key in ["publicOrigin", "issuer", "nativeClientId", "apiAudience", "publicCertificateSha256",
                "upstreamOrigin", "upstreamTlsName", "upstreamCaFile"]:
        result[key] = plain(value[key], key)
    require(https_origin(result["publicOrigin"]) == ORIGIN and result["issuer"] == ISSUER, "Confirm this deployment origin and issuer")
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", result["nativeClientId"])
            and result["nativeClientId"] != "bubble-lock-dashboard", "Use a separate registered native client identifier")
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}", result["apiAudience"]), "Invalid verified API audience")
    require(type(value["keycloakMajorVersion"]) is int and value["keycloakMajorVersion"] >= 25,
            "Verify Keycloak version; this template uses the basic client scope introduced by version 25")
    for key in ["basicScopeExcludedFromTokenScope", "publicCertificateConfirmed", "upstreamVerifiesOidcAndEnrollment"]:
        require(value[key] is True, "Required deployment fact is unconfirmed: " + key)
    fingerprint = result["publicCertificateSha256"].replace(":", "").lower()
    require(re.fullmatch(r"[0-9a-f]{64}", fingerprint), "Expected an independently confirmed SHA256 certificate fingerprint")
    result["publicCertificateSha256"] = fingerprint
    result["publicOrigin"] = ORIGIN
    result["upstreamOrigin"] = https_origin(result["upstreamOrigin"])
    result["upstreamTlsName"] = hostname(result["upstreamTlsName"])
    ca = PurePosixPath(result["upstreamCaFile"])
    require(ca.is_absolute() and ".." not in ca.parts and re.fullmatch(r"/[A-Za-z0-9._/-]+", str(ca))
            and ca.suffix in (".pem", ".crt"), "Expected a reviewed absolute CA PEM/CRT path, never a Gateway key")
    result["upstreamCaFile"] = str(ca)
    return result


def validate_artifacts(artifacts, profile):
    client = json.loads(artifacts["native-client.json"])
    scope = json.loads(artifacts["portal-read-scope.json"])
    require(client.get("clientId") == profile["nativeClientId"] and client.get("publicClient") is True
            and client.get("protocol") == "openid-connect" and client.get("standardFlowEnabled") is True, "Native public code client required")
    require(all(client.get(key) is False for key in ["bearerOnly", "implicitFlowEnabled", "directAccessGrantsEnabled",
                                                   "serviceAccountsEnabled", "authorizationServicesEnabled", "fullScopeAllowed"]), "Unexpected native grant or privilege")
    require(not any(key.lower() in {"secret", "clientsecret", "password", "credentials"} for key in client), "Client secrets are forbidden")
    require(client.get("redirectUris") == [REDIRECT] and client.get("webOrigins") == [], "Exact native redirect without web wildcard required")
    require(client.get("defaultClientScopes") == ["basic"] and client.get("optionalClientScopes") == ["offline_access", "portal:read"], "Native scopes must remain minimal")
    attrs = client.get("attributes", {})
    require(attrs.get("pkce.code.challenge.method") == "S256" and attrs.get("id.token.signed.response.alg") == "RS256"
            and attrs.get("access.token.signed.response.alg") == "RS256" and attrs.get("exclude.issuer.from.auth.response") == "false", "PKCE/RS256/issuer response settings required")
    require(attrs.get("oauth2.device.authorization.grant.enabled") == "false" and attrs.get("oidc.ciba.grant.enabled") == "false", "Unused grants must stay disabled")
    require(scope.get("name") == "portal:read" and scope.get("attributes", {}).get("include.in.token.scope") == "true", "Explicit portal read scope required")
    mappers = scope.get("protocolMappers", [])
    require(len(mappers) == 1 and mappers[0].get("protocolMapper") == "oidc-audience-mapper", "Expected one explicit audience mapper")
    config = mappers[0].get("config", {})
    require(config.get("included.custom.audience") == profile["apiAudience"] and config.get("access.token.claim") == "true"
            and config.get("id.token.claim") == "false", "API audience must bind the access token only")
    nginx = "\n".join(line for line in artifacts["nginx-server.conf"].splitlines() if not line.lstrip().startswith("#"))
    for required in ["location = /v1/portal/locations {", "if ($request_method != GET) { return 405; }",
                     "if ($args != \"\") { return 400; }", "location ^~ /v1/ {", "return 403;",
                     "proxy_pass_request_headers off;", "proxy_set_header Authorization $http_authorization;",
                     "proxy_pass_request_body off;", "proxy_ssl_verify on;", "proxy_ssl_protocols TLSv1.3;",
                     "proxy_ssl_server_name on;", "proxy_ssl_certificate \"\";", "proxy_ssl_certificate_key \"\";",
                     "proxy_cache off;", "proxy_no_cache 1;", "proxy_cache_bypass 1;", "proxy_next_upstream off;",
                     "limit_req zone=bubble_mobile_location burst=10 nodelay;", "limit_req_status 429;"]:
        require(required in nginx, "Missing reviewed native proxy boundary")
    require(len(re.findall(r"\bproxy_pass\s", nginx)) == 1 and len(re.findall(r"\blocation\s", nginx)) == 2,
            "Only the exact native read route may be forwarded")
    require("proxy_pass " + profile["upstreamOrigin"] + ";" in nginx
            and "proxy_ssl_name " + profile["upstreamTlsName"] + ";" in nginx
            and "proxy_ssl_trusted_certificate " + profile["upstreamCaFile"] + ";" in nginx, "Upstream TLS binding differs")
    require("limit_req_zone $binary_remote_addr" in artifacts["nginx-http.conf"], "Trusted socket-IP rate limit required")
    for content in artifacts.values():
        require("{{" not in content and "__REQUIRED_" not in content, "Unresolved deployment template")


def render(profile):
    values = {"NATIVE_CLIENT_ID": profile["nativeClientId"], "API_AUDIENCE": profile["apiAudience"],
              "PUBLIC_ORIGIN": profile["publicOrigin"], "PUBLIC_HOST": urlsplit(profile["publicOrigin"]).hostname,
              "ISSUER": profile["issuer"], "UPSTREAM_ORIGIN": profile["upstreamOrigin"],
              "UPSTREAM_AUTHORITY": urlsplit(profile["upstreamOrigin"]).netloc,
              "UPSTREAM_TLS_NAME": profile["upstreamTlsName"], "UPSTREAM_CA_FILE": profile["upstreamCaFile"]}
    artifacts = {}
    for source, destination in TEMPLATES.items():
        content = (ROOT / source).read_text(encoding="utf-8")
        for name, value in values.items():
            content = content.replace("{{" + name + "}}", value)
        artifacts[destination] = content
    validate_artifacts(artifacts, profile)
    return artifacts


def write_artifacts(artifacts, profile, output, workspace):
    output, workspace = Path(output).resolve(), Path(workspace).resolve(strict=True)
    require(workspace != Path("/"), "Use a specific workspace")
    output.relative_to(workspace)
    require(output != workspace and (not output.exists() or not any(output.iterdir())), "Output must be a dedicated empty workspace directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix="native-prepare-", dir=output.parent))
    try:
        for name, content in artifacts.items():
            path = staging / name
            path.write_text(content, encoding="utf-8")
            path.chmod(0o600)
        metadata = {"status": "locally-validated-not-applied", "remote_actions": False,
                    "profile": profile, "files": {name: hashlib.sha256(content.encode()).hexdigest() for name, content in artifacts.items()},
                    "remaining_checks": ["Live Keycloak client/scope registration and assigned-user checks", "Deployed Nginx configuration test and reload by its owner", "Verified public/upstream TLS chain and hostname", "Authenticated JWT audience/scope and location-lease tests"]}
        path = staging / "preparation.json"
        path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        path.chmod(0o600)
        os.replace(staging, output)
    finally:
        if staging.exists():
            shutil.rmtree(staging)
    return output


def main():
    parser = argparse.ArgumentParser(description="Validate and prepare native deployment configuration locally; never apply remote changes.")
    parser.add_argument("--config", type=Path, default=ROOT / "deployment.template.json")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--workspace-root", type=Path)
    args = parser.parse_args()
    try:
        profile = validate_profile(json.loads(args.config.read_text(encoding="utf-8")))
        artifacts = render(profile)
        if args.output:
            require(args.workspace_root is not None, "Set --workspace-root with --output")
            write_artifacts(artifacts, profile, args.output, args.workspace_root)
        print(json.dumps({"validated": True, "rendered": args.output is not None, "remote_actions": False, "files": len(artifacts)}))
        return 0
    except (OSError, ValueError, TypeError, KeyError) as failure:
        print("Deployment preparation rejected: " + str(failure))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
