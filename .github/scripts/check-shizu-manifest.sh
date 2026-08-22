#!/usr/bin/env bash
# Verify shizu_store.json still describes reality.
#
# The Shizu CoreFetch store ignores a manifest it cannot parse or validate and
# silently falls back to default GitHub repository data. Nothing reports the
# failure, so every assertion here exists to make one silent failure loud.
#
# Usage: .github/scripts/check-shizu-manifest.sh [--network] [--warn-changelog-drift]
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT" || exit 2

MANIFEST="shizu_store.json"
SCHEMA=".github/shizu_store.schema.json"
FASTLANE="fastlane/metadata/android"
SHOTS="$FASTLANE/en-US/images/phoneScreenshots"
RAW_BASE="https://raw.githubusercontent.com/trinadhthatakula/Thor/master"

NETWORK=0
# --warn-changelog-drift downgrades exactly ONE condition — the changelog no
# longer matching production's release notes — to a warning with exit 0. See
# changelog_drift() below for why the scope is that narrow and must stay so.
WARN_CHANGELOG_DRIFT=0
usage() { printf 'usage: %s [--network] [--warn-changelog-drift]\n' "$0" >&2; exit 2; }
while [ "$#" -gt 0 ]; do
  case "$1" in
    --network)              NETWORK=1 ;;
    --warn-changelog-drift) WARN_CHANGELOG_DRIFT=1 ;;
    *)                      usage ;;
  esac
  shift
done

failures=0
warnings=0
fail()    { printf '  FAIL %s\n' "$*" >&2; failures=$((failures + 1)); }
warn()    { printf '  WARN %s\n' "$*" >&2; warnings=$((warnings + 1)); }
ok()      { printf '  ok   %s\n' "$*"; }
section() { printf '\n== %s ==\n' "$*"; }

# The ONE condition --warn-changelog-drift softens, and the only call site that
# may ever route through it.
#
# shizu_store.json carries the changelog of the last PRODUCTION release. A
# production promotion moves that target, runs no pull_request workflow, and the
# manifest is refreshed by a separate commit to master. In the window between
# the two, this check fails — and it is deliberately not path-filtered, so it
# fails on EVERY open PR at once, for people who did not cause the drift and
# cannot fix it from their branch. A red check nobody can act on is a red check
# everyone learns to ignore.
#
# Keep the scope at exactly this. A schema violation, a missing file, an
# unresolvable ref, a dead URL, a wrong SDK level all stay hard failures in
# every mode: each describes a manifest that is broken, not one that is behind.
#
# Both codes name the same complaint - "this function is never called" - and
# which one you get depends on the shellcheck you have. 0.9 (what ubuntu-latest
# preinstalls) says SC2317; 0.10 split it out as SC2329. Naming only one is a
# gate that passes locally and fails in CI, which is how this line got written.
# An unrecognised code in a disable directive is ignored, so listing both is
# safe on every version.
# shellcheck disable=SC2317,SC2329 # invoked indirectly, as compare_text's $reporter
changelog_drift() {
  if [ "$WARN_CHANGELOG_DRIFT" -eq 0 ]; then
    fail "$*"
    return
  fi
  warnings=$((warnings + 1))
  # ::warning:: renders as an annotation on the PR's Checks tab; the indented
  # lines are for whoever opens the raw log.
  printf '::warning::%s — not caused by this PR, and not fixable from this branch; see the log\n' "$*"
  printf '  WARN %s\n' "$*" >&2
  printf '       Nothing in this PR caused this. shizu_store.json carries the\n' >&2
  printf '       changelog of the last production release, and a production\n' >&2
  printf '       promotion moves that target before the manifest catches up.\n' >&2
  printf '       Fix (a commit on master, not on this branch): run\n' >&2
  printf '       .github/scripts/sync-shizu-changelog.sh and commit\n' >&2
  printf '       shizu_store.json — release-notes/README.md, Step 5.\n' >&2
}

# The manifest's twelve image URLs are fetched back to back from one address,
# and raw.githubusercontent.com answers a burst like that with a 429 often
# enough to have tripped this checker twice while it was being written. The
# link check below already forgives 429; this one demands a 200, so give it one
# retry. A transient refusal then passes on the second attempt, while a file
# that is genuinely missing fails both and is still reported.
image_code() {
  _c="$(curl -sSL --max-time 25 -o /dev/null -w '%{http_code}' "$1")" || _c=000
  if [ "$_c" != "200" ]; then
    sleep 3
    _c="$(curl -sSL --max-time 25 -o /dev/null -w '%{http_code}' "$1")" || _c=000
  fi
  printf '%s' "$_c"
}

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
  # $1 label, $2 jq filter, $3 file, $4 optional reporter for a MISMATCH.
  # Defaults to fail; only the changelog passes anything else. A missing file
  # stays a hard failure in every mode — an absent file is a broken repo, not a
  # manifest that is merely behind.
  local label="$1" filter="$2" file="$3" reporter="${4:-fail}" from_json from_file
  if [ ! -f "$file" ]; then fail "$label: $file not found"; return; fi
  from_json="$(jq -r "$filter" "$MANIFEST")"
  from_file="$(cat "$file")"
  if [ "$from_json" = "$from_file" ]; then
    ok "$label matches $file"
  else
    "$reporter" "$label has drifted from $file"
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
# bytes — the Hindi file is 3947 characters but 8939 bytes, so an unpinned
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
  # shellcheck disable=SC2012 # screenshot filenames are controlled alphanumeric; find adds no value here
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
# This check is NOT gated on screenshots being non-empty — it used to iterate
# .screenshots alone, which let icon_url and banner_url both be repointed at
# /dev/ while the checker printed "all checks passed".  The network tier could
# not catch it either: those files exist on dev and answer 200.
# The // idiom stays throughout: a jq filter that aborts on null is how the
# original "Cannot iterate over null" bug got in.  startswith() errors on null,
# so an absent icon_url/banner_url becomes a MISSING: sentinel that fails this
# check loudly instead of emptying $bad_prefix into a vacuous pass.  Only
# icon_url is schema-required; banner_url is optional upstream, but Thor ships
# one and a listing that silently loses its banner is exactly the sort of rot
# this script exists to catch, so the sentinel hard-requires it here.
# The sentinels and the URLs carry no spaces — $bad_prefix is word-split below.
bad_prefix="$(jq -r --arg b "$RAW_BASE" \
  '[(.icon_url // "MISSING:icon_url"), (.banner_url // "MISSING:banner_url"), ((.screenshots // [])[])]
   | .[] | select(startswith($b) | not)' "$MANIFEST")"
if [ -z "$bad_prefix" ]; then
  ok "icon_url, banner_url and every screenshot URL are pinned to master"
else
  # Distinct diagnoses: "not pinned" misreads badly for a field that is absent.
  for u in $bad_prefix; do
    case "$u" in
      MISSING:*) fail "required image field is absent: ${u#MISSING:}" ;;
      *)         fail "image URL not pinned to $RAW_BASE: $u" ;;
    esac
  done
fi

section "locale keys"
# The schema has no propertyNames constraint on locales, so an unrecognised
# locale code (e.g. "zh-rCN", "hi-IN") would pass schema validation while the
# store silently ignores the whole manifest.  Also, "en" must not appear under
# locales — it is the top-level base, not a locale override.  These are the
# two distinct failures and get distinct messages.
#
# The permitted set is not ours to choose: it is the list in the `locales`
# description of the upstream schema, which is prose and therefore enforced by
# nothing on their side.  Re-read it whenever the schema is re-vendored — it
# grew from 9 to 11 (adding "tr" and "cs") in the revision that also renamed
# `ad` to `has_ads`, and a stale copy here rejects a locale the store accepts.
if jq -e '((.locales // {} | keys) - ["ar","en","fr","es","pt","ru","hi","zh","ja","tr","cs"]) == []' "$MANIFEST" >/dev/null 2>&1; then
  ok "locale keys are all in the permitted set (ar en fr es pt ru hi zh ja tr cs)"
else
  fail "locales contains unrecognised locale keys (valid: ar en fr es pt ru hi zh ja tr cs)"
  jq -r '((.locales // {} | keys) - ["ar","en","fr","es","pt","ru","hi","zh","ja","tr","cs"])[]' "$MANIFEST" >&2
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
# LOCKSTEP-BEGIN
# shizu_store.json's download_url is /releases/latest/, which GitHub
# resolves to the newest NON-pre-release - production's build. dev and
# master both mint pre-releases, so their gradle.properties is one or more
# codes ahead of what that URL actually serves. Read the code from
# production so the changelog we assert matches the APK a user downloads.
#
# The versionCode pattern is ANCHORED on purpose: an unanchored 'versionCode'
# also matches initialVersionCode=1921, feeding two lines into the arithmetic -
# the bug that made the old release-manager workflow unusable (deleted
# 2026-07-27). The `head -n 1` hardens it further: even a gradle.properties
# carrying two legitimate matches yields one number rather than a syntax error.
#
# LOCKSTEP: everything between the two sentinels is byte-identical in
# check-shizu-manifest.sh and sync-shizu-changelog.sh - edit both copies
# together. .github/scripts/test/test-shizu-version-source.sh extracts the two
# regions and diffs them, so drift is a red test rather than a discovery.
# Failure REPORTING sits outside the sentinels on purpose: the checker
# accumulates and the sync script aborts, and that difference is deliberate.
production_ref="${SHIZU_VERSION_REF:-origin/production}"
if ! git rev-parse --verify --quiet "$production_ref" >/dev/null; then
  # A shallow or single-branch clone will not have it. Fetch just that ref -
  # but ONLY when we picked the default. An explicit SHIZU_VERSION_REF that
  # does not resolve is an operator error, and fetching production instead
  # would answer a question nobody asked: measured before this guard,
  # SHIZU_VERSION_REF=origin/v1.93.0 in a clone without that ref reported
  # production's 1940 and exited 0. Leaving the unresolvable override in place
  # makes `git show` fail, which the guard below reports against the ref that
  # was actually requested.
  #
  # Setting production_ref=FETCH_HEAD unconditionally after a `|| true` fetch
  # LOOKS like a stale-FETCH_HEAD trap, and is not, by a git implementation
  # detail worth writing down: git truncates .git/FETCH_HEAD to 0 bytes at the
  # start of every fetch attempt, including one that then fails. Measured — a
  # clone whose FETCH_HEAD held master (versionCode 9990) with a broken remote
  # URL read EMPTY, not 9990, and the guard below fired. That is what makes this
  # fail closed. Do NOT "fix" it into `git fetch … || production_ref=<fallback>`:
  # that reintroduces the stale read, and a wrong-but-plausible version silently
  # attaches the wrong changelog to a shipped APK.
  if [ -z "${SHIZU_VERSION_REF:-}" ]; then
    git fetch --quiet --depth=1 origin production 2>/dev/null || true
    production_ref="FETCH_HEAD"
  fi
fi

# `|| true` is load-bearing and must stay. sync-shizu-changelog.sh runs under
# `set -e`, where a failing pipeline in an assignment kills the script AT THIS
# LINE - before the guard below can say why, and with git's own stderr already
# swallowed by the 2>/dev/null. Measured: without it, the identical block prints
# the ::error:: under `set -uo` and nothing at all under `set -euo`. The checker
# has no -e today, but its header already warns against constructs that break
# "the day someone adds set -e". One spelling has to be right under both.
version_code="$(git show "${production_ref}:gradle.properties" 2>/dev/null \
  | grep -E '^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*[0-9]+[[:space:]]*$' \
  | head -n 1 | cut -d= -f2 | tr -d '[:space:]')" || true

version_name="$((version_code / 1000)).$(((version_code % 1000) / 10)).$((version_code % 10))"
notes="release-notes/v$version_name/playstore.txt"
[ -f "$notes" ] || notes="release-notes/$version_name/playstore.txt"
# LOCKSTEP-END

# Reporting is deliberately NOT in the lockstep block: this script accumulates
# failures and the other one aborts, and that difference is the whole point of
# each. `fail`, not `exit 1` - an exit here would abandon the --network tier
# below on the strength of one transient fetch failure, and that tier is the
# only thing that catches rot originating outside the repository.
if [ -z "$version_code" ]; then
  fail "could not read versionCode from ${production_ref}:gradle.properties — fetch it with: git fetch origin production"
elif [ ! -f "$notes" ]; then
  fail "no playstore.txt for v$version_name (versionCode $version_code)"
else
  # The only call site that may pass a reporter other than the default.
  compare_text "changelog" '.changelog' "$notes" changelog_drift
  printf '       (versionCode %s -> v%s)\n' "$version_code" "$version_name"
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
    code="$(image_code "$u")"
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
  # docshizu.siwane.xyz sits behind bot protection that 403s datacenter address
  # ranges. A GitHub Actions runner is in that range; an ordinary connection is
  # not, so this tier passes locally and 403s in CI. Try a browser UA before
  # concluding anything — it costs one request and tells us whether the block is
  # keyed on the agent or on the address.
  up_code=""
  for ua in 'thor-shizu-audit/1' \
            'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36'; do
    up_code="$(curl -sSL --max-time 25 -A "$ua" -o "$tmp/upstream.json" \
                    -w '%{http_code}' "$UPSTREAM_SCHEMA_URL" 2>/dev/null)" || true
    [ -n "$up_code" ] || up_code=000
    [ "$up_code" = "200" ] && break
  done
  if [ "$up_code" != "200" ]; then
    # Unreachable is not drift. Failing the weekly run on a block we cannot lift
    # would leave the tracking issue permanently open, and an alarm that is
    # always on is an alarm nobody reads.
    warn "could not fetch $UPSTREAM_SCHEMA_URL (HTTP $up_code) — the live schema was NOT compared"
    printf '       (run this checker off a CI runner to cover it)\n' >&2
  elif ! jq -e . "$tmp/upstream.json" >/dev/null 2>&1; then
    warn "upstream schema fetch returned non-JSON (captive portal?) — the live schema was NOT compared"
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
fi

printf '\n'
if [ "$failures" -eq 0 ]; then
  if [ "$warnings" -eq 0 ]; then
    printf 'shizu_store.json: all checks passed\n'
  else
    printf 'shizu_store.json: all checks passed, %d warning(s) (see WARN above)\n' "$warnings"
  fi
  exit 0
fi
printf 'shizu_store.json: %d check(s) failed\n' "$failures" >&2
printf 'if only the changelog drifted, run: .github/scripts/sync-shizu-changelog.sh\n' >&2
exit 1
