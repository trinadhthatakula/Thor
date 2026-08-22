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

make_files() {
  # $1 = dir, rest = names of note files to create (small, inside every budget)
  local dir="$1"; shift
  mkdir -p "$dir/release-notes/v9.99.9"
  local f
  for f in "$@"; do
    echo "note" > "$dir/release-notes/v9.99.9/$f"
  done
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

check_args() {
  # As check(), but every remaining argument is passed to the script ahead of
  # the version name - which is how release-rung.yml passes --require.
  local label="$1" expect_pass="$2" dir="$3"; shift 3
  assertions=$((assertions + 1))
  if (cd "$dir" && bash "$script" "$@" 9.99.9 160) >/dev/null 2>&1; then
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

# 511 rockets at wrapper=1: 1022 + 1 = 1023 UTF-16 units (pass), but
# 2044 + 1 = 2045 UTF-8 bytes (fail). The case above separates UTF-16 from
# character counting (500 rockets is over budget under both). This one
# separates UTF-16 from byte counting. Neither case alone pins the unit
# uniquely; together they do.
d="$(mktemp -d)"
mkdir -p "$d/release-notes/v9.99.9"
python3 -c "
import sys
# 511 rockets = 1022 UTF-16 units. Plus a 1-unit wrapper = 1023 <= 1024.
open(sys.argv[1], 'w', encoding='utf-8').write('\U0001F680' * 511)
open(sys.argv[2], 'w', encoding='utf-8').write('ok')
" "$d/release-notes/v9.99.9/telegram.md" "$d/release-notes/v9.99.9/playstore.txt"
check "UTF-16 fits but byte count would not" pass "$d" 1
rm -rf "$d"

# Missing notes must not pass silently.
d="$(mktemp -d)"; mkdir -p "$d/release-notes"
check "missing notes dir" fail "$d"
rm -rf "$d"

# --- --require: the files a rung consumes -------------------------------------
#
# This is what release-rung.yml passes when require_notes is true. The bug it
# closes: a directory holding only playstore.txt scored "playstore: 19/500" and
# exited 0, so the production rung published a raw git log as its release body
# and broadcast the generic bullet to Telegram.

PROD_REQ=(--require github.md --require playstore.txt --require telegram.md)

# The complete directory a production release actually has.
d="$(mktemp -d)"; make_files "$d" github.md telegram.md playstore.txt
check_args "all three present, all three required" pass "$d" "${PROD_REQ[@]}"
rm -rf "$d"

# Each required file missing in turn.
d="$(mktemp -d)"; make_files "$d" telegram.md playstore.txt
check_args "github.md missing, required" fail "$d" "${PROD_REQ[@]}"
rm -rf "$d"

d="$(mktemp -d)"; make_files "$d" github.md playstore.txt
check_args "telegram.md missing, required" fail "$d" "${PROD_REQ[@]}"
rm -rf "$d"

d="$(mktemp -d)"; make_files "$d" github.md telegram.md
check_args "playstore.txt missing, required" fail "$d" "${PROD_REQ[@]}"
rm -rf "$d"

# The exact directory the reviewer reproduced the bug with.
d="$(mktemp -d)"; make_files "$d" playstore.txt
check_args "playstore.txt only, production requirements" fail "$d" "${PROD_REQ[@]}"
rm -rf "$d"

# The --require=X spelling has to behave identically to --require X.
d="$(mktemp -d)"; make_files "$d" playstore.txt
check_args "--require=github.md, absent" fail "$d" --require=github.md
rm -rf "$d"

# A rung that does not broadcast must not be made to carry telegram.md: a gate
# on a file nothing reads can only ever fire falsely.
d="$(mktemp -d)"; make_files "$d" github.md playstore.txt
check_args "telegram.md absent, not required (telegram: false rung)" pass "$d" \
  --require github.md --require playstore.txt
rm -rf "$d"

# require_notes: false must keep falling back rather than failing - the dev and
# beta rungs publish without curated notes on purpose.
d="$(mktemp -d)"; make_files "$d" playstore.txt
check_args "playstore.txt only, nothing required" pass "$d"
rm -rf "$d"

d="$(mktemp -d)"; make_files "$d" github.md playstore.txt
check_args "telegram.md absent, nothing required" pass "$d"
rm -rf "$d"

# playstore.txt has no fallback anywhere, so it stays an error even unrequired.
d="$(mktemp -d)"; make_files "$d" github.md telegram.md
check_args "playstore.txt absent, nothing required" fail "$d"
rm -rf "$d"

# An unrecognised option must not be silently swallowed as a version name.
d="$(mktemp -d)"; make_files "$d" github.md telegram.md playstore.txt
check_args "unknown option rejected" fail "$d" --strict
rm -rf "$d"

echo "  ${assertions} assertion(s)"
[ "$failed" -eq 0 ]
