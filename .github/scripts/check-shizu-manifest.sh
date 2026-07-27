#!/usr/bin/env bash
# Verify shizu_store.json still describes reality.
#
# The Shizu CoreFetch store ignores a manifest it cannot parse or validate and
# silently falls back to default GitHub repository data. Nothing reports the
# failure, so every assertion here exists to make one silent failure loud.
#
# Usage: .github/scripts/check-shizu-manifest.sh
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT" || exit 2

MANIFEST="shizu_store.json"
SCHEMA=".github/shizu_store.schema.json"
FASTLANE="fastlane/metadata/android"
SHOTS="$FASTLANE/en-US/images/phoneScreenshots"
RAW_BASE="https://raw.githubusercontent.com/trinadhthatakula/Thor/master"

failures=0
fail()    { printf '  FAIL %s\n' "$*" >&2; failures=$((failures + 1)); }
ok()      { printf '  ok   %s\n' "$*"; }
section() { printf '\n== %s ==\n' "$*"; }

need() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'missing required tool: %s\n' "$1" >&2
    printf 'install: brew install %s  (or pipx install %s)\n' "$1" "$1" >&2
    exit 2
  }
}
need jq
need check-jsonschema

# hi lives in fastlane as hi-IN; the other manifest locales have no fastlane
# counterpart and are checked for length and presence only.
fastlane_dir_for_locale() {
  case "$1" in
    hi) printf 'hi-IN' ;;
    *)  printf '' ;;
  esac
}

section "schema"
if check-jsonschema --schemafile "$SCHEMA" "$MANIFEST" >/dev/null 2>&1; then
  ok "validates against $SCHEMA"
else
  fail "does not validate against $SCHEMA"
  check-jsonschema --schemafile "$SCHEMA" "$MANIFEST" >&2
fi

section "omitted by design"
if jq -e 'has("version_name") or has("version_code")' "$MANIFEST" >/dev/null; then
  fail "version_name/version_code must stay absent — the store reads them from the release, and no workflow can push to master to keep them current"
else
  ok "version_name and version_code absent"
fi

section "copy matches fastlane"
compare_text() {
  # $1 label, $2 jq filter, $3 file
  local label="$1" filter="$2" file="$3" from_json from_file
  if [ ! -f "$file" ]; then fail "$label: $file not found"; return; fi
  from_json="$(jq -r "$filter" "$MANIFEST")"
  from_file="$(cat "$file")"
  if [ "$from_json" = "$from_file" ]; then
    ok "$label matches $file"
  else
    fail "$label has drifted from $file"
    diff <(printf '%s\n' "$from_file") <(printf '%s\n' "$from_json") >&2 || true
  fi
}
compare_text "short_description"    '.short_description'    "$FASTLANE/en-US/short_description.txt"
compare_text "detailed_description" '.detailed_description' "$FASTLANE/en-US/full_description.txt"

for loc in $(jq -r 'if has("locales") then .locales | keys[] else empty end' "$MANIFEST"); do
  dir="$(fastlane_dir_for_locale "$loc")"
  [ -n "$dir" ] || continue
  compare_text "locales.$loc.short_description"    ".locales.\"$loc\".short_description"    "$FASTLANE/$dir/short_description.txt"
  compare_text "locales.$loc.detailed_description" ".locales.\"$loc\".detailed_description" "$FASTLANE/$dir/full_description.txt"
done

section "short_description length"
# jq's length counts Unicode codepoints, which is what Google Play limits.
# A byte count would wrongly reject Devanagari and Arabic.
while read -r label len; do
  if [ "$len" -le 80 ]; then ok "$label is $len chars"; else fail "$label is $len chars, over Play's 80"; fi
done < <(jq -r '["en", (.short_description|length)], (if has("locales") then (.locales|to_entries[]|[.key, (.value.short_description|length)]) else empty end) | @tsv' "$MANIFEST")

section "screenshots match the directory"
manifest_shots="$(jq -r '.screenshots[]' "$MANIFEST" | sed 's#.*/##' | sort)"
actual_shots="$(ls "$SHOTS" | sort)"
if [ "$manifest_shots" = "$actual_shots" ]; then
  ok "$(printf '%s' "$actual_shots" | wc -l | tr -d ' ') screenshots listed, matching $SHOTS"
else
  fail "screenshots array does not match $SHOTS"
  diff <(printf '%s\n' "$actual_shots") <(printf '%s\n' "$manifest_shots") >&2 || true
fi

bad_prefix="$(jq -r --arg b "$RAW_BASE" '.screenshots[] | select(startswith($b) | not)' "$MANIFEST")"
if [ -z "$bad_prefix" ]; then
  ok "every screenshot URL is pinned to master"
else
  fail "screenshot URLs not pinned to $RAW_BASE:"
  printf '    %s\n' $bad_prefix >&2
fi

section "sdk versions"
check_int() {
  # $1 manifest key, $2 expected value, $3 source description
  local got; got="$(jq -r ".$1" "$MANIFEST")"
  if [ "$got" = "$2" ]; then ok "$1 = $got (matches $3)"; else fail "$1 = $got but $3 says $2"; fi
}
toml_min="$(grep -E '^minSdk = ' gradle/libs.versions.toml | tr -dc '0-9')"
toml_target="$(grep -E '^targetSdk = ' gradle/libs.versions.toml | tr -dc '0-9')"
check_int min_sdk    "$toml_min"    "gradle/libs.versions.toml"
check_int target_sdk "$toml_target" "gradle/libs.versions.toml"

section "changelog matches the current release notes"
# Anchored on purpose: an unanchored 'versionCode' also matches
# initialVersionCode=1921, which is the bug that made release-manager.yml
# unusable — two lines fed into arithmetic.
version_code="$(grep -E '^versionCode=' gradle.properties | cut -d= -f2 | tr -dc '0-9')"
if [ -z "$version_code" ]; then
  fail "could not read versionCode from gradle.properties"
else
  version_name="$((version_code / 1000)).$(((version_code % 1000) / 10)).$((version_code % 10))"
  notes="release-notes/v$version_name/playstore.txt"
  [ -f "$notes" ] || notes="release-notes/$version_name/playstore.txt"
  if [ ! -f "$notes" ]; then
    fail "no playstore.txt for v$version_name (versionCode $version_code)"
  else
    compare_text "changelog" '.changelog' "$notes"
    printf '       (versionCode %s -> v%s)\n' "$version_code" "$version_name"
  fi
fi

printf '\n'
if [ "$failures" -eq 0 ]; then
  printf 'shizu_store.json: all checks passed\n'
  exit 0
fi
printf 'shizu_store.json: %d check(s) failed\n' "$failures" >&2
printf 'if only the changelog drifted, run: .github/scripts/sync-shizu-changelog.sh\n' >&2
exit 1
