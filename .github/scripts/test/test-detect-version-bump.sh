#!/usr/bin/env bash
# Builds a throwaway git repo per case so the assertions are against real
# `git show` behaviour rather than a mock.
set -euo pipefail
script="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/detect-version-bump.sh"
assertions=0
failed=0

setup_repo() {
  d="$(mktemp -d)"
  git -C "$d" init -q
  git -C "$d" config user.email t@example.com
  git -C "$d" config user.name t
  printf 'org.gradle.jvmargs=-Xmx4g\nversionCode=%s\n' "$1" > "$d/gradle.properties"
  git -C "$d" add gradle.properties
  git -C "$d" commit -q -m first
  echo "$d"
}

bump_repo() {
  printf 'org.gradle.jvmargs=-Xmx4g\nversionCode=%s\n' "$2" > "$1/gradle.properties"
  git -C "$1" add gradle.properties
  git -C "$1" commit -q -m bump
}

expect() {
  local label="$1" haystack="$2" needle="$3"
  assertions=$((assertions + 1))
  if printf '%s' "$haystack" | grep -qx -- "$needle"; then
    echo "  ok: $label"
  else
    echo "  FAIL: $label - expected line '$needle' in:"
    printf '%s\n' "$haystack" | sed 's/^/       /'
    failed=1
  fi
}

# 1. An unchanged code is not a release.
d="$(setup_repo 1940)"; git -C "$d" commit -q --allow-empty -m noop
out="$(cd "$d" && bash "$script" 'HEAD^')"
expect "unchanged code -> changed=false" "$out" "changed=false"
expect "unchanged code still reports the code" "$out" "code=1940"
rm -rf "$d"

# 2. A bumped code is a release, and the derived name is right.
d="$(setup_repo 1940)"; bump_repo "$d" 1941
out="$(cd "$d" && bash "$script" 'HEAD^')"
expect "bumped code -> changed=true" "$out" "changed=true"
expect "bumped code reports the new code" "$out" "code=1941"
expect "bumped code derives the name" "$out" "name=1.94.1"
rm -rf "$d"

# 3. A stable (code ending in 0) is NOT special. The digit gate is gone -
#    branch identity decides the rung now, not arithmetic on the version.
d="$(setup_repo 1939)"; bump_repo "$d" 1940
out="$(cd "$d" && bash "$script" 'HEAD^')"
expect "a stable is an ordinary bump" "$out" "changed=true"
expect "a stable derives x.y.0" "$out" "name=1.94.0"
rm -rf "$d"

# 4. Fails OPEN: an unreadable old value must not silently skip a release.
d="$(setup_repo 1940)"
out="$(cd "$d" && bash "$script" 'refs/heads/nonexistent')"
expect "unreadable old ref -> changed=true" "$out" "changed=true"
rm -rf "$d"

# 5. A JVM tweak with no version change is not a release, even though
#    gradle.properties itself changed.
d="$(setup_repo 1940)"
printf 'org.gradle.jvmargs=-Xmx8g\nversionCode=1940\n' > "$d/gradle.properties"
git -C "$d" add gradle.properties; git -C "$d" commit -q -m tune
out="$(cd "$d" && bash "$script" 'HEAD^')"
expect "jvmargs change alone -> changed=false" "$out" "changed=false"
rm -rf "$d"

# 6. An unreadable CURRENT value is a hard error, not a fail-open.
d="$(setup_repo 1940)"
printf 'org.gradle.jvmargs=-Xmx4g\n' > "$d/gradle.properties"
assertions=$((assertions + 1))
if (cd "$d" && bash "$script" 'HEAD') >/dev/null 2>&1; then
  echo "  FAIL: missing current versionCode should exit non-zero"
  failed=1
else
  echo "  ok: missing current versionCode exits non-zero"
fi
rm -rf "$d"

echo "  ${assertions} assertion(s)"
[ "$failed" -eq 0 ]
