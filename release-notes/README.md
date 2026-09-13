# 📦 Thor Release Notes Guide

This directory holds Thor's official release notes. They are written by hand and consumed by
automation, so the shape of each file matters as much as its content.

Every release gets a directory named `v<versionName>` (e.g. `v1.93.2`) containing exactly three
files: `playstore.txt`, `telegram.md`, `github.md`. Two further channels are generated *from* those
files rather than written separately.

> 📖 For the branch model itself — which merge runs which rung, why only one of them uploads, and
> the post-release back-merge — see
> [docs/branching-and-releases.md](../docs/branching-and-releases.md). This guide covers the
> *content* of the notes; that one covers the *routing*.

---

## Which merge publishes what

Three branches, three rungs, one Play upload. A rung is identified by the branch it runs on —
there is no arithmetic on the version number anywhere in the routing.

| merge | workflow | Play | GitHub | Telegram |
|---|---|---|---|---|
| `<feature>` → `dev` | `1-dev-publish.yml` | **uploads** to `alpha` (Closed testing), then assigns that same release to `internal` | pre-release `v<name>-dev-<run>` | yes |
| `dev` → `master` | `2-master-promote.yml` | promotes `alpha` → `beta` (Open testing) | pre-release `v<name>-beta-<run>` | no |
| `master` → `production` | `3-production-promote.yml` | promotes `beta` → `production` | **release** `v<name>` (Latest) | yes |

Only `1-dev-publish.yml` uploads an artifact. Play allows one upload per version code per app —
not per track — so a second uploader is what produced `Version code NNNN has already been used`.
The other two rungs move that same upload up a track, and the `internal` mirror is not a second
upload either: it assigns the release `alpha` already holds. Two tracks, one upload.

Every rung still builds the APKs from its own commit: the GitHub assets, the Telegram broadcast
and reproducibility all need an APK built from the tree being released. Only the Play artifact is
promoted rather than rebuilt.

A push that does not change `versionCode` still builds, but publishes nothing — except on
`production`, where it is an error, because there is nothing to promote.

### Mid-cycle bug fixes

**There is no hotfix bypass.** A fix lands on `dev` with a *new* version code and re-enters at the
bottom rung, superseding the previous candidate on `alpha`, then climbs as usual.

This is deliberate rather than an oversight. A fast lane straight to `beta` or `production` would
be a second Play uploader, and one code with two uploaders is the entire problem the ladder
exists to remove. A fix that genuinely cannot wait for the ladder is a fix that should ship as a
new version, which is what this does.

### When notes are required

Curated notes (`release-notes/v<name>/`) are **required** for a `production` release and optional
below it. A dev or beta build with no notes directory falls back to the commit log; a production
push with none fails before it builds.

Sizes are checked pre-flight by `.github/scripts/check-notes-budget.sh`:

- `telegram.md` — the **assembled** caption must fit 1024 UTF-16 units. The wrapper the workflow
  adds measures 145–163 units on the ladder's own three branches (it varies by rung and actor);
  `check-notes-budget.sh` defaults to 164, the most the ladder can ever pass it. Telegram *rejects*
  an oversized caption rather than truncating it.
- `playstore.txt` — at most 500 characters (Play's own limit; the checker fails above 500, not at
  it).

Run it yourself before opening the release PR:

```bash
.github/scripts/check-notes-budget.sh 1.94.1
```

The 164 default is a hand-run convenience, **not a universal ceiling**: the wrapper contains the
ref name, so a `workflow_dispatch` from a long-named branch costs more — this branch measured 187.
It is the largest number the three rungs can pass, chosen so a hand run is never *looser* than the
release that follows; if you are releasing from anything else, pass the measured wrapper size as a
second argument. On the ladder itself the workflow measures the wrapper per run and passes the real
number, so the default never decides a real release.

⚠️ It has **no room to spare** — it used to, and stopped. The gate cannot know the track yet
(fastlane writes `track.txt` during the build), so it substitutes the longest label any rung emits,
which is now the dev rung's two-track `Closed + Internal Testing`. That raised what CI passes on
every rung — to 161/164 on `production` — so a default of 160 would now pass captions the release
itself refuses. If a track label ever gets longer, this default moves with it;
`test-caption-wrapper.sh` fails if it does not.

---

## 🗺️ Five channels, three files

```
release-notes/v<version>/
├── playstore.txt ──┬─→ Google Play  "What's new"      (fastlane supply, via Fastfile)
│                   ├─→ F-Droid      fastlane/metadata/android/en-US/changelogs/<versionCode>.txt
│                   └─→ Shizu Store  shizu_store.json → .changelog
├── telegram.md ──────→ Telegram broadcast             (sendDocument *caption*)
└── github.md ────────→ GitHub Release body
```

**`playstore.txt` is the single source for three channels.** All three copies must stay
byte-identical, because each consumer reads its own copy: F-Droid reads `fastlane/`, the Shizu store
reads `shizu_store.json`. Write the text once, then propagate it (Step 5).

Only one of those copies is audited. `.github/scripts/check-shizu-manifest.sh` compares
`shizu_store.json`'s `.changelog` against `playstore.txt`, and `pr-ci.yml`'s `shizu-manifest` job
runs it on **every**
PR — deliberately un-path-filtered, since `on.pull_request.paths` would gate the whole workflow
including the required `build-and-test` check. So a forgotten `sync-shizu-changelog.sh` surfaces on
the PR rather than becoming a silent store regression. The **`fastlane/` copy is checked by
nothing** — the `diff` in Step 6 is its only gate, which is why Step 6 is not optional.

**It surfaces as a `::warning::` on a PR, not a red check.** The checker derives its expected
version from **`origin/production`**, so a production promotion moves the target without any PR
running, and this job is un-path-filtered — without the softening, one promotion would redden every
open PR at once, for authors who did not cause the drift and cannot fix it from their branch.
`pr-ci.yml` therefore passes `--warn-changelog-drift`, which downgrades **only** that one condition.
The **weekly `shizu-store-audit.yml` runs the same checker with no flag**, so a manifest left
un-synced is still a hard failure with a tracking issue — just at the place where the fix is a
commit away. Everything else (schema violation, missing file, unresolvable ref, dead URL) fails hard
in both.

Who reads what, exactly:

| File | Consumer | Where |
|---|---|---|
| `github.md` | GitHub Release body | `release-rung.yml`, step *Prepare Release Notes* |
| `telegram.md` | Telegram broadcast caption | `release-rung.yml`, step *Publish to Telegram*; `telegram-release.yml`, step *Read Version Info* |
| `playstore.txt` | Play `whats_new`; copied to `fastlane/…/changelogs/<versionCode>.txt` | `fastlane/Fastfile`, `prepare_release_artifacts` and `copy_playstore_notes` |
| `fastlane/…/changelogs/<versionCode>.txt` | F-Droid changelog | the F-Droid builder reads the repo directly |
| `shizu_store.json` → `.changelog` | Shizu CoreFetch store listing | `.github/scripts/sync-shizu-changelog.sh` |

Step names rather than line numbers on purpose — the line references that used to sit here were
stale within two commits, and a wrong line number is worse than none.

Both CI paths fall back from `release-notes/v<version>/` to `release-notes/<version>/` (no `v`).
Use the `v` form; the fallback exists only for old directories.

---

## 📋 The three files

### 1️⃣ `playstore.txt` — Google Play, F-Droid, Shizu Store

* **Format**: plain text. Bullets start with `• ` and lead with an emoji.
* **Size**: **at most 500 characters**, counting newlines and spaces. Play rejects more, and so
  does `check-notes-budget.sh` (`> 500`, so exactly 500 passes). Aim for 440–490 so a late edit
  does not push it over.
* **Line breaks**: **MANDATORY** blank line between bullets. Without them the Play Console and the
  store listing bunch everything into one illegible paragraph.
* **Voice**: consumer language. No PR numbers, no file paths, no internal vocabulary
  (*gateway*, *rung*, *probe*, *binder*, *reflection*, class names). Describe what changed for the
  person holding the phone.
* Because this text is reused verbatim by F-Droid and the Shizu Store, it has to read well with no
  surrounding context.

### 2️⃣ `telegram.md` — the broadcast

* **Format**: punchy markdown, emoji-led bullets, mobile-first.
* **Size**: **under 860 UTF-16 code units.** ⚠️ **Exceeding this broadcasts nothing — see the trap
  below.** Measure UTF-16 units, not characters: most emoji count as **2**. The wrapper the
  workflow adds is 145–163 units on the ladder's own three branches (it varies by rung and by actor;
  `dev` is the dearest because its caption names two tracks), but it **contains the branch name**, so
  a `workflow_dispatch` from a long-named branch measures 187 — which is why `release-rung.yml`
  measures it per run rather than assuming a constant. `check-notes-budget.sh` defaults to 164 when
  you run it by hand — run it without a second argument for a conservative pre-flight check.
* **Line breaks**: **MANDATORY** blank line between bullets, or Telegram's mobile client squeezes
  the whole thing into a dense block.

### 3️⃣ `github.md` — the audit trail

* **Format**: full markdown, no size limit, as detailed as the release deserves.
* **Content**: `## ✨ Highlights`, then `## What's Changed` with one `###` section per theme
  carrying PR numbers, then a project/internal section for the work users never see (name it for
  what the release actually contained — v1.93.2 used `## 🌐 Project: the site, the stores, the
  build`), then `## 🛠 Commits Log`.
* Include real short commit hashes (e.g. `(5f3d34d)`) — this file is the open-source accountability
  record. Internal work (docs, CI, tests) belongs here too, in a lower section.

### 🗑️ Retention — this directory keeps the last **20** releases

When a bump takes the count past 20, delete the oldest `v*` directories in the same
`chore(release)` commit until 20 remain. Nothing reads a past release's directory: the published
GitHub release bodies are already on GitHub, F-Droid's changelog history lives in
`fastlane/metadata/android/*/changelogs/<versionCode>.txt` — **which is never pruned** — and every
script here takes the version as an argument. Before deleting, confirm the same for each directory
you are dropping, substituting its real version into the pattern — for `v1.90.2` that is
`rg -n 'release-notes/v1\.90\.2' --glob '!release-notes/**' .`. Run
`.github/scripts/test/run-tests.sh` **after** the deletion as well as before it — a run against the
pre-pruning tree says nothing about the tree you are committing.

---

## ⚠️ Traps that have already cost a release

**1. An oversized `telegram.md` posts NOTHING, and you find out mid-release.**
The notes are sent as a `sendDocument` **caption**, not as a message — both in `release-rung.yml`
and in `telegram-release.yml`. Telegram caps captions at **1024 UTF-16 units** and **rejects** an
oversized one outright — it does not truncate.

Both workflows now carry `--fail-with-body` and capture the response, so under GitHub's default
`bash -e` shell the step goes **red** and Telegram's own `"caption is too long"` line lands in the
log. Red is not harmless, though. On the ladder the Play upload has already happened by then, and
`Create GitHub Release` still runs, because it is guarded by `!cancelled()` precisely so a broadcast
cannot veto a release. What you get is a version live on Play and tagged on GitHub that nobody was
told about, learned from a red check. `telegram-release.yml` is the way to send it after the fact.

⚠️ `--fail-with-body` differs from `--fail` in exactly one way: it writes the error body to stdout.
Redirecting that to `/dev/null` — which both sends in `telegram-release.yml` used to do — silently
reverts it to `--fail` and throws away the only line that says why. Do not add a `> /dev/null`.

Either way the discovery lands *during* the release, which is why the budget is checked pre-flight
instead. `release-rung.yml` prepends a header that costs **145–163 UTF-16 units on the ladder's own
three branches** (it varies by rung and by actor, and it counts the blank line that joins it to
`telegram.md`). The 860 budget is `1024 - 164`, and 164 is what the *gate* passes on `production`:
it cannot know the track yet, so it prices every rung at the longest label — `dev`'s two-track
`Closed + Internal Testing`. **163 is not a ceiling** — the header contains the branch name, so a
`workflow_dispatch` from a long-named branch costs more; this branch measures 187. That is why
`release-rung.yml` measures the header per run instead of assuming a constant, and why a file sized
to exactly 860 can pass on `dev` and be refused on a dispatch.
`telegram-release.yml` uses a shorter header and also appends a GitHub-link footer. For reference:
v1.93.1 was 695 units (fine); v1.93.0 was 1008 (**already over the budget when it shipped**).

**2. Baseline the commit range on the last release TAG, not on `master`.**
`master` runs ahead of its own release tag, so `master..dev` undercounts. Use the newest tag by
*creation date* — including `-dev-N` pre-release tags, which are real releases for this purpose.

**3. Do not write GitHub closing keywords into the notes.**
`master` is the default branch, so a `dev` → `master` PR body **does** trigger auto-close. Any
`closes #N` / `fixes #N` / `resolves #N` (and `-es`/`-ed` forms) will close that issue the moment
the release merges. `part of #N` and `completes #N` are safe. Grep before opening the PR:
```bash
grep -nEi '(close[sd]?|fix(e[sd])?|resolve[sd]?) +#[0-9]+' release-notes/v<version>/*
```

**4. `shizu_store.json` must never gain `version_name` or `version_code`.**
The store reads those from the GitHub release, and no workflow can push to `master` to keep them
current — a stale pair there would misreport the current version forever. `check-shizu-manifest.sh`
fails the build if either key appears.

---

## 🔄 Step-by-step

### Step 1 — Find the baseline tag
```bash
git tag --sort=-creatordate | head -5
gh release list --limit 10   # confirms which of those were pre-releases
```

### Step 2 — Read the range
```bash
BASE=<that tag>
git log $BASE..dev --no-merges --format='%h %s' --reverse
git log $BASE..dev --merges --format='%h %s'          # the PR list
git diff --stat $BASE..dev
```

### Step 3 — Bump the version
Edit **`versionCode` in `gradle.properties` only** — `versionName` is derived
(`1932` → `1.93.2`) and must never be set by hand. The bump and the notes belong in the
**same commit**.

### Step 4 — Write the three files
Sort every change into *user-visible*, *project-visible* and *internal* first. A commit prefixed
`fix(` that only touched comments or KDoc is **internal** — check the diff, not the subject. Users
should never be told about dependency bumps or documentation.

```
release-notes/v<version>/{playstore.txt,telegram.md,github.md}
```

### Step 5 — Propagate `playstore.txt` to the other two channels
```bash
cp release-notes/v<version>/playstore.txt \
   fastlane/metadata/android/en-US/changelogs/<versionCode>.txt   # F-Droid + Play

# EVERY locale needs the file, not just en-US
for d in fastlane/metadata/android/*/; do
  [ -f "$d/changelogs/<versionCode>.txt" ] ||
    cp release-notes/v<version>/playstore.txt "$d/changelogs/<versionCode>.txt"
done
```

⚠️ **`en-US` alone is not enough.** supply enumerates locales from the metadata directory and
emits an *empty* what's-new for any locale with no file for this code — it blanks those users'
release notes rather than leaving the previous one standing. `test-changelog-locale-parity.sh`
enforces this, and `pr-ci.yml` runs it on every PR, so a missing locale is a red check on the
release PR rather than a store regression found later.

Translate if you can; if you cannot, English is the correct placeholder — a translated release
would still beat a blank one, and a blank one is the failure mode this prevents. A translation
added later is safe: `copy_playstore_notes` in `fastlane/Fastfile` overwrites `en-US` only and
skips any other locale whose file is already non-empty, so the production upload will not
overwrite a contributed translation with English.

⚠️ **The Shizu manifest is no longer part of this step.** `sync-shizu-changelog.sh` reads
`versionCode` from **`origin/production:gradle.properties`**, not from the working tree, because
`shizu_store.json`'s `download_url` is `/releases/latest/` — which GitHub resolves to the newest
**non-pre-release**. Under the three-rung ladder that is production's build, while `dev` and
`master` both mint pre-releases, so the tree you are preparing runs one or more codes ahead of the
APK that URL actually serves.

Run it here and it will sync the changelog of the **last production release**, print
`changelog already current for v<production version>`, and exit 0 — which reads like "done" while
`shizu_store.json` never receives the version you are preparing. The manifest is refreshed **after
the production promotion**, not during release prep:

```bash
git fetch origin production                # the script reads this ref; a shallow clone has it not
.github/scripts/sync-shizu-changelog.sh    # on master, once production carries the new versionCode
git add shizu_store.json                   # and commit it to master
```

Until that lands, `shizu-manifest` warns on PRs and the weekly audit fails — see the note under the
diagram above. `SHIZU_VERSION_REF` overrides the ref for a one-off; if it does not resolve, the
script fails loudly rather than quietly falling back to production.

CI never runs *this* script: the `master` ruleset requires a PR and a status check, and a
`GITHUB_TOKEN`-authored PR does not trigger `pull_request` workflows, so no bot can land that
commit. Do not read that as "nothing checks the result" — its counterpart
`check-shizu-manifest.sh` runs on every PR (see the note under the diagram above).

The `cp` writes a **trailing newline** the manifest does not carry: `jq` stores the changelog
stripped, by design, so `check-shizu-manifest.sh` passing while a naive byte comparison of the two
says "different" is the expected state, not a defect.

### Step 6 — Verify before committing
`.github/scripts/check-notes-budget.sh` checks both size limits in one step:

```bash
.github/scripts/check-notes-budget.sh <version>   # e.g. 1.94.1

# the three copies agree, and the manifest still describes reality
diff release-notes/v<version>/playstore.txt fastlane/metadata/android/en-US/changelogs/<versionCode>.txt
.github/scripts/check-shizu-manifest.sh                 # add --network for the URL tier

# what pr-ci.yml will run — includes the locale-parity gate from Step 5
.github/scripts/test/run-tests.sh
```

### Step 7 — Stage explicitly
```bash
git add release-notes/v<version>/ \
        fastlane/metadata/android/*/changelogs/<versionCode>.txt \
        shizu_store.json gradle.properties
```

The `*/` is deliberate — staging only `en-US` is how a locale goes missing. Never `git add -A`:
`docs/audit/` is untracked on purpose and must stay that way.

---

## After the release: back-merge

`master` and `production` each gain a merge commit that `dev` does not have. Bring `dev` back
level before starting the next cycle:

```bash
git checkout dev && git pull
git merge --no-ff origin/production -m "Merge branch 'production' into dev"
git push origin dev
git rev-list --count origin/production ^dev   # expect 0
```

One merge of `origin/production` levels `dev` against both rungs at once. After the production
rung has run, `origin/master` is an ancestor of `origin/production` — the `master`→`production`
PR merge is what put it there — so merging production carries the master merge commit along for
free. No separate merge of `origin/master` is needed.

This push goes straight to `dev`, which the `DevRules` ruleset permits through a RepositoryRole
bypass. It is the one exception to "never push directly to `dev`". Skipping it means the next
feature branch forks from a tree that is missing the release commit.

**Mid-cycle exception:** if the `master` rung has run (alpha promoted to beta) but `production`
has not yet run, use `origin/master` instead. The `master`→`production` merge has not happened
yet, so `master` is not an ancestor of `production` and merging production would miss the beta
rung's merge commit. Mid-cycle neither branch is an ancestor of the other, which is why the ref
you merge is the rung that last ran.
