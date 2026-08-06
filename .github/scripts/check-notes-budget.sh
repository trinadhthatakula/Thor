#!/usr/bin/env bash
# Pre-flight size gate for release notes.
#
# usage: check-notes-budget.sh <version-name> [wrapper-units]
#
# Telegram caps a sendDocument CAPTION at 1024 UTF-16 units and REJECTS an
# oversized one - it does not truncate. The send has no --fail and its output
# is discarded, so an over-budget caption makes the step go green having posted
# nothing. That is why this runs pre-flight, before anything publishes: bolting
# it next to the curl would fail a half-published release.
#
# Measure UTF-16 units, not bytes and not characters. One emoji is a single
# character, two UTF-16 units, and four UTF-8 bytes.
#
# wrapper-units is the size of the caption scaffolding the workflow wraps
# around telegram.md. Measured per rung and actor:
#   dev rung:        149 (own actor) / 152 (bot actor)
#   beta rung:       145 (own actor) / 148 (bot actor)
#   production rung: 146 (own actor) / 149 (bot actor)
# 160 is a deliberate upper bound covering all three rungs in all modes.
set -euo pipefail

version_name="${1:?usage: check-notes-budget.sh <version-name> [wrapper-units]}"
wrapper_units="${2:-160}"

TELEGRAM_CAP=1024
PLAYSTORE_CAP=500

dir="release-notes/v${version_name}"
[ -d "$dir" ] || dir="release-notes/${version_name}"
if [ ! -d "$dir" ]; then
  echo "::error::no release-notes directory for v${version_name}" >&2
  exit 1
fi

status=0

telegram="$dir/telegram.md"
if [ -f "$telegram" ]; then
  units="$(python3 -c "
import sys
text = open(sys.argv[1], encoding='utf-8').read()
print(len(text.encode('utf-16-le')) // 2)
" "$telegram")"
  total=$((units + wrapper_units))
  if [ "$total" -gt "$TELEGRAM_CAP" ]; then
    echo "::error::telegram caption is ${total} UTF-16 units (${units} in file + ${wrapper_units} wrapper), cap is ${TELEGRAM_CAP}. Telegram rejects rather than truncates, and the send reports success either way." >&2
    status=1
  else
    echo "  telegram: ${total}/${TELEGRAM_CAP} units ($((TELEGRAM_CAP - total)) spare)"
  fi
else
  echo "::warning::no telegram.md in $dir" >&2
fi

playstore="$dir/playstore.txt"
if [ -f "$playstore" ]; then
  chars="$(python3 -c "
import sys
print(len(open(sys.argv[1], encoding='utf-8').read()))
" "$playstore")"
  if [ "$chars" -gt "$PLAYSTORE_CAP" ]; then
    echo "::error::playstore.txt is ${chars} characters, cap is ${PLAYSTORE_CAP}." >&2
    status=1
  else
    echo "  playstore: ${chars}/${PLAYSTORE_CAP} characters"
  fi
else
  echo "::error::no playstore.txt in $dir" >&2
  status=1
fi

exit "$status"
