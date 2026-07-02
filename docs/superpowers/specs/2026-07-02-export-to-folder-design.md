# Export App to Folder — Design

**Date:** 2026-07-02
**Issue:** #164 — ".APK, .XAPK, .APKS and Split bundle export?"
**Branch:** `feat/export-to-folder`

## Goal

Let the user **export an installed app to a user-visible folder** (not just Share
it), via a simple bottom sheet: a default **`Downloads/Thor`** target with a
**"Change"** option to pick any folder (Storage Access Framework), remembered
across exports.

## Constraints (why this design)

- Thor targets **SDK 37** (minSdk 28) → **scoped storage** is fully enforced.
  Legacy external writes are a no-op on API 30+, and **`MANAGE_EXTERNAL_STORAGE`
  is Play-policy-restricted** — so it is **not used**.
- Thor currently declares **no storage permissions** (only a `FileProvider`).
- The bundle-building already exists in `ShareAppUseCase` and is reused, not
  duplicated.

## Decisions

1. **Format mirrors Share:** an app with **no splits → `.apk`**; an app **with
   splits → `.apks`** (base + splits + `metadata.json` + `manifest.json`). No
   format picker.
2. **Two write paths, no risky permission:**
   - Default **`Downloads/Thor`** via `MediaStore.Downloads`
     (`RELATIVE_PATH = "Download/Thor"`) on API 29+; on **API 28** a legacy write
     behind `WRITE_EXTERNAL_STORAGE android:maxSdkVersion="28"`.
   - Custom folder via **SAF** (`DocumentFile`), the tree URI persisted in DataStore.
3. **Single-app export** for v1 (from the App Info sheet). Batch export
   (mirroring the existing multi-select `ShareApps`) is a **follow-up**, out of scope.

## Design

### 1. Reuse — extract the bundle builder (DRY)
`ShareAppUseCase` currently builds the shareable file in cache (gather base +
splits, `copyFileSafely` with a **root fallback**, generate
`metadata.json`/`manifest.json` via `ApksMetadataGenerator`, zip splits into
`.apks`). Extract that into a shared:

- **`AppBundleBuilder`** (`data/…`): `suspend fun build(appInfo: AppInfo): Result<File>`
  → a cache file (`.apk` for no-split, `.apks` for split). Behavior identical to
  today's Share output.

Then:
- `ShareAppUseCase` → `build()` → `FileProvider.getUriForFile(...)` (unchanged behavior).
- `ExportAppUseCase` (new) → `build()` → write the cache file to the resolved target.

### 2. Target resolution + persistence
- **DataStore:** add `exportDirUri: String? = null` to `UserPreferences`;
  `PreferenceRepository.setExportDirUri(uri: String?)` (null clears it).
- **`ExportTargetResolver`** (new; testable): given the saved `exportDirUri`,
  returns the write target:
  - saved URI present **and** valid (`DocumentFile.fromTreeUri(ctx, uri).exists() && canWrite()`)
    → **SAF target** (the tree `DocumentFile`).
  - otherwise → **Downloads/Thor default** (and, if the pref was set-but-invalid,
    signal the caller to clear it so the sheet reverts to the default label).
- **Display name** for the sheet: `"Downloads/Thor"` for the default; for a saved
  folder, the tree `DocumentFile.name` (or last path segment).

### 3. Writers
`ExportAppUseCase` writes the built cache file to the resolved target:
- **`MediaStoreExporter`** (default): API 29+ inserts into
  `MediaStore.Downloads.EXTERNAL_CONTENT_URI` with `DISPLAY_NAME`, MIME
  `application/vnd.android.package-archive` (`.apk`) or `application/octet-stream`
  (`.apks`), and `RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/Thor"`, using
  `IS_PENDING` during the copy. **API 28**: write to
  `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)/Thor/<name>`
  (guarded by the `maxSdkVersion="28"` permission).
- **SAF writer**: `treeDoc.createFile(mime, name)` → `contentResolver.openOutputStream(doc.uri)`
  → stream the cache file in. If a same-named file exists, overwrite (delete-then-create).
- Returns a human-readable location string for the confirmation ("Saved to Downloads/Thor" / the folder name).

### 4. UI — the Export bottom sheet
- New `AppClickAction.Export(appInfo)` (sibling of `ShareApp`), wired from the App
  Info sheet's actions. Handling it opens the **Export bottom sheet**.
- Sheet contents (Compose `ModalBottomSheet`):
  - Title + the app being exported (name/icon).
  - **Target row:** the effective target label (`Downloads/Thor` or saved folder),
    with a **"Change"** action → `rememberLauncherForActivityResult(OpenDocumentTree)`
    → on result `takePersistableUriPermission(READ|WRITE)` + `setExportDirUri(uri)`;
    the label updates.
  - **Export** button → runs `ExportAppUseCase` (off-main; a progress state) →
    on success dismiss + a "Saved to …" snackbar/toast; on failure an error message.

### 5. Manifest
```xml
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
```
(Only requested on API 28, for the legacy Downloads write; not present on API 29+.)

## Error handling / edge cases
- **Saved folder deleted / permission lost:** resolver detects invalid tree →
  clears `exportDirUri` → falls back to `Downloads/Thor`; the sheet shows the
  default again.
- **Protected/system apps:** APK copy uses the existing `copyFileSafely` root
  fallback in the builder; degrades without root (consistent with Share).
- **Write failure** (no space, revoked SAF grant mid-write): surfaced as an error
  message; no partial `IS_PENDING`/temp file left dangling (finalize/delete on failure).
- **Name collisions:** overwrite the same-named export.
- **API 28 without `WRITE_EXTERNAL_STORAGE` granted:** request it at export time;
  if denied, surface an error (SAF path still works as an alternative).

## Testing
- **Unit test `ExportTargetResolver`** with a mockable validity check: saved-valid
  → SAF target; saved-invalid → default + clear signal; none → default.
- **Behavior-preserving check:** Share still produces the same `.apk`/`.apks`
  after the `AppBundleBuilder` extraction (compile + the existing Share flow).
- **Manual:** export a single-APK app (→ `.apk`) and a split app (→ `.apks`) to
  Downloads/Thor; pick a custom folder via Change and export there; delete the
  custom folder and confirm fallback to Downloads/Thor; verify on a rooted device
  a system/protected app exports via the root copy; API 28 legacy path if available.

## Out of scope (follow-ups)
- Batch/multi-select export (mirror `ShareApps`).
- Additional formats (real `.xapk`, raw split folder) — a later format selector.
- Exporting app **data** (that's #51's root-gated territory).

## Files touched (indicative)
- Extract: `data/…/AppBundleBuilder.kt` (from `ShareAppUseCase`); modify `ShareAppUseCase` to use it.
- New: `ExportAppUseCase`, `ExportTargetResolver`, `MediaStoreExporter` (+ SAF write).
- `domain/model/UserPreferences.kt` (+`exportDirUri`), `PreferenceRepository` (+ setter) + impl.
- `AndroidManifest.xml` (+`WRITE_EXTERNAL_STORAGE maxSdkVersion=28`).
- `domain/model/AppClickAction.kt` (+`Export`), the App Info sheet action wiring, and the new Export bottom-sheet composable + its state.
- test: `ExportTargetResolverTest`.
