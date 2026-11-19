#!/usr/bin/env bash
set -euo pipefail
phase=${1:?Use portrait/navigation/recovery/layout}
case "$phase" in portrait|navigation|recovery|layout);;*)exit 2;;esac
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the authorized test phone}"
: "${WORKSPACE_ROOT:?Set WORKSPACE_ROOT for private evidence output}"
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd -- "$source_dir/../../.." && pwd)
adb=${ADB:-"$repo/app/scripts/adb-windows.sh"}
output=${QA_OUTPUT_DIR:-"$WORKSPACE_ROOT/.local/calm-console-device-qa"}
label=${QA_LABEL:-"$phase-$(date -u +%Y%m%dT%H%M%SZ)"}
[[ $label =~ ^[A-Za-z0-9._-]+$ ]] || exit 2
umask 077
mkdir -p "$output"
"$adb" -s "$ANDROID_SERIAL" shell am instrument -w -e phase "$phase" -e requireRelease "${QA_REQUIRE_RELEASE:-yes}" org.privacybubble.designqa/org.privacybubble.designqa.PortalDesignInstrumentation | tee "$output/$label.txt"
# `am instrument` can exit zero even when the instrumentation reports failure.
python3 - "$output/$label.txt" "$phase" <<'PY'
from pathlib import Path
import sys
expected='PASS: design-'+sys.argv[2]+';'
raise SystemExit(0 if any(line.startswith(expected) for line in Path(sys.argv[1]).read_text().splitlines()) else 1)
PY
