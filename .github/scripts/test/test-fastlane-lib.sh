#!/usr/bin/env bash
# Runs the Ruby unit tests for fastlane/lib so one runner covers every
# non-Gradle test in the repo.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

ruby "$repo_root/fastlane/test/test_thor_release.rb"
echo "  ok: fastlane/lib unit tests passed"

# A GitHub workflow command has to start the line, and inside a Fastfile `puts`
# is NOT Kernel#puts: fast_file.rb defines its own, which runs the puts action
# and prints through UI.message, which prefixes "[HH:MM:SS]: " unless
# FASTLANE_HIDE_TIMESTAMP is set - this repo does not set it. So a bare
# `puts "::warning..."` emits no annotation while looking exactly like it does,
# in a build that stays green. Only an explicit receiver bypasses the override.
fastfile="$repo_root/fastlane/Fastfile"
bare="$(grep -nE '^[[:space:]]*puts[[:space:]]*["'"'"']::' "$fastfile" || true)"
if [ -n "$bare" ]; then
  echo "::error::Fastfile emits a GitHub workflow command through fastlane's puts action, which prefixes the line and voids the annotation. Use \$stdout.puts:" >&2
  echo "$bare" >&2
  exit 1
fi
echo "  ok: no workflow command in the Fastfile is emitted through fastlane's puts"

# Non-vacuity: the check above passes trivially if nothing emits one at all.
# If a future edit deliberately removes the last annotation, update this line
# rather than deleting it, so "nothing to check" stays a decision and not a
# silent gap.
# [$] rather than \$ so the pattern needs no backslash escape inside single
# quotes, which shellcheck reads as an attempted expansion (SC2016).
emitted="$(grep -cE '([$]stdout|STDOUT|Kernel)[.]puts[[:space:]]*["'"'"']::' "$fastfile" || true)"
if [ "$emitted" -lt 1 ]; then
  echo "::error::the Fastfile no longer emits any GitHub workflow command with an explicit receiver, so the check above is vacuous" >&2
  exit 1
fi
echo "  ok: ${emitted} workflow command(s) emitted with an explicit receiver"
