#!/usr/bin/env bash
# check-notes-budget.sh can only enforce what the workflow asks it to enforce,
# and the script's own tests cannot see the call site. This pins the wiring:
#
#   1. the pre-flight step passes --require for every file the rung consumes,
#   2. it does so only when require_notes is true, and only asks for
#      telegram.md on a rung that broadcasts,
#   3. the commit-log fallback and the generic-bullet caption are both
#      unreachable when require_notes is true, and
#   4. production, the rung whose comment promises "notes a human wrote",
#      actually sets require_notes: true.
#
# Ordering matters in (3): a guard that sits AFTER the fallback it is meant to
# prevent is not a guard, so the assertion is on index order, not presence.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
assertions=0

python3 - "$repo_root" <<'PY'
import sys, pathlib, yaml

root = pathlib.Path(sys.argv[1])
wf_dir = root / ".github" / "workflows"
bad = []
checked = 0


def check(cond, message):
    global checked
    checked += 1
    if not cond:
        bad.append(message)


rung = yaml.safe_load((wf_dir / "release-rung.yml").read_text())
steps = rung["jobs"]["rung"]["steps"]
by_name = {s.get("name"): s for s in steps if s.get("name")}

for name in ("Check release-notes budget", "Prepare Release Notes", "Send APK to Telegram"):
    check(name in by_name, f"release-rung.yml has no step named {name!r}")
if bad:
    sys.exit("  release-rung.yml step names moved:\n    " + "\n    ".join(bad))

# 1 + 2 - the pre-flight gate names the consumed files.
budget = by_name["Check release-notes budget"]
body = budget["run"]
for token in ("--require github.md", "--require playstore.txt", "--require telegram.md"):
    check(token in body, f"pre-flight gate never passes {token!r}")
check('"$REQUIRE" = "true"' in body, "pre-flight gate does not branch on require_notes")
check('"$TELEGRAM" = "true"' in body, "pre-flight gate requires telegram.md unconditionally")
check(body.find('"$REQUIRE" = "true"') < body.find("--require github.md"),
      "the --require list is not built under the require_notes branch")
check(body.find('"$TELEGRAM" = "true"') < body.find("--require telegram.md"),
      "telegram.md is required ahead of the inputs.telegram branch, i.e. unconditionally")
env = budget.get("env") or {}
check(env.get("REQUIRE") == "${{ inputs.require_notes }}", "gate step does not read inputs.require_notes")
check(env.get("TELEGRAM") == "${{ inputs.telegram }}", "gate step does not read inputs.telegram")
check("check-notes-budget.sh" in body, "pre-flight gate no longer calls check-notes-budget.sh")

# 3 - the fallbacks are unreachable when notes are required.
prep = by_name["Prepare Release Notes"]
prep_body = prep["run"]
g = prep_body.find('"$REQUIRE" = "true"')
f = prep_body.find("github_release_notes_fallback.md")
check(g != -1, "no require_notes guard on the commit-log fallback")
check(f != -1, "the commit-log fallback vanished - this test is now checking nothing")
check(g < f, "the require_notes guard sits after the commit-log fallback it must prevent")
check((prep.get("env") or {}).get("REQUIRE") == "${{ inputs.require_notes }}",
      "Prepare Release Notes does not read inputs.require_notes")

tg = by_name["Send APK to Telegram"]
tg_body = tg["run"]
g = tg_body.find('"$REQUIRE" = "true"')
f = tg_body.find("Bug fixes and performance improvements")
check(g != -1, "no require_notes guard on the generic-bullet caption")
check(f != -1, "the generic-bullet caption vanished - this test is now checking nothing")
check(g < f, "the require_notes guard sits after the generic bullet it must prevent")
check((tg.get("env") or {}).get("REQUIRE") == "${{ inputs.require_notes }}",
      "Send APK to Telegram does not read inputs.require_notes")

# 4 - the rung that promises curated notes turns the gate on, and the two that
# promise a fallback leave it off.
expected = {
    "1-dev-publish.yml": False,
    "2-master-promote.yml": False,
    "3-production-promote.yml": True,
}
for fname, want in expected.items():
    doc = yaml.safe_load((wf_dir / fname).read_text())
    jobs = doc.get("jobs") or {}
    got = [(j.get("with") or {}).get("require_notes") for j in jobs.values() if isinstance(j, dict)]
    check(got == [want], f"{fname}: require_notes is {got}, expected [{want}]")

if checked == 0:
    sys.exit("  nothing was checked - the test is vacuous")
if bad:
    sys.exit("  require_notes wiring broken:\n    " + "\n    ".join(bad))
print(f"  ok: {checked} wiring assertion(s)")
PY

assertions=$((assertions + 1))
echo "  ${assertions} assertion(s)"
