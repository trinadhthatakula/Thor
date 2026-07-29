# Follow-up: two branches publish to Play, and a version code can only be used once

**Status:** OPEN — surfaced while fixing follow-up #5, deliberately **not** fixed there. #5's brief
was "stop master going red on commits that were never releases", and it does exactly that. This is
the part that survives, and it needs an owner decision about release flow, not a workflow edit.
**Severity:** Minor today, Major the next time a `chore(release)` lands. It cannot corrupt anything —
Play's rejection is the safety net working — but it makes one of two green-looking pipelines fail for
a reason that has nothing to do with the code.
**Effort:** small once the flow is chosen; the choice is the work.
**Raised by:** the CI half of the `chore/tier0-batch-1` batch (2026-07-30).

Files: `.github/workflows/dev-check.yml`, `.github/workflows/production-deploy.yml`,
`fastlane/Fastfile:116 (lane :distribute_dev)`, `fastlane/Fastfile:121 (lane :distribute_production)`

## Problem

Google Play version codes are unique **per app**, not per track. The invariant is about *uploads*,
not tracks: a given version code may be **uploaded** exactly once, ever. It may afterwards appear on
any number of tracks, because promoting an already-uploaded artifact is not a second upload — which
is the whole basis of option 1 below.

Thor uploads from two places:

| Workflow | Branch | Lane | Track |
|---|---|---|---|
| `dev-check.yml` | `master` | `distribute_dev` | `internal` |
| `production-deploy.yml` | `production` | `distribute_production` | `alpha` |

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
   to `internal`; production promotes that same code from `internal` to `alpha` rather than uploading
   a second time (`upload_to_play_store(track_promote_to:)`, or `supply --track internal
   --track_promote_to alpha` with no AAB). One artifact, one code, two tracks, and the tracks then
   genuinely mean "this build has been through internal testing" rather than "this build was built
   twice". Costs a Fastfile lane change.

2. **One publisher, one branch.** Pick master or production as the only lane that talks to Play and
   make the other build-and-verify only. Simplest to implement, and it was this batch's first draft —
   the owner rejected it, on the grounds that master publishing to `internal` is a wanted behaviour,
   not an accident.

3. **Two codes per release.** Bump again between master and production. Honest and trivially correct,
   but it doubles the version-code churn and means the artifact users get on `alpha` is not
   bit-identical to the one that passed `internal` — which defeats the point of having an internal
   track.

Option 1 is the only one that keeps both tracks *and* keeps the artifact identical across them.

## Acceptance

- A single `chore(release)` merged into `dev` and then into both `master` and `production` results in
  zero `Version code NNNN has already been used` failures.
- The artifact on `alpha` is the same build that went to `internal`, or the docs say plainly why it
  is not.
- Whatever is chosen is written down in the workflow comments, which currently describe the shape of
  the problem but not the intended flow.
