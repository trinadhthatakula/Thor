# Add Freezer to Launcher — Design

**Issue:** [#210](https://github.com/trinadhthatakula/Thor/issues/210) · **Date:** 2026-07-04 · **Status:** Approved design, pre-implementation

## Summary

An opt-in setting, **"Add Freezer to launcher"**, that lets a user place **home-screen shortcuts for frozen apps** (and two bulk actions) so frozen apps can be launched without opening Thor:

- Each frozen app can get a home-screen shortcut showing its **grayscale icon** (matching the freezer list). Tapping it **enables the app in the background, then launches it** (or launches directly if already enabled).
- Two **bulk-action** shortcuts — **Freeze all** and **Unfreeze all** — pinnable to the home screen **and** exposed as long-press dynamic shortcuts on Thor's launcher icon.
- **Re-freeze is unchanged**: a launched app stays in the freezer set (just temporarily enabled), so the existing screen-off `AutoFreezeManager` re-disables it on lock — only when the existing auto-freeze setting is on.

The whole feature reuses Thor's existing freeze/enable path and screen-off re-freeze; it adds one shortcut manager, one invisible trampoline activity, a settings toggle, and Freezer UI actions.

## Background (how the codebase constrains this)

- **"Frozen" = disabled**, not suspended/hidden. Freezing calls `ManageAppUseCase.setAppDisabled(pkg, true)` (`domain/usecase/ManageAppUseCase.kt`), which resolves to `COMPONENT_ENABLED_STATE_DISABLED_USER` via the active gateway (Root `pm disable`, Shizuku/Dhizuku `setApplicationEnabledSetting`). Freezer *membership* is a Room `freezer` table (`FreezerRepository`), independent of the OS enabled flag.
- **A disabled app's own launcher icon disappears and it cannot be launched** until re-enabled. So surfacing a frozen app in the launcher **cannot** reuse the target's launcher entry — it requires a **Thor-owned pinned shortcut** that trampolines through Thor to enable-then-launch.
- **No launcher/shortcut code exists** in the app today (net-new). **No foreground/recents watcher exists.** The only re-freeze mechanism is `AutoFreezeManager` (`data/service/AutoFreezeManager.kt`), a `@Single` that registers a runtime `ACTION_SCREEN_OFF` receiver and re-disables every freezer package on screen-off + keyguard-locked, gated on `prefs.autoFreezeEnabled`, started from `ThorApplication.onCreate()`.
- **Trampoline precedent:** `ShortcutTriggerActivity` (`Theme.NoDisplay`, not exported, finishes synchronously) — the template for the invisible launch activity.
- **minSdk 28.** `requestPinShortcut` / dynamic shortcuts / `ShortcutManagerCompat` require **no permission**. No foreground service is needed.

## Non-goals (v1)

- **User apps only.** System apps freeze via *uninstall-for-user* and re-enable via *install-existing* (heavier, slower); their launcher shortcuts are deferred to a later version.
- **No "removed from recents" detection.** Re-freeze rides the existing screen-off path. A foreground-leave watcher (UsageStats) is possible future work but is explicitly out of scope here.
- **No force-removal of pinned icons.** Android cannot silently remove a pinned shortcut; cleanup can only *disable* it (greys it with a short message).

## User flow

1. User enables **Settings → "Add Freezer to launcher"** (off by default).
2. In the Freezer, each frozen **user** app shows an **"Add to Home screen"** action; a **"Pin all to Home screen"** button pins every eligible frozen app (sequential system dialogs). Bulk **Freeze all** / **Unfreeze all** can be pinned the same way, and also appear on long-press of Thor's icon.
3. Tapping a per-app shortcut → invisible `FreezerLaunchActivity`:
   - App disabled → `setAppDisabled(pkg, false)` via active gateway → resolve launch intent → `startActivity`.
   - App already enabled → launch directly.
   - No active privilege → toast, finish.
4. Tapping **Freeze all** / **Unfreeze all** → same trampoline, bulk `setAppDisabled(all freezer pkgs, true/false)`, privilege-guarded, finish.
5. On screen lock (if auto-freeze is on), `AutoFreezeManager` re-disables freezer apps as it already does — **no change**.

## Architecture & components

### 1. Settings toggle (standard 4-file pattern)
Mirror `autoFreezeEnabled`:
- `domain/model/UserPreferences.kt` — add `val addFreezerToLauncher: Boolean = false`.
- `data/repository/PreferenceRepositoryImpl.kt` — `booleanPreferencesKey("add_freezer_to_launcher")`, map into read, add `setAddFreezerToLauncher(...)` writer.
- `domain/repository/PreferenceRepository.kt` — `suspend fun setAddFreezerToLauncher(enabled: Boolean)`.
- `presentation/settings/SettingsViewModel.kt` + `SettingsScreen.kt` — `setAddFreezerToLauncher(...)` + a `SettingsSwitchRow` in the Freezer settings area.

### 2. `FreezerShortcutManager` (`@Single`, e.g. `data/launcher/FreezerShortcutManager.kt`)
Wraps `androidx.core.content.pm.ShortcutManagerCompat`. Injected where needed via `by inject()` (Activity) / constructor (ViewModel). Responsibilities:
- `pinAppShortcut(app)` — build `ShortcutInfoCompat` id = package name, short/long label = app label, icon = **grayscale bitmap** (see Icon handling), intent = explicit `FreezerLaunchActivity` intent with `EXTRA_ACTION=LAUNCH`, `EXTRA_PACKAGE=pkg`; call `requestPinShortcut`.
- `pinBulkShortcut(FREEZE_ALL | UNFREEZE_ALL)` — Thor vector icon, explicit trampoline intent with the action.
- `syncDynamicShortcuts(enabled)` — when the setting is on, publish Freeze all / Unfreeze all as **dynamic** shortcuts (long-press Thor icon); when off, remove them.
- `disableAppShortcut(pkg, reason)` — `ShortcutManagerCompat.disableShortcuts` for cleanup.
- `isPinSupported()` — `ShortcutManagerCompat.isRequestPinShortcutSupported`.

### 3. `FreezerLaunchActivity` (invisible trampoline, `presentation/launcher/FreezerLaunchActivity.kt`)
- `Theme.NoDisplay`, `exported=false` (launched only by Thor-published shortcut intents targeting it explicitly), mirrors `ShortcutTriggerActivity`. `by inject()` its deps (`ManageAppUseCase`/`SystemRepository`, `FreezerRepository`, `PrivilegeManager`, `FreezerShortcutManager`, `PackageManager`).
- Reads `EXTRA_ACTION`:
  - `LAUNCH` → if the app is disabled and `!hasPrivilege` → toast + `finish()`. Otherwise the (still-invisible, still-**foreground**) activity awaits `setAppDisabled(pkg, false)` off the main thread, then `getLaunchIntentForPackage(pkg)` → `startActivity`, then `finish()`. It must remain the foreground activity through `startActivity` so the target launch satisfies Android 10+ background-activity-launch rules — so the LAUNCH path is *not* fire-and-finish.
  - `FREEZE_ALL` / `UNFREEZE_ALL` → privilege-guard, then hand the bulk `setAppDisabled` loop over `FreezerRepository.getAllPackageNames()` to `FreezerShortcutManager`'s **app-scoped `CoroutineScope`** (survives the finishing activity), and `finish()` immediately — no activity is started here, so there's no background-launch concern.
- Being `Theme.NoDisplay`, nothing visible flashes in either case; the LAUNCH path lives only for the brief enable-then-start window.

### 4. Freezer UI actions (`presentation/freezer/*`)
- Per frozen **user** app: an **"Add to Home screen"** action (in the app's action menu / `ManageFreezerSheet`), visible only when `addFreezerToLauncher` is on and `isPinSupported()`.
- A **"Pin all to Home screen"** action and **Freeze all / Unfreeze all** pin actions in the Freezer settings sheet.
- Wire through `FreezerViewModel` → `FreezerShortcutManager`.

### 5. Dynamic shortcuts lifecycle
`syncDynamicShortcuts` is called when the setting toggles (from `SettingsViewModel`) and at app start (`ThorApplication`, next to `autoFreezeManager` startup) so the long-press actions reflect the current setting.

## Icon handling

Build the shortcut icon from `PackageManager.getApplicationIcon(pkg)` (works for a disabled-but-installed package in Thor's own process, given `QUERY_ALL_PACKAGES`), draw to a `Bitmap`, apply a **saturation-0 `ColorMatrixColorFilter`** to grayscale it, wrap via `IconCompat.createWithBitmap`. Capture at pin time; if the icon fails to load, fall back to an icon cached when the app was frozen. (Reuses the same grayscale treatment the freezer list already applies.)

## Cleanup / lifecycle

- **Permanent unfreeze** (app removed from the Room freezer set) → `disableAppShortcut(pkg, "No longer frozen")`.
- **Setting turned off** → disable all Thor freezer shortcuts + remove the dynamic bulk shortcuts.
- **Re-freeze** (screen-off) → shortcut keeps working (enable-then-launch next tap).

## Edge cases

- **No active privilege on tap** → toast ("Enable Root / Shizuku / Dhizuku to launch frozen apps"), finish. Mirrors `FreezerTileService` `hasPrivilege` guard.
- **Launcher doesn't support pinning** (`isRequestPinShortcutSupported == false`) → hide/disable the pin actions, surface a short explanation; dynamic long-press actions still work.
- **Enable→launch race** (enabled state / launch intent not instantly visible) → after `setAppDisabled(false)` returns, retry `getLaunchIntentForPackage` briefly before giving up.
- **System app in freezer** → no "Add to Home screen" action offered in v1.
- **Icon load failure** → cached-at-freeze fallback.

## Manifest / DI

- Register `FreezerLaunchActivity` (`exported=false`, `Theme.NoDisplay`, `excludeFromRecents=true`, `noHistory=true`).
- **No new permissions.** (`requestPinShortcut`/dynamic shortcuts need none; `QUERY_ALL_PACKAGES` already present; no foreground service.)
- Confirm `androidx.core` (ShortcutManagerCompat/IconCompat) is on the classpath (it is, transitively via Compose/AndroidX; add explicit `androidx.core:core-ktx` if not).
- DI: `FreezerShortcutManager` = `@Single` (Koin component-scan), like `AutoFreezeManager`; the trampoline activity retrieves deps via `by inject()`.

## Testing

- **Unit:** grayscale-bitmap util; trampoline action decision (disabled→enable+launch, enabled→launch-direct, no-privilege→toast+finish, FREEZE_ALL/UNFREEZE_ALL loop over the freezer set); `FreezerShortcutManager` builds correct `ShortcutInfoCompat` (id/label/intent/action extras) and disable logic.
- **Device:** enable setting → pin a frozen user app → grayscale icon appears → tap enables + launches → lock screen → app re-freezes; pin/long-press Freeze all & Unfreeze all → freezer set toggles; unfreeze an app → its shortcut greys out.

## File-by-file change list

**New**
- `data/launcher/FreezerShortcutManager.kt` — `@Single` shortcut wrapper + grayscale icon util.
- `presentation/launcher/FreezerLaunchActivity.kt` — invisible action-dispatched trampoline.

**Modified**
- `domain/model/UserPreferences.kt`, `domain/repository/PreferenceRepository.kt`, `data/repository/PreferenceRepositoryImpl.kt` — new toggle.
- `presentation/settings/SettingsViewModel.kt`, `presentation/settings/SettingsScreen.kt` — toggle row + `syncDynamicShortcuts` on change.
- `presentation/freezer/FreezerViewModel.kt` + freezer sheets/screen — "Add to Home screen", "Pin all", "Freeze all/Unfreeze all" pin actions (gated on setting + `isPinSupported`).
- `ThorApplication.kt` — `syncDynamicShortcuts(prefs.addFreezerToLauncher)` at start (next to `autoFreezeManager`).
- `app/src/main/AndroidManifest.xml` — register `FreezerLaunchActivity`.

## Future work

- System-app launcher shortcuts (account for install-existing re-enable).
- Optional foreground-leave re-freeze (UsageStats) as an alternative to screen-off, for users who want instant re-freeze after closing an app.
