#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
workspace=$(cd -- "$script_dir/../../../.." && pwd)
runtime="$workspace/.local/mobius-runtime"
source_dir="$workspace/.local/source/mobius4"
node="$workspace/.local/node/bin/node"
[[ -f $runtime/config.json && -f $runtime/pg-user && -x $node ]] || { echo 'Run setup.sh first.' >&2; exit 1; }
pg_user=$(cat "$runtime/pg-user")
pg_env=(env "LD_LIBRARY_PATH=$runtime/root/usr/lib/x86_64-linux-gnu" "PROJ_DATA=$runtime/root/usr/share/proj")
pg_cmd=(); if [[ $(id -u) == 0 ]]; then pg_cmd=(runuser -u "$pg_user" --); elif [[ $(id -un) != "$pg_user" ]]; then echo 'Run as the runtime PostgreSQL user.' >&2; exit 1; fi
pgbin="$runtime/root/usr/lib/postgresql/16/bin"
if ! "${pg_cmd[@]}" "${pg_env[@]}" "$pgbin/pg_ctl" -D "$runtime/data" status >/dev/null 2>&1; then
  "${pg_cmd[@]}" "${pg_env[@]}" "$pgbin/pg_ctl" -D "$runtime/data" -l "$runtime/logs/postgres.log" -o "-c listen_addresses='' -c unix_socket_directories=$runtime/socket -p 55439" start
fi
if ! "${pg_cmd[@]}" "${pg_env[@]}" "$pgbin/psql" -h "$runtime/socket" -p 55439 -d postgres -Atqc "SELECT 1 FROM pg_database WHERE datname='mobius_app_test'" | grep -qx 1; then
  "${pg_cmd[@]}" "${pg_env[@]}" "$pgbin/createdb" -h "$runtime/socket" -p 55439 mobius_app_test
fi
"${pg_cmd[@]}" "${pg_env[@]}" "$pgbin/psql" -h "$runtime/socket" -p 55439 -d mobius_app_test -c 'CREATE EXTENSION IF NOT EXISTS postgis;'
if [[ -f $runtime/mobius.pid ]]; then
  pid=$(cat "$runtime/mobius.pid")
  if [[ $pid =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then echo 'Mobius PID already exists; inspect status before restarting.' >&2; exit 1; fi
  echo 'Stale Mobius PID file; inspect it before manual recovery.' >&2; exit 1
fi
port=$("$node" -e 'const c=require(process.argv[1]); process.stdout.write(String(c.http.port))' "$runtime/config.json")
"$node" -e 'const s=require("net").createServer(); s.on("error",()=>process.exit(1)); s.listen(Number(process.argv[1]),"127.0.0.1",()=>s.close());' "$port" || { echo 'Mobius port is in use; refusing to start.' >&2; exit 1; }
(cd "$source_dir"; NODE_CONFIG="$(cat "$runtime/config.json")" nohup setsid "$node" mobius4.js >> "$runtime/logs/mobius.log" 2>&1 & echo $! > "$runtime/mobius.pid")
pid=$(cat "$runtime/mobius.pid")
for attempt in {1..50}; do
  if ! kill -0 "$pid" 2>/dev/null; then echo 'Mobius process exited; see local log.' >&2; exit 1; fi
  if "$node" -e 'const n=require("net"); const s=n.connect(Number(process.argv[1]),"127.0.0.1"); s.on("connect",()=>{s.destroy();process.exit(0)}); s.on("error",()=>process.exit(1));' "$port"; then echo "Mobius HTTP loopback port $port is ready."; exit 0; fi
  sleep .2
done
echo 'Mobius did not become ready; inspect local log.' >&2; exit 1
