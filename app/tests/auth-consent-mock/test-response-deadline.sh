#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
output="$root/build/response-deadline-test"
mkdir -p "$output"
javac -d "$output" "$root/app/src/oidcTest/java/org/privacybubble/app/auth/ResponseDeadline.java" "$root/tests/auth-consent-mock/java/org/privacybubble/app/auth/ResponseDeadlineTest.java"
java -cp "$output" org.privacybubble.app.auth.ResponseDeadlineTest
