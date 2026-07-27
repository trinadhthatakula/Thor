#!/usr/bin/env bash
# Verify shizu_store.json still describes reality.
#
# The Shizu CoreFetch store ignores a manifest it cannot parse or validate and
# silently falls back to default GitHub repository data. Nothing reports the
# failure, so every assertion here exists to make one silent failure loud.
#
# Usage: .github/scripts/check-shizu-manifest.sh [--network]
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT" || exit 2

MANIFEST="shizu_store.json"
SCHEMA=".github/shizu_store.schema.json"
FASTLANE="fastlane/metadata/android"
SHOTS="$FASTLANE/en-US/images/phoneScreenshots"
RAW_BASE="https://raw.githubusercontent.com/trinadhthatakula/Thor/master"

[ "$#" -le 1 ] || { printf 'usage: %s [--network]\n' "$0" >&2; exit 2; }
NETWORK=0
case "${1:-}" in
  --network) NETWORK=1 ;;
  "")        ;;
  *)         printf 'usage: %s [--network]\n' "$0" >&2; exit 2 ;;
esac

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
# An `if`, not `[ ... ] && need curl`: the && form makes the whole line exit
# non-zero when NETWORK is 0, which would abort the script the day someone
# adds `set -e`.
if [ "$NETWORK" -eq 1 ]; then need curl; fi

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

section "full_description length"
# Google Play hard-caps full_description at 4000 characters and rejects the
# upload over it.  This has been a manual watch item across three tasks; the
# Hindi copy sits ~50 characters under the cap, so an innocuous edit can breach
# it.  Automated now.
#
# wc -m counts CHARACTERS only under a UTF-8 locale.  Under LC_ALL=C it counts
# bytes — the Hindi file is 3948 characters but 8930 bytes, so an unpinned
# locale turns this assertion into a guaranteed false failure on any runner
# that does not happen to have a UTF-8 LC_CTYPE.  Resolve the locale here
# rather than trusting the caller's environment.
desc_locale=""
for cand in C.UTF-8 en_US.UTF-8 C.utf8 en_US.utf8; do
  if locale -a 2>/dev/null | grep -qxF "$cand"; then desc_locale="$cand"; break; fi
done
count_chars() {
  if [ -n "$desc_locale" ]; then
    LC_ALL="$desc_locale" wc -m < "$1" | tr -d ' '
  else
    # Fallback for a host with no UTF-8 locale at all.  jq decodes UTF-8
    # regardless of locale and its length is codepoints, which is exactly what
    # UTF-8 `wc -m` reports (verified identical on both files).
    jq -Rs 'length' < "$1"
  fi
}
found_desc=0
for f in "$FASTLANE"/*/full_description.txt; do
  [ -f "$f" ] || continue
  found_desc=1
  n="$(count_chars "$f")"
  if [ "$n" -le 4000 ]; then
    ok "$f is $n chars ($((4000 - n)) to spare)"
  else
    fail "$f is $n chars, over Play's 4000 by $((n - 4000))"
  fi
done
[ "$found_desc" -eq 1 ] || fail "no full_description.txt found under $FASTLANE/*/ — nothing was length-checked"

section "screenshots match the directory"
# Compared as FULL URLs, not basenames.  A basename-only comparison passed a
# manifest whose every screenshot pointed at .../hi-IN/images/phoneScreenshots/
# — a directory that does not exist — because the file names still lined up.
# Ten dead links would have shipped to master.  Building the expected URL from
# $RAW_BASE/$SHOTS and comparing exactly makes a wrong branch, a wrong path and
# a wrong filename all land in the same assertion.
#
# (.screenshots // [])[] suppresses jq errors when screenshots is null/missing
# (fix: was '.screenshots[]' which emits "Cannot iterate over null" to stderr).
manifest_shots="$(jq -r '(.screenshots // [])[]' "$MANIFEST" | sort)"
# Guard directory existence separately from emptiness — two distinct root causes.
if [ ! -d "$SHOTS" ]; then
  fail "screenshots: $SHOTS does not exist"
  expected_shots=""
else
  expected_shots="$(ls "$SHOTS" | sed "s#^#$RAW_BASE/$SHOTS/#" | sort)"
  # Guard against vacuous pass: if both sets are empty the assertion below
  # would pass silently.  An empty screenshot set is always a failure.
  [ -n "$expected_shots" ] || fail "screenshots: $SHOTS is empty — at least one screenshot is required"
fi
if [ "$manifest_shots" = "$expected_shots" ] && [ -n "$expected_shots" ]; then
  # printf '%s\n' adds a trailing newline so wc -l counts all N lines, not N-1
  # (fix: was 'printf %s' which yielded N-1 for a clean 10-screenshot tree).
  ok "$(printf '%s\n' "$expected_shots" | wc -l | tr -d ' ') screenshots listed, matching $SHOTS"
elif [ -n "$expected_shots" ]; then
  fail "screenshots array does not match $SHOTS (compared as full URLs)"
  printf '    "<" = on disk but not listed in .screenshots;  ">" = listed but no such file at that URL\n' >&2
  diff <(printf '%s\n' "$expected_shots") <(printf '%s\n' "$manifest_shots") >&2 || true
fi

section "image urls are pinned to master"
# icon_url and banner_url are schema-required, so this check is NOT gated on
# screenshots being non-empty — it used to iterate .screenshots alone, which
# let both of them be repointed at /dev/ while the checker printed "all checks
# passed".  The network tier could not catch it either: those files exist on
# dev and answer 200.
# The // idiom stays throughout: a jq filter that aborts on null is how the
# original "Cannot iterate over null" bug got in.  startswith() errors on null,
# so a missing icon_url/banner_url becomes a MISSING: sentinel that fails this
# check loudly instead of emptying $bad_prefix into a vacuous pass.  The
# sentinels carry no spaces — $bad_prefix is word-split when printed below.
bad_prefix="$(jq -r --arg b "$RAW_BASE" \
  '[(.icon_url // "MISSING:icon_url"), (.banner_url // "MISSING:banner_url"), ((.screenshots // [])[])]
   | .[] | select(startswith($b) | not)' "$MANIFEST")"
if [ -z "$bad_prefix" ]; then
  ok "icon_url, banner_url and every screenshot URL are pinned to master"
else
  fail "image URLs not pinned to $RAW_BASE:"
  printf '    %s\n' $bad_prefix >&2
fi

section "locale keys"
# The schema has no propertyNames constraint on locales, so an unrecognised
# locale code (e.g. "zh-rCN", "hi-IN") would pass schema validation while the
# store silently ignores the whole manifest.  Also, "en" must not appear under
# locales — it is the top-level base, not a locale override.  These are the
# two distinct failures and get distinct messages.
if jq -e '((.locales // {} | keys) - ["ar","en","fr","es","pt","ru","hi","zh","ja"]) == []' "$MANIFEST" >/dev/null 2>&1; then
  ok "locale keys are all in the permitted set (ar en fr es pt ru hi zh ja)"
else
  fail "locales contains unrecognised locale keys (valid: ar en fr es pt ru hi zh ja)"
  jq -r '((.locales // {} | keys) - ["ar","en","fr","es","pt","ru","hi","zh","ja"])[]' "$MANIFEST" >&2
fi
if jq -e '.locales // {} | has("en") | not' "$MANIFEST" >/dev/null 2>&1; then
  ok "locales does not duplicate the top-level en base"
else
  fail "locales must not contain \"en\" — en is the top-level base, not a locale override"
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
# initialVersionCode=1921, feeding two lines into arithmetic — the bug that
# made the old release-manager workflow unusable (deleted 2026-07-27).
# LOCKSTEP: this block (versionCode grep, version arithmetic, notes fallback path) is
# kept in lockstep with sync-shizu-changelog.sh — update both scripts together.
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

if [ "$NETWORK" -eq 1 ]; then
  UPSTREAM_SCHEMA_URL="https://docshizu.siwane.xyz/schema.json"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT

  section "image urls"
  # Images must be exactly 200. These are raw.githubusercontent.com URLs with
  # no redirects and no bot protection, so anything else is a real failure.
  # A `for` loop, not a pipe into `while`: a pipeline runs its body in a
  # subshell, where every fail() would increment a copy of $failures that dies
  # with the subshell. URLs contain no whitespace, so word splitting is safe.
  for u in $(jq -r '[.icon_url, .banner_url, ((.screenshots // [])[])] | .[]' "$MANIFEST"); do
    code="$(curl -sSL --max-time 25 -o /dev/null -w '%{http_code}' "$u")"
    if [ "$code" = "200" ]; then ok "$code  ${u##*/}"; else fail "image not served: $code $u"; fi
  done

  section "link urls"
  # A 403 or 429 from a site with bot protection is not a dead link, and
  # failing on it would make the weekly audit cry wolf until nobody reads it —
  # which is the exact failure this audit exists to prevent.
  for u in $(jq -r '[.repo_url, .donate_url, .developer.account_url, (.developer.socials // {} | .[])] | map(select(. != null)) | unique | .[]' "$MANIFEST"); do
    code="$(curl -sSL --max-time 25 -A 'thor-shizu-audit/1' -o /dev/null -w '%{http_code}' "$u")"
    case "$code" in
      403|429) ok   "$code  $u  (bot protection, treated as reachable)" ;;
      2??|3??) ok   "$code  $u" ;;
      *)       fail "link unreachable: $code $u" ;;
    esac
  done

  section "download_url"
  dl="$(jq -r .download_url "$MANIFEST")"
  final="$(curl -sIL --max-time 40 -o /dev/null -w '%{http_code} %{url_effective}' "$dl")"
  dl_code="${final%% *}"
  dl_url="${final#* }"
  if [ "$dl_code" = "200" ]; then
    ok "download_url resolves ($dl_code)"
    case "$dl_url" in
      *foss-release.apk*) ok "resolves to the foss artifact" ;;
      *) fail "resolves to something other than foss-release.apk: $dl_url" ;;
    esac
  else
    fail "download_url returned $dl_code"
  fi

  section "upstream schema"
  if curl -fsSL --max-time 25 "$UPSTREAM_SCHEMA_URL" -o "$tmp/upstream.json"; then
    if ! jq -e . "$tmp/upstream.json" >/dev/null 2>&1; then
      fail "upstream schema fetch returned non-JSON (captive portal?) — treat as a fetch failure, not a manifest failure"
    else
      if check-jsonschema --schemafile "$tmp/upstream.json" "$MANIFEST" >/dev/null 2>&1; then
        ok "manifest validates against the LIVE schema"
      else
        fail "manifest does NOT validate against the live schema — the store is rejecting this file right now"
        check-jsonschema --schemafile "$tmp/upstream.json" "$MANIFEST" >&2
      fi
      if diff -u "$SCHEMA" "$tmp/upstream.json" > "$tmp/schema.diff"; then
        ok "vendored schema is identical to upstream"
      else
        fail "vendored schema differs from upstream — review and re-vendor:"
        cat "$tmp/schema.diff" >&2
      fi
    fi
  else
    fail "could not fetch $UPSTREAM_SCHEMA_URL"
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
