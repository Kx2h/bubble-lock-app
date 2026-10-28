#!/usr/bin/env bash
# JVM trust is limited to a throwaway per-process store; system and Android trust remain untouched.
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
workspace=$(cd -- "$script_dir/../../../../.." && pwd)
export HOST_JDK_HOME=${HOST_JDK_HOME:-"$workspace/.local/jdk17"}
export NIMBUS_JAR=${NIMBUS_JAR:-$(find "$workspace/.local/gradle-home/caches/modules-2/files-2.1/com.nimbusds/nimbus-jose-jwt/9.47" -name 'nimbus-jose-jwt-9.47.jar' -type f -print -quit)}
[[ -x $HOST_JDK_HOME/bin/java && -x $HOST_JDK_HOME/bin/javac && -f $NIMBUS_JAR ]] || { echo 'Prepare the workspace JDK 17 and Gradle Nimbus dependency first.' >&2; exit 1; }
"$workspace/.local/node/bin/node" --test "$script_dir/host-java-integration.test.mjs"
