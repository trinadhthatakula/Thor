# 🪜 Branching and releases

How Thor's branches fit together, and how a commit travels from a pull request to the Play Store.

**Read this if** you are opening a PR, wondering which branch to target, or about to cut a release.
For *writing* the release notes themselves, see [`release-notes/README.md`](../release-notes/README.md) —
this document covers the routing, that one covers the content.

---

## The three branches

Thor has exactly three permanent branches. Everything else is a short-lived topic branch.

| Branch | What it is | Who merges into it |
|---|---|---|
| `dev` | Integration branch. All work lands here first. | Anyone, via PR |
| `master` | What open testers are running. | Maintainer, via PR from `dev` |
| `production` | What the public is running. | Maintainer, via PR from `master` |

**Never delete `production`.** It sits a long way behind `dev` between releases, so tools that
list "merged" branches will happily offer it up. It is a live release lane.

```
  feature/x ─┐
  fix/y ─────┼──▶ dev ──────▶ master ──────▶ production
  translate/z┘     │            │                │
                   ▼            ▼                ▼
              Play alpha    Play beta      Play production
             (closed test)  (open test)      (everyone)
```

---

## If you are contributing

1. Branch from `dev`. Name it `feature/<name>`, `fix/<name>` or `translate/<locale>`.
2. Open your PR **against `dev`**. Never against `master` or `production`.
3. Do **not** bump `versionCode` in your PR. Releases are cut separately — see below.
4. CI will run build, tests, lint, CodeQL and the static-analysis gates on your PR.

That is the whole contributor story. The rest of this document is about what happens after your
work is merged.

---

## The release ladder

A release climbs three rungs, one per branch. **A rung is identified by the branch it runs on** —
there is no arithmetic on the version number anywhere in the routing.

| Merge | Workflow | Play | GitHub | Telegram |
|---|---|---|---|---|
| `<topic>` → `dev` | [`1-dev-publish.yml`](../.github/workflows/1-dev-publish.yml) | **uploads** to `alpha` (Closed testing) | pre-release `v<name>-dev-<run>` | ✅ |
| `dev` → `master` | [`2-master-promote.yml`](../.github/workflows/2-master-promote.yml) | promotes `alpha` → `beta` (Open testing) | pre-release `v<name>-beta-<run>` | ❌ |
| `master` → `production` | [`3-production-promote.yml`](../.github/workflows/3-production-promote.yml) | promotes `beta` → `production` | **release** `v<name>` (Latest) | ✅ |

All three are thin callers of one shared implementation,
[`release-rung.yml`](../.github/workflows/release-rung.yml). They differ only in the inputs they
declare. **If a rung needs to behave differently, add an input — do not fork the workflow.**

### Rung 1 — `dev`

Builds both flavours, uploads the `store` AAB to Play's `alpha` track, publishes a GitHub
pre-release with the APKs attached, and broadcasts to the Telegram testers' channel.

This is the only rung that puts bytes into Play.

### Rung 2 — `master`

Promotes the existing `alpha` release to `beta`. Builds the APKs (GitHub assets and
reproducibility need them) but uploads nothing to Play. No Telegram broadcast — open testers
already have the build from Play, and the announcement belongs to the production rung.

### Rung 3 — `production`

Promotes `beta` to `production`, mints the **only** GitHub release that is not marked pre-release,
and broadcasts. The tag has no suffix, because that is the tag Obtainium, IzzyOnDroid and the Shizu
Store all resolve.

---

## The rule the whole thing exists to protect

> **Exactly one rung uploads an artifact to Play.**

Google Play accepts a given `versionCode` **once per app — not once per track**. A second upload of
the same code fails with:

```
Version code 1940 has already been used. Try another version code.
```

The previous design had two branches building and uploading, which meant every release was one
mis-timed merge away from that error. The ladder removes the possibility rather than working around
it: `dev` uploads, and the other two rungs move that same upload up a track.

The invariant is enforced in three independent places, so breaking it takes three mistakes:

- [`fastlane/Fastfile`](../fastlane/Fastfile) appends the `bundleStoreRelease` Gradle task **only**
  when uploading, so a promote lane never produces an AAB for Fastlane to find.
- The promote lanes set **both** `skip_upload_apk` and `skip_upload_aab`. The second one matters:
  it defaults to `false`, so setting only the first still uploads the bundle.
- [`fastlane/lib/thor_release.rb`](../fastlane/lib/thor_release.rb) restricts uploads to the
  `internal` and `alpha` tracks and raises otherwise.

---

## Version numbers

A single integer in [`gradle.properties`](../gradle.properties) drives everything:

```properties
versionCode=1940
```

`versionName` is **derived** — `1940` → `1.94.0` — and must never be hand-edited.

**Bumping `versionCode` is what makes a push a release.** A push to `dev` that does not change it
still builds and verifies, but publishes nothing. On `production` an unchanged version is a hard
error, because there is nothing to promote.

Bump the version in its own `chore(release)` commit, never mixed into a feature PR.

---

## Release notes

Curated notes live in `release-notes/v<name>/` and are **required on the production rung only**.
Below it they are optional and the GitHub body falls back to the commit log.

| File | Consumed by | Limit |
|---|---|---|
| `github.md` | the GitHub release body | — |
| `playstore.txt` | Play, F-Droid, Shizu Store | 500 characters |
| `telegram.md` | the Telegram broadcast | 1024 **UTF-16 units** for the assembled caption |

The Telegram limit is the sharp one. Telegram **rejects** an oversized caption rather than
truncating it, and it counts UTF-16 units, not characters and not bytes — **one emoji is 1
character but 2 units and 4 bytes.** The caption is also wrapped in a header the workflow builds,
so the file's own budget is roughly 860 units.

Check before you push, not after:

```bash
.github/scripts/check-notes-budget.sh 1.94.0
```

The same gate runs pre-flight in CI, *before* Fastlane, so an oversized caption costs you a failed
check rather than a half-finished release.

`playstore.txt` is also copied into `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`
for **every** locale, not just `en-US`. Fastlane's `supply` enumerates locales from that directory
and sends an *empty* what's-new for any locale missing a file for this code — blanking those users'
release notes instead of leaving the previous ones. `test-changelog-locale-parity.sh` enforces it on
every PR. English is an acceptable placeholder where no translation exists; a translation added
later is not overwritten.

---

## Mid-cycle bug fixes

**There is no hotfix bypass, and that is deliberate.**

A fix lands on `dev` with a *new* `versionCode`, superseding the previous candidate on `alpha`, and
climbs the ladder normally. A fast lane straight to `beta` or `production` would be a second
uploader for one version code — the exact failure the ladder exists to remove.

A fix that genuinely cannot wait for the ladder is a fix that should ship as a new version, which
is what this does.

---

## After a release: the back-merge

`master` and `production` each pick up a merge commit that `dev` does not have. Level `dev` back up
before starting the next cycle, or the next topic branch forks from a tree missing the release:

```bash
git checkout dev && git pull
git merge --no-ff origin/production -m "Merge branch 'production' into dev"
git push origin dev
git rev-list --count origin/production ^dev   # expect 0
```

One merge of `origin/production` levels `dev` against both rungs at once, because by then `master`
is an ancestor of `production`.

**Mid-cycle exception:** if `master` has run but `production` has not yet, merge `origin/master`
instead. Until the `master`→`production` merge happens, neither branch is an ancestor of the other —
so the ref you merge is whichever rung ran last.

This push goes directly to `dev`, which the `DevRules` ruleset permits through a repository-role
bypass. It is the one and only exception to "never push directly to `dev`".

---

## Where builds end up

| Channel | Source | Notes |
|---|---|---|
| Play — Closed testing | `dev` rung upload | testers opted in via the Play link |
| Play — Open testing | `master` rung promotion | |
| Play — Production | `production` rung promotion | |
| GitHub Releases | every rung | only the production rung is not a pre-release |
| Telegram | `dev` and `production` rungs | `master` is silent |
| IzzyOnDroid | the `v<name>` tag | rebuilt from source, verified against the tag |
| Obtainium | `/releases/latest/` | resolves to the production rung's release |
| Shizu Store | `shizu_store.json` on `master` | see [`release-notes/README.md`](../release-notes/README.md) |

### Why the three GitHub releases for one version differ

Every rung builds its APKs from **its own commit**, and AGP embeds that commit in
`META-INF/version-control-info.textproto`. So the `-dev`, `-beta` and stable releases for one
version carry different bytes.

This is expected. It is not a signing problem and not a reproducibility problem: IzzyOnDroid
rebuilds at the tag, and each tag is pinned to the commit that produced it via `target_commitish`.
Only the *Play* artifact is promoted rather than rebuilt.

---

## When something goes wrong

| Symptom | Cause | Fix |
|---|---|---|
| `Version code N has already been used` | Something other than the `dev` rung tried to upload | Bump `versionCode` and re-enter at `dev`. Do not retry the promote. |
| Production rung fails before building | `require_notes` — `release-notes/v<name>/` is missing a required file | Add the missing file, push again. Nothing was published. |
| Telegram step fails, release still published | By design — a broadcast failure must not veto a release | Re-send manually; the release is fine. |
| Caption rejected by Telegram | `telegram.md` too long once wrapped | Run `check-notes-budget.sh` and trim. |
| A rung publishes nothing | `versionCode` unchanged since the last release | Expected. Bump it if you meant to release. |

---

## Verifying locally

```bash
.github/scripts/test/run-tests.sh                          # shell script tests
ruby -Ifastlane/lib -Ifastlane/test fastlane/test/test_thor_release.rb
find .github/scripts -name '*.sh' -print0 | xargs -0 shellcheck -x -S style
./gradlew testFossDebugUnitTest --rerun-tasks
```

For `actionlint` and `shellcheck`, use **the versions CI pins**, not whatever your package manager
installed. Both tools change their findings across versions — actionlint 1.7.7 raises an SC2153
that 1.7.12 does not, and shellcheck renamed SC2317 to SC2329 in 0.10 — so a newer local build is a
green run that proves nothing about the job. Both pins, with their checksums and a fetch command,
are at the install steps in [`pr-ci.yml`](../.github/workflows/pr-ci.yml).

---

## File map

| Path | Role |
|---|---|
| `.github/workflows/release-rung.yml` | the shared rung implementation |
| `.github/workflows/{1,2,3}-*.yml` | thin per-branch callers |
| `.github/scripts/detect-version-bump.sh` | decides whether a push publishes |
| `.github/scripts/check-notes-budget.sh` | pre-flight notes size and presence gate |
| `fastlane/lib/thor_release.rb` | pure routing logic, unit-tested |
| `fastlane/Fastfile` | the lanes themselves |
| `release-notes/README.md` | how to write the notes |
