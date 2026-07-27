# Shizu CoreFetch store listing — design

**Date:** 2026-07-27
**Status:** Approved design, pending implementation
**Target branch:** `master` (via PR), then merge `master` → `dev`

## Goal

List Thor on [Shizu CoreFetch](https://docshizu.siwane.xyz/), a decentralized store for
Shizuku-powered Android apps, by adding a `shizu_store.json` manifest to the repository root.
Ship the manifest alongside a refresh of the store-facing copy (fastlane descriptions, README)
so the listing, the Play listing, and the repo all describe the same v1.93.0 app.

The store discovers Thor automatically from GitHub repository data whether or not this file
exists. The manifest only *overrides* that default with custom branding, translations, and a
native comment thread. A manifest that fails validation is ignored and the store silently falls
back to the defaults — so schema correctness is the one hard requirement.

## Background

Facts established while scoping this work:

- The store reads `shizu_store.json` from the **root of the default branch**. Thor's default
  branch is `master`. A file on `dev` would never be seen.
- `master` is already at `versionCode=1930` → `versionName` 1.93.0, and v1.93.0 is released.
  The manifest can be written against the current release with no version bump.
- The schema (`https://docshizu.siwane.xyz/schema.json`, draft-07) sets
  `"additionalProperties": false` at the top level. Inventing a field invalidates the whole file.
- The `foss` flavor is the correct artifact for a sideload store; `store` carries Play Billing.

### The offline claim is false and must be fixed

`fastlane/.../full_description.txt`, both `short_description.txt` files, and `README.md` all
claim Thor is "100% offline" with "zero internet permissions required". This is no longer true:
`app/src/main/AndroidManifest.xml` declares `android.permission.INTERNET` for both flavors.

The actual network surface, verified by reading the code rather than grepping:

- `data/repository/StoreRepositoryImpl.kt` is the **only** file that performs networking. It uses
  the JDK's `HttpURLConnection` — there is no OkHttp or Ktor in the project.
- Exactly one endpoint is contacted:
  `https://raw.githubusercontent.com/trinadhthatakula/Thor-Extensions/main/catalog/extensions.json`,
  reached from `ExtensionBrowseViewModel.refresh()` only when the user opens the Extensions store.
- Extension APK downloads enforce HTTPS (`SecurityException` on cleartext), verify a pinned signer,
  and check a SHA-256 hash.
- Coil is bundled as `coil-compose` only, with no network fetcher, so app icons are strictly local.

So: no analytics, no crash reporters, no ads, no trackers — all still true. "100% offline" and
"zero internet permissions" — false. Publishing the false version to a new store would be
shipping a fresh privacy claim we know to be wrong, so it is corrected in the same change.

## Scope

One PR to `master` containing twelve changes, then a `master` → `dev` merge.

| # | Change | Rationale |
|---|--------|-----------|
| 1 | Add `shizu_store.json` at repo root | The deliverable |
| 2 | Add `.github/workflows/shizu-store-audit.yml` | Weekly audit that the listing is still intact |
| 3 | Delete `.github/workflows/release-manager.yml` | Unused and broken (see below) |
| 4 | Copy `featureGraphic.png` from `dev` to `master` | Store banner should be the current one |
| 5 | Rewrite `fastlane/.../en-US/full_description.txt` | v1.93.0 features + offline fix + size fix |
| 6 | Rewrite `fastlane/.../en-US/short_description.txt` | Contains "100% offline" |
| 7 | Rewrite `fastlane/.../hi-IN/{full,short}_description.txt` | Same claims in Hindi |
| 8 | Update `README.md` | Offline fix, size fix, v1.93.0 features, 2 dead links |
| 9 | Add `.github/shizu_store.schema.json` | Vendored schema, for deterministic validation |
| 10 | Add `.github/scripts/check-shizu-manifest.sh` | Shared checker for PR CI and the weekly audit |
| 11 | Add `.github/scripts/sync-shizu-changelog.sh` | One command to refresh `changelog` at release time |
| 12 | Add a manifest-check job to `.github/workflows/pr-ci.yml` | Catch drift in the PR that causes it |

### Out of scope

- Bumping `versionCode`. This ships against the released 1.93.0.
- Publishing anything to Google Play. `fastlane/Fastfile` runs `upload_to_play_store` with
  `skip_upload_metadata: true` and `skip_upload_images: true`, so editing these files changes the
  repo, IzzyOnDroid, and the shizu listing — **not** the Play listing. Updating Play is a separate
  manual `supply` run and is the owner's call.
- Adding new screenshots. The existing ten are reused.
- The `ads` block. Thor is ad-free; `ad` is set to `false` and `ads` is omitted entirely.

## Manifest design

### File: `shizu_store.json` (repo root, `master`)

```json
{
  "schema_version": 1,
  "app_name": "Thor - App Manager",
  "package_name": "com.valhalla.thor",
  "min_sdk": 28,
  "target_sdk": 37,
  "short_description": "<en short_description, mirrors fastlane>",
  "detailed_description": "<en full_description, \\n-escaped>",
  "developer_message": "<see below>",
  "icon_url": "https://raw.githubusercontent.com/trinadhthatakula/Thor/master/fastlane/metadata/android/en-US/images/icon.png",
  "banner_url": "https://raw.githubusercontent.com/trinadhthatakula/Thor/master/fastlane/metadata/android/en-US/images/featureGraphic.png",
  "screenshots": ["<10 raw.githubusercontent URLs, 0.png then 1.jpg…9.jpg>"],
  "repo_url": "https://github.com/trinadhthatakula/Thor",
  "download_url": "https://github.com/trinadhthatakula/Thor/releases/latest/download/foss-release.apk",
  "changelog": "<release-notes/v1.93.0/playstore.txt>",
  "store_issue_number": 279,
  "category": "Tools",
  "license": "GPL-3.0-or-later",
  "open_source": true,
  "requires_shizuku": true,
  "ad": false,
  "donate_url": "https://www.patreon.com/trinadh",
  "tags": ["shizuku", "root", "dhizuku", "app-manager", "debloat", "freeze", "uninstall",
           "apk-installer", "split-apk", "package-manager", "foss", "material-you",
           "jetpack-compose", "kotlin", "device-owner"],
  "developer": {
    "name": "Trinadh Thatakula",
    "username": "trinadhthatakula",
    "account_url": "https://github.com/trinadhthatakula",
    "socials": {
      "github": "https://github.com/trinadhthatakula",
      "telegram": "https://t.me/thorAppDev"
    }
  },
  "locales": {
    "ar": { "short_description": "…", "detailed_description": "…", "developer_message": "…" },
    "es": { … }, "fr": { … }, "hi": { … }, "zh": { … }
  }
}
```

### Field decisions

**`version_name` and `version_code` are omitted.** The store already knows Thor's version: it reads
the repository's releases, and the manifest exists to *override* those defaults, not to restate
them. Writing them down creates a fact that must be re-synced on every release and that can
disagree with `download_url` — which always serves the newest release regardless of what the
manifest claims. Omitting them makes that disagreement unrepresentable.

This also removes the only reason the manifest would ever need a machine to write it, which
matters because **no machine can**. The `CodePush rules` ruleset (id 3613480, enforcement
`active`) targets `~DEFAULT_BRANCH` with `pull_request` and a required `build-and-test` status
check. Its bypass actors are the Admin role and one integration limited to `mode: pull_request`;
`github-actions[bot]` is not among them, so a workflow cannot push to `master`. The obvious
fallback is dead too: a pull request opened with `secrets.GITHUB_TOKEN` does not trigger
`pull_request` workflows, so `build-and-test` would never run and the required check would stay
pending forever. Only a PAT belonging to a human with the Admin role could do it, and no
`PAT_TOKEN` secret exists.

The constraint and the correct design point the same way, so no workaround is needed. The residual
assumption is stated under *Risks*.

**`download_url` → `releases/latest/download/foss-release.apk`.** GitHub 302-redirects this to the
newest release's asset, so it never goes stale, and it still ends in `.apk` as the schema's
`^https?://.*\.apk$` pattern demands. Omitting the field entirely would let the store guess from
release assets and possibly pick the `.aab` or the Play-Billing `store-release.apk`.

**Images pinned to `/master/`, not to a tag.** They track the default branch, so refreshing a
screenshot on `master` updates the listing with no manifest edit.

**`changelog` is top-level only, never per-locale.** The schema permits `changelog` inside
`locales`, but translated changelogs would have to be regenerated every release or silently rot
into showing stale notes to non-English users. One field, one source of truth, one thing to
refresh at release time.

It is the one field that is kept rather than omitted despite needing a refresh. Dropping it would
fall back to the GitHub release body, which is `release-notes/v*/github.md` — long-form Markdown
written for a release page, not the six curated bullets of `playstore.txt`. Its staleness is also
harmless in a way a stale version number is not: showing last release's notes is a cosmetic
inaccuracy, whereas advertising a version that `download_url` does not serve is a functional lie.
`sync-shizu-changelog.sh` refreshes it in one command and PR CI fails when it is stale.

**`app_name` and `developer_name` are not translated.** The brand and the person's name stay
identical in every locale; only the three prose fields vary.

**`requires_shizuku: true`** is a judgment call worth stating. Thor supports three privilege
backends — Root, Shizuku, and Dhizuku — so Shizuku is not strictly required. But Thor cannot do
its core work without *some* backend, and Shizuku is the path for the non-rooted users who are
this store's audience. `true` conveys "you will need Shizuku running" accurately for them.

**Omitted:** `min_shizuku_version` (no floor is enforced anywhere in the code, so any number would
be invented), `app_website` (none exists distinct from `repo_url`), `ads`, and every optional
`developer` field beyond the four above — no `email` per the owner's decision, no `website`,
`portfolio`, or developer `banner_url`.

**`short_description` headroom.** The schema allows 200 characters; Play allows 80. The manifest
reuses the fastlane text verbatim so there is a single source of truth, accepting the shorter
limit rather than maintaining two variants.

### `developer_message`

> Thor is built and maintained in the open by one developer, and it will always be free.
> If it saved you some time, a star on GitHub or a comment below genuinely helps.

### `store_issue_number: 279`

GitHub issue [#279](https://github.com/trinadhthatakula/Thor/issues/279),
"💬 Shizu Store — Feedback & Comments", created for this purpose. Its comments render natively
inside the store listing. The body routes bug reports, feature requests, and security issues to
the proper trackers so the thread stays usable as a comment feed. It should be pinned in the
repo's issue list.

## Copy changes

### The offline claim — replacement wording

`full_description.txt`, privacy section:

```
🔒 PRIVACY-FIRST & LIGHTWEIGHT
• No analytics, no crash reporters, no ads, no trackers — ever.
• The only network access is the optional Extensions store, which fetches its catalog and
  verified extension APKs over HTTPS with a pinned signer and SHA-256 check. Every other
  feature works fully offline.
• Open Source: Licensed under GNU GPL v3.0-or-later (libre software).
• Ultra-lightweight: the direct-download APK is just 3.24 MB.
```

The artifact is named explicitly because the same text feeds both this store and Play, and Play
delivers smaller per-device splits from the AAB. An unqualified "3.24 MB download" would be
wrong in one of the two places.

The opening paragraph's "without trackers, ads, or internet permissions" becomes "without
trackers, ads, or telemetry".

`short_description.txt` (en-US), 79 → 73 chars:

```
Freeze, debloat & install apps via Shizuku, Root & Dhizuku. Ad-free & FOSS
```

The hi-IN files get the equivalent corrections; its short description currently ends
"Offline & FOSS" and its privacy section says "100% ऑफलाइन: किसी इंटरनेट अनुमति की आवश्यकता नहीं है।".

### Size claims

Current text says "~2 MB download size, less than 4 MB installed". The measured `foss-release`
APK is **3.24 MB** (down from 6.23 MB in the v1.93.0 size work). The installed-size figure is
dropped rather than guessed — it varies by device and ABI, and no measurement backs the old
number. The README's "PlayStore Download Size (around 3.0 MB)" and "Smallest APK size (less than
6 MB)" get the same treatment.

### v1.93.0 feature refresh

Both descriptions and the README gain, drawn from `release-notes/v1.93.0/`:

- **Auto Reinstall** — sync and reinstall apps with custom install-time options.
- **Extension Manager** — an in-app store for optional, signature-verified extensions.
- **Redesigned Home** — an adaptive bento grid with one-tap Extension Manager.
- **Odin root engine** — the in-house Kotlin root shell, now open-sourced and published to
  Maven Central as `com.trinadhthatakula:odin`.

The "Technical Highlights" line "Shell Execution: Custom fork of libsu (suCore) fully converted
to Kotlin" is now wrong on two counts — the module was renamed and extracted — and becomes
"Shell Execution: Odin, an in-house Kotlin root-shell engine published on Maven Central".

### README dead links

Credits links to `github.com/trinadhthatakula/Thor/tree/master/suCore` and
`.../blob/master/suCore/README.md`. `:suCore` no longer exists on `master` **or** `dev` —
`settings.gradle.kts` includes only `:app`, `:bypass`, and `:vm-runtime`, because the module was
extracted to a standalone repository. Both links repoint to
`https://github.com/trinadhthatakula/Odin`.

## CI: `shizu-store-audit.yml`

Nothing in CI writes the manifest. Since `version_name` and `version_code` are omitted, the only
field that tracks a release is `changelog`, and a developer refreshing it during release prep —
in the same commit that bumps `versionCode` and adds `release-notes/v*/` — is both simpler and the
only thing the branch ruleset permits. CI's job is to *verify*, and to notice when something
outside the repository breaks.

### Trigger

```yaml
on:
  schedule:
    - cron: "0 6 * * 1"   # Mondays 06:00 UTC
  workflow_dispatch:
```

One job, `audit`, with `permissions: { contents: read, issues: write }` — it files a tracking
issue but never commits. `runs-on: ubuntu-latest`, following `production-deploy.yml`'s shape for
checkout and tooling setup, minus the Java, Ruby, and keystore steps it has no use for.

Scheduled workflows always run the copy of the file on the default branch, which is `master` and
where this file lives.

`workflow_dispatch` is included so the audit can be run on demand — after a release, or to confirm
a fix — without waiting for Monday.

### Job outline — `audit`

1. **Checkout** with the default `GITHUB_TOKEN`; read-only.
2. **Run `.github/scripts/check-shizu-manifest.sh --network`**, which performs every check in
   *Guarding against silent rot* below.
3. **On failure, open or update the tracking issue** labelled `shizu-store-audit`, with the script's
   output as the body. **On success, close it** if one is open.

### Release-time changelog refresh

`.github/scripts/sync-shizu-changelog.sh` is run by the developer, not by CI. It derives the
version from `gradle.properties` with an anchored `grep -E '^versionCode='` — the anchor matters,
because an unanchored `versionCode` also matches `initialVersionCode=1921`, which is exactly the
bug that made `release-manager.yml` unusable — resolves
`release-notes/v$VERSION/playstore.txt` with the same `v`-prefix fallback `production-deploy.yml`
uses, and rewrites the single `changelog` field with `jq --arg`. Using `jq` rather than `sed`
means a changelog containing quotes or newlines cannot corrupt the file.

Forgetting to run it is not a silent failure: PR CI recomputes the expected changelog and fails
with the command to fix it.

### Deleting `release-manager.yml`

The owner has never used it, and it could not have worked as written:

- `grep "versionCode" $PROPS_FILE | cut -d'=' -f2` matches both `initialVersionCode` and
  `versionCode`, yielding two lines, which then feed into `$((CURRENT_CODE + 1))` — an arithmetic
  syntax error.
- `grep "versionName"` matches only commented-out lines, since `versionName` is computed in
  Gradle rather than stored.

Deleting it removes a trap rather than merely removing dead weight.

## Guarding against silent rot

The store's response to a broken manifest is to say nothing: it ignores the file, falls back to
default GitHub data, and reports no error to anyone. Every failure mode below therefore degrades
the listing invisibly. Rot cannot be prevented — the manifest duplicates facts that live
elsewhere — so the goal is to convert each silent failure into a loud one.

| Rot | Detected by | Tier |
|-----|-------------|------|
| Screenshot, icon, or banner renamed or deleted | URL returns 404 | network |
| Repo renamed, or default branch renamed off `master` | every raw URL dies | network |
| Release deleted, or `foss-release.apk` renamed | `download_url` 404 | network |
| `full_description.txt` edited, manifest not | text comparison | local |
| Screenshot added to the directory, not to `screenshots` | directory comparison | local |
| `donate_url`, Telegram link, or issue #279 goes away | URL 404 | network |
| Upstream tightens the schema | vendored-schema diff | network |

### Ownership rule

> Every field is hand-written. CI writes nothing and fails the build when the manifest stops
> matching reality.

Facts CI could otherwise be tempted to own are instead not stated: the version fields are omitted
so the store reads them from the release, and `changelog` — the one field that must be refreshed —
is refreshed by a script the developer runs, then verified.

Deliberately, CI does **not** generate `detailed_description` from `full_description.txt`.
Generating it would eliminate the drift class, but replace it with a worse footgun: someone edits
the manifest, CI silently reverts them, and JSON offers nowhere to warn them — the schema's
`additionalProperties: false` rejects even a `_comment` key. Failing loudly is better than being
silently overwritten.

### Tier 1 — local checks, on every PR

A job in the existing `pr-ci.yml`, path-filtered to `shizu_store.json`, `fastlane/**`,
`release-notes/**`, `gradle.properties`, and the checker script itself. No network access, so it
runs in seconds:

1. Validate against the vendored schema.
2. `detailed_description` equals `full_description.txt`; `short_description` equals
   `short_description.txt`; the same for each locale that has a fastlane counterpart.
3. The `screenshots` array equals the actual files in `.../images/phoneScreenshots/`.
4. `changelog` equals `release-notes/v$VERSION/playstore.txt`, where `$VERSION` is computed from
   `gradle.properties`.
5. `min_sdk` and `target_sdk` equal the values in `gradle/libs.versions.toml`.
6. `version_name` and `version_code` are **absent**. They are omitted by design, and an assertion
   is what keeps a future edit from reintroducing a field nothing can keep current.

`release-notes/**` and `gradle.properties` are in the path filter because of check 4: a version
bump changes the expected changelog without touching the manifest, so the PR that bumps must be
the PR that fails.

This catches drift in the pull request that introduces it, which is the only cheap moment to fix
it.

### Tier 2 — weekly network audit

`shizu-store-audit.yml`, weekly. It runs the tier-1 checks plus:

1. `curl` every URL in the manifest and require a 200.
2. Follow `download_url` and confirm it still resolves to a real `foss-release.apk`.
3. Re-validate the manifest against the **live upstream schema**, not the vendored copy.
4. Diff `.github/shizu_store.schema.json` against upstream and flag any change.

This is the only tier that catches rot originating outside the repository, which is most of the
table above.

Steps 3 and 4 are deliberately both present, because they answer different questions. The diff
says *what* changed upstream; validating against the live schema says whether that change
actually breaks us. Running only the diff would mean reading every upstream schema edit to work
out whether it matters. Running only the live validation would mean a red build with no
explanation of what moved. Together they give a verdict and its cause.

This is also why the manifest is validated against two different schemas depending on tier: PR CI
uses the vendored copy so the check is deterministic and independent of third-party uptime, while
the weekly audit — which can afford network access and a flaky third party — asks the question
that actually matters, *would the store accept this file today*.

**On failure it opens a tracking issue** labelled `shizu-store-audit`, updating the existing open
one instead of filing duplicates, and closes it when checks pass again. A red scheduled run is
itself easy to miss — which would make the rot guard the very thing that rots silently.

### Vendoring the schema

`.github/shizu_store.schema.json` is a committed copy of the upstream schema. Validating against
the live URL would make CI depend on a third party's uptime, and — worse — would let the check
silently change meaning whenever upstream edits the schema. With a vendored copy, validation is
deterministic and schema changes surface as a reviewable diff from the weekly audit instead of as
a listing that quietly stopped working.

### Shared checker

Tiers 1 and 2 overlap almost entirely, so the logic lives in one place:
`.github/scripts/check-shizu-manifest.sh`, which takes a `--network` flag to enable the URL
checks. Both jobs call it. Duplicating these assertions across two workflows would create a third
thing that can drift.

## Translations

`locales` covers `ar`, `es`, `fr`, `hi`, `zh`; `en` is the top-level base. Each carries
`short_description`, `detailed_description`, and `developer_message`.

- **Hindi** is taken from the existing `fastlane/metadata/android/hi-IN/` files, with the offline
  and size claims corrected to match the English rewrite.
- **Arabic, Spanish, French, Chinese** are translated fresh, using the terminology already
  established in `app/src/main/res/values-{ar,es,fr,zh-rCN}/strings.xml` so the listing and the
  app agree on how "freeze", "debloat", "suspend", and "privilege mode" are rendered. Note the
  store's `zh` maps to the app's `values-zh-rCN`.

Each translation is produced and then checked by an independent pass, because a mistranslated
privacy claim is the same problem as an untrue English one.

## Verification

Before opening the PR:

Most of this is `check-shizu-manifest.sh --network`, run locally. Stated explicitly so the script
has a definition of done:

1. Validate `shizu_store.json` against the vendored schema — it must pass draft-07 validation,
   since `additionalProperties: false` means a single stray key voids the file. Confirm the
   vendored copy is identical to upstream at time of writing.
2. `curl -sI` every URL in the manifest — 12 image URLs, `repo_url`, `download_url`, `donate_url`,
   and both developer social links — and confirm each returns 200 (or 302 for `download_url`,
   which should resolve to the v1.93.0 `foss-release.apk`).
3. Confirm `download_url` resolves to the `foss` artifact, not `store` or `.aab`.
4. Confirm the cherry-picked `featureGraphic.png` is byte-identical to `dev`'s blob.
5. Check both `short_description` values are ≤ 80 characters.
6. Re-read the finished copy for any remaining "offline"/"no internet" claim in English or Hindi.
7. `actionlint` the new workflow. It is not installed locally; install it or accept that the first
   CI run is the syntax check.
8. Re-measure the published `foss-release.apk` from the v1.93.0 release rather than reusing the
   3.24 MB figure from the earlier size work, so the number in the copy describes the artifact
   users will actually download.
9. Confirm `sync-shizu-changelog.sh` leaves the manifest unchanged.

After the PR merges:

1. Merge `master` → `dev`.
2. Pin issue #279 and create the `shizu-store-audit` label.
3. Confirm the listing renders in the Shizu CoreFetch app — banner, screenshots, translations, and
   the #279 comment thread. **Check specifically whether a version number is shown**, since the
   manifest omits it and relies on the store reading the release.
4. Run the audit workflow once via `workflow_dispatch` rather than waiting for Monday.

## Sequencing

1. Branch `feat/shizu-store-manifest` from `master`.
2. Cherry-pick `e2d5b522` (the feature graphic) — it touches only that one file.
3. Rewrite the fastlane copy and the README.
4. Write `shizu_store.json`, embedding the finished English copy and the translations.
5. Vendor `.github/shizu_store.schema.json`.
6. Write `.github/scripts/check-shizu-manifest.sh` and run it locally until green — it *is* the
   bulk of the verification list, so writing it before the workflows means the checks are proven
   before any CI depends on them.
7. Write `.github/scripts/sync-shizu-changelog.sh` and confirm it is a no-op against the manifest
   written in step 4 — if it produces a diff, one of the two is wrong.
8. Add `shizu-store-audit.yml` and the `pr-ci.yml` check job; delete `release-manager.yml`.
9. Run the remaining manual verification; open the PR.
10. On merge: `master` → `dev`, pin #279, create the `shizu-store-audit` label (it does not exist
    yet, and `gh issue create --label` fails on an unknown label).

## Risks

The three rot risks originally identified here — the manifest degrading silently, schema
violations failing invisibly, and fastlane/manifest drift — are addressed by the tiered checks in
*Guarding against silent rot* above. What remains:

**The version fields are assumed to have a fallback.** Omitting `version_name` and `version_code`
rests on the store deriving them from the GitHub release, which the documentation implies — apps
are listed "automatically via your GitHub repository data", with the manifest overriding those
defaults — but never states for these two fields specifically. If instead the store renders an
empty version, the listing looks unfinished. The cost of being wrong is cosmetic and the fix is
one commit; the cost of the alternative is a number that goes stale on a branch no automation can
write. The first render in the store settles it, and it is on the post-merge checklist.

**Detection lags by up to a week.** External rot — a deleted release, a dead donate link — is
caught by the weekly audit, so a listing can be broken for seven days before anyone hears. Daily
runs were considered and rejected: the added flake risk from transient network failures outweighs
shortening an already-tolerable window for a store listing.

**The audit can only check what it can reach.** It verifies that URLs resolve and that the
manifest is internally consistent. It cannot verify that Shizu CoreFetch actually accepted the
file, because the store exposes no API for that. The first render in the app is a manual check,
and afterwards we are inferring health from inputs rather than observing it.

**Translations are unverifiable by CI.** The checks confirm each locale's text exists and matches
its fastlane counterpart where one exists, but nothing detects a translation that is present,
well-formed, and wrong. This is why each is produced and then reviewed by an independent pass — a
mistranslated privacy claim is the same problem as an untrue English one.

**The vendored schema is a snapshot.** It can fall out of date with upstream, and the two
directions of drift are not equally dangerous:

| Upstream change | Our copy becomes | Consequence |
|---|---|---|
| **Loosens** — new optional field, higher `maxLength`, dropped constraint | stricter than reality | CI rejects manifests the store would accept. We cannot use the new field until someone updates the copy. Red build, working listing. **Fails closed.** |
| **Tightens** — new required field, lower limit, narrower pattern | more permissive than reality | CI passes a manifest the store rejects. The store falls back to bare GitHub defaults — banner, translations, screenshots and comment thread all vanish. Green build, broken listing. **Fails open.** |

A concrete version of the second row, using this manifest: `tags` has `maxItems: 15` and we ship
exactly 15. If Shizu lowers that to 10, our vendored copy still calls 15 legal, CI passes, and the
store discards the entire file. Nothing anywhere reports an error.

This is why the audit's schema diff is load-bearing rather than housekeeping: **local validation
cannot detect the second case by construction.** It amounts to asking a stale reference whether it
is stale, and a self-consistent reference always answers no. Comparing the vendored copy against
upstream is the only check whose answer does not come from the thing that is out of date.

The audit therefore does both: it validates the manifest against the live upstream schema, which
answers whether we are currently rejected, and diffs the vendored copy, which explains why. The
diff alone would leave a human to classify every upstream edit; the live check alone would report
a failure with no indication of what moved.

Residual risk: this narrows the tightening window to at most a week rather than closing it. A
schema change landing the day after an audit run leaves the listing silently degraded until the
next Monday. Accepted for the same reason as the other cadence trade-offs — a store listing does
not warrant daily runs against a third-party endpoint.
