# Freezer Redesign — Design Spec

**Date:** 2026-06-02  
**Status:** Approved

---

## Overview

The current Freezer screen is a filtered copy of the App List (filtering by enabled/disabled/suspended state). This redesign replaces it entirely with a purpose-built feature: a user-curated list of apps that can be bulk-frozen with one tap, also accessible from a Quick Settings tile.

---

## What Changes

The old Freezer is scrapped. The new Freezer:
- Maintains its own Room table of package names the user has explicitly added
- Shows those apps in a grid (same `AppItemGrid` as AppList)
- Lets the user freeze all with one button, or manage the list via a dedicated sheet
- Exposes a Quick Settings tile for freezing from outside the app

Auto Freeze is out of scope for this spec — the settings sheet reserves a placeholder for it.

---

## Section 1 — Data Layer

### `FreezerEntity`
**File:** `data/source/local/room/FreezerEntity.kt`

Single-column Room entity, table name `freezer_apps`.

```kotlin
@Entity(tableName = "freezer_apps")
data class FreezerEntity(
    @PrimaryKey val packageName: String
)
```

Intentionally minimal. Future fields (custom label, shortcut, auto-freeze config, freeze timestamp) will be added as new columns with default values when needed.

### `FreezerDao`
**File:** `data/source/local/room/FreezerDao.kt`

```kotlin
@Dao
interface FreezerDao {
    @Query("SELECT * FROM freezer_apps")
    fun getAll(): Flow<List<FreezerEntity>>

    @Query("SELECT packageName FROM freezer_apps")
    suspend fun getAllPackageNames(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM freezer_apps WHERE packageName = :packageName)")
    suspend fun contains(packageName: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: FreezerEntity)

    @Query("DELETE FROM freezer_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
```

### `AppDatabase`
**File:** `data/source/local/room/AppDatabase.kt` — **modified**

- Version bumped `2 → 3`
- `FreezerEntity` added to `entities` list
- `fallbackToDestructiveMigration` already set — no migration SQL needed

---

## Section 2 — Domain Layer

### `FreezerRepository`
**File:** `domain/repository/FreezerRepository.kt`

```kotlin
interface FreezerRepository {
    fun getAll(): Flow<List<String>>           // package names, reactive
    suspend fun getAllPackageNames(): List<String>   // one-shot, for QS tile
    suspend fun add(packageName: String)
    suspend fun remove(packageName: String)
    suspend fun contains(packageName: String): Boolean
}
```

### `FreezerRepositoryImpl`
**File:** `data/repository/FreezerRepositoryImpl.kt`

Thin wrapper over `FreezerDao`. Annotated `@Single` for Koin component scan pickup. Maps `FreezerEntity` to `String` (packageName) before exposing flows.

### Use Cases
No `FreezerUseCase` is introduced in this iteration — `FreezerViewModel` calls `FreezerRepository` and `ManageAppUseCase` directly, consistent with how other ViewModels in this codebase operate.

**Future note:** A `FreezerUseCase` should be introduced when Auto Freeze or other multi-step freezer operations land. At that point, the ViewModel's direct repo calls should be migrated into it.

### Snackbar Contract
When `AppInfoDialog` freezes an app that is **not** in the freezer, the host ViewModel emits a `SnackbarEvent`:
- Message: `"Frozen"`
- Action label: `"Add to Freezer"`
- Action callback: `freezerRepository.add(packageName)`

This only fires on freeze (not unfreeze) and only when the app is not already in the freezer. No new domain model is needed — this is a ViewModel-level concern.

This occurs in two screens:
- **`FreezerScreen`** — `FreezerViewModel` already has `FreezerRepository`, handles natively.
- **`AppListScreen`** — `AppListViewModel` needs `FreezerRepository` injected so it can call `contains()` after a freeze and emit the snackbar event. This is a small, additive change to `AppListViewModel`'s constructor.

---

## Section 3 — Presentation Layer

### `FreezerViewModel`
**File:** `presentation/freezer/FreezerViewModel.kt` — **replaced**

**State (`FreezerUiState`):**
```
isLoading: Boolean
isRoot: Boolean
isShizuku: Boolean
freezerApps: List<AppInfo>       // package names joined with apps cache
allInstalledApps: List<AppInfo>  // for ManageFreezerSheet — loaded lazily on first sheet open, not at screen start
isGrid: Boolean
multiSelection: Set<String>      // package names of long-pressed apps
actionMessage: SnackbarEvent?    // message + optional action
```

**Key functions:**
- `freezeAll()` — calls `ManageAppUseCase.setAppDisabled(pkg, true)` for every package in `freezerApps`
- `unfreezeAll()` — called from settings sheet; unfreezes all, does not remove from freezer list
- `removeFromFreezer(packageNames: Set<String>)` — removes from DB and unfreezes each
- `addToFreezer(packageName: String)` — called from snackbar "Add to Freezer" action
- `toggleManaged(packageName: String, add: Boolean)` — called from `ManageFreezerSheet` checkbox; adds/removes from DB and freezes/unfreezes in background
- `dismissMessage()` — clears snackbar event

### `FreezerScreen`
**File:** `presentation/freezer/FreezerScreen.kt` — **replaced**

**Header row:** `❄️ Freezer` title (left) · `Freeze All` filled button + `⚙` icon button (right).

**Body:** `LazyVerticalGrid(GridCells.Adaptive(100.dp))` reusing `AppItemGrid`. Shows `freezerApps`. Empty state shown when list is empty, prompting user to tap ＋.

**Multi-select:** Long-press enters multi-select mode. `MultiSelectHeader` appears (reused from AppList) with a "Remove from Freezer" action. Clearing selection exits multi-select mode.

**FAB:** ＋ positioned bottom-right, opens `ManageFreezerSheet`.

**Dialogs:** Tapping an app card opens `AppInfoDialog` (same as AppList). After a freeze action on an app not in the freezer, a `Snackbar` with "Add to Freezer" action is shown.

### `ManageFreezerSheet`
**File:** `presentation/freezer/ManageFreezerSheet.kt` — **new**

`ModalBottomSheet` (same shape/style as `AppFilterSheet`).

- Search bar at top
- `LazyVerticalGrid(GridCells.Adaptive(100.dp))` of all installed user apps
- Apps already in the freezer shown with a checkmark overlay on their icon
- Tapping a checked app → `toggleManaged(pkg, add = false)` — removes from freezer + unfreezes in background
- Tapping an unchecked app → `toggleManaged(pkg, add = true)` — adds to freezer + freezes in background
- No confirm button — changes are applied live

### `FreezerSettingsSheet`
**File:** `presentation/freezer/FreezerSettingsSheet.kt` — **new**

`ModalBottomSheet` triggered by the `⚙` icon.

Contents (v1):
- **Unfreeze All** — destructive-tinted button, calls `viewModel.unfreezeAll()`, dismisses sheet
- **Auto Freeze** section — visible but disabled, labelled `"Coming soon"`

---

## Section 4 — Quick Settings Tile

### `FreezerTileService`
**File:** `presentation/tile/FreezerTileService.kt` — **new**

Extends `TileService`.

**Tile states:**

| State | Visual | Subtitle |
|-------|--------|----------|
| All frozen | Active (highlighted) | `"All frozen"` |
| Some/all active | Inactive (dimmed) | `"X apps · tap to freeze"` |
| No privilege | Unavailable (greyed) | `"No privilege granted"` |

**Lifecycle:**
- `onStartListening()` — launches coroutine to read `FreezerRepository.getAllPackageNames()` and check privileges; updates tile state
- `onClick()` — if no privilege: shows `Toast` `"Grant Root / Shizuku / Dhizuku first"` and returns. Otherwise: calls `ManageAppUseCase.setAppDisabled(pkg, true)` for all freezer packages, updates tile to active state
- `onStopListening()` — cancels coroutine

**DI:** Koin dependencies (`FreezerRepository`, `ManageAppUseCase`, `SystemRepository`) are resolved via `KoinComponent` + `get()` inside `onStartListening`, not constructor injection (Android-managed lifecycle).

**Manifest declaration:**
```xml
<service
    android:name=".presentation.tile.FreezerTileService"
    android:exported="true"
    android:icon="@drawable/frozen"
    android:label="@string/freezer"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE" />
    </intent-filter>
</service>
```

---

## Section 5 — DI & Navigation

### Koin
One new binding in `di/Modules.kt` inside `AppModule`:
```kotlin
@Single
fun freezerDao(appDatabase: AppDatabase): FreezerDao = appDatabase.freezerDao()
```
`FreezerRepositoryImpl` is auto-discovered by the existing `@ComponentScan("com.valhalla.thor")`.

### Navigation
No changes. `FREEZER` destination in `AppDestinations` stays. `FreezerScreen` replaces the old composable at the same route. `ManageFreezerSheet` and `FreezerSettingsSheet` are internal composable state inside `FreezerScreen`, not navigation destinations.

---

## File Summary

| Action | File |
|--------|------|
| New | `data/source/local/room/FreezerEntity.kt` |
| New | `data/source/local/room/FreezerDao.kt` |
| Modified | `data/source/local/room/AppDatabase.kt` |
| New | `domain/repository/FreezerRepository.kt` |
| New | `data/repository/FreezerRepositoryImpl.kt` |
| Replaced | `presentation/freezer/FreezerScreen.kt` |
| Replaced | `presentation/freezer/FreezerViewModel.kt` |
| New | `presentation/freezer/ManageFreezerSheet.kt` |
| New | `presentation/freezer/FreezerSettingsSheet.kt` |
| New | `presentation/tile/FreezerTileService.kt` |
| Modified | `AndroidManifest.xml` |
| Modified | `di/Modules.kt` |
| Modified | `presentation/appList/AppListViewModel.kt` (inject FreezerRepository for snackbar) |

---

## Out of Scope

- Auto Freeze (settings placeholder reserved)
- `FreezerUseCase` (introduce when Auto Freeze lands)
- System apps in the freezer (only user apps for now)
- Per-app freeze method selection (always uses `setAppDisabled`)
