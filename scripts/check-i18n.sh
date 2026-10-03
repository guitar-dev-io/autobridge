#!/usr/bin/env bash
# Guard the translation set so a new language is a complete language.
#
# Three things have to agree for Android to resolve a locale at runtime, and nothing in the build
# notices when they drift apart:
#
#   1. res/values-<tag>/strings.xml   — the translations themselves
#   2. res/xml/locales_config.xml     — what the Android 13+ per-app language picker offers
#   3. resourceConfigurations in app/build.gradle.kts — what survives resource shrinking
#
# A locale missing from (2) is translated but unreachable from system Settings; one missing from
# (3) is stripped from the APK and silently falls back to English. This checks all three, then
# checks every locale actually translates every default string.
#
# It also keeps Thai out of Kotlin. Hardcoded Thai is worse than hardcoded English here: it shows
# up for English users with no way to turn it off. The exception is the voice-command vocabularies,
# which have to match what the user *says* regardless of the UI language and so stay bilingual in
# code — ALLOW_THAI_IN_CODE lists them.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RES_DIR="$ROOT_DIR/app/src/main/res"
GRADLE_FILE="$ROOT_DIR/app/build.gradle.kts"
LOCALES_CONFIG="$RES_DIR/xml/locales_config.xml"
SRC_DIR="$ROOT_DIR/app/src/main/java"

# Keyword vocabularies, not UI text: these match what the user says or what a provider named its
# content, neither of which has anything to do with the language the UI is drawn in. Moving them
# into res/values-th would make a Thai phrase stop matching the moment someone picked English.
ALLOW_THAI_IN_CODE=(
  "dev/autobridge/agent/AgentCommandParser.kt"
  "dev/autobridge/remote/CommandParser.kt"
  "dev/autobridge/iptv/XtreamClient.kt"
)

status=0
fail() { echo "check-i18n: $*" >&2; status=1; }

[[ -d "$RES_DIR/values" ]] || { fail "no $RES_DIR/values"; exit 1; }

# --- The three locale lists -------------------------------------------------------------------
# "en" is the default res/values and has no values-en directory.
translated=$( (echo en; ls -d "$RES_DIR"/values-* 2>/dev/null \
  | sed 's|.*/values-||' | grep -vE '^(night|v[0-9]+|w[0-9]+dp|land|port)$' || true) | sort -u)
declared=$(sed -n 's/.*android:name="\([^"]*\)".*/\1/p' "$LOCALES_CONFIG" | sort -u)
kept=$(sed -n 's/.*resourceConfigurations *+= *setOf(\(.*\)).*/\1/p' "$GRADLE_FILE" \
  | tr -d '" ' | tr ',' '\n' | grep -v '^$' | sort -u)

for name in declared:"$declared" kept:"$kept"; do
  label=${name%%:*}; list=${name#*:}
  missing=$(comm -23 <(echo "$translated") <(echo "$list"))
  extra=$(comm -13 <(echo "$translated") <(echo "$list"))
  [[ -z "$missing" ]] || fail "locale(s) translated but absent from $label: $(echo $missing)"
  [[ -z "$extra" ]] || fail "locale(s) in $label with no res/values-<tag>: $(echo $extra)"
done

# --- Every locale translates every default string ---------------------------------------------
# translatable="false" marks a string that is the same in every language - the brand, and the
# endonyms in the language picker, which are each written in their own language on purpose. Those
# belong in res/values only, so they are excluded from what a locale is expected to cover.
keys_of() { grep -o '<\(string\|plurals\|string-array\) name="[^"]*"[^>]*' "$1" \
  | grep -v 'translatable="false"' | sed 's/.*name="\([^"]*\)".*/\1/' | sort -u; }

default_keys=$(keys_of "$RES_DIR/values/strings.xml")
translatable="$default_keys"

# A name declared twice fails aapt's resource merge, which happens before Kotlin is compiled - so
# the failure surfaces as an unrelated-looking build error. Catching it here names the key.
for file in "$RES_DIR/values/strings.xml" "$RES_DIR"/values-*/strings.xml; do
  [[ -f "$file" ]] || continue
  dupes=$(grep -o '<\(string\|plurals\|string-array\) name="[^"]*"' "$file" \
    | sed 's/.*name="\([^"]*\)".*/\1/' | sort | uniq -d)
  if [[ -n "$dupes" ]]; then
    fail "${file#"$ROOT_DIR/"} declares the same name twice:"
    echo "$dupes" | sed 's/^/    /' >&2
  fi
done

for tag in $translated; do
  [[ "$tag" == en ]] && continue
  file="$RES_DIR/values-$tag/strings.xml"
  [[ -f "$file" ]] || { fail "values-$tag has no strings.xml"; continue; }
  locale_keys=$(keys_of "$file")
  missing=$(comm -23 <(echo "$translatable") <(echo "$locale_keys"))
  orphan=$(comm -13 <(echo "$default_keys") <(echo "$locale_keys"))
  if [[ -n "$missing" ]]; then
    fail "values-$tag is missing $(echo "$missing" | wc -l | tr -d ' ') string(s):"
    echo "$missing" | sed 's/^/    /' >&2
  fi
  if [[ -n "$orphan" ]]; then
    fail "values-$tag translates string(s) that no longer exist in values:"
    echo "$orphan" | sed 's/^/    /' >&2
  fi
done

# --- No Thai UI text left in Kotlin -----------------------------------------------------------
offenders=$(python3 "$ROOT_DIR/scripts/i18n_thai_scan.py" "$SRC_DIR" "${ALLOW_THAI_IN_CODE[@]}" || true)
if [[ -n "$offenders" ]]; then
  fail "Thai string literals in Kotlin — move them to res/values-th and reference them by id:"
  echo "$offenders" | sed 's/^/    /' >&2
fi

if [[ $status -eq 0 ]]; then
  echo "check-i18n: OK — locales [$(echo $translated)], $(echo "$default_keys" | wc -l | tr -d ' ') strings each"
fi

# Advisory: English still hardcoded in Kotlin. Not a gate — unlike Thai, it only means a locale is
# untranslated rather than actively wrong — so it reports and never changes the exit status.
echo
echo "check-i18n: still to extract (advisory, does not fail)"
python3 "$ROOT_DIR/scripts/i18n_hardcoded_scan.py" "$SRC_DIR" --top 10 | sed 's/^/  /'

exit $status
