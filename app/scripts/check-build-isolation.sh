#!/usr/bin/env bash
set -euo pipefail
app_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
aapt=${AAPT:-"${ANDROID_HOME:?Set ANDROID_HOME or AAPT}/build-tools/35.0.0/aapt"}
debug="$app_dir/app/build/outputs/apk/debug/app-debug.apk"
release="$app_dir/app/build/outputs/apk/release/app-release-unsigned.apk"
[[ -x $aapt && -f $debug && -f $release ]] || { echo 'Build debug and release APKs first.' >&2; exit 1; }
xml_path() {
  "$aapt" dump --values resources "$1" | awk '/resource .*:xml\/network_security_config: t=/{getline; split($0, parts, "\""); print parts[2]; exit}'
}
debug_path=$(xml_path "$debug")
release_path=$(xml_path "$release")
[[ -n $debug_path && -n $release_path ]] || { echo 'Network config not found.' >&2; exit 1; }
debug_xml=$("$aapt" dump xmltree "$debug" "$debug_path")
release_xml=$("$aapt" dump xmltree "$release" "$release_path")
[[ $debug_xml == *'C: "127.0.0.1"'* && $debug_xml == *'cleartextTrafficPermitted=(type 0x12)0xffffffff'* ]] || { echo 'Debug local test exception is missing.' >&2; exit 1; }
[[ $debug_xml != *'C: "localhost"'* && $debug_xml != *'trust-anchors'* ]] || { echo 'Default debug APK unexpectedly trusts a synthetic CA.' >&2; exit 1; }
[[ $release_xml == *'cleartextTrafficPermitted=(type 0x12)0x0'* && $release_xml != *'domain-config'* && $release_xml != *'0xffffffff'* ]] || { echo 'Release cleartext policy is unsafe.' >&2; exit 1; }
"$aapt" dump xmltree "$debug" AndroidManifest.xml | grep -q 'net.openid.appauth.RedirectUriReceiverActivity' || { echo 'Debug test redirect receiver is missing.' >&2; exit 1; }
if "$aapt" dump xmltree "$release" AndroidManifest.xml | grep -q 'net.openid.appauth.RedirectUriReceiverActivity'; then echo 'Test redirect receiver is present in release.' >&2; exit 1; fi
if "$aapt" dump xmltree "$debug" AndroidManifest.xml | grep -q 'org.privacybubble.app.auth.OidcSyntheticActivity'; then echo 'Synthetic Activity is present in default debug.' >&2; exit 1; fi
"$aapt" dump xmltree "$debug" AndroidManifest.xml | grep -q 'org.privacybubble.app.SyntheticConsentActivity' || { echo 'Debug consent test Activity is missing.' >&2; exit 1; }
if "$aapt" dump xmltree "$release" AndroidManifest.xml | grep -Eq 'org.privacybubble.app.(SyntheticConsentActivity|auth.(OidcSyntheticActivity|StateMismatchProbeActivity))'; then echo 'Test Activity is present in release.' >&2; exit 1; fi
echo 'PASS: default debug excludes synthetic CA and Activity; release rejects cleartext and test Activities'
