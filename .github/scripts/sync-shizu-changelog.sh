#!/usr/bin/env bash
# Refresh shizu_store.json's changelog from the current release notes.
#
# Run this during release prep, in the same commit that bumps versionCode and
# adds release-notes/v<version>/. CI never runs it: the branch ruleset on
# master requires a pull request and a build-and-test status check, and a
# GITHUB_TOKEN-authored PR does not trigger pull_request workflows, so no bot
# can land a commit here. PR CI verifies the result instead.
#
# Usage: .github/scripts/sync-shizu-changelog.sh
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
# LOCKSTEP: this block (production ref, versionCode grep, version
# arithmetic, notes fallback path) is kept in lockstep with
# sync-shizu-changelog.sh - update both scripts together.
production_ref="${SHIZU_VERSION_REF:-origin/production}"
if ! git rev-parse --verify --quiet "$production_ref" >/dev/null; then
  # A shallow or single-branch clone will not have it. Fetch just that ref.
  git fetch --quiet --depth=1 origin production 2>/dev/null || true
  production_ref="FETCH_HEAD"
fi

version_code="$(git show "${production_ref}:gradle.properties" 2>/dev/null \
  | grep -E '^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*[0-9]+[[:space:]]*$' \
  | head -n 1 | cut -d= -f2 | tr -d '[:space:]')"

if [ -z "$version_code" ]; then
  echo "::error::could not read versionCode from ${production_ref}:gradle.properties" >&2
  exit 1
fi

version_name="$((version_code / 1000)).$(((version_code % 1000) / 10)).$((version_code % 10))"
# LOCKSTEP-END

notes="release-notes/v$version_name/playstore.txt"
[ -f "$notes" ] || notes="release-notes/$version_name/playstore.txt"
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
