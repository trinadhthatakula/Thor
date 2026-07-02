# Reactive Privilege Flow — Design

**Date:** 2026-07-02
**Branch:** `feat/reactive-privilege-flow`

## Problem

Privilege availability (root / Shizuku / Dhizuku) is probed **one-shot per ViewModel**
(`HomeViewModel`, `AppListViewModel`, `FreezerViewModel` each call
`systemRepository.isRootAvailable()` etc. once), and there is **no reactive source**.
Consequences:

- A first-launch **Shizuku grant is not observed** by screens created before the grant
  (Freezer needed an app restart). Only `HomeActivity`'s `ShizukuPermissionHandler`
  refreshes `HomeViewModel`.
- The "active mode" resolution (`preferred ?: firstAvailable(Root→Shizuku→Dhizuku)`) is
  duplicated ad-hoc (`HomeViewModel:57`).
- A stop-gap `ShizukuPermissionHandler`-in-`FreezerViewModel` shim was added to work
  around the above; it should be replaced by the shared source.

## Goal

A single reactive source of truth for privilege availability + the active mode,
observed by Home / AppList / Freezer, updated automatically on Shizuku binder/permission
events and preference changes.

## Design

### 1. Enum

Extend `PrivilegeMode`:

```kotlin
enum class PrivilegeMode { NONE, ROOT, SHIZUKU, DHIZUKU }
```

- `preferredPrivilegeMode: PrivilegeMode?` in prefs is **unchanged** (nullable; `null` =
  auto). `NONE` is used **only** by the reactive *active* value and is **never persisted**
  as a preference.

### 2. Emitted state

```kotlin
data class PrivilegeState(
    val root: Boolean = false,
    val shizuku: Boolean = false,
    val dhizuku: Boolean = false,
    val active: PrivilegeMode = PrivilegeMode.NONE,
    val isReady: Boolean = false // false until the first probe completes
) {
    val hasAnyPrivilege: Boolean get() = active != PrivilegeMode.NONE
}
```

`isReady` lets consumers distinguish "not probed yet" from "probed, none available"
(avoids a flash of the no-privilege UI on cold start).

### 3. `PrivilegeManager` (`@Single`, data layer)

```kotlin
@Single
class PrivilegeManager(
    private val systemRepository: SystemRepository,
    private val preferenceRepository: PreferenceRepository
) {
    // process-lifetime; not cancelled (this @Single lives for the app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val state: StateFlow<PrivilegeState>
    fun refresh() // manual re-probe (e.g. after the user enables root)
}
```

Responsibilities:

- **Probe** root/shizuku/dhizuku off the main thread (`Dispatchers.IO`) on init, on
  `refresh()`, and on Shizuku events.
- **Own the app-wide Shizuku listeners** (`OnBinderReceivedListener`,
  `OnRequestPermissionResultListener`) — registered once for the process lifetime;
  re-probe on binder-received / permission-granted. This replaces the per-VM handlers.
- **Observe `preferredPrivilegeMode`** (`preferenceRepository.userPreferences`) and
  recompute `active` when it changes.
- **Resolve `active`** with a pure function:
  `resolveActive(preferred, root, shizuku, dhizuku)` = the preferred mode if it is
  available; else the first available in Root→Shizuku→Dhizuku order; else `NONE`.
- Publish via `MutableStateFlow` updated with `update { }`.

Lifecycle: as a Koin `@Single` it is created when first injected (a consumer VM at app
start) and lives for the process; Shizuku listeners are never unregistered (process
lifetime), so no leak concern beyond app teardown.

### 4. Consumer migration (full)

- **HomeViewModel** — collect `privilegeManager.state`; drop its one-shot probes;
  `activePrivilegeMode` = `state.active`; keep dashboard-specific data loading.
- **AppListViewModel** — replace the one-shot probe (`AppListViewModel.kt:117-123`) by
  combining `privilegeManager.state` with the app-list flow.
- **FreezerViewModel** — collect `privilegeManager.state` for `isRoot/isShizuku/isDhizuku`;
  **remove** `loadPrivileges()` and the `ShizukuPermissionHandler` shim + `onCleared`
  override added earlier.
- **HomeActivity** — keeps `ShizukuPermissionHandler` only to **request** permission
  (`checkAndRequestPermission`); its `onPermissionGranted` may call
  `privilegeManager.refresh()` (belt-and-suspenders), but the manager's own listeners
  already handle the grant.

### 5. DI

Koin **annotation** style, matching the codebase (`@Single` / `@KoinViewModel` scanned via
KSP): annotate `PrivilegeManager` with `@Single`. It owns its own process-lifetime
`CoroutineScope` internally (no raw `CoroutineScope` injected). Consumer VMs receive it via
constructor injection like their other dependencies.

## Out of scope (this branch)

- `SystemRepositoryImpl.getActiveGateway()` — the **imperative** gateway resolution used
  by privileged operations stays as-is (reads prefs + validates). It is not on the
  reactive path; converging it onto `PrivilegeManager` is a possible follow-up. This keeps
  the privileged-operation path stable.
- The permission-**request** UX (unchanged).

## Testing

- Unit-test the pure `resolveActive(preferred, root, shizuku, dhizuku)` resolver across
  the matrix (preferred available / preferred unavailable / none available / each fallback).
- Manager wiring (Shizuku listeners, probes) is device/permission-dependent → manual
  verification: fresh install → grant Shizuku when prompted → Freezer recognizes it with
  no restart; toggle preferred mode in Settings → `active` updates live.

## Risks

- Root probe runs a shell command; keep strictly off the main thread (already the case).
- Ensure `PrivilegeManager` is actually instantiated at startup (it is, via VM injection)
  so its listeners are registered before the first grant.
