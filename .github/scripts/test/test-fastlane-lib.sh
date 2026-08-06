#!/usr/bin/env bash
# Runs the Ruby unit tests for fastlane/lib so one runner covers every
# non-Gradle test in the repo.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

ruby "$repo_root/fastlane/test/test_thor_release.rb"
echo "  ok: fastlane/lib unit tests passed"
