#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
workspace=$(cd -- "$script_dir/../../../.." && pwd)
runtime="$workspace/.local/mobius-runtime"
receipt=$(mktemp "$runtime/bootstrap-receipt.XXXXXXXX.json")
"$workspace/.local/node/bin/node" "$script_dir/bootstrap.mjs" "$runtime/config.json" "$receipt"
echo "Receipt: $receipt"
