#!/usr/bin/env python3
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
from urllib.parse import urlsplit
from zipfile import ZipFile


def check(value, label):
    if not value:
        raise RuntimeError(label)
    checks.append(label)


def dump(*args):
    return subprocess.check_output([str(aapt), "dump", *map(str, args)], stderr=subprocess.PIPE).decode()


def resource_file(kind, name):
    lines = resources.splitlines()
    for index, line in enumerate(lines):
        if re.search(r"^\s*resource .*:" + re.escape(kind) + "/" + re.escape(name) + r": t=", line):
            for value in lines[index + 1:index + 5]:
                match = re.search(r'"(res/[^\"]+)"', value)
                if match:
                    return match.group(1)
    raise RuntimeError("Required packaged resource is missing: " + name)


def resource_xml(name):
    return dump("xmltree", apk, resource_file("xml", name))


parser = argparse.ArgumentParser(description="Inspect the optimized Android release's packaged security boundaries.")
parser.add_argument("apk", type=Path)
parser.add_argument("--configured-auth", action="store_true")
parser.add_argument("--portal-origin")
parser.add_argument("--portal-certificate", type=Path)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
portal = bool(args.portal_origin or args.portal_certificate)
if portal and (not args.portal_origin or not args.portal_certificate or args.configured_auth):
    parser.error("Portal inspection requires origin and public certificate, separately from native auth")
portal_host = None
portal_pin = None
portal_der = None
if portal:
    origin = urlsplit(args.portal_origin)
    if origin.scheme != "https" or not origin.hostname or origin.username or origin.password or origin.path not in ("", "/") or origin.query or origin.fragment:
        parser.error("Portal inspection requires an exact HTTPS origin")
    portal_host = origin.hostname
    portal_der = subprocess.check_output(["openssl", "x509", "-in", str(args.portal_certificate), "-outform", "DER"])
    public_key = subprocess.check_output(["openssl", "x509", "-in", str(args.portal_certificate), "-pubkey", "-noout"])
    spki = subprocess.check_output(["openssl", "pkey", "-pubin", "-outform", "DER"], input=public_key)
    portal_pin = base64.b64encode(hashlib.sha256(spki).digest()).decode("ascii")
apk = args.apk
sdk = Path(os.environ["ANDROID_HOME"])
aapt = sdk / "build-tools/35.0.0/aapt"
checks = []
manifest = dump("xmltree", apk, "AndroidManifest.xml")
badging = dump("badging", apk)
resources = dump("--values", "resources", apk)
permissions = dump("permissions", apk)
check("sdkVersion:'29'" in badging and "targetSdkVersion:'36'" in badging, "Android 10 minimum and API 36 target")
check(not re.search(r"android:debuggable[^\n]*0xffffffff", manifest), "release is not debuggable")
check("android:allowBackup" in manifest and not re.search(r"android:allowBackup[^\n]*0xffffffff", manifest), "backup disabled")
check("android:dataExtractionRules" in manifest, "explicit cloud and device-transfer policy")
check(not re.search(r"ACCESS_(FINE_LOCATION|COARSE_LOCATION|WIFI_STATE)", permissions), "no phone GPS or Wi-Fi identifier permissions")
check(not re.search(r"DeveloperTools|Synthetic|StateMismatchProbe", manifest), "no test components")
check(("net.openid.appauth.RedirectUriReceiverActivity" in manifest) == args.configured_auth, "callback matches provisioning profile")
network = resource_xml("network_security_config")
if portal:
    check("0xffffffff" not in network and 'src="system"' in network, "Portal profile rejects cleartext and retains system roots")
    check(network.count("E: certificates ") == 2 and 'src="user"' not in network,
          "Portal profile has only system and explicit server trust anchors")
    check(network.count("E: domain-config") == 1 and network.count("E: domain ") == 1
          and ('C: "' + portal_host + '"') in network, "Portal trust limited to the configured exact host")
    check("includeSubdomains" in network and "pin-set" in network and portal_pin in network
          and 'digest="SHA-256"' in network, "Portal host has exact leaf SPKI pin without subdomain inheritance")
    check("org.privacybubble.app.ServerPortalActivity" in manifest, "Portal Activity is packaged")
    check("launchable-activity: name='org.privacybubble.app.ServerPortalActivity'" in badging,
          "Provisioned portal is the actual launch Activity")
else:
    check("0xffffffff" not in network and 'src="system"' in network and "domain-config" not in network, "HTTPS and system trust only")
transfer = resource_xml("data_extraction_rules")
check("cloud-backup" in transfer and "device-transfer" in transfer and transfer.count('path="."') >= 18, "credentials excluded from cloud and device transfer")
native = []
with ZipFile(apk) as archive:
    if portal:
        packaged_pem = archive.read(resource_file("raw", "server_portal_certificate"))
        packaged_der = subprocess.check_output(["openssl", "x509", "-inform", "PEM", "-outform", "DER"], input=packaged_pem)
        check(packaged_der == portal_der, "Only the explicit user-trusted public server certificate is bundled")
    dex = b"".join(archive.read(name) for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name))
    for forbidden in [b"DeveloperToolsActivity", b"SyntheticConsentActivity", b"debug-device-session", b"TestStateClient", b"OidcBrowserFlow", b"RotatingSession", b"setDebugAccessToken", b"/home/kx2h/"]:
        check(forbidden not in dex, "DEX excludes " + ("build workspace paths" if forbidden.startswith(b"/home/") else forbidden.decode()))
    check(archive.read("assets/licenses/open_source_licenses.txt").startswith(b"Bubble"), "offline legal notices bundled")
    for name in archive.namelist():
        if not name.endswith(".so") or not name.startswith(("lib/arm64-v8a/", "lib/x86_64/")):
            continue
        binary = archive.read(name)
        check(binary[:5] == b"\x7fELF\x02", "64-bit ELF " + name)
        order = "<" if binary[5] == 1 else ">"
        header = struct.unpack_from(order + "HHIQQQIHHHHHH", binary, 16)
        headers = [struct.unpack_from(order + "IIQQQQQQ", binary, header[4] + index * header[8]) for index in range(header[9])]
        check(all(item[7] >= 16384 for item in headers if item[0] == 1), "16KB load alignment " + name)
        stacks = [item for item in headers if item[0] == 0x6474E551]
        check(bool(stacks) and all(not item[1] & 1 for item in stacks), "non-executable stack " + name)
        check(any(item[0] == 0x6474E552 for item in headers), "RELRO " + name)
        native.append({"name": name, "sha256": hashlib.sha256(binary).hexdigest()})
check(len(native) == 4, "both renderers and both 64-bit ABIs covered")
subprocess.run([str(sdk / "build-tools/35.0.0/zipalign"), "-c", "-P", "16", "4", str(apk)], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
checks.append("16KB native ZIP alignment")
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps({"format": "privacy-bubble.release-inspection.v1", "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "size_bytes": apk.stat().st_size, "configured_auth": args.configured_auth, "portal_origin": args.portal_origin, "portal_spki_sha256": portal_pin, "passed": True, "checks": checks, "native_files": native}, indent=2) + "\n")
print(json.dumps({"passed": True, "checks": len(checks), "native_files": len(native)}))
