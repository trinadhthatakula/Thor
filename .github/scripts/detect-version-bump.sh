#!/usr/bin/env bash
# Did this push change versionCode?
#
# usage: detect-version-bump.sh <old-ref> [properties-path]
# prints: changed=true|false
#         code=<integer>
#         name=<x.y.z>
#
# Compares the PARSED value, not `git diff --quiet -- gradle.properties`: that
# file also carries Gradle daemon flags and memory settings, and a JVM tweak is
# not a release. HEAD^ on a PR merge commit is the previous branch tip (first
# parent), which is the comparison we want.
#
# Fails OPEN on an unreadable OLD value - report a release and let Play
# adjudicate, rather than silently skipping a real one. An unreadable CURRENT
# value is a hard error: there is nothing to publish and nothing to compare.
#
# Play's duplicate-code rejection is not suppressed anywhere and must not be.
# It is the real versioning gate and it is server-side. This script only
# decides whether a push was MEANT to publish.
set -euo pipefail

old_ref="${1:?usage: detect-version-bump.sh <old-ref> [properties-path]}"
props="${2:-gradle.properties}"

# Anchored, and rejects comments: an unanchored match also finds
# initialVersionCode=1921, which fed two lines into arithmetic and made the old
# release-manager workflow unusable.
read_code() {
  grep -E '^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*[0-9]+[[:space:]]*$' \
    | head -n 1 | cut -d= -f2 | tr -d '[:space:]'
}

new_code="$(read_code < "$props" || true)"
if [ -z "$new_code" ]; then
  echo "::error::versionCode not found in $props" >&2
  exit 1
fi

old_code="$(git show "${old_ref}:${props}" 2>/dev/null | read_code || true)"

name="$((new_code / 1000)).$(((new_code % 1000) / 10)).$((new_code % 10))"

if [ -n "$old_code" ] && [ "$old_code" = "$new_code" ]; then
  echo "::notice::versionCode unchanged at $new_code - building for verification only." >&2
  echo "changed=false"
else
  echo "::notice::versionCode ${old_code:-<unreadable>} -> $new_code - publishing." >&2
  echo "changed=true"
fi

echo "code=$new_code"
echo "name=$name"
