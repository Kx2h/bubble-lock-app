#!/usr/bin/env bash
# Explicitly target one device and the APP debug package only.
set -euo pipefail
package=org.privacybubble.app
port=tcp:8787
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
app_dir=$(cd -- "$script_dir/.." && pwd)
adb=${ADB:-adb}
usage() { echo "Usage: ADB=/path/to/adb $0 <serial> <status|install|launch|cleanup>" >&2; exit 2; }
[[ $# == 2 && -n $1 && $1 != -* ]] || usage
serial=$1
command=$2
case "$command" in status|install|launch|cleanup) ;; *) usage ;; esac
"$adb" -s "$serial" get-state | tr -d '\r' | grep -qx device || { echo 'Device is unavailable or unauthorized.' >&2; exit 1; }
case "$command" in
  status)
    "$adb" -s "$serial" shell pm path "$package" || true
    "$adb" -s "$serial" reverse --list
    ;;
  install)
    apk="$app_dir/app/build/outputs/apk/debug/app-debug.apk"
    [[ -f $apk ]] || { echo 'Build the debug APK first.' >&2; exit 1; }
    if "$adb" -s "$serial" shell pm path "$package" | grep -q '^package:'; then
      echo 'Package already exists; refusing to replace it. Remove it explicitly first if appropriate.' >&2; exit 1
    fi
    if "$adb" -s "$serial" reverse --list | awk -v port="$port" '$2 == port { found=1 } END { exit !found }'; then
      echo 'Reverse port already exists; refusing to change it.' >&2; exit 1
    fi
    "$adb" -s "$serial" install "$apk"
    echo 'Installed. Use launch to set up the test connection.'
    ;;
  launch)
    if ! "$adb" -s "$serial" shell pm path "$package" | grep -q '^package:'; then echo 'Test app is not installed.' >&2; exit 1; fi
    if "$adb" -s "$serial" reverse --list | awk -v port="$port" '$2 == port { found=1 } END { exit !found }'; then
      echo 'Reverse port already exists; refusing to change it.' >&2; exit 1
    fi
    "$adb" -s "$serial" reverse "$port" "$port"
    "$adb" -s "$serial" shell am start -n "$package/.MainActivity"
    ;;
  cleanup)
    echo "Removing $package and reverse mapping $port on the selected device."
    if "$adb" -s "$serial" shell pm path "$package" | grep -q '^package:'; then "$adb" -s "$serial" uninstall "$package"; fi
    if "$adb" -s "$serial" reverse --list | awk -v port="$port" '{ sub(/\r$/, "", $3) } $2 == port && $3 == port { found=1 } END { exit !found }'; then "$adb" -s "$serial" reverse --remove "$port"; fi
    ;;
esac
