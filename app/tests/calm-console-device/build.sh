#!/usr/bin/env bash
# Separate QA package: never packaged into the product APK.
set -euo pipefail
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
: "${WORKSPACE_ROOT:?Set WORKSPACE_ROOT to the directory containing local build tools}"
sdk=${ANDROID_HOME:-"$WORKSPACE_ROOT/.local/android"}
java=${JAVA_HOME:-"$WORKSPACE_ROOT/.local/jdk17"}
tools="$sdk/build-tools/${ANDROID_BUILD_TOOLS_VERSION:-36.0.0}"
key=${QA_KEYSTORE:-"$WORKSPACE_ROOT/.local/android-user/debug.keystore"}
output=${QA_OUTPUT_DIR:-"$WORKSPACE_ROOT/.local/calm-console-device-qa"}
umask 077
mkdir -p "$output/classes" "$output/dex"
"$java/bin/javac" -source 17 -target 17 -classpath "$sdk/platforms/android-36/android.jar" -d "$output/classes" "$source_dir"/src/org/privacybubble/designqa/*.java
"$java/bin/jar" --create --file "$output/qa.jar" -C "$output/classes" .
JAVA_HOME="$java" "$tools/d8" --min-api 29 --lib "$sdk/platforms/android-36/android.jar" --output "$output/dex" "$output/qa.jar"
"$tools/aapt" package -f -M "$source_dir/AndroidManifest.xml" -I "$sdk/platforms/android-36/android.jar" -F "$output/base.apk"
python3 - "$output" <<'PY'
from pathlib import Path
import sys,zipfile
root=Path(sys.argv[1])
with zipfile.ZipFile(root/'base.apk','a') as apk:apk.write(root/'dex/classes.dex','classes.dex')
PY
"$tools/zipalign" -f 4 "$output/base.apk" "$output/aligned.apk"
# The existing workspace QA signer uses the public Android debug-key password.
JAVA_HOME="$java" "$tools/apksigner" sign --ks "$key" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$output/design-qa.apk" "$output/aligned.apk"
JAVA_HOME="$java" "$tools/apksigner" verify --print-certs "$output/design-qa.apk"
printf 'Built separate QA package: %s\n' "$output/design-qa.apk"
