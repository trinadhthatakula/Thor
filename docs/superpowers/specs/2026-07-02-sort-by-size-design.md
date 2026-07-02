# Sort Apps by Size — Design

**Date:** 2026-07-02
**Issue:** #57 — "Sort by Size Option for Installed Apps"
**Branch:** `feat/sort-by-size`

## Goal

Add a **"Size"** option to the installed-apps sort menu, ordering apps by their
**total install size** — app code + user data + cache — with a persisted,
stale-while-revalidate cache and a first-class Usage Access permission flow.

## Decisions

1. **Metric = total install size** (`appBytes + dataBytes + cacheBytes`) via
   `StorageStatsManager.queryStatsForPackage()`.
2. **Persisted + stale-while-revalidate.** Sizes are cached in Room and reused on
   the next launch (shown instantly), then recomputed in the background.
3. **Permission (`GET_USAGE_STATS`) = best-effort silent grant, always verified,
   dialog fallback.**
   - When Thor **first gains a privilege**, it silently attempts to grant itself
     the app-op via the active gateway (**shell first**: `appops set … GET_USAGE_STATS allow`).
   - The silent path may be impossible (e.g. Android 16+ tightening) — so we
     **never assume it worked**: `isGranted()` is re-verified at point of use, and
     if false we show a dialog that deep-links to Usage Access settings.
4. **Settings → new "Permissions" section** exposes the Usage Access state
   persistently (Granted, or a control that opens settings).

## Design

### 1. Domain
- **`SortBy`** (`domain/model/SortBy.kt`): uncomment `SIZE` (enum +
  `asGeneralName()` → `"Size"`). The sort menu already renders `SortBy.entries`.
- **`AppInfo`** (`domain/model/AppInfo.kt`): add `val installSize: Long? = null`
  (bytes; `null` = unknown / not yet computed).

### 2. Persistence — Room cache (stale-while-revalidate)
- **`AppEntity`**: add `val installSize: Long? = null` column; map it in
  `fromDomain` / `toAppInfo`.
- **`AppDatabase`**: bump `version` 4 → 5 and add `AutoMigration(from = 4, to = 5)`
  (adding a nullable column is a valid auto-migration; schema export is on).
- **`AppDao`**: add `suspend fun updateInstallSizes(sizes: Map<String, Long>)` (or
  a batch update) to write freshly-computed sizes back into the `apps` table.
- Behavior: the cached `installSize` is served immediately on next launch; a
  background recompute (below) refreshes it. New apps with no cached size sort as
  unknown until the background pass fills them.

### 3. Storage-stats source (new)
- **`StorageStatsHelper`** (`data/…`):
  `suspend fun installSizes(packages: List<String>): Map<String, Long>` using
  `getSystemService(StorageStatsManager::class.java).queryStatsForPackage(app.storageUuid, pkg, Process.myUserHandle())`
  and summing `appBytes + dataBytes + cacheBytes`. On `Dispatchers.IO`; a
  per-package failure is skipped (stays unknown), never throws.

### 4. Usage-access permission (new)
- **Manifest:** add `<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions"/>`.
- **`UsageAccessManager`** (new; or `SystemRepository` methods):
  - `fun isGranted(): Boolean` — `AppOpsManager` `unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS, myUid, packageName) == MODE_ALLOWED` (API 28 fallback: `checkOpNoThrow`).
  - `suspend fun tryGrantViaPrivilege(): Boolean` — if a privileged gateway is
    active, `executeShellCommand("appops set com.valhalla.thor GET_USAGE_STATS allow")`
    (shell first), then re-check `isGranted()`. Returns the verified result.
  - `fun usageAccessIntent(): Intent` — `ACTION_USAGE_ACCESS_SETTINGS` with
    `data = Uri.fromParts("package", packageName, null)` (best-effort per-app
    deep-link), falling back to the plain action on failure.
- **Proactive silent grant (PrivilegeManager hook):** observe
  `PrivilegeManager.state`; the first time `hasAnyPrivilege` becomes true and
  Usage Access is not granted, call `tryGrantViaPrivilege()` **once** (guarded by a
  DataStore flag so we don't retry every launch). Best-effort — failure is fine.

### 5. Settings → "Permissions" section (new UI)
- New section in `SettingsScreen` (alongside Appearance / Security / About) with a
  **Usage Access** row:
  - **Granted:** static "Granted ✓" (the app can't revoke it).
  - **Not granted:** a `Switch`/action that opens `usageAccessIntent()`.
  - The row reflects the **real system state**, re-checked on `ON_RESUME` (so
    returning from settings updates it live).
- **Caveat (documented in-code):** the per-app deep-link is best-effort and
  OEM-dependent — some devices land on the Usage Access list instead of Thor's row.

### 6. Sort trigger flow (`presentation/appList/AppListViewModel`)
When the sort is `SortBy.SIZE` (on selection, or a refresh while size-sort is active):
1. Sort **immediately** using whatever cached `installSize` values exist (from Room).
2. If `!isGranted()` → `tryGrantViaPrivilege()`. Still not granted → emit a one-shot
   `NeedsUsageAccess` UI event; stop (order stays on the cached/unknown result).
3. Granted → set `isComputingSizes`, call `StorageStatsHelper.installSizes(currentPackages)`
   off-main, update `_rawState` (`AppInfo.installSize`) **and** persist via
   `AppDao.updateInstallSizes(...)`, clear the flag → `processList()` re-sorts.

### 7. Sort comparator
- `getSortedList()`: add `SortBy.SIZE -> compareBy(nullsFirst()) { it.installSize }`
  (unknown sizes sort smallest; the existing DESC toggle → largest first).

### 8. App-list UI
- Sort menu: no change (auto-populates).
- `isComputingSizes` → existing loading affordance (only noticeable on the first,
  uncached computation).
- `NeedsUsageAccess` event → dialog: "Thor needs Usage Access to read app sizes",
  with a button to `usageAccessIntent()` (and a pointer to Settings → Permissions).
- **Optional (recommended):** show `Formatter.formatShortFileSize(context, installSize)`
  in the App Info details sheet. Compact list rows unchanged (density).

## Out of scope
- Per-app breakdown (code vs data vs cache) — one total suffices for sorting.
- A "size computed at" timestamp (can be added later if we want to surface freshness).
- Total-vs-APK-size toggle (install size is the chosen metric).

## Error handling / edge cases
- `StorageStatsManager` failure for a package → `installSize` stays whatever was
  cached (or `null`); sorts as smallest, no crash.
- Silent grant fails (Android 16+, Dhizuku, no privilege) → verified `isGranted()`
  is false → dialog / Settings path; the rest of the app is unaffected.
- Permission revoked externally → next `isGranted()` check re-triggers the flow;
  cached sizes remain until a successful recompute.
- Cold cache / new install → size unknown until first successful compute.

## Testing
- **Unit test** the comparator: `getSortedList` orders a fixed `List<AppInfo>` by
  `installSize` ascending/descending with `null` first. (StorageStats query,
  app-op grant, and the Settings deep-link are device/permission-dependent →
  manual verification.)
- **Manual:** root & Shizuku → first launch after privilege → confirm silent grant
  (where OS allows) → "Size" sorts correctly, sizes match the App Info sheet, and
  values persist across a relaunch (instant on next open). Android 16+/Dhizuku/no
  privilege → confirm the dialog + Settings → Permissions flow and live re-check
  on resume. Toggle asc/desc; verify a clean loading state on the first (uncached)
  compute of a large list.

## Files touched
- `domain/model/SortBy.kt`, `domain/model/AppInfo.kt`
- `data/source/local/room/AppEntity.kt`, `AppDatabase.kt` (v5 + AutoMigration), `AppDao.kt`
- `data/…/StorageStatsHelper.kt` (new)
- usage-access check/grant (new `UsageAccessManager` and/or `SystemRepository`/gateway) + the PrivilegeManager hook
- `AndroidManifest.xml` (+`PACKAGE_USAGE_STATS`)
- `presentation/appList/AppListViewModel.kt` (trigger flow, loading, event, comparator)
- `presentation/appList/AppListScreen.kt` / widgets (loading + `NeedsUsageAccess` dialog)
- `presentation/settings/SettingsScreen.kt` (+ ViewModel) — new **Permissions** section
- *(optional)* App Info details sheet (formatted size)
- test: comparator unit test
