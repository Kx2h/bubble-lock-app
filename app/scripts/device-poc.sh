#!/usr/bin/env bash
# Explicitly target one device and the APP debug package only.
set -euo pipefail
package=org.privacybubble.app
port=tcp:8787
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
app_dir=$(cd -- "$script_dir/.." && pwd)
adb=${ADB:-adb}
usage() { echo "Usage: ADB=/path/to/adb $0 <serial> <status|install|launch|cleanup|cleanup-port>" >&2; exit 2; }
[[ $# == 2 && -n $1 && $1 != -* ]] || usage
serial=$1
command=$2
case "$command" in status|install|launch|cleanup|cleanup-port) ;; *) usage ;; esac
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
package_path() {
  local paths path
  paths=$(package_paths) || return 1
  [[ $paths == package:* && $paths != *$'\n'* ]] || return 1
  path=${paths#package:}
  # Do not pass arbitrary shell metacharacters returned by a device to adb shell.
  [[ $path =~ ^/data/app/[a-zA-Z0-9_./+=~-]+$ ]] || return 1
  printf '%s\n' "$path"
}
package_hash() {
  local path=$1 output digest rest
  output=$("$adb" -s "$serial" shell sha256sum "$path" | tr -d '\r') || return 1
  read -r digest rest <<< "$output"
  [[ $digest =~ ^[a-fA-F0-9]{64}$ ]] || return 1
  printf '%s\n' "${digest,,}"
}
get_reverse_list() { "$adb" -s "$serial" reverse --list; }
has_reverse() { awk -v port="$port" '$2 == port { found=1 } END { exit !found }' <<< "$1"; }
has_our_reverse() { awk -v port="$port" '{ sub(/\r$/, "", $3) } $2 == port && $3 == port { found=1 } END { exit !found }' <<< "$1"; }
prepare_markers() { (umask 077; mkdir -p "$marker_dir"); }
require_single_owner_user() {
  local users current line heading=0 owners=0
  users=$("$adb" -s "$serial" shell pm list users | tr -d '\r') || { echo 'Could not list Android users.' >&2; return 1; }
  current=$("$adb" -s "$serial" shell am get-current-user | tr -d '\r') || { echo 'Could not identify current Android user.' >&2; return 1; }
  [[ $current == 0 ]] || { echo 'Current Android user is not owner 0; refusing package changes.' >&2; return 1; }
  while IFS= read -r line; do
    if [[ $line =~ ^[[:space:]]*Users:[[:space:]]*$ ]]; then heading=$((heading + 1));
    elif [[ $line =~ ^[[:space:]]*UserInfo\{0:[^}]*\}([[:space:]].*)?$ ]]; then owners=$((owners + 1));
    elif [[ ! $line =~ ^[[:space:]]*$ ]]; then
      echo 'Multiple Android users or an unrecognized user list; refusing package changes.' >&2; return 1
    fi
  done <<< "$users"
  [[ $heading == 1 && $owners == 1 ]] || { echo 'Could not establish a single Android owner; refusing package changes.' >&2; return 1; }
}
remove_owned_reverse() {
  local reverse_list
  reverse_list=$(get_reverse_list) || { echo 'Could not query reverse mappings.' >&2; return 1; }
  [[ -f $port_marker ]] || { echo 'No reverse ownership marker; leaving mappings unchanged.'; return 0; }
  if has_reverse "$reverse_list" && ! has_our_reverse "$reverse_list"; then
    echo 'Reverse mapping changed; refusing to remove it.' >&2; return 1
  fi
  if has_our_reverse "$reverse_list"; then "$adb" -s "$serial" reverse --remove "$port"; fi
  rm -f -- "$port_marker"
}
case "$command" in
  status)
    "$adb" -s "$serial" shell pm path "$package" || true
    "$adb" -s "$serial" reverse --list
    ;;
  install)
    require_single_owner_user
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
    installed_path=$(package_path) || { echo 'Installed, but could not verify APK path; automatic cleanup is disabled.' >&2; exit 1; }
    actual_hash=$(package_hash "$installed_path") || { echo 'Installed, but could not verify APK hash; automatic cleanup is disabled.' >&2; exit 1; }
    [[ $actual_hash == "$expected_hash" ]] || { echo 'Installed APK hash differs; automatic cleanup is disabled.' >&2; exit 1; }
    # A private marker survives in-place app updates but is removed with its app
    # data on uninstall. A matching APK alone cannot identify an installation.
    owner_token=$(head -c 32 /dev/urandom | sha256sum | awk '{print $1}')
    [[ $owner_token =~ ^[0-9a-f]{64}$ ]] || { echo 'Could not create ownership token.' >&2; exit 1; }
    "$adb" -s "$serial" shell "run-as $package sh -c 'umask 077; printf %s $owner_token > .device-poc-owner'" || {
      echo 'Installed, but could not create an app-private ownership marker; automatic cleanup is disabled.' >&2; exit 1;
    }
    device_token=$("$adb" -s "$serial" shell run-as "$package" cat .device-poc-owner | tr -d '\r') || {
      echo 'Installed, but could not read the ownership marker; automatic cleanup is disabled.' >&2; exit 1;
    }
    [[ $device_token == "$owner_token" ]] || { echo 'Ownership marker mismatch; automatic cleanup is disabled.' >&2; exit 1; }
    (umask 077; printf '%s\n%s\n%s\n' "$actual_hash" "$installed_path" "$owner_token" > "$package_marker")
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
    require_single_owner_user
    paths=$(package_paths) || { echo 'Could not query package state.' >&2; exit 1; }
    reverse_list=$(get_reverse_list) || { echo 'Could not query reverse mappings.' >&2; exit 1; }
    if [[ -n $paths ]]; then
      [[ -f $package_marker ]] || { echo 'Package was not installed by this script; refusing to uninstall.' >&2; exit 1; }
      mapfile -t owner_record < "$package_marker"
      [[ ${#owner_record[@]} == 3 && ${owner_record[0]} =~ ^[a-f0-9]{64}$ && ${owner_record[2]} =~ ^[a-f0-9]{64}$ ]] || {
        echo 'Incomplete or old ownership marker; refusing to uninstall.' >&2; exit 1;
      }
      installed_path=$(package_path) || { echo 'Could not verify installed APK path; refusing to uninstall.' >&2; exit 1; }
      [[ $installed_path == "${owner_record[1]}" ]] || { echo 'Installed APK path changed; refusing to uninstall.' >&2; exit 1; }
      actual_hash=$(package_hash "$installed_path") || { echo 'Could not verify installed APK; refusing to uninstall.' >&2; exit 1; }
      [[ $actual_hash == "${owner_record[0]}" ]] || { echo 'Installed APK changed; refusing to uninstall.' >&2; exit 1; }
      device_token=$("$adb" -s "$serial" shell run-as "$package" cat .device-poc-owner | tr -d '\r') || {
        echo 'Could not verify app-private ownership marker; refusing to uninstall.' >&2; exit 1;
      }
      [[ $device_token == "${owner_record[2]}" ]] || { echo 'App installation ownership changed; refusing to uninstall.' >&2; exit 1; }
    fi
    if [[ -f $port_marker ]] && has_reverse "$reverse_list" && ! has_our_reverse "$reverse_list"; then
      echo 'Reverse mapping changed; refusing to remove it.' >&2; exit 1
    fi
    if [[ -n $paths ]]; then "$adb" -s "$serial" uninstall "$package"; fi
    rm -f -- "$package_marker"
    if [[ -f $port_marker ]] && has_our_reverse "$reverse_list"; then "$adb" -s "$serial" reverse --remove "$port"; fi
    rm -f -- "$port_marker"
    ;;
  cleanup-port)
    remove_owned_reverse
    ;;
esac
