#!/usr/bin/env bash
# Ubuntu 24.04 amd64 isolated, temporary APP Mobius4 test environment.
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
workspace=$(cd -- "$script_dir/../../../.." && pwd)
runtime="$workspace/.local/mobius-runtime"
source_dir="$workspace/.local/source/mobius4"
node="$workspace/.local/node/bin/node"
npm="$workspace/.local/node/bin/npm"
[[ -x $node && -x $npm ]] || { echo 'Workspace Node 22 is required.' >&2; exit 1; }
[[ ! -e $runtime && ! -e $source_dir ]] || { echo 'Runtime or source already exists; refusing to overwrite.' >&2; exit 1; }
[[ $(uname -m) == x86_64 ]] || { echo 'Requires Ubuntu 24.04 amd64.' >&2; exit 1; }
. /etc/os-release
[[ ${ID:-} == ubuntu && ${VERSION_ID:-} == 24.04 ]] || { echo 'Requires Ubuntu 24.04.' >&2; exit 1; }
pg_user=$(id -un)
if [[ $(id -u) == 0 ]]; then pg_user=$(stat -c %U "$workspace"); fi
[[ $pg_user != root ]] || { echo 'PostgreSQL requires a non-root workspace owner.' >&2; exit 1; }
mkdir -p "$runtime/debs" "$runtime/root" "$runtime/data" "$runtime/socket" "$runtime/logs" "$runtime/npm-cache" "$workspace/.local/source"
if [[ $(id -u) == 0 ]]; then chown "$pg_user" "$runtime/data" "$runtime/socket" "$runtime/logs"; fi
chmod 700 "$runtime/data" "$runtime/socket"
(
 cd "$runtime/debs"
 apt download postgresql-16=16.15-0ubuntu0.24.04.1 postgresql-client-16=16.15-0ubuntu0.24.04.1 libpq5=16.15-0ubuntu0.24.04.1 postgresql-16-postgis-3=3.4.2+dfsg-1ubuntu3 postgresql-16-postgis-3-scripts=3.4.2+dfsg-1ubuntu3 libgeos-c1t64=3.12.1-3build1 libgeos3.12.1t64=3.12.1-3build1 libproj25=9.4.0-1build2 libprotobuf-c1=1.4.1-1ubuntu4 proj-data=9.4.0-1build2
)
for package in "$runtime"/debs/*.deb; do dpkg-deb -x "$package" "$runtime/root"; done
ln -s postgis-3.control "$runtime/root/usr/share/postgresql/16/extension/postgis.control"
git clone --no-checkout https://github.com/IoTKETI/mobius4.git "$source_dir"
git -C "$source_dir" checkout --detach e8f75b1d6ee13a0e034379ec8dab26a6ef4fe8f8
cp "$script_dir/keyring-specializations.json" "$source_dir/config/specializations.json"
python3 - "$source_dir/bindings/http.js" <<'PY'
import pathlib,sys
p=pathlib.Path(sys.argv[1]); s=p.read_text(); a='http.createServer(app).listen(config.http.port);'; b="http.createServer(app).listen(config.http.port, '127.0.0.1');"
if s.count(a) != 1: raise SystemExit('Unexpected upstream HTTP binding')
p.write_text(s.replace(a,b))
PY
(cd "$source_dir" && PATH="$workspace/.local/node/bin:$PATH" npm_config_cache="$runtime/npm-cache" "$npm" ci --omit=dev)
pg_env=(env "LD_LIBRARY_PATH=$runtime/root/usr/lib/x86_64-linux-gnu" "PROJ_DATA=$runtime/root/usr/share/proj")
pg_cmd=(); if [[ $(id -u) == 0 ]]; then pg_cmd=(runuser -u "$pg_user" --); fi
"${pg_cmd[@]}" "${pg_env[@]}" "$runtime/root/usr/lib/postgresql/16/bin/initdb" -D "$runtime/data" --no-locale -E UTF8 --auth-local=trust --auth-host=scram-sha-256
port=${MOBIUS_PORT:-50545}
[[ $port =~ ^[0-9]+$ && $port -ge 1024 && $port -le 65535 ]] || { echo 'Invalid MOBIUS_PORT' >&2; exit 1; }
"$node" - "$runtime/config.json" "$runtime/socket" "$pg_user" "$port" <<'NODE'
const fs=require('fs'),crypto=require('crypto'); const [file,socket,user,port]=process.argv.slice(2);
const config={cse:{admin:'appdev-'+crypto.randomBytes(9).toString('hex'),poa:[`http://127.0.0.1:${port}`]},db:{host:socket,port:55439,name:'mobius_app_test',user,pw:crypto.randomBytes(24).toString('hex')},mqtt:{enabled:false},http:{port:Number(port)},logging:{file:{enabled:false},console:{enabled:true,pretty:false}}}; fs.writeFileSync(file,JSON.stringify(config),{mode:0o600,flag:'wx'});
NODE
printf '%s\n' "$pg_user" > "$runtime/pg-user"
echo 'Runtime created. Run start.sh, then bootstrap.sh. Existing files are never replaced.'
