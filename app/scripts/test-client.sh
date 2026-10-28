#!/usr/bin/env bash
set -euo pipefail
app_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
javac=${JAVAC:-javac}
java=${JAVA:-java}
out="$app_dir/build/client-test"
mkdir -p "$out"
"$javac" --add-modules jdk.httpserver -d "$out" \
  "$app_dir/app/src/main/java/org/privacybubble/app/TestStateClient.java" \
  "$app_dir/app/src/main/java/org/privacybubble/app/TestResourceTimestamp.java" \
  "$app_dir/app/src/main/java/org/privacybubble/app/TestRefreshState.java" \
  "$app_dir/app/src/main/java/org/privacybubble/app/TestObservation.java" \
  "$app_dir/tests/manual/org/privacybubble/app/TestStateClientTest.java" \
  "$app_dir/tests/manual/org/privacybubble/app/TestResourceTimestampTest.java" \
  "$app_dir/tests/manual/org/privacybubble/app/TestRefreshStateTest.java" \
  "$app_dir/tests/manual/org/privacybubble/app/TestObservationTest.java"
"$java" --add-modules jdk.httpserver -cp "$out" org.privacybubble.app.TestStateClientTest
"$java" -cp "$out" org.privacybubble.app.TestResourceTimestampTest
"$java" -cp "$out" org.privacybubble.app.TestRefreshStateTest
"$java" -cp "$out" org.privacybubble.app.TestObservationTest
