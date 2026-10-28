#!/usr/bin/env bash
# Creates mock-only CA and localhost leaf in the workspace; never installs trust.
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
workspace=$(cd -- "$script_dir/../../../../.." && pwd)
dir="$workspace/.local/oidc-mock"
[[ ! -e $dir ]] || { echo 'Certificate directory already exists; refusing to overwrite.' >&2; exit 1; }
umask 077
mkdir -p "$dir"
openssl req -x509 -newkey rsa:2048 -nodes -days 7 -keyout "$dir/ca.key" -out "$dir/ca.pem" -subj '/CN=Privacy Bubble Test CA' -addext 'basicConstraints=critical,CA:TRUE' -addext 'keyUsage=critical,keyCertSign,cRLSign' >/dev/null 2>&1
openssl req -new -newkey rsa:2048 -nodes -keyout "$dir/server.key" -out "$dir/server.csr" -subj '/CN=localhost' >/dev/null 2>&1
cat > "$dir/server.ext" <<'EXT'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,IP:127.0.0.1
EXT
openssl x509 -req -days 7 -in "$dir/server.csr" -CA "$dir/ca.pem" -CAkey "$dir/ca.key" -CAcreateserial -out "$dir/server.pem" -extfile "$dir/server.ext" >/dev/null 2>&1
openssl verify -CAfile "$dir/ca.pem" -verify_hostname localhost "$dir/server.pem" >/dev/null
printf 'Local mock certificates created at %s; no trust store was modified.\n' "$dir"
