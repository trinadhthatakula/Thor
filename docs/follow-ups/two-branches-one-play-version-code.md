# Follow-up: two branches publish to Play, and a version code can only be used once

**Status:** RESOLVED 2026-08-05 — the owner chose a flow the options below did not list, and it is
implemented in `dev-check.yml`. See [Resolution](#resolution). Kept rather than deleted because the
reasoning is the only written record of *why* Thor routes releases by the last digit of the version
code, and both workflows now point here.
**Severity:** was Minor day to day, Major the next time a `chore(release)` landed. It could not
corrupt anything — Play's rejection was the safety net working — but it made one of two
green-looking pipelines fail for a reason that had nothing to do with the code.
**Effort:** small once the flow was chosen; the choice was the work.
**Raised by:** the CI half of the `chore/tier0-batch-1` batch (2026-07-30).

Files: `.github/workflows/dev-check.yml`, `.github/workflows/production-deploy.yml`,
`fastlane/Fastfile (lane :distribute_dev)`, `fastlane/Fastfile (lane :distribute_production)`

## Problem

Google Play version codes are unique **per app**, not per track. The invariant is about *uploads*,
not tracks: a given version code may be **uploaded** exactly once, ever. It may afterwards appear on
any number of tracks, because promoting an already-uploaded artifact is not a second upload — which
is the whole basis of option 1 below.

Thor uploads from two places:

| Workflow | Branch | Lane | Track |
|---|---|---|---|
| `dev-check.yml` | `master` | `distribute_dev` | `alpha` (closed testing) |
| `production-deploy.yml` | `production` | `distribute_production` | `beta` (open testing) |

(Both tracks moved up one rung in PR #320 — `internal`→`alpha` and `alpha`→`beta`. Neither lane
has ever written `production`: promoting a build to the production track is a manual Play Console
action, and that manual step is the release gate. The collision below is unaffected by the move,
because Play's uniqueness is per app, not per track.)

Both branches are fed from `dev` by merge — the 2026-07-22 run history shows the *same* commit
(`Merge pull request #274 from trinadhthatakula/dev`) triggering both workflows. So a `chore(release)`
commit carrying `versionCode=NNNN` reaches master and production with the identical code, both lanes
try to upload it, and whichever loses the race dies on `Version code NNNN has already been used`.

This is not hypothetical and it is not new. It is the second, harder half of the failure #5 was
filed against; the first half — non-release commits attempting to publish at all — is fixed.

### Why #5's fix doesn't cover it

`dev-check.yml` now publishes only when `versionCode` actually changed against `HEAD^`. That removes
every *ordinary* master push from the collision, which is the great majority of them, and is why
master stops being permanently red. But a `chore(release)` commit is precisely the case where the
code *did* change — so it still publishes, and it still collides with production.

The version-code guard added to `production-deploy.yml` doesn't cover it either, and cannot: it
compares production against production's own previous tip. It has no way to know master already
claimed the code an hour ago.

## Options

Not a recommendation — this is the owner's call about how Thor releases.

1. **Promote instead of re-uploading.** The correct Play-native answer. master uploads the artifact
   to `alpha`; production promotes that same code from `alpha` to `beta` rather than uploading
   a second time (`upload_to_play_store(track_promote_to:)`, or `supply --track alpha
   --track_promote_to beta` with no AAB). One artifact, one code, two tracks, and the tracks then
   genuinely mean "this build has been through closed testing" rather than "this build was built
   twice". Costs a Fastfile lane change.

2. **One publisher, one branch.** Pick master or production as the only lane that talks to Play and
   make the other build-and-verify only. Simplest to implement, and it was this batch's first draft —
   the owner rejected it, on the grounds that master publishing to a Play test track is a wanted
   behaviour, not an accident (as of 2026-08 that track is `alpha`, not `internal`).

3. **Two codes per release.** Bump again between master and production. Honest and trivially correct,
   but it doubles the version-code churn and means the artifact users get on `beta` is not
   bit-identical to the one that passed `alpha` — which defeats the point of having a closed
   track.

Option 1 is the only one that keeps both tracks *and* keeps the artifact identical across them.

## Resolution

**Chosen: option 4, which none of the three above described — route by the last digit of the version
code.** Implemented 2026-08-05 in `.github/workflows/dev-check.yml`, in the `Detect release` step.

`versionName` is derived as `code/1000 . code%1000/10 . code%10`, so the **last digit is the patch
number**: a code ending in `0` is an `x.y.0`, a stable. `dev-check.yml` now sets `release=false` for
those, builds them for verification, and publishes nothing. Every version code therefore has exactly
one uploader:

| version code | example | publisher | track | GitHub release |
|---|---|---|---|---|
| ends in 0 | `1940` → `1.94.0` | `production-deploy.yml` (branch `production`) | `beta` | full release |
| anything else | `1933` → `1.93.3` | `dev-check.yml` (branch `master`) | `alpha` | pre-release |

Why this rather than option 1 or 2:

* It keeps what the owner **rejected option 2 to protect** — master really does still publish to a
  Play test track — while getting option 2's simplicity. Only the *stables* are withheld.
* It needs no Fastfile change, so `distribute_dev` and `distribute_production` keep uploading real
  artifacts and neither lane grows a promote-vs-upload branch.
* The trade against option 1 is stated plainly rather than hidden: the artifact on `beta` is **not**
  the bytes that went through `alpha`, because a stable never goes to `alpha` at all. What `alpha`
  tested is the last dev build before it. That is a weaker claim than option 1's, and it is the
  price of not touching the lanes. Option 1 remains available later, and its acceptance criterion
  is the one that would supersede this table.

Two things this does **not** do, both deliberate:

* **`workflow_dispatch` still overrides it.** The dispatch arm returns `release=true` before the
  digit check, because a manual dispatch is an explicit request to publish. Using it on a stable
  consumes the code production is about to ask for, so the escape hatch for a *stable* is
  `production-deploy.yml`'s own `workflow_dispatch`, not master's.
* **Nothing enforces the rule on the production side.** `production-deploy.yml` will happily upload
  a non-stable code, so merging a mid-cycle build into `production` re-opens the collision from the
  other direction. The convention that production receives stables is what holds there, not a check.
  Add a guard if that convention is ever broken by accident.

## Acceptance

- [x] A single `chore(release)` merged into `dev` and then into both `master` and `production`
      results in zero `Version code NNNN has already been used` failures — the two branches can no
      longer both attempt the same code, since the code's last digit decides which one publishes it.
- [x] The artifact on `beta` is the same build that went to `alpha`, **or the docs say plainly why
      it is not** — it is not, and the Resolution above says so.
- [x] Whatever is chosen is written down in the workflow comments — `dev-check.yml`'s `Detect
      release` step and `production-deploy.yml`'s two Play-upload comments now describe the intended
      flow, not just the shape of the problem.

First release to exercise it: **v1.94.0 (`1940`)**, which is why the change landed before that merge
rather than after it.
