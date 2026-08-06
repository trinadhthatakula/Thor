#!/usr/bin/env bash
# Runs every test-*.sh in this directory.
#
# Each test script is self-contained: it creates its own fixtures under a
# mktemp -d, cleans up on exit, prints one line per assertion, and exits
# non-zero on the first failure. A test file that exits 0 having asserted
# nothing is indistinguishable from a passing one, so every test script prints
# its own assertion count — that count is for a reader, this runner does not
# add them up. What the runner does enforce is that it found test files at all:
# a glob that matches nothing would otherwise report a green run of zero tests.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
failed=0
ran=0

shopt -s nullglob
for t in "$here"/test-*.sh; do
  ran=$((ran + 1))
  printf '\n=== %s ===\n' "$(basename "$t")"
  if bash "$t"; then
    printf '    PASS\n'
  else
    printf '    FAIL\n'
    failed=$((failed + 1))
  fi
done

if [ "$ran" -eq 0 ]; then
  echo "ERROR: no test-*.sh found in $here — a run that tests nothing is not a pass." >&2
  exit 1
fi

printf '\n%d test file(s), %d failed\n' "$ran" "$failed"
[ "$failed" -eq 0 ]
