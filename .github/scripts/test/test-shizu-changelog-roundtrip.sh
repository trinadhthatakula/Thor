#!/usr/bin/env bash
# The actual sync script must preserve internal newlines through JSON, even
# when notes contain quotes, emoji, or literal backslash-n text.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

python3 - "$repo_root" <<'PY'
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

root = pathlib.Path(sys.argv[1])
properties = (root / 'gradle.properties').read_text(encoding='utf-8')
match = re.search(r'^\s*versionCode\s*=\s*(\d+)\s*$', properties, re.MULTILINE)
assert match, 'versionCode missing from gradle.properties'
code = int(match.group(1))
version = f'{code // 1000}.{code % 1000 // 10}.{code % 10}'
current_notes = root / 'release-notes' / f'v{version}' / 'playstore.txt'
if not current_notes.exists():
    current_notes = root / 'release-notes' / version / 'playstore.txt'

escaped = '• 🛠 First "quoted" bullet.\n\n• 🎨 Literal \\n stays literal.\n\n• 🚀 Final bullet.\n'
with tempfile.TemporaryDirectory(prefix='thor-shizu-roundtrip-') as directory:
    fixture = pathlib.Path(directory)
    script = fixture / '.github/scripts/sync-shizu-changelog.sh'
    script.parent.mkdir(parents=True)
    shutil.copy2(root / '.github/scripts/sync-shizu-changelog.sh', script)
    notes = fixture / 'release-notes' / f'v{version}' / 'playstore.txt'
    notes.parent.mkdir(parents=True)
    notes.write_bytes(current_notes.read_bytes() if current_notes.exists() else escaped.encode('utf-8'))
    manifest = fixture / 'shizu_store.json'
    manifest.write_text(json.dumps({'changelog': 'old notes', 'name': 'fixture'}), encoding='utf-8')
    (fixture / 'gradle.properties').write_text(f'versionCode={code}\n', encoding='utf-8')

    def git(*args):
        subprocess.run(['git', '-C', directory, *args], check=True, capture_output=True, text=True)

    git('init', '-q', '-b', 'fixture')
    git('add', 'gradle.properties')
    git('-c', 'user.name=Changelog Test', '-c', 'user.email=test@example.invalid',
        '-c', 'commit.gpgsign=false', 'commit', '-qm', 'fixture version')
    git('update-ref', 'refs/remotes/origin/production', 'HEAD')
    env = dict(os.environ)
    env.pop('SHIZU_VERSION_REF', None)

    def sync(expected=0, override=None):
        result = subprocess.run(['bash', str(script)], env=env if override is None else {**env, 'SHIZU_VERSION_REF': override},
                                capture_output=True, text=True)
        assert result.returncode == expected, result.stdout + result.stderr

    sync()
    result = json.loads(manifest.read_text(encoding='utf-8'))
    assert result['changelog'] == notes.read_text(encoding='utf-8').rstrip('\n'), 'Current notes lost content in JSON'
    assert result['name'] == 'fixture', 'Sync changed unrelated manifest fields'
    print(f'  ok: current v{version} notes round-trip through Shizu JSON without truncation')

    original = manifest.read_bytes()
    sync()
    assert manifest.read_bytes() == original, 'Sync is not idempotent'
    print('  ok: repeated sync leaves the manifest identical')

    notes.write_text(escaped, encoding='utf-8')
    sync()
    assert json.loads(manifest.read_text(encoding='utf-8'))['changelog'] == escaped.rstrip('\n')
    print('  ok: all bullets, blank lines, quotes, emoji, and literal backslashes survive')

    original = manifest.read_bytes()
    notes.write_text(' \n\n', encoding='utf-8')
    sync(expected=1)
    assert manifest.read_bytes() == original, 'Empty notes changed the manifest'
    print('  ok: empty notes fail without changing the manifest')

    notes.write_text(escaped, encoding='utf-8')
    sync(expected=1, override='missing-production-ref')
    assert manifest.read_bytes() == original, 'Invalid version ref changed the manifest'
    print('  ok: invalid explicit version ref fails without changing the manifest')
    print('  6 assertion(s)')
PY
