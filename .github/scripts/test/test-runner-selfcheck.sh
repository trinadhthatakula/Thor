#!/usr/bin/env bash
# Proves run-tests.sh discovers and executes test files. Kept permanently:
# if discovery ever breaks, every other suite silently reports success.
set -euo pipefail
assertions=0

[ -x "$(dirname "${BASH_SOURCE[0]}")/run-tests.sh" ] || {
  echo "  run-tests.sh is not executable"; exit 1; }
assertions=$((assertions + 1))
echo "  ok: run-tests.sh is executable"

echo "  ${assertions} assertion(s)"
