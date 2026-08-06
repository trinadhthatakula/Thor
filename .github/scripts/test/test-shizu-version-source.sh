#!/usr/bin/env bash
# The two Shizu scripts must read gradle.properties from the production
# branch, because shizu_store.json's download_url resolves to the newest
# non-pre-release - which under the three-rung ladder is production's build,
# not the working tree's.
set -euo pipefail
scripts_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
assertions=0
failed=0

check() {
  local label="$1" file="$2" pattern="$3" want="$4"
  assertions=$((assertions + 1))
  if grep -qE -- "$pattern" "$file"; then got=present; else got=absent; fi
  if [ "$got" = "$want" ]; then
    echo "  ok: $label ($got)"
  else
    echo "  FAIL: $label - expected $want, got $got for /$pattern/ in $file"
    failed=1
  fi
}

for f in check-shizu-manifest.sh sync-shizu-changelog.sh; do
  path="$scripts_dir/$f"
  assertions=$((assertions + 1))
  if [ -f "$path" ]; then
    echo "  ok: $f exists"
  else
    echo "  FAIL: $f is missing"
    failed=1
    continue
  fi

  # Must read the code from a production ref.
  check "$f reads gradle.properties from a production ref" \
    "$path" 'git show[^|]*production[^|]*gradle\.properties' present

  # Must NOT fall back to a bare working-tree read for the version.
  check "$f does not grep the working-tree gradle.properties for versionCode" \
    "$path" "^[^#]*grep[^|]*versionCode[^|]*['\\\"]?gradle\.properties" absent
done

# The LOCKSTEP contract: the resolver block must be byte-identical in both
# scripts, because the duplication IS the contract.
#
# Asserting that the word "lockstep" appears in both files is not a gate. It
# stays green while one copy divides by 100 instead of 1000, and it stays green
# while one copy reads origin/master instead of origin/production - which is the
# exact bug the production rebinding exists to fix. So extract the region
# between the sentinels and compare it exactly.
#
# sync-shizu-changelog.sh is run by nothing in CI. This comparison is the only
# automated thing standing between it and silent divergence.
extract_block() {
  awk '/^# LOCKSTEP-BEGIN$/ { in_block = 1; next }
       /^# LOCKSTEP-END$/   { in_block = 0 }
       in_block' "$1"
}

for f in check-shizu-manifest.sh sync-shizu-changelog.sh; do
  assertions=$((assertions + 1))
  if grep -qxF '# LOCKSTEP-BEGIN' "$scripts_dir/$f" \
     && grep -qxF '# LOCKSTEP-END' "$scripts_dir/$f"; then
    echo "  ok: $f carries both LOCKSTEP sentinels"
  else
    echo "  FAIL: $f is missing a '# LOCKSTEP-BEGIN' or '# LOCKSTEP-END' sentinel"
    failed=1
  fi
done

assertions=$((assertions + 1))
block_a="$(extract_block "$scripts_dir/check-shizu-manifest.sh")"
block_b="$(extract_block "$scripts_dir/sync-shizu-changelog.sh")"
if [ -z "$block_a" ] || [ -z "$block_b" ]; then
  # Without this, deleting both blocks would compare "" to "" and pass.
  echo "  FAIL: a LOCKSTEP block is empty - an empty-vs-empty comparison is not a gate"
  failed=1
elif [ "$block_a" = "$block_b" ]; then
  echo "  ok: the LOCKSTEP resolver block is byte-identical in both scripts"
else
  echo "  FAIL: the LOCKSTEP resolver blocks have drifted ('<' = checker, '>' = sync):"
  diff <(printf '%s\n' "$block_a") <(printf '%s\n' "$block_b") | sed 's/^/    /' || true
  failed=1
fi

echo "  ${assertions} assertion(s)"
[ "$failed" -eq 0 ]
