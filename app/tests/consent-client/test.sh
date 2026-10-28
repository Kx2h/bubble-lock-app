#!/usr/bin/env bash
set -euo pipefail
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
app_dir=$(cd -- "$here/../.." && pwd)
mkdir -p "$app_dir/build"
tmp=$(mktemp -d "$app_dir/build/consent-validator.XXXXXXXX")
trap 'rm -rf -- "$tmp"' EXIT
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -d "$tmp" \
  "$app_dir/app/src/debug/java/org/privacybubble/app/SyntheticConsentValidator.java" \
  "$app_dir/app/src/debug/java/org/privacybubble/app/SyntheticPending.java" \
  "$app_dir/app/src/debug/java/org/privacybubble/app/SyntheticMutationDispatch.java" \
  "$app_dir/app/src/debug/java/org/privacybubble/app/SyntheticGrantExpiry.java" \
  "$here/SyntheticConsentValidatorTest.java" \
  "$here/SyntheticPendingTest.java" \
  "$here/SyntheticMutationDispatchTest.java" \
  "$here/SyntheticGrantExpiryTest.java"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$tmp" org.privacybubble.app.SyntheticConsentValidatorTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$tmp" org.privacybubble.app.SyntheticPendingTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$tmp" org.privacybubble.app.SyntheticMutationDispatchTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$tmp" org.privacybubble.app.SyntheticGrantExpiryTest
