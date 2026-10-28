#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
workspace=$(cd -- "$script_dir/../../../.." && pwd)
runtime="$workspace/.local/mobius-runtime"
source_dir="$workspace/.local/source/mobius4"
[[ -f $runtime/pg-user ]] || { echo 'No configured runtime.' >&2; exit 1; }
if [[ -f $runtime/mobius.pid ]]; then
  pid=$(cat "$runtime/mobius.pid")
  [[ $pid =~ ^[0-9]+$ ]] || { echo 'Invalid Mobius PID.' >&2; exit 1; }
  if kill -0 "$pid" 2>/dev/null; then
    [[ $(readlink -f "/proc/$pid/cwd") == "$source_dir" ]] && tr '\0' ' ' < "/proc/$pid/cmdline" | grep -q 'node mobius4.js' || { echo 'PID is not this runtime; refusing to stop.' >&2; exit 1; }
    kill -TERM "$pid"
    for attempt in {1..100}; do kill -0 "$pid" 2>/dev/null || break; sleep .2; done
    if kill -0 "$pid" 2>/dev/null; then echo 'Mobius still stopping; inspect process.' >&2; exit 1; fi
  fi
  rm -- "$runtime/mobius.pid"
fi
# A server started manually or whose PID file was lost must not lose its DB.
port=$("$workspace/.local/node/bin/node" -e 'const c=require(process.argv[1]); process.stdout.write(String(c.http.port))' "$runtime/config.json")
if "$workspace/.local/node/bin/node" -e 'const n=require("net"); const s=n.connect(Number(process.argv[1]),"127.0.0.1"); s.on("connect",()=>{s.destroy();process.exit(0)}); s.on("error",()=>process.exit(1));' "$port"; then
  echo 'Mobius port is still active without a verified stopped PID; refusing to stop PostgreSQL.' >&2
  exit 1
fi
pg_user=$(cat "$runtime/pg-user"); pg_env=(env "LD_LIBRARY_PATH=$runtime/root/usr/lib/x86_64-linux-gnu" "PROJ_DATA=$runtime/root/usr/share/proj")
pg_cmd=(); if [[ $(id -u) == 0 ]]; then pg_cmd=(runuser -u "$pg_user" --); elif [[ $(id -un) != "$pg_user" ]]; then echo 'Run as the runtime PostgreSQL user.' >&2; exit 1; fi
pgctl="$runtime/root/usr/lib/postgresql/16/bin/pg_ctl"
if "${pg_cmd[@]}" "${pg_env[@]}" "$pgctl" -D "$runtime/data" status >/dev/null 2>&1; then "${pg_cmd[@]}" "${pg_env[@]}" "$pgctl" -D "$runtime/data" stop -m fast; fi
echo 'Stopped the local runtime; data preserved.'
