#!/usr/bin/env bash
set -euo pipefail

echo "AutoBridge environment check"
echo "----------------------------"
java -version

if [[ -x ./gradlew ]]; then
  ./gradlew --version
elif command -v gradle >/dev/null 2>&1; then
  gradle --version
else
  echo "Gradle not found in PATH. Open the project in Android Studio or install Gradle 8.13."
fi

if [[ -n "${ANDROID_HOME:-}" ]]; then
  echo "ANDROID_HOME=$ANDROID_HOME"
else
  echo "ANDROID_HOME is not set. Android Studio can manage the SDK automatically."
fi

echo
echo "Translation set"
echo "---------------"
"$(dirname "$0")/scripts/check-i18n.sh" || true
