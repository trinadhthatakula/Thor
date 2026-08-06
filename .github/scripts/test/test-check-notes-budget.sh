#!/usr/bin/env bash
set -euo pipefail
script="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/check-notes-budget.sh"
assertions=0
failed=0

make_notes() {
  # $1 = dir, $2 = telegram byte count, $3 = playstore byte count
  mkdir -p "$1/release-notes/v9.99.9"
  head -c "$2" /dev/zero | tr '\0' 'a' > "$1/release-notes/v9.99.9/telegram.md"
  head -c "$3" /dev/zero | tr '\0' 'b' > "$1/release-notes/v9.99.9/playstore.txt"
}

check() {
  local label="$1" expect_pass="$2" dir="$3" wrapper="${4:-160}"
  assertions=$((assertions + 1))
  if (cd "$dir" && bash "$script" 9.99.9 "$wrapper") >/dev/null 2>&1; then
    got=pass
  else
    got=fail
  fi
  if [ "$got" = "$expect_pass" ]; then
    echo "  ok: $label ($got)"
  else
    echo "  FAIL: $label - expected $expect_pass, got $got"
    failed=1
  fi
}

# Comfortably inside both budgets.
d="$(mktemp -d)"; make_notes "$d" 600 300
check "notes within budget" pass "$d"
rm -rf "$d"

# 900 + 160 wrapper = 1060 > 1024.
d="$(mktemp -d)"; make_notes "$d" 900 300
check "telegram over the caption cap" fail "$d"
rm -rf "$d"

# Exactly at the cap: 864 + 160 = 1024. Must pass - the limit is inclusive.
d="$(mktemp -d)"; make_notes "$d" 864 300
check "telegram exactly at the cap" pass "$d"
rm -rf "$d"

# One over.
d="$(mktemp -d)"; make_notes "$d" 865 300
check "telegram one unit over" fail "$d"
rm -rf "$d"

# playstore.txt cap is 500.
d="$(mktemp -d)"; make_notes "$d" 600 520
check "playstore over 500 chars" fail "$d"
rm -rf "$d"

# An emoji is ONE character but TWO UTF-16 units. Counting bytes or
# characters instead of UTF-16 units is the whole reason this gate exists.
d="$(mktemp -d)"
mkdir -p "$d/release-notes/v9.99.9"
python3 -c "
import sys
# 500 rockets = 1000 UTF-16 units. Plus a 30-unit wrapper = 1030 > 1024.
open(sys.argv[1], 'w', encoding='utf-8').write('\U0001F680' * 500)
open(sys.argv[2], 'w', encoding='utf-8').write('ok')
" "$d/release-notes/v9.99.9/telegram.md" "$d/release-notes/v9.99.9/playstore.txt"
check "emoji counted as 2 UTF-16 units" fail "$d" 30
rm -rf "$d"

# Missing notes must not pass silently.
d="$(mktemp -d)"; mkdir -p "$d/release-notes"
check "missing notes dir" fail "$d"
rm -rf "$d"

echo "  ${assertions} assertion(s)"
[ "$failed" -eq 0 ]
