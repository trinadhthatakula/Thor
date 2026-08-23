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
#      documented numbers behind,
#   4. the measurement is compared against the declared floor with `-gt`, i.e.
#      the larger wins, and
#   5. the long-ref figure the comment cites is real, and is above every entry
#      in the per-rung table. That table is the ONLY thing anyone reads to size
#      a telegram.md by hand, and read alone it looks like a ceiling. It is not:
#      the wrapper contains the ref name, the table's refs are the ladder's own
#      short ones, and the whole reason the gate measures per run is that a
#      dispatch from a long branch costs more. Pinning the cited number keeps
#      that counter-example honest rather than leaving it as prose, and
#   6. check-notes-budget.sh's copy of that table agrees, and its hand-run
#      default is at least as large as the biggest wrapper the ladder can pass
#      it. 1-5 pinned only release-rung.yml, so the second copy drifted the
#      first time the numbers moved; and a default below what CI passes turns
#      the hand-run pre-flight into a false green.
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
# destination: dev uploads to alpha and mirrors onto internal, so it names both,
# and the promote lanes name their single target.
RUNGS = {
    "dev": ("1-dev-publish.yml", "dev", "Closed + Internal Testing"),
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

# 5 - the long-ref counter-example the same comment cites. Parsed rather than
# hardcoded so the prose and the assertion cannot drift apart: reword the
# sentence and this stops finding it, which is a failure, not a silent pass.
prose = "\n".join(re.sub(r"^\s*#\s?", "", ln) for ln in rung_text.splitlines() if re.match(r"^\s*#", ln))
prose = re.sub(r"\s+", " ", prose)
cited = re.search(
    r"workflow_dispatch of the (\w+) rung from (\S+) assembles a real wrapper of (\d+)", prose)
check(cited is not None,
      "release-rung.yml no longer cites a long-ref wrapper measurement - assertion 5 has nothing to pin")
if cited:
    rung_name, long_ref, cited_units = cited.group(1), cited.group(2), int(cited.group(3))
    check(rung_name in RUNGS, f"the cited long-ref example names rung {rung_name!r}, which is not in {sorted(RUNGS)}")
    if rung_name in RUNGS:
        fname, _, track = RUNGS[rung_name]
        doc = yaml.safe_load((wf_dir / fname).read_text())
        job = next(iter((doc.get("jobs") or {}).values()))
        with_ = job.get("with") or {}
        # Either actor - the point is that the cited number is one the workflow
        # can really produce, not which of the two produced it.
        got = tuple(header(with_["title_prefix"], long_ref, actor, track, with_["caption_status"])
                    for actor in ("trinadhthatakula", "github-actions[bot]"))
        check(cited_units in got,
              f"the comment says ref {long_ref!r} assembles {cited_units} units, a fresh measurement says {got}")
        # The counter-example only counter-exemplifies if it is bigger.
        ceiling = max(max(pair) for pair in documented.values())
        check(cited_units > ceiling,
              f"the cited long-ref figure {cited_units} is not above the per-rung table's maximum {ceiling} - "
              "the table would then read as a ceiling, which is the misreading this pins against")

# 6 - check-notes-budget.sh holds a SECOND copy of that table, and the default
# the table justifies. Nothing pinned it until now, and it drifted at the first
# opportunity: when the track label grew to name two tracks, release-rung.yml's
# copy was updated and this one silently kept the old numbers. The default is
# worse than a stale comment - it is the figure a HAND run actually uses, so if
# it sits below what the ladder passes, a hand run green-lights a caption the
# release then refuses, which is the discovery this whole pre-flight exists to
# move earlier.
budget_text = (root / ".github" / "scripts" / "check-notes-budget.sh").read_text()


def rung_inputs(fname):
    doc = yaml.safe_load((wf_dir / fname).read_text())
    job = next(iter((doc.get("jobs") or {}).values()))
    return job.get("with") or {}


budget_doc = dict(
    (m.group(1), (int(m.group(2)), int(m.group(3))))
    for m in re.finditer(
        r"^#\s+(dev|beta|production) rung:\s+(\d+) \(own actor\) / (\d+) \(bot actor\)$",
        budget_text, re.M)
)
check(budget_doc == documented,
      f"check-notes-budget.sh documents {budget_doc} but release-rung.yml documents {documented} - "
      "the two copies of the per-rung table have drifted")

default_m = re.search(r'^wrapper_units="\$\{2:-(\d+)\}"$', budget_text, re.M)
check(default_m is not None, "check-notes-budget.sh has no parseable wrapper-units default to pin")
if default_m and placeholder:
    default_units = int(default_m.group(1))
    # Measured with the PLACEHOLDER label, not each rung's real one: what the
    # gate passes is what a hand run has to match, and the gate prices every
    # rung at the longest label because track.txt does not exist yet.
    gate_max = max(
        header(rung_inputs(fname)["title_prefix"], ref, actor,
               placeholder.group(1), rung_inputs(fname)["caption_status"])
        for fname, ref, _ in RUNGS.values()
        for actor in ("trinadhthatakula", "github-actions[bot]")
    )
    check(default_units >= gate_max,
          f"check-notes-budget.sh defaults to {default_units} units, but the ladder passes it up to "
          f"{gate_max} - a hand run would be looser than the release that follows it")

# The long-ref counter-example is cited in that header too, a third copy.
budget_long = re.search(r"long branch name measures (\d+)", budget_text)
check(budget_long is not None,
      "check-notes-budget.sh no longer cites a long-ref measurement - a third copy of it moved")
if budget_long and cited:
    check(int(budget_long.group(1)) == int(cited.group(3)),
          f"check-notes-budget.sh cites {budget_long.group(1)} units for a long ref, "
          f"release-rung.yml cites {cited.group(3)}")

if checked == 0:
    sys.exit("  nothing was checked - the test is vacuous")
if bad:
    sys.exit("  caption wrapper:\n    " + "\n    ".join(bad))
print(f"  ok: {checked} caption-wrapper assertion(s)")
PY

assertions=$((assertions + 1))
echo "  ${assertions} assertion(s)"
