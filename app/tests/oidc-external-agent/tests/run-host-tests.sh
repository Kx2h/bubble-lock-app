#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
javac=${JAVAC:-javac}
java=${JAVA:-java}
out="$root/build/jvm-test"
mkdir -p "$out"
"$javac" -d "$out" "$root/src/main/java/org/privacybubble/oidcsurrogate/SyntheticRequest.java" "$root/tests/SyntheticRequestTest.java"
"$java" -cp "$out" org.privacybubble.oidcsurrogate.SyntheticRequestTest
