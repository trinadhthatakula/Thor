#!/usr/bin/env bash
# Exercise the real Fastfile copying path and require complete canonical
# changelogs, since a non-empty first line passes the locale-presence gate.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

ruby "$repo_root/fastlane/test/test_fastfile_changelogs.rb"

python3 - "$repo_root" <<'PY'
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
properties = (root / 'gradle.properties').read_text(encoding='utf-8')
current = re.search(r'^\s*versionCode\s*=\s*(\d+)\s*$', properties, re.MULTILINE)
assert current, 'versionCode missing from gradle.properties'
current_code = int(current.group(1))
checked = 0
for source in sorted((root / 'release-notes').glob('*/playstore.txt')):
    major, minor, patch = map(int, source.parent.name.removeprefix('v').split('.'))
    code = major * 1000 + minor * 10 + patch
    reference = root / 'fastlane/metadata/android/en-US/changelogs' / f'{code}.txt'
    # Some historical releases predate the committed Fastlane changelogs.
    # Existing pairs must agree, and the current release must have its copies.
    if code != current_code and not reference.exists():
        continue
    for locale in ('en-US', 'en-GB'):
        target = root / 'fastlane/metadata/android' / locale / 'changelogs' / f'{code}.txt'
        assert target.is_file(), f'Missing canonical changelog: {target}'
        assert source.read_bytes() == target.read_bytes(), f'Truncated or stale changelog: {target}'
        checked += 1
assert checked > 0, 'No source/changelog pairs checked'
print(f'  ok: {checked} canonical changelogs preserve all source bytes')
print(f'  {checked} assertion(s)')
PY
