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

# The LOCKSTEP contract has to survive this change: both files must still
# derive the same version name the same way.
assertions=$((assertions + 1))
if grep -q 'lockstep' "$scripts_dir/check-shizu-manifest.sh" \
   && grep -q -i 'lockstep' "$scripts_dir/sync-shizu-changelog.sh"; then
  echo "  ok: both scripts still carry the LOCKSTEP notice"
else
  echo "  FAIL: the LOCKSTEP notice must be in BOTH files, not just one"
  failed=1
fi

echo "  ${assertions} assertion(s)"
[ "$failed" -eq 0 ]
