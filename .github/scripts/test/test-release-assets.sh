#!/usr/bin/env bash
# The public release pages must offer only installable artifacts. An .aab on
# a page users browse gets downloaded and filed as a bug, and every store
# client (Obtainium apkFilterRegEx, IzzyOnDroid ApkMatch) has to filter past
# it.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
assertions=0

python3 - "$repo_root" <<'PY'
import sys, pathlib, yaml

root = pathlib.Path(sys.argv[1])
checked = 0
bad = []

for wf in sorted((root / ".github" / "workflows").glob("*.yml")):
    doc = yaml.safe_load(wf.read_text())
    if not isinstance(doc, dict):
        continue
    for job_name, job in (doc.get("jobs") or {}).items():
        if not isinstance(job, dict):
            continue
        for step in (job.get("steps") or []):
            if not str(step.get("uses", "")).startswith("softprops/action-gh-release@"):
                continue
            files = str((step.get("with") or {}).get("files", ""))
            checked += 1
            for line in files.splitlines():
                line = line.strip()
                # endswith(".aab") already covers every glob form the action
                # accepts - "*.aab" and "**/*.aab" both end in ".aab" - which is
                # why the "*.aab" disjunct that used to sit here could never be
                # the deciding one. A path that does not name the extension at
                # all (a bare "outputs/**") would slip past this; that is the
                # known limit, and no release step uses one.
                if line.endswith(".aab"):
                    bad.append(f"{wf.name}:{job_name}: {line}")

if checked == 0:
    sys.exit("  no softprops/action-gh-release steps found - the test is vacuous")
if bad:
    sys.exit("  .aab published as a release asset:\n    " + "\n    ".join(bad))
print(f"  ok: {checked} release step(s) publish no .aab")
PY

assertions=$((assertions + 1))
echo "  ${assertions} assertion(s)"
