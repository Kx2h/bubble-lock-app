#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
output="$root/build/response-deadline-test"
mkdir -p "$output"
javac -d "$output" "$root/app/src/oidcTest/java/org/privacybubble/app/auth/ResponseDeadline.java" "$root/tests/auth-consent-mock/java/org/privacybubble/app/auth/ResponseDeadlineTest.java" "$root/app/src/oidcTest/java/org/privacybubble/app/auth/ConsentReviewGate.java" "$root/tests/auth-consent-mock/java/org/privacybubble/app/auth/ConsentReviewGateTest.java" "$root/app/src/oidcTest/java/org/privacybubble/app/auth/ConsentGrantExpiry.java" "$root/tests/auth-consent-mock/java/org/privacybubble/app/auth/ConsentGrantExpiryTest.java"
java -cp "$output" org.privacybubble.app.auth.ResponseDeadlineTest
java -cp "$output" org.privacybubble.app.auth.ConsentReviewGateTest
java -cp "$output" org.privacybubble.app.auth.ConsentGrantExpiryTest
