#!/usr/bin/env bash
# Bridge WSL's APK path to a separately running Windows ADB server.
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
workspace=$(cd -- "$script_dir/../../../.." && pwd)
adb_exe=${ADB_WINDOWS_PATH:-"$workspace/.local/android-windows/platform-tools/adb.exe"}
args=("$@")
for ((i=0; i<${#args[@]}; i++)); do
  if [[ ${args[i]} == install && $((i+1)) -eq $((${#args[@]}-1)) ]]; then
    args[i+1]=$(wslpath -w "${args[i+1]}")
    break
  fi
done
exec "$adb_exe" -P "${ADB_WINDOWS_PORT:-5038}" "${args[@]}"
