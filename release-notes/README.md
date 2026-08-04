# 📦 Thor Release Notes Guide

This directory holds Thor's official release notes. They are written by hand and consumed by
automation, so the shape of each file matters as much as its content.

Every release gets a directory named `v<versionName>` (e.g. `v1.93.2`) containing exactly three
files: `playstore.txt`, `telegram.md`, `github.md`. Two further channels are generated *from* those
files rather than written separately.

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
including the required `build-and-test` check. So a forgotten `sync-shizu-changelog.sh` is a red
`shizu-manifest` check, not a silent store regression. The **`fastlane/` copy is checked by
nothing** — the `diff` in Step 6 is its only gate, which is why Step 6 is not optional.

Who reads what, exactly:

| File | Consumer | Where |
|---|---|---|
| `github.md` | GitHub Release body | `dev-check.yml:166`, `production-deploy.yml:159` |
| `telegram.md` | Telegram broadcast caption | `dev-check.yml:192`, `telegram-release.yml:84` |
| `playstore.txt` | Play `whats_new`; copied to `fastlane/…/changelogs/<versionCode>.txt` | `fastlane/Fastfile:89-113` |
| `fastlane/…/changelogs/<versionCode>.txt` | F-Droid changelog | the F-Droid builder reads the repo directly |
| `shizu_store.json` → `.changelog` | Shizu CoreFetch store listing | `.github/scripts/sync-shizu-changelog.sh` |

Both CI paths fall back from `release-notes/v<version>/` to `release-notes/<version>/` (no `v`).
Use the `v` form; the fallback exists only for old directories.

---

## 📋 The three files

### 1️⃣ `playstore.txt` — Google Play, F-Droid, Shizu Store

* **Format**: plain text. Bullets start with `• ` and lead with an emoji.
* **Size**: **strictly under 500 characters**, counting newlines and spaces. Play rejects more.
  Aim for 440–490 so a late edit does not push it over.
* **Line breaks**: **MANDATORY** blank line between bullets. Without them the Play Console and the
  store listing bunch everything into one illegible paragraph.
* **Voice**: consumer language. No PR numbers, no file paths, no internal vocabulary
  (*gateway*, *rung*, *probe*, *binder*, *reflection*, class names). Describe what changed for the
  person holding the phone.
* Because this text is reused verbatim by F-Droid and the Shizu Store, it has to read well with no
  surrounding context.

### 2️⃣ `telegram.md` — the broadcast

* **Format**: punchy markdown, emoji-led bullets, mobile-first.
* **Size**: **under ~870 UTF-16 code units.** ⚠️ **Exceeding this fails silently — see the trap
  below.** Measure UTF-16 units, not characters: most emoji count as **2**. The budget is the 1024
  cap minus the wrapper the workflow adds, measured at **149** units (`dev-check.yml`, the longer of
  the two) and **141** (`telegram-release.yml`) for v1.93.2. The wrapper is not fixed — it carries
  the branch name, the actor and the track label — so keep the margin.
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

---

## ⚠️ Traps that have already cost a release

**1. An oversized `telegram.md` posts NOTHING, and CI still goes green.**
The notes are sent as a `sendDocument` **caption** (`telegram-release.yml:146`,
`dev-check.yml:252`), not as a message. Telegram caps captions at **1024 UTF-16 units** and
**rejects** an oversized one outright — it does not truncate. The `curl` has no `--fail` and its
output is discarded, so the step succeeds having broadcast nothing. The workflow prepends a header
and appends a GitHub-link footer worth roughly **140 units**, which is where the ~870 budget comes
from. For reference: v1.93.1 was 695 units (fine); v1.93.0 was 1008 (**already over the budget when
it shipped**).

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
.github/scripts/sync-shizu-changelog.sh                          # Shizu Store manifest
```
`sync-shizu-changelog.sh` reads `versionCode` from `gradle.properties`, derives the version name,
and writes `playstore.txt` into `shizu_store.json`'s `changelog` with `jq`. Run it **after** Step 3
or it will look for the wrong directory. CI never runs *this* script: the `master` ruleset requires
a PR and a status check, and a `GITHUB_TOKEN`-authored PR does not trigger `pull_request` workflows,
so no bot can land that commit. Do not read that as "nothing checks the result" — its counterpart
`check-shizu-manifest.sh` runs on every PR (see the note under the diagram above).

The `cp` writes a **trailing newline** the manifest does not carry: `jq` stores the changelog
stripped, by design, so `check-shizu-manifest.sh` passing while a naive byte comparison of the two
says "different" is the expected state, not a defect.

### Step 6 — Verify before committing
Nothing in CI checks either size, so this step is the only gate on both — see
`docs/follow-ups/telegram-caption-length-guard.md` for why the Telegram one is not yet automated.

```bash
# sizes
wc -m release-notes/v<version>/playstore.txt            # must be < 500
python3 -c "t=open('release-notes/v<version>/telegram.md',encoding='utf-8').read(); \
print(sum(2 if ord(c)>0xFFFF else 1 for c in t))"       # must be < ~870

# the three copies agree, and the manifest still describes reality
diff release-notes/v<version>/playstore.txt fastlane/metadata/android/en-US/changelogs/<versionCode>.txt
.github/scripts/check-shizu-manifest.sh                 # add --network for the URL tier
```

### Step 7 — Stage explicitly
```bash
git add release-notes/v<version>/ \
        fastlane/metadata/android/en-US/changelogs/<versionCode>.txt \
        shizu_store.json gradle.properties
```
