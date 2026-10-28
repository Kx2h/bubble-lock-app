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
# ADB's get-state can report "device" even when the devices list says
# "unauthorized". Require an exact serial and authorized state from the list.
device_list=$("$adb" devices) || { echo 'Could not list ADB devices.' >&2; exit 1; }
authorized=0
matches=0
while IFS=$'\t ' read -r listed_serial listed_state _; do
  listed_state=${listed_state%$'\r'}
  if [[ $listed_serial == "$serial" ]]; then
    matches=$((matches + 1))
    if [[ $listed_state == device ]]; then authorized=$((authorized + 1)); fi
  fi
done <<< "$device_list"
[[ $matches == 1 && $authorized == 1 ]] || { echo 'Device is unavailable or unauthorized.' >&2; exit 1; }
# Local markers only describe operations performed by this script on this device.
# Hash the serial so it is never used as a filesystem path.
device_key=$(printf '%s' "$serial" | sha256sum | awk '{print $1}')
marker_dir="$app_dir/.local/device-poc/$device_key"
package_marker="$marker_dir/package.sha256"
port_marker="$marker_dir/reverse-8787"
package_paths() {
  "$adb" -s "$serial" shell pm path "$package" | tr -d '\r'
}
package_hash() {
  local paths path output digest rest
  paths=$(package_paths) || return 1
  [[ $paths == package:* && $paths != *$'\n'* ]] || return 1
  path=${paths#package:}
  # Do not pass arbitrary shell metacharacters returned by a device to adb shell.
  [[ $path =~ ^/data/app/[a-zA-Z0-9_./+=~-]+$ ]] || return 1
  output=$("$adb" -s "$serial" shell sha256sum "$path" | tr -d '\r') || return 1
  read -r digest rest <<< "$output"
  [[ $digest =~ ^[a-fA-F0-9]{64}$ ]] || return 1
  printf '%s\n' "${digest,,}"
}
get_reverse_list() { "$adb" -s "$serial" reverse --list; }
has_reverse() { awk -v port="$port" '$2 == port { found=1 } END { exit !found }' <<< "$1"; }
has_our_reverse() { awk -v port="$port" '{ sub(/\r$/, "", $3) } $2 == port && $3 == port { found=1 } END { exit !found }' <<< "$1"; }
prepare_markers() { (umask 077; mkdir -p "$marker_dir"); }
case "$command" in
  status)
    "$adb" -s "$serial" shell pm path "$package" || true
    "$adb" -s "$serial" reverse --list
    ;;
  install)
    apk="$app_dir/app/build/outputs/apk/debug/app-debug.apk"
    [[ -f $apk ]] || { echo 'Build the debug APK first.' >&2; exit 1; }
    paths=$(package_paths) || { echo 'Could not query package state.' >&2; exit 1; }
    if [[ -n $paths ]]; then
      echo 'Package already exists; refusing to replace it. Remove it explicitly first if appropriate.' >&2; exit 1
    fi
    reverse_list=$(get_reverse_list) || { echo 'Could not query reverse mappings.' >&2; exit 1; }
    if has_reverse "$reverse_list"; then
      echo 'Reverse port already exists; refusing to change it.' >&2; exit 1
    fi
    prepare_markers
    [[ ! -e $package_marker ]] || { echo 'Existing package marker needs review before installation.' >&2; exit 1; }
    expected_hash=$(sha256sum "$apk" | awk '{print $1}')
    "$adb" -s "$serial" install "$apk"
    actual_hash=$(package_hash) || { echo 'Installed, but could not verify APK hash; automatic cleanup is disabled.' >&2; exit 1; }
    [[ $actual_hash == "$expected_hash" ]] || { echo 'Installed APK hash differs; automatic cleanup is disabled.' >&2; exit 1; }
    (umask 077; printf '%s\n' "$actual_hash" > "$package_marker")
    echo 'Installed. Use launch to set up the test connection.'
    ;;
  launch)
    paths=$(package_paths) || { echo 'Could not query package state.' >&2; exit 1; }
    [[ -n $paths ]] || { echo 'Test app is not installed.' >&2; exit 1; }
    reverse_list=$(get_reverse_list) || { echo 'Could not query reverse mappings.' >&2; exit 1; }
    if has_reverse "$reverse_list"; then
      echo 'Reverse port already exists; refusing to change it.' >&2; exit 1
    fi
    prepare_markers
    [[ ! -e $port_marker ]] || { echo 'Existing reverse marker needs review before launch.' >&2; exit 1; }
    "$adb" -s "$serial" reverse --no-rebind "$port" "$port"
    (umask 077; printf '%s\n' "$port" > "$port_marker")
    "$adb" -s "$serial" shell am start -n "$package/.MainActivity"
    ;;
  cleanup)
    paths=$(package_paths) || { echo 'Could not query package state.' >&2; exit 1; }
    reverse_list=$(get_reverse_list) || { echo 'Could not query reverse mappings.' >&2; exit 1; }
    if [[ -n $paths ]]; then
      [[ -f $package_marker ]] || { echo 'Package was not installed by this script; refusing to uninstall.' >&2; exit 1; }
      expected_hash=$(cat "$package_marker")
      actual_hash=$(package_hash) || { echo 'Could not verify installed APK; refusing to uninstall.' >&2; exit 1; }
      [[ $actual_hash == "$expected_hash" ]] || { echo 'Installed APK changed; refusing to uninstall.' >&2; exit 1; }
    fi
    if [[ -f $port_marker ]] && has_reverse "$reverse_list" && ! has_our_reverse "$reverse_list"; then
      echo 'Reverse mapping changed; refusing to remove it.' >&2; exit 1
    fi
    if [[ -n $paths ]]; then "$adb" -s "$serial" uninstall "$package"; fi
    rm -f -- "$package_marker"
    if [[ -f $port_marker ]] && has_our_reverse "$reverse_list"; then "$adb" -s "$serial" reverse --remove "$port"; fi
    rm -f -- "$port_marker"
    ;;
esac
