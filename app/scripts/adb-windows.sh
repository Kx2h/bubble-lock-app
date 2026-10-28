#!/usr/bin/env bash
# Bridge WSL's APK path to a separately running Windows ADB server.
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
workspace=$(cd -- "$script_dir/../../../.." && pwd)
adb_exe=${ADB_WINDOWS_PATH:-"$workspace/.local/android-windows/platform-tools/adb.exe"}
args=("$@")
for ((i=0; i<${#args[@]}; i++)); do
  case ${args[i]} in
    -s|-t|-H|-P|-L|--one-device) i=$((i+1)) ;;
    -a|-d|-e|--exit-on-write-error) ;;
    install)
      apk_index=$((${#args[@]}-1))
      if (( apk_index > i )); then
        apk=${args[apk_index]}
        case $apk in
          [[:alpha:]]:[\\/]*|\\\\*|-) ;;
          *)
            if [[ -f $apk ]]; then args[apk_index]=$(wslpath -w "$apk"); fi
            ;;
        esac
      fi
      break
      ;;
    *) break ;;
  esac
done
exec "$adb_exe" -P "${ADB_WINDOWS_PORT:-5038}" "${args[@]}"
