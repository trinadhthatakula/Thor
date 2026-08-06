#!/usr/bin/env bash
# The Telegram caption is assembled twice in release-rung.yml: once for real in
# "Send APK to Telegram", and once in "Check release-notes budget" to measure
# how big the scaffolding is before anything publishes. Two copies of one format
# string is a drift risk, and the drift is silent - a reworded header that
# changed only the send site would leave the gate measuring a caption nobody
# sends, which is exactly the failure the gate exists to catch.
#
# So this pins them together, the same way lockstep.test.ts pins the version
# resolver across the shizu scripts:
#
#   1. the two printf format strings are byte-identical,
#   2. the placeholder Track label the measurement uses is at least as long as
#      every label the send site's case statement can emit - fastlane has not
#      written track.txt yet at measure time, and only an OVER-estimate is safe,
#   3. the per-rung table in the input's comment still matches a fresh
#      measurement, so a reworded title or status line cannot leave the
#      documented numbers behind, and
#   4. the measurement is compared against the declared floor with `-gt`, i.e.
#      the larger wins.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
assertions=0

python3 - "$repo_root" <<'PY'
import sys, pathlib, re, subprocess, yaml

root = pathlib.Path(sys.argv[1])
wf_dir = root / ".github" / "workflows"
rung_path = wf_dir / "release-rung.yml"
rung_text = rung_path.read_text()
rung = yaml.safe_load(rung_text)
steps = rung["jobs"]["rung"]["steps"]
by_name = {s.get("name"): s for s in steps if s.get("name")}

bad = []
checked = 0


def check(cond, message):
    global checked
    checked += 1
    if not cond:
        bad.append(message)


def units(text):
    return len(text.encode("utf-16-le")) // 2


FMT_RE = re.compile(r'printf "(\U0001F680[^"]*)"')

for name in ("Check release-notes budget", "Send APK to Telegram"):
    check(name in by_name, f"release-rung.yml has no step named {name!r}")
if bad:
    sys.exit("  release-rung.yml step names moved:\n    " + "\n    ".join(bad))

gate_body = by_name["Check release-notes budget"]["run"]
send_body = by_name["Send APK to Telegram"]["run"]

gate_fmts = FMT_RE.findall(gate_body)
send_fmts = FMT_RE.findall(send_body)
check(len(gate_fmts) == 1, f"expected 1 caption format string in the gate step, found {len(gate_fmts)}")
check(len(send_fmts) == 1, f"expected 1 caption format string in the send step, found {len(send_fmts)}")
if bad:
    sys.exit("  caption format strings not found:\n    " + "\n    ".join(bad))

# 1 - byte-identical.
check(gate_fmts[0] == send_fmts[0],
      f"the gate measures a different caption than the send builds:\n      gate: {gate_fmts[0]!r}\n      send: {send_fmts[0]!r}")

fmt = send_fmts[0]

# 2 - the placeholder Track label must not be shorter than any real one.
labels = re.findall(r'TRACK_LABEL="([^"$]+)"', send_body)
check(len(labels) >= 4, f"expected the track-label case statement, found labels {labels}")
placeholder = re.search(r'"\$GITHUB_ACTOR" "([^"]*)" "\$CAPTION_STATUS"', gate_body)
check(placeholder is not None, "the gate's measurement does not substitute a literal Track label")
if placeholder and labels:
    longest = max(labels, key=units)
    check(units(placeholder.group(1)) >= units(longest),
          f"the gate measures Track {placeholder.group(1)!r} ({units(placeholder.group(1))} units) "
          f"but the send can emit {longest!r} ({units(longest)}) - that under-estimates the caption")

# 4 - larger of the two wins.
check(re.search(r'\[ "\$MEASURED" -gt "\$WRAPPER" \]', gate_body) is not None,
      "the measurement is not compared with -gt against the declared floor, so the larger may not win")


def header(title, ref, actor, track, status):
    # Assembled through a real shell, with the workflow's own format string.
    out = subprocess.run(["bash", "-c", 'printf "$1" "$2" "$3" "$4" "$5" "$6"',
                          "_", fmt, title, ref, actor, track, status],
                         capture_output=True, check=True)
    # + 2 for the blank line that joins the header to telegram.md.
    return units(out.stdout.decode("utf-8")) + 2


# 3 - the documented table still holds. Track labels come from the rung's own
# destination: dev uploads to alpha, and the promote lanes name their target.
RUNGS = {
    "dev": ("1-dev-publish.yml", "dev", "Closed Testing"),
    "beta": ("2-master-promote.yml", "master", "Open Testing"),
    "production": ("3-production-promote.yml", "production", "Production"),
}
documented = dict(
    (m.group(1), (int(m.group(2)), int(m.group(3))))
    for m in re.finditer(r"^      #     (dev|beta|production)\s+(\d+)\s+(\d+)$", rung_text, re.M)
)
check(set(documented) == set(RUNGS), f"the per-rung wrapper table in release-rung.yml is {sorted(documented)}")

for name, (fname, ref, track) in RUNGS.items():
    if name not in documented:
        continue
    doc = yaml.safe_load((wf_dir / fname).read_text())
    job = next(iter((doc.get("jobs") or {}).values()))
    with_ = job.get("with") or {}
    got = (header(with_["title_prefix"], ref, "trinadhthatakula", track, with_["caption_status"]),
           header(with_["title_prefix"], ref, "github-actions[bot]", track, with_["caption_status"]))
    check(got == documented[name],
          f"{name} rung wrapper measures {got}, the comment in release-rung.yml says {documented[name]}")

if checked == 0:
    sys.exit("  nothing was checked - the test is vacuous")
if bad:
    sys.exit("  caption wrapper:\n    " + "\n    ".join(bad))
print(f"  ok: {checked} caption-wrapper assertion(s)")
PY

assertions=$((assertions + 1))
echo "  ${assertions} assertion(s)"
