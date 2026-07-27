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

One PR to `master` containing eleven changes, then a `master` → `dev` merge.

| # | Change | Rationale |
|---|--------|-----------|
| 1 | Add `shizu_store.json` at repo root | The deliverable |
| 2 | Add `.github/workflows/shizu-store-sync.yml` | Keep the manifest fresh; audit it weekly |
| 3 | Delete `.github/workflows/release-manager.yml` | Unused and broken (see below) |
| 4 | Copy `featureGraphic.png` from `dev` to `master` | Store banner should be the current one |
| 5 | Rewrite `fastlane/.../en-US/full_description.txt` | v1.93.0 features + offline fix + size fix |
| 6 | Rewrite `fastlane/.../en-US/short_description.txt` | Contains "100% offline" |
| 7 | Rewrite `fastlane/.../hi-IN/{full,short}_description.txt` | Same claims in Hindi |
| 8 | Update `README.md` | Offline fix, size fix, v1.93.0 features, 2 dead links |
| 9 | Add `.github/shizu_store.schema.json` | Vendored schema, for deterministic validation |
| 10 | Add `.github/scripts/check-shizu-manifest.sh` | Shared checker for PR CI and the weekly audit |
| 11 | Add a manifest-check job to `.github/workflows/pr-ci.yml` | Catch drift in the PR that causes it |

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
  "version_name": "1.93.0",
  "version_code": 1930,
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

**`download_url` → `releases/latest/download/foss-release.apk`.** GitHub 302-redirects this to the
newest release's asset, so it never goes stale, and it still ends in `.apk` as the schema's
`^https?://.*\.apk$` pattern demands. Omitting the field entirely would let the store guess from
release assets and possibly pick the `.aab` or the Play-Billing `store-release.apk`.

**Images pinned to `/master/`, not to a tag.** They track the default branch, so refreshing a
screenshot on `master` updates the listing with no manifest edit.

**`changelog` is top-level only, never per-locale.** The schema permits `changelog` inside
`locales`, but translated changelogs would have to be regenerated every release or silently rot
into showing stale notes to non-English users. One field, one source of truth, one thing for CI
to update.

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

## CI: `shizu-store-sync.yml`

### Trigger

```yaml
on:
  workflow_run:
    workflows: ["2. Production Build & Distribute"]
    types: [completed]
  schedule:
    - cron: "0 6 * * 1"   # weekly audit, Mondays 06:00 UTC
  workflow_dispatch:
```

The file holds two jobs: `sync`, which runs after a release, and `audit`, which runs weekly.
Each guards on `github.event_name` so the scheduled run never rewrites the manifest and the
release run never files an audit issue.

Chaining off the release workflow rather than mirroring its `push: production` trigger is a
correctness requirement, not a preference:

1. **A push trigger would advertise a version that does not exist yet.** Both workflows would
   start at once, but `production-deploy.yml` takes roughly fifteen minutes to build, sign, and
   upload. During that window the manifest would name the new version while
   `releases/latest/download/…` still served the previous APK — store users would be offered an
   "update" that installs the older build.
2. **`on: release: [published]` would never fire at all.** `production-deploy.yml` creates the
   release with `secrets.GITHUB_TOKEN`, and GitHub deliberately does not start new workflow runs
   from events authored by that token. `workflow_run` is explicitly exempt from that rule.

`workflow_run` workflows always execute the copy of the file on the default branch. Since the
default branch is `master` and that is where this file lives, that behaviour is what we want.

### Job outline — `sync`

Modelled on `production-deploy.yml`: `runs-on: ubuntu-latest`, `permissions: contents: write`,
and a `concurrency` group so two syncs cannot race on the same file.

Guard:

```yaml
if: >-
  github.event_name == 'workflow_dispatch' ||
  (github.event.workflow_run.conclusion == 'success' &&
   github.event.workflow_run.head_branch == 'production')
```

A `schedule` run satisfies neither clause, so the weekly audit cannot rewrite the manifest — the
guard already expresses that without a separate exclusion. The `audit` job takes the complementary
guard (`schedule` or `workflow_dispatch`) and needs `issues: write` rather than `contents: write`,
since it files a tracking issue but never commits.

Steps:

1. **Checkout `master`** with `token: ${{ secrets.PAT_TOKEN || secrets.GITHUB_TOKEN }}`.
2. **Derive the version** from `master`'s `gradle.properties`, using an anchored
   `grep -E '^versionCode='`. The anchor matters: an unanchored `versionCode` also matches
   `initialVersionCode=1921`, which is exactly the bug that made `release-manager.yml` unusable.
   Then `MAJOR=$((C/1000))`, `MINOR=$(((C%1000)/10))`, `PATCH=$((C%10))`.
3. **Verify the release exists** — `gh release view "v$VERSION"` — and fail loudly if it does not,
   rather than publishing a manifest pointing at a missing APK.
4. **Resolve the changelog** from `release-notes/v$VERSION/playstore.txt`, falling back to
   `release-notes/$VERSION/playstore.txt` the way `production-deploy.yml` does.
5. **Rewrite three fields** with `jq --arg`: `version_name`, `version_code`, `changelog`.
   Everything else in the manifest is hand-maintained. `jq` handles JSON escaping, so a changelog
   containing quotes or newlines cannot corrupt the file.
6. **Validate** — `jq empty` for syntax, plus an assertion that the four required fields
   (`app_name`, `package_name`, `short_description`, `icon_url`) are present and non-empty.
   An invalid manifest is silently ignored by the store, so a broken file would fail invisibly.
7. **Commit and push to `master`** only if `git diff --quiet` reports a change, with `[skip ci]`
   in the message.

`[skip ci]` is required, not cosmetic: `dev-check.yml` triggers on `push` to `master`, so without
it every sync would kick off an unnecessary build.

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

> CI owns `version_name`, `version_code`, and `changelog`. Everything else is hand-written, and
> CI fails the build when it stops matching reality.

Deliberately, CI does **not** generate `detailed_description` from `full_description.txt`.
Generating it would eliminate the drift class, but replace it with a worse footgun: someone edits
the manifest, CI silently reverts them, and JSON offers nowhere to warn them — the schema's
`additionalProperties: false` rejects even a `_comment` key. Failing loudly is better than being
silently overwritten.

### Tier 1 — local checks, on every PR

A job in the existing `pr-ci.yml`, path-filtered to `shizu_store.json`, `fastlane/**`, and
`gradle.properties`. No network access, so it runs in seconds:

1. Validate against the vendored schema.
2. `detailed_description` equals `full_description.txt`; `short_description` equals
   `short_description.txt`; the same for each locale that has a fastlane counterpart.
3. The `screenshots` array equals the actual files in `.../images/phoneScreenshots/`.
4. `version_code` equals `gradle.properties`, and `version_name` is its computed form.

This catches drift in the pull request that introduces it, which is the only cheap moment to fix
it.

### Tier 2 — weekly network audit

A second job in `shizu-store-sync.yml` under a `schedule:` trigger — weekly, keeping everything
in one workflow file rather than adding another. It runs the tier-1 checks plus:

1. `curl` every URL in the manifest and require a 200.
2. Follow `download_url` and confirm it still resolves to a real `foss-release.apk`.
3. Diff `.github/shizu_store.schema.json` against upstream and flag any change.

This is the only tier that catches rot originating outside the repository, which is most of the
table above.

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
7. `actionlint` the new workflow.
8. Re-measure the published `foss-release.apk` from the v1.93.0 release rather than reusing the
   3.24 MB figure from the earlier size work, so the number in the copy describes the artifact
   users will actually download.

After the PR merges:

1. Merge `master` → `dev`.
2. Pin issue #279.
3. Confirm the listing renders in the Shizu CoreFetch app.

## Sequencing

1. Branch `feat/shizu-store-manifest` from `master`.
2. Cherry-pick `e2d5b522` (the feature graphic) — it touches only that one file.
3. Rewrite the fastlane copy and the README.
4. Write `shizu_store.json`, embedding the finished English copy and the translations.
5. Vendor `.github/shizu_store.schema.json`.
6. Write `.github/scripts/check-shizu-manifest.sh` and run it locally until green — it *is* the
   bulk of the verification list, so writing it before the workflows means the checks are proven
   before any CI depends on them.
7. Add `shizu-store-sync.yml` (sync + weekly audit jobs) and the `pr-ci.yml` check job; delete
   `release-manager.yml`.
8. Run the remaining manual verification; open the PR.
9. On merge: `master` → `dev`, pin #279.

## Risks

The three rot risks originally identified here — the manifest degrading silently, schema
violations failing invisibly, and fastlane/manifest drift — are addressed by the tiered checks in
*Guarding against silent rot* above. What remains:

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

**The vendored schema is a snapshot.** If upstream loosens the schema, our copy stays stricter
than necessary and we lose access to new fields until someone notices the audit's diff. That is
the acceptable direction to fail in; the reverse — upstream tightening while we validate against
a stale permissive copy — is exactly what the diff exists to catch.
