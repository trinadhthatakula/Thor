#!/usr/bin/env bash
# Refresh shizu_store.json's changelog from the release notes of the last
# PRODUCTION release.
#
# Run this AFTER a production promotion, on master, and commit shizu_store.json
# there - NOT during release prep on dev, and NOT in the commit that bumps
# versionCode. The version comes from origin/production (the LOCKSTEP block
# below explains why), so running it at release prep re-syncs the PREVIOUS
# release and prints "changelog already current", which reads like success while
# the version being prepared never reaches the manifest. release-notes/README.md
# Step 5 carries the sequence. `git fetch origin production` first.
#
# CI never runs it: the branch ruleset on master requires a pull request and a
# build-and-test status check, and a GITHUB_TOKEN-authored PR does not trigger
# pull_request workflows, so no bot can land a commit here. PR CI verifies the
# result instead - as a warning, because a production promotion moves the target
# without any PR running; the weekly shizu-store-audit is the hard gate.
#
# Usage: [SHIZU_VERSION_REF=<ref>] .github/scripts/sync-shizu-changelog.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

MANIFEST="shizu_store.json"

command -v jq >/dev/null 2>&1 || { printf 'missing required tool: jq\n' >&2; exit 2; }

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

# Reporting is deliberately NOT in the lockstep block: this script aborts and
# the checker accumulates, and that difference is the whole point of each.
if [ -z "$version_code" ]; then
  printf 'could not read versionCode from %s:gradle.properties\n' "$production_ref" >&2
  printf 'fetch the ref first: git fetch origin production\n' >&2
  exit 1
fi

if [ ! -f "$notes" ]; then
  printf 'no release notes for v%s (versionCode %s)\n' "$version_name" "$version_code" >&2
  printf 'expected release-notes/v%s/playstore.txt\n' "$version_name" >&2
  exit 1
fi

notes_content="$(cat "$notes")"
if [ -z "$(printf '%s' "$notes_content" | tr -d '[:space:]')" ]; then
  printf 'release-notes file is empty: %s\n' "$notes" >&2
  printf 'write the release notes before running this script\n' >&2
  exit 1
fi

# --arg via command substitution strips trailing newlines, matching how the
# checker compares. jq handles JSON escaping, so quotes or newlines in the
# notes cannot corrupt the manifest the way sed would.
# Temp file lives in the same directory as the manifest so mv is atomic on
# the same filesystem — a half-written manifest is invisible to the store.
# trap is set before mktemp so a kill between the two cannot leak a stray
# shizu_store.json.XXXXXX file into the repo root.
tmp=""
trap 'rm -f "$tmp"' EXIT
tmp="$(mktemp "${MANIFEST}.XXXXXX")"
jq --arg cl "$notes_content" '.changelog = $cl' "$MANIFEST" > "$tmp"

if cmp -s "$MANIFEST" "$tmp"; then
  printf 'changelog already current for v%s\n' "$version_name"
  exit 0
fi

# chmod before mv: mktemp creates files at 0600; mv replaces the inode so the
# manifest would inherit that mode.  cp would preserve the existing mode but
# is not atomic.  Set 0644 explicitly to match what git tracks (100644).
chmod 644 "$tmp"
mv "$tmp" "$MANIFEST"
printf 'changelog updated from %s (v%s)\n' "$notes" "$version_name"
printf 'review the diff, then commit shizu_store.json with the version bump.\n'
