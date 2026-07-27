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

One PR to `master` containing eight changes, then a `master` → `dev` merge.

| # | Change | Rationale |
|---|--------|-----------|
| 1 | Add `shizu_store.json` at repo root | The deliverable |
| 2 | Add `.github/workflows/shizu-store-sync.yml` | Keep the manifest fresh after each release |
| 3 | Delete `.github/workflows/release-manager.yml` | Unused and broken (see below) |
| 4 | Copy `featureGraphic.png` from `dev` to `master` | Store banner should be the current one |
| 5 | Rewrite `fastlane/.../en-US/full_description.txt` | v1.93.0 features + offline fix + size fix |
| 6 | Rewrite `fastlane/.../en-US/short_description.txt` | Contains "100% offline" |
| 7 | Rewrite `fastlane/.../hi-IN/{full,short}_description.txt` | Same claims in Hindi |
| 8 | Update `README.md` | Offline fix, size fix, v1.93.0 features, 2 dead links |

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
  workflow_dispatch:
```

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

### Job outline

Modelled on `production-deploy.yml`: `runs-on: ubuntu-latest`, `permissions: contents: write`,
and a `concurrency` group so two syncs cannot race on the same file.

Guard:

```yaml
if: >-
  github.event_name == 'workflow_dispatch' ||
  (github.event.workflow_run.conclusion == 'success' &&
   github.event.workflow_run.head_branch == 'production')
```

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

1. Validate `shizu_store.json` against `schema.json` — it must pass draft-07 validation, since
   `additionalProperties: false` means a single stray key voids the file.
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
5. Add `shizu-store-sync.yml`; delete `release-manager.yml`.
6. Run the verification list; open the PR.
7. On merge: `master` → `dev`, pin #279.

## Risks

**The manifest can rot silently.** Everything except the three CI-managed fields is
hand-maintained. If a screenshot is renamed or the feature graphic moves, the listing degrades
with no error anywhere — the store just falls back or shows a broken image. Accepted: the
alternative, generating the whole manifest in CI, is far more machinery than one file justifies.

**A schema violation fails invisibly.** The store ignores an invalid manifest and falls back to
default GitHub data rather than reporting an error. This is why validation is a pre-PR gate and
why the CI job validates after every rewrite.

**Fastlane and the manifest can drift.** `detailed_description` is a copy of
`full_description.txt`, not a reference to it. If someone edits one, the other goes stale. Left
as a known limitation rather than solved with generation.
