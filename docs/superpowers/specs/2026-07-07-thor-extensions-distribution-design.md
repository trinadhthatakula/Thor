# Thor-Extensions — Distribution, Signing & In-App Browser — Design Spec

**Date:** 2026-07-07
**Status:** Design (pending user review)
**Related:** Spec A — [Strombringer](2026-07-07-strombringer-xposed-extension-design.md).

## 1. Goal

Turn the bare `Thor-Extensions` repo into the **distribution rail** for Thor extensions: source hosting (verified/unverified), a **dedicated signing key**, **GitHub-Actions-built signed release APKs**, a machine-readable **`catalog/extensions.json`**, and an **in-app browser** in Thor that lists + one-tap-installs verified extensions. Third-party developers can submit extensions.

## 2. The dedicated "Thor Extensions" signing key (trust anchor)

- Generate a **new keystore, separate from both Thor app keys** (its own alias/passwords). This is the single trust anchor: **all `verified/` extensions are signed with it**, and Thor pins **its cert SHA-256** in the allowlist (Spec A §4.1).
- Store it in **Thor-Extensions GitHub secrets** (`EXT_KEYSTORE_BASE64`, `EXT_KEY_ALIAS`, `EXT_KEY_PASSWORD`, `EXT_KEYSTORE_PASSWORD`). CI decodes the base64 keystore to a file at build time.
- The cert SHA-256 is recorded in `docs/` here and hard-coded in Thor. Rotating it is a coordinated change (new Thor release adds the new hash to the allowlist before extensions switch keys).
- **Blast radius:** compromise of this key ≠ compromise of the Thor app-release key; the two roles stay separate.

## 3. Repo structure

```
Thor-Extensions/
├── README.md               # what extensions are, install/safety, LSPosed note for Strombringer
├── CONTRIBUTING.md         # third-party submission flow
├── catalog/extensions.json # machine-readable index Thor fetches
├── scripts/build-changed.sh# CI helper: build+release only version-bumped verified exts
├── .github/workflows/release.yml
├── verified/               # SOURCE of first-party / vetted extensions
│   ├── thor-automation-extension/   # add a release signingConfig (currently has none)
│   └── strombringer/                # Spec A
└── unverified/             # SOURCE of third-party submissions (pre-vetting), source-only
```

Each `verified/<ext>/` is a standalone Gradle project that (a) declares a `version` (e.g. in `gradle.properties`), (b) has a **`release` `signingConfig`** reading the dedicated key from env (mirrors Thor's `app/build.gradle.kts:93-106` pattern — the automation-extension currently has **no** signing config and must gain one), and (c) `compileOnly`s `thor-extension-api` (+ Asgard, + Xposed API for Strombringer).

## 4. CI (`release.yml`) — version-bump-triggered, like Asgard/extension-api

- **Trigger:** push to `main` touching `verified/**`.
- **Detect:** `scripts/build-changed.sh` walks each `verified/<ext>/`, reads its `version`, and skips any where a release tag `<ext-id>-v<version>` already exists (idempotent — bumping one extension doesn't rebuild all).
- **Build + sign:** JDK 21; `./gradlew :app:assembleRelease` with the decoded dedicated keystore → a signed APK.
- **Release:** create GitHub Release `<ext-id>-v<version>`, attach `<ext-id>-<version>.apk`.
- **Catalog:** rewrite that extension's entry in `catalog/extensions.json` (version, `apkUrl` = the release asset URL, `sha256` of the APK) and **commit it back** to `main` (`[skip ci]`).

Result: bump a verified extension's version → CI builds, signs, releases, and updates the catalog, hands-free.

## 5. `catalog/extensions.json` schema

```json
{
  "schemaVersion": 1,
  "extensions": [
    {
      "id": "com.valhalla.strombringer",
      "name": "Strombringer",
      "description": "Auto-unfreeze suspended apps; danger-zone installer (sig-bypass, downgrade); installer spoof.",
      "author": "Thor",
      "version": "1.0.0",
      "verified": true,
      "requiresLSPosed": true,
      "minThorVersionCode": 1910,
      "minSdk": 29,
      "apkUrl": "https://github.com/trinadhthatakula/Thor-Extensions/releases/download/com.valhalla.strombringer-v1.0.0/strombringer-1.0.0.apk",
      "sha256": "…",
      "sourcePath": "verified/strombringer"
    }
  ]
}
```
`unverified` entries may appear with `"verified": false` and **no `apkUrl`** (source-only) — the browser shows them as "source only, build yourself."

## 6. In-app extension browser (Thor `:app`, foss now has INTERNET)

- New "Extensions → Browse" screen: fetch `catalog/extensions.json` (raw GitHub URL), render the list (name, version, verified badge, `requiresLSPosed` hint, size).
- **Install:** download the APK, **verify its SHA-256 against the catalog** (integrity layer), then hand off to Thor's existing installer (`PortableInstaller`). After install, `ExtensionManager` gates loading on the **cert allowlist** (Spec A §4.1) — so download-integrity *and* signer-authenticity are both enforced.
- **Strombringer** entry shows a "Requires LSPosed — activate after install" note; Thor detects the active flag (Spec A §5.2) and surfaces the danger-zone config once active.
- Offline/failure: graceful empty/error state; the screen is opt-in (not on a hot path).

## 7. Third-party submission flow (`CONTRIBUTING.md`)

1. Dev opens a PR adding `unverified/<their-ext>/` (source only).
2. Maintainer reviews source (safety, policy, no malware).
3. On approval: move to `verified/`, add/confirm the **release `signingConfig`**, bump version → CI signs with the dedicated key + releases + catalogs it. The extension is now signed by the dedicated Thor Extensions key → loadable.
4. Until then it stays `unverified/` (source-only; a user can build+self-sign, but a stock Thor won't load a non-allowlisted signature — the future untrusted-trust opt-in will relax this).

## 8. Security
- **Authenticity:** cert allowlist (Spec A §4.1) — only dedicated-key-signed extensions load.
- **Integrity:** catalog `sha256` verified on download.
- **Transport:** HTTPS raw-GitHub for the catalog + release assets.
- Dedicated key isolated from app-release keys; lives only in Thor-Extensions CI secrets.

## 9. Testing
- `scripts/build-changed.sh`: unit/dry-run — detects exactly the version-bumped extensions; idempotent when tags exist.
- CI on a throwaway version bump: produces a signed APK whose cert SHA-256 == the pinned allowlist value; catalog entry rewritten with the correct `apkUrl` + `sha256`.
- In Thor: browse → download → SHA-256 mismatch is rejected; a good APK installs and `ExtensionManager` loads it; an APK signed by a non-dedicated key is rejected.

## 10. Build order & out of scope
- **Order:** (1) dedicated key + pin its hash in Thor (unblocks Spec A trust); (2) add release `signingConfig` to the automation-extension + `release.yml` + `build-changed.sh`; (3) `catalog/extensions.json` + README/CONTRIBUTING; (4) in-app browser.
- **Out of scope:** the untrusted/third-party-cert opt-in trust UI (future); paid/licensed extensions; delta updates.
