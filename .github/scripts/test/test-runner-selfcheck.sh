#!/usr/bin/env bash
# Proves run-tests.sh discovers and executes test files. Kept permanently:
# if discovery ever breaks, every other suite silently reports success - a
# green CI job that ran nothing looks exactly like a green CI job that ran
# everything.
#
# This used to check only the executable bit, which proves nothing about
# discovery: a runner whose glob matched nothing is still executable. So it
# now runs a COPY of the real runner against throwaway fixtures and asserts
# the four properties the rest of the suite depends on:
#
#   1. it runs the tests it finds,
#   2. a failing test fails the run,
#   3. finding NOTHING fails the run - the fail-closed property, and
#   4. discovery is not recursive, so a helper parked in a subdirectory is
#      not silently promoted to a test.
#
# The copy is the point. Pointing the real runner at a fixture directory is
# not possible - it globs its own directory, by design, so there is no path
# argument to hijack - and running the real one in place would recurse into
# this very file.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
runner="$here/run-tests.sh"
assertions=0

[ -x "$runner" ] || { echo "  run-tests.sh is not executable"; exit 1; }
assertions=$((assertions + 1))
echo "  ok: run-tests.sh is executable"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fixture() { # dir, name, exit-code
  mkdir -p "$1"
  printf '#!/usr/bin/env bash\necho "  ran %s"\nexit %s\n' "$2" "$3" > "$1/$2"
  chmod +x "$1/$2"
}

# 1 + 4: two passing tests are found and run; a script one directory down is
# not. The nested one exits 1, so if discovery ever went recursive this case
# turns red rather than merely counting one extra file.
green="$tmp/green"
mkdir -p "$green"
cp "$runner" "$green/"
fixture "$green" "test-alpha.sh" 0
fixture "$green" "test-beta.sh" 0
fixture "$green/nested" "test-should-not-run.sh" 1

out="$("$green/run-tests.sh" 2>&1)" || {
  echo "  run-tests.sh failed on two passing fixtures:"; echo "$out"; exit 1; }
assertions=$((assertions + 1))
echo "  ok: a run of passing tests exits 0"

case "$out" in
  *"ran test-alpha.sh"*) ;;
  *) echo "  test-alpha.sh was never executed:"; echo "$out"; exit 1 ;;
esac
case "$out" in
  *"ran test-beta.sh"*) ;;
  *) echo "  test-beta.sh was never executed:"; echo "$out"; exit 1 ;;
esac
assertions=$((assertions + 1))
echo "  ok: every discovered test actually ran"

case "$out" in
  *"test-should-not-run.sh"*)
    echo "  discovery reached a subdirectory - it must not:"; echo "$out"; exit 1 ;;
esac
assertions=$((assertions + 1))
echo "  ok: discovery is not recursive"

case "$out" in
  *"2 test file(s), 0 failed"*) ;;
  *) echo "  the summary line does not report 2 files, 0 failed:"; echo "$out"; exit 1 ;;
esac
assertions=$((assertions + 1))
echo "  ok: the summary counts what ran"

# 2: one failing test must fail the whole run.
red="$tmp/red"
mkdir -p "$red"
cp "$runner" "$red/"
fixture "$red" "test-ok.sh" 0
fixture "$red" "test-broken.sh" 1

if out="$("$red/run-tests.sh" 2>&1)"; then
  echo "  a failing test did not fail the run:"; echo "$out"; exit 1
fi
assertions=$((assertions + 1))
echo "  ok: a failing test fails the run"

# 3: the fail-closed property. This is the one that matters - a runner that
# reports success having found nothing is indistinguishable from a passing
# suite, and every other test file in this directory is trusting it.
empty="$tmp/empty"
mkdir -p "$empty"
cp "$runner" "$empty/"

if out="$("$empty/run-tests.sh" 2>&1)"; then
  echo "  a run that discovered ZERO tests reported success:"; echo "$out"; exit 1
fi
assertions=$((assertions + 1))
echo "  ok: discovering zero tests fails the run"

echo "  ${assertions} assertion(s)"
