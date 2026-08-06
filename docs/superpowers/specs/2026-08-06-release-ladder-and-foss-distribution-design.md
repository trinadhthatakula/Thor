# Release ladder and FOSS distribution — design

**Date:** 2026-08-06
**Status:** design approved, not yet implemented
**Scope:** CI/CD branch topology, Play track promotion, tagging and reproducibility, release notes,
and the FOSS store fronts (Obtainium, IzzyOnDroid, Shizu Store, F-Droid, OpenAPK).

---

## 1. Problem

Two branches publish, both fed from `dev`, and both want the same Play version code. Play allows a
version code to be **uploaded once per app**, not once per track, so whichever lane ran first killed
the other.

The mitigation shipped 2026-08-05 (PR #359) routes on the **last digit** of `versionCode`: a code
ending in `0` is a stable and publishes from `dev` → `production`; anything else publishes from
`dev` → `master`. It works, but it encodes release intent in an arithmetic property of the version
number, and it fails whenever intent and digit disagree — authoring release notes for a stable on
`dev` and merging them to `master` still enters the dev lane. `workflow_dispatch` overrides the gate
entirely, and nothing on the production side rejects a non-stable code.

This design removes the routing question rather than answering it better: **branch identity decides
the rung, and exactly one branch ever uploads to Play.**

---

## 2. Current state (measured 2026-08-06)

| Fact | Evidence |
|---|---|
| `dev-check.yml` triggers on push to **`master`** | `.github/workflows/dev-check.yml:4-5` |
| Digit gate skips codes ending in `0` | `dev-check.yml:147-155`, `case "$NEW" in *0)` |
| `workflow_dispatch` arm returns `release=true` **above** the digit check | `dev-check.yml:113-117` |
| `production-deploy.yml` triggers on push to **`production`** | `production-deploy.yml:4-5` |
| `dev` publishes nothing | no workflow triggers on it |
| Fastlane rejects the `production` track by design | `fastlane/Fastfile:16` |
| `distribute_dev` → `alpha`; `distribute_production` → `beta` | `Fastfile:143`, `Fastfile:149` |
| Neither release step sets `target_commitish` | `production-deploy.yml:188-201`, `dev-check.yml:288-302` |
| Repository default branch is `master` | `gh repo view` |
| `production` has **never** carried a commit of its own | `git log origin/production --not origin/master` is empty |
| Every stable tag points at a `Merge pull request #N from …/dev` commit | `v1.94.0 v1.93.0 v1.92.0 v1.82.3 v1.82.2 v1.81.8` |
| Release APKs embed the build commit sha | `META-INF/version-control-info.textproto` in published v1.94.0 `foss-release.apk` = `f9e823c177a17361a5dcda28d3157cede558075d` = `git rev-parse v1.94.0^{commit}` |
| `foss` flavour appends `-foss` to versionName | `app/build.gradle.kts:197` |
| `hi-IN` metadata has **no** `changelogs/` directory | `fastlane/metadata/android/hi-IN/` holds only `full_description.txt`, `short_description.txt`, `title.txt` |
| Toolchain | `agp = 9.4.0-alpha07`, `gradle = 9.6.1`, `kotlin = 2.4.10`, compile/target SDK 37, minSdk 28 |

The tag currently lands on the right commit **by accident**: `production` is always a fast-forward
of `master`, so the default branch and the built commit happen to coincide. That coincidence is
load-bearing and undocumented.

---

## 3. Part A — the three-rung ladder

### Topology

| Branch | Trigger | Builds | Play | GitHub | Telegram |
|---|---|---|---|---|---|
| `dev` | push **where `versionCode` changed** | yes | **upload** AAB → `alpha` | pre-release `v<name>-dev-<run>` | testers channel |
| `master` | push | yes | **promote** `alpha` → `beta` | pre-release `v<name>-beta-<run>` | — |
| `production` | push | yes | **promote** `beta` → `production` | release `v<name>` (Latest) | release post |

`dev` receives every feature-branch merge, so its rung is conditional: a push that leaves
`versionCode` untouched runs `pr-ci.yml` and nothing else — no build, no upload, no tag. Publishing a
candidate is therefore an explicit act, the `chore(release)` commit that bumps the code, exactly as it
is today. `master` and `production` receive commits only from the rung below, so their triggers are
unconditional.

### Invariants

1. **Exactly one branch uploads to Play.** `dev` uploads; `master` and `production` promote. A
   version code is therefore offered to Play's uploader exactly once, in one branch, in one workflow.
   The duplicate-code error becomes structurally unreachable rather than handled.
2. **A rung may only promote a version code already present in the track below it.** Skipping a rung
   produces a red build, not a wrong promotion.
3. **`gradle.properties` at the merged commit is the source of truth for the version code.** The Play
   API is queried only to *assert* that invariant 2 holds. It is never asked "what is newest?" — see
   §4.
4. **All three rungs build.** Each rung produces its own APKs from its own commit; nothing is carried
   forward as bytes. This is required for reproducibility, not merely tidy — see §5.

### What this deletes

- The `versionCode`-ends-in-`0` gate (`dev-check.yml:147-155`).
- The `workflow_dispatch` override that bypasses it (`dev-check.yml:113-117`) — dispatch remains, but
  as a re-run of the rung the branch already implies.
- The `Merge branch 'master' into dev` back-merge. `master` and `production` receive no commits of
  their own, so `dev` never falls behind.
- The unmoved-version-code refusal in `production-deploy.yml:70-101` in its current form; the check
  becomes "is this code in `beta`?" rather than "did the number change?".

### Mid-cycle bug fixes

A fix lands on `dev` with a **new version code** and re-enters at the bottom rung. It supersedes the
previous candidate on `alpha`, then climbs. There is no hotfix bypass. This was chosen deliberately
over a fast lane: a bypass reintroduces a second Play uploader, which is the entire problem.

### Workflow layout

Three thin caller workflows (`dev-publish.yml`, `master-promote.yml`, `production-promote.yml`)
delegating to one reusable workflow that holds the shared build/sign/notes machinery. The rung
differs only in inputs: Play action (upload vs promote), source track, destination track, tag shape,
prerelease flag, and whether Telegram fires.

---

## 4. Part B — Play and Fastlane mechanics

### Verified behaviour

- Version codes are unique **per app**, not per track.
- Promotion is `edits.tracks.update` (PUT of `Track.releases[].versionCodes`) followed by
  `edits.commit`. The Console calls it "Add from library".
- Promotion does **not** remove the code from the source track. Play's own fallback status
  `Promoted` is defined as the same bundles being active in both.
- Cross-track ordering is never blocked, only reported as a non-blocking validation message
  (`Shadowed` / `Promoted` / `Superseded` / `Partially shadowed`). The only hard error is *within* a
  track: a new APK may not have a lower version code than the one it replaces.
- Serving rule: a user receives the highest version code compatible with their device from a track
  they are eligible for. Production eligibility is universal.

### Required changes to `fastlane/Fastfile`

**1. Split the track guard so the "one uploader" rule is enforced by the code, not by convention.**

`Fastfile:16` currently reads `allowed_tracks = %w[internal alpha beta]`, excluding `production`
because promotion was a manual Console step. Promotion is now automated, so the guard must change —
but it should get *stricter*, not looser:

```ruby
UPLOAD_TRACKS   = %w[internal alpha].freeze      # the only tracks an artifact may be uploaded to
PROMOTE_TARGETS = %w[beta production].freeze     # the only tracks a lane may promote into
```

A lane that uploads may not name `beta` or `production`; a lane that promotes may not upload. The
existing comment at `Fastfile:11-14` explaining why `production` is excluded must be rewritten to
record the new reasoning, not simply deleted.

**2. A promote lane must set `skip_upload_apk: true` **and** `skip_upload_aab: true`.**

`upload_to_play_store` back-fills `params[:aab]` from `SharedValues::GRADLE_AAB_OUTPUT_PATH` whenever
it is nil, and both skip flags default to `false`. Because every rung now builds before promoting,
`lane_context` will be populated — a promote lane copied from `prepare_release_artifacts` will upload
the AAB and die on the duplicate code. This is the actual mechanism behind the error that motivated
this work.

**3. Pin `version_code:` explicitly on every promotion.** Never resolve it from Play.

**4. Carry forward the four skips already set** at `Fastfile:123-127` (`skip_upload_metadata`,
`skip_upload_images`, `skip_upload_screenshots`, `skip_upload_apk`). They are correct today and their
defaults are all `false`; a new lane written without them re-pushes the entire store listing.

### Why the Play API is an assertion, not an oracle

`google_play_track_version_codes` returns codes from draft, halted and in-progress releases
indistinguishably and in no defined order. Worse, `version_code:` selects a *release*, not a code:
`track_to.releases = [release]` promotes **every** code in that release, and a draft release selected
this way is force-flipped to `completed`.

Compounding it: because promotion leaves the code active in the source track, "newest in `alpha`"
never changes after a promote — and after the first full ladder run, "newest in `beta`" is a **dev**
code. An unpinned production promote would push a dev build to production.

So the ladder reads `gradle.properties`, and queries Play only to answer "is code N present in track
X?" — a yes/no assertion that fails the build when false.

### Do not string-match the Play error

`dev-check.yml:135` quotes *"Version code N has already been used. Try another version code."* as
fact. Google publishes no such string; the `edits.tracks` error-code table in the Android Publisher
documentation ships with a header row and zero data rows. The quoted text is attested only by a
user-authored community thread title. Error handling must key on HTTP status and structured response
fields, never on that sentence.

---

## 5. Part C — tagging and reproducibility

### The embedded commit sha

AGP emits `META-INF/version-control-info.textproto` by default in release builds (`VcsInfo`, since
AGP 8.3.1). Thor configures no `vcsInfo` block anywhere, so every release APK carries the sha of the
commit it was built from. IzzyOnDroid's `rbtlog` clones **at the tag**, asserts
`git rev-parse HEAD == APP_COMMIT`, rebuilds, and compares whole-file sha256 with the signature
stripped.

Consequently the tag must point at the commit that was built. If it does not, the rebuild embeds a
different sha and reproducibility fails. IzzyOnDroid has already hand-repaired Thor's recipe twice
for exactly this — `git reset --soft d22684f2c98e781f5cb3ef62817ac918421afc9c` for `Thor_v1702` and
`git reset --soft d2457e434611d5e26a5a047f5234f2b46236ad96` for `Thor_v1706`.

### Fix

Add `target_commitish` to all three release steps:

```yaml
tag_name: v${{ steps.prep_notes.outputs.version_name }}
target_commitish: ${{ github.sha }}
```

The GitHub API's documented default for `target_commitish` is *the repository's default branch* —
`master`. `actions/checkout` on a `push` event checks out `github.sha`, so pinning the field makes
the build, the embedded sha, and the tag the same commit by construction, regardless of whether
`production` is fast-forwarded or merged. This also makes the merge style a free choice rather than a
hidden constraint.

### `vcsInfo` stays on

Considered and rejected: `vcsInfo { include = false }`. The embedded sha is what allowed both Izzy
diagnoses, it is free forensics on any APK a user sends in, and disabling it changes APK contents —
which itself requires an Izzy recipe update. Once the tag is pinned, the sha costs nothing.

### Tag shapes and the prerelease flag

| Rung | Tag | `prerelease` | IzzyOnDroid | Obtainium (default) |
|---|---|---|---|---|
| `dev` | `v<name>-dev-<run>` | `true` | skipped via `GHSkipPre` | skipped |
| `master` | `v<name>-beta-<run>` | `true` | skipped via `GHSkipPre` | skipped |
| `production` | `v<name>` | **`false`** | ingested | offered |

The flag is load-bearing and has already failed once: **`v1.81.9-dev-82` is `prerelease=false` on the
GitHub API and appears in Thor's rbtlog recipe** — a dev build was ingested as a stable. One-off
cleanup task: `gh release edit v1.81.9-dev-82 --prerelease`.

The tag suffix is `github.run_number`, not the version code. This is intentional and stays: re-running
a failed publish on the same commit mints a second release rather than colliding.

### Release assets

`production-deploy.yml:194-197` publishes three assets. The AAB is dropped:

```
foss-release.apk      → IzzyOnDroid, Obtainium, F-Droid, direct download
store-release.apk     → direct download, Play parity
```

The AAB has no consumer — it is not installable, and Play re-signs it with Google's app-signing key,
so it is not the artifact any user receives. Removing it also removes one thing that every store
client's asset filter has to step past.

### Residual

`assets/dexopt/baseline.prof` and `.profm` ship (AGP merges library profiles even with
`app/baselineprofile/` deliberately out of the build) and F-Droid documents them as intermittently
non-deterministic. Empirically fine on Thor today. Recorded so that a rebuild failing on nothing that
changed has a first suspect.

---

## 6. Part D — release notes, changelogs, and the Shizu manifest

### Timing

Notes are **auto-generated at `dev` and curated at `production`.**

| Rung | Play changelog | GitHub body | Telegram |
|---|---|---|---|
| `dev` | auto, or skipped when absent | auto-generated from commits since last tag | testers channel |
| `master` | unchanged — the promotion carries alpha's notes forward | auto-generated | — |
| `production` | curated `release-notes/v<name>/playstore.txt` | curated `github.md` | curated `telegram.md` |

`promote_track` copies the source release object wholesale, so **without an explicit metadata pass,
production would inherit the dev auto-notes verbatim.** The curated swap at the production rung must
be an explicit `skip_upload_changelogs: false` step, not an assumed default.

### Live bug: blank Hindi changelogs

`fastlane/metadata/android/hi-IN/` has no `changelogs/` directory. `supply` enumerates locales from
the metadata directory, so `hi-IN` is included in every changelog upload; with no file to read,
`changelog_text` falls through to `''` and a `LocalizedText` is emitted anyway. **Every release that
uploads changelogs already blanks the Hindi what's-new.**

Fix: create `fastlane/metadata/android/hi-IN/changelogs/` and populate it alongside `en-US`. Until
translations exist, mirror the English text — release notes in English under a Hindi locale are
strictly better than none, and `supply` offers no per-language changelog skip.

### Shizu Store

`shizu_store.json` stays on `master`; moving it would require re-registering the live raw URL with
Shizu Store for no functional gain.

`.github/scripts/check-shizu-manifest.sh:245-262` derives the expected version name from
`gradle.properties` **on the branch under test** and compares `.changelog` against
`release-notes/v<name>/playstore.txt`. Under the ladder, `dev` bumps the code on every candidate, so
the check would go red on every dev push. Rebind it to read `gradle.properties` from the `production`
branch.

This also repairs a pre-existing inconsistency: `shizu_store.json:25` already hardcodes
`releases/latest/download/foss-release.apk`, which resolves to the production release. The manifest's
`download_url` and its `.changelog` currently track different clocks by design; after the rebind they
track the same one.

The checker runs on **every** PR via `pr-ci.yml:148-164`, so a forgotten sync is a red check rather
than a silent regression. The writer `sync-shizu-changelog.sh` is never run by CI, and the
`fastlane/` changelog copy is audited by nothing — both unchanged by this design, both worth knowing.

### Pre-flight size gate

`telegram.md`'s assembled caption is capped at 1024 UTF-16 units and Telegram **rejects** an oversized
one without truncating; the `curl` has no `--fail` and its output is discarded, so the step goes green
having posted nothing. `playstore.txt` is capped at 500 characters and CI checks neither.

The guard belongs in a **pre-flight** step at the top of the rung, not next to the `curl` — in the
current `dev-check.yml` the Telegram step runs after the Play upload and before the GitHub release, so
a guard bolted on there fails a half-published release. Tracked in
`docs/follow-ups/telegram-caption-length-guard.md`; folded into this design as a task because the
reusable workflow is the natural home for it.

---

## 7. Part E — store fronts

### `versionNameSuffix = "-foss"` is dropped

`app/build.gradle.kts:197`. Two independent defects resolve:

**Obtainium.** `1.94.0-foss` matches none of Obtainium's strict version patterns — its recognised
suffix list is `alpha|beta|rc|pre|dev|snapshot|nightly|ose|[0-9]+`, with no `foss`. Reconciliation
returns null, Obtainium sets `versionDetection = false` and stamps `installedVersion = latestVersion`.
A user on an outdated foss build is marked **up to date and never offered the update**.

**F-Droid.** The `Binaries:` field substitutes only `%v` and `%c`. With the suffix, `%v` is
`1.94.0-foss` and the URL resolves to a tag that does not exist; no substitution can repair it.
Without the suffix:

```
Binaries: https://github.com/trinadhthatakula/Thor/releases/download/v%v/foss-release.apk
```

Cost: no on-device way to distinguish `foss` from `store` in Settings → App info. Accepted. Version
codes are unaffected, so no user sees a downgrade.

### Obtainium

No repository change beyond the above; the deliverable is a **published configuration**, because
Obtainium's defaults select `store-release.apk`:

```jsonc
{
  "id": "com.valhalla.thor",
  "url": "https://github.com/trinadhthatakula/Thor",
  "author": "trinadhthatakula",
  "name": "Thor",
  "additionalSettings": {
    "apkFilterRegEx": "foss-release\\.apk",   // exactly one match → index 0, deterministic
    "includePrereleases": false               // pinned: two of three rungs mint pre-releases
  }
}
```

`includePrereleases` already defaults to `false`, but it is pinned because it is now load-bearing.
Shipped as an `obtainium://app/` deep link behind the `apps.obtainium.imranr.dev/redirect` wrapper,
placed in `README.md`, the `web/` landing page, and the store docs.

### IzzyOnDroid — no configuration change required

`GHSkipPre` already excludes pre-releases, so the new `-beta-<run>` rung is filtered out for free.
`ApkMatch` already pins `foss-release.apk`, so dropping the AAB only reduces ambiguity.

Two courtesy notifications, not requirements:
- the displayed version string changes `1.94.0-foss` → `1.94.0`;
- `target_commitish` now guarantees the tag matches the build commit, retiring the `git reset --soft`
  patches in Thor's rbtlog recipe.

### F-Droid — ask before building

Route: **developer-signed reproducible** (`Binaries:` + `Builds.binary` + `AllowedAPKSigningKeys`).
`docs/fdroid-submission.md` (last touched 2026-08-01, `6035f373`) was written for Route B — an MR
against `fdroiddata` for an F-Droid-*built* app — and needs a full rewrite for this route.

**Sequencing decision: open the RFP before doing the metadata work.** Developer-signed still means
F-Droid builds and byte-compares; they do not merely mirror the APK. So their buildserver must be able
to run **Gradle 9.6.1 with AGP 9.4.0-alpha07**, and that is the one thing that can bounce the
submission after all other work has landed. The RFP states the toolchain plainly and asks outright.
Metadata, recipe, and the doc rewrite are gated on the answer.

### OpenAPK

A listing form against an existing GitHub release, with no repository coupling. Sequenced **last**,
because it snapshots the release page as it stands at submission time.

### Accrescent — permanently out of scope

Accrescent's published requirements exclude Thor by construction, under **Manual checks**:

> **ADB access** — "Utilizing ADB (Android Debug Bridge) directly or indirectly within an app is
> strictly forbidden. This restriction includes, but is not limited to using a proxy like Shizuku or
> directly using wireless debugging."
>
> **Root access** — "Apps may not utilize root access for any functionality. This includes apps which
> attempt to exploit the current device to gain root access as well as apps which access privileged
> functionality only available due to previously rooting the device."

Thor's entire `SystemGateway` layer is root, Shizuku and Dhizuku. Additionally `QUERY_ALL_PACKAGES`
and `REQUEST_INSTALL_PACKAGES` both trigger manual review, and self-updaters are prohibited outright.
This is not a maturity gap that later work closes — it is a permanent exclusion. Recorded here so the
question is not reopened.

Source: <https://accrescent.app/docs/guide/appendix/requirements.html>

---

## 8. Decisions

| # | Decision | Choice | Rationale |
|---|---|---|---|
| 1 | Which branch mints the Latest GitHub release | `production` only; `master` mints `-beta-<run>` pre-release | one Latest release per stable, matching the Play production promotion |
| 2 | Hotfix path | always the full ladder | a bypass reintroduces a second Play uploader |
| 3 | Release-notes timing | auto at `dev`, curated at `production` | dev candidates are frequent and internal; stables are public |
| 4 | Spec scope | one combined spec, Parts A–E | the store fixes depend on the ladder's tag and asset shape |
| 5 | F-Droid route | developer-signed / reproducible | preserves Thor's own signing key across all FOSS channels |
| 6 | Does `master` build | yes — all three rungs build | required for reproducibility (§5), not merely tidy |
| 7 | Two APKs per release | keep both, fix the defaults | `apkFilterRegEx` pins Obtainium to `foss-release.apk` |
| 8 | `versionNameSuffix = "-foss"` | drop it | fixes Obtainium version detection and unblocks F-Droid `Binaries:` |
| 9 | AAB as a release asset | drop it | no consumer; Play re-signs it |
| 10 | `vcsInfo` | keep enabled | forensic value; free once the tag is pinned |
| 11 | F-Droid sequencing | RFP first, metadata after | the toolchain question gates everything downstream |
| 12 | Accrescent | permanent no | prohibited by their published requirements |

---

## 9. Risks and open items

| Item | Status |
|---|---|
| F-Droid buildserver support for AGP 9.4.0-alpha07 / Gradle 9.6.1 | **open** — the RFP asks; everything F-Droid-side is gated on the answer |
| `baseline.prof` / `.profm` determinism | known, empirically fine, not mitigated |
| `v1.81.9-dev-82` mis-flagged `prerelease=false` | one-off `gh release edit`; task, not a design decision |
| Telegram 1024-unit caption cap | folded into the pre-flight gate (§6) |
| `sync-shizu-changelog.sh` never runs in CI; `fastlane/` changelog copy audited by nothing | pre-existing, unchanged by this design |
| First ladder run leaves a dev code active in `beta` | expected — invariant 3 means promotion always names the code explicitly |

---

## 10. Verification notes

Claims in §2 and §5 were checked against the repository and the published v1.94.0 artifacts rather
than inferred. Two beliefs held earlier in design discussion were refuted by that check and are
corrected here:

- **"The three rungs produce byte-identical APKs."** False. Each rung builds a different commit and
  the sha is embedded, so each rung's APK differs. This is what makes `target_commitish` necessary.
- **"`master` can resolve the newest alpha code at run time."** False. See §4 — the Play query has no
  status filter and no ordering, and promotion leaves codes active in the source track.
