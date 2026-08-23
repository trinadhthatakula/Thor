#!/usr/bin/env bash
# Every softprops/action-gh-release step must pin target_commitish, or the
# tag is created on the repository default branch instead of the commit that
# was built - which silently breaks IzzyOnDroid reproducibility, because the
# APK embeds its build commit sha in META-INF/version-control-info.textproto.
#
# Present-and-non-empty is not enough: target_commitish takes a branch name as
# happily as a sha, and `target_commitish: master` is present, non-empty, and
# exactly the bug this test exists to catch - on the master rung it would even
# LOOK right. github.sha is the checked-out commit, so it is the only value
# that is correct by construction on all three rungs. If a future rung needs a
# different pin, it is an input carrying a sha - widen this test deliberately
# rather than dropping the equality.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
assertions=0

python3 - "$repo_root" <<'PY'
import sys, pathlib, yaml

root = pathlib.Path(sys.argv[1])
EXPECTED = "${{ github.sha }}"
bad = []
checked = 0

for wf in sorted((root / ".github" / "workflows").glob("*.yml")):
    doc = yaml.safe_load(wf.read_text())
    if not isinstance(doc, dict):
        continue
    for job_name, job in (doc.get("jobs") or {}).items():
        if not isinstance(job, dict):
            continue
        for step in (job.get("steps") or []):
            uses = str(step.get("uses", ""))
            if not uses.startswith("softprops/action-gh-release@"):
                continue
            checked += 1
            with_ = step.get("with") or {}
            where = f"{wf.name}:{job_name}:{step.get('name', '<unnamed>')}"
            if "target_commitish" not in with_:
                bad.append(f"{where} — no target_commitish")
            elif str(with_["target_commitish"]).strip() != EXPECTED:
                bad.append(f"{where} — target_commitish is {with_['target_commitish']!r}, want {EXPECTED!r}")

if checked == 0:
    sys.exit("  no softprops/action-gh-release steps found - the test is vacuous")
if bad:
    sys.exit("  target_commitish must be the built commit:\n    " + "\n    ".join(bad))
print(f"  ok: all {checked} release step(s) pin target_commitish to {EXPECTED}")
PY

assertions=$((assertions + 1))
echo "  ${assertions} assertion(s)"
