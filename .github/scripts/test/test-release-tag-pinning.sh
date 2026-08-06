#!/usr/bin/env bash
# Every softprops/action-gh-release step must pin target_commitish, or the
# tag is created on the repository default branch instead of the commit that
# was built - which silently breaks IzzyOnDroid reproducibility, because the
# APK embeds its build commit sha in META-INF/version-control-info.textproto.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
assertions=0

python3 - "$repo_root" <<'PY'
import sys, pathlib, yaml

root = pathlib.Path(sys.argv[1])
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
            if "target_commitish" not in with_:
                bad.append(f"{wf.name}:{job_name}:{step.get('name', '<unnamed>')}")

if checked == 0:
    sys.exit("  no softprops/action-gh-release steps found - the test is vacuous")
if bad:
    sys.exit("  missing target_commitish:\n    " + "\n    ".join(bad))
print(f"  ok: all {checked} release step(s) pin target_commitish")
PY

assertions=$((assertions + 1))
echo "  ${assertions} assertion(s)"
