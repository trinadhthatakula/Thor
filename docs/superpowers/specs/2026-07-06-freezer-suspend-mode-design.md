# Freezer: Suspend-vs-Freeze mode — Design Spec

**Issue:** [#239 "[Feature]: Suspend by default"](https://github.com/trinadhthatakula/Thor/issues/239) (enhancement, area: Freezer)
**Date:** 2026-07-06
**Status:** Design approved (pending spec review)

## 1. Goal

Let the user choose the **action the Freezer performs** when it "freezes" an app:

- **Freeze** (current behavior) — `setAppDisabled(pkg, true)` = `pm disable`. App is fully disabled and disappears from the launcher.
- **Suspend** (new) — `setAppSuspended(pkg, true)` = `pm suspend`. App stays installed and visible but is paused: it can't run, shows a "paused" dialog on tap, and its notifications are suppressed.

The choice is **global** (one mode for the whole Freezer) and is honored by **every** freeze path: single-app freeze, the multi-select / "Freeze all" toolbar, and the screen-off **auto-freeze**. Default stays **Freeze** so existing users see no behavior change on update.

## 2. Why this is small

`setAppSuspended` is **already implemented end-to-end** and simply never wired into the Freezer:

- `SystemGateway.setAppSuspended()` — `domain/gateway/SystemGateway.kt:19`, implemented in all three backends:
  - `RootSystemGateway.kt:149` (shell `pm suspend` + reflection fallback)
  - `ShizukuSystemGateway.kt:73` (`reflector.setAppSuspended`)
  - `DhizukuSystemGateway.kt:134` (`reflector.setAppSuspended` / DevicePolicyManager)
- `ManageAppUseCase.setAppSuspended()` — `domain/usecase/ManageAppUseCase.kt:21`
- `MainViewModel` already routes both action families symmetrically:
  - `AppClickAction.Suspend/UnSuspend` → `setAppSuspended` (`MainViewModel.kt:355-356`)
  - `MultiAppAction.Suspend/UnSuspend` → `performLoggedMultiAction { setAppSuspended }` (`MainViewModel.kt:429-440`)
- `AppInfo.isSuspended` already exists and is rendered (e.g. `ManageFreezerSheet.kt:179`).

So we do **not** write any new gateway/usecase/action code. We add one preference, thread it through the Freezer, and fix the places that assume **"frozen == disabled."**

## 3. The one real subtlety: "frozen" is currently defined as "disabled"

Today the Freezer treats an app as frozen iff `!app.enabled`. A **suspended** app is still `enabled`, so without changes a suspended app would look "not frozen." Two consequences must be handled:

1. **Frozen predicate.** Redefine frozen as `!enabled || isSuspended` everywhere the Freezer reasons about state (toolbar enablement, `appsToFreeze` / `appsToUnfreeze`, empty/active/frozen filters at `FreezerScreen.kt:120-128`).
2. **State-aware unfreeze.** After a mode switch a user can have a *mixed* set (some disabled, some suspended). "Unfreeze" must restore each app by its actual state: **unsuspend** suspended apps and **enable** disabled apps. A uniform `UnFreeze` (which only calls `setAppDisabled(false)`) would leave suspended apps still suspended, and vice-versa.

## 4. Design decisions (confirmed with user)

| Decision | Choice |
|---|---|
| Scope | **Global** — one mode, honored by all freeze paths |
| Default for existing users | **Freeze** (no behavior change on update) |
| UI | **Both**: a `ConnectedButtonGroup` (Freeze \| Suspend) in the Freezer settings sheet **and** a "Suspend instead of freeze" switch in the main Settings screen — both backed by the single `freezerMode` preference so they always agree |

## 5. Data model & persistence

**New:** `domain/model/FreezerMode.kt`
```kotlin
enum class FreezerMode { FREEZE, SUSPEND }
```

**`domain/model/UserPreferences.kt`** — add near the Auto-Freeze field (line ~28):
```kotlin
// Freezer action: what "freeze" does (FREEZE = pm disable, SUSPEND = pm suspend)
val freezerMode: FreezerMode = FreezerMode.FREEZE,
```

**`domain/repository/PreferenceRepository.kt`** — add (near line 41):
```kotlin
suspend fun setFreezerMode(mode: FreezerMode)
```

**`data/repository/PreferenceRepositoryImpl.kt`** — mirror the `ThemeMode`/`AnimationIntensity` string-enum pattern (both already stored via `stringPreferencesKey`):
- Key: `val FREEZER_MODE = stringPreferencesKey("freezer_mode")` (in `Keys`, ~line 55).
- Hydrate in the `map`/`userPreferences` builder (~line 118):
  ```kotlin
  freezerMode = prefs[Keys.FREEZER_MODE]
      ?.let { runCatching { FreezerMode.valueOf(it) }.getOrNull() }
      ?: FreezerMode.FREEZE,
  ```
- Setter (mirror `setAutoFreezeEnabled`, ~line 195):
  ```kotlin
  override suspend fun setFreezerMode(mode: FreezerMode) {
      dataStore.edit { it[Keys.FREEZER_MODE] = mode.name }
  }
  ```

No Room/DataStore migration needed — absent key falls back to `FREEZE`.

## 6. ViewModel & state

**`FreezerViewModel.kt`**
- `FreezerUiState` (`:31`) — add `val freezerMode: FreezerMode = FreezerMode.FREEZE`.
- `observePreferences` (`:296`) — hydrate `freezerMode` from prefs alongside `autoFreezeEnabled`.
- New setter mirroring `setAutoFreezeEnabled` (`:311`):
  ```kotlin
  fun setFreezerMode(mode: FreezerMode) = viewModelScope.launch {
      preferenceRepository.setFreezerMode(mode)
  }
  ```
- `freezeSingleApp` (`:225`) — when `state.freezerMode == SUSPEND`, call `manageAppUseCase.setAppSuspended(pkg, true)` instead of `setAppDisabled(pkg, true)`.
- `unfreezeSingleApp` (`:263`) — **state-aware restore**: if `app.isSuspended` → `setAppSuspended(pkg, false)`; else if `!app.enabled` → `setAppDisabled(pkg, false)`.

## 7. Freeze entry points — how each honors the mode

All four paths already have a Freeze *and* a Suspend variant; we select the variant by mode rather than adding new logic.

1. **Multi-select / "Freeze all" toolbar** (`FreezerScreen.kt:357-401`, and `onUnfreezeAll` at the settings-sheet call site `:466`).
   Emit the mode-appropriate `MultiAppAction`:
   ```kotlin
   val freezeAction = if (state.freezerMode == SUSPEND)
       MultiAppAction.Suspend(appsToFreeze) else MultiAppAction.Freeze(appsToFreeze)
   ```
   For **unfreeze/restore** of a possibly-mixed set, restore by state (see §8).

2. **Single app** — handled in `FreezerViewModel.freezeSingleApp/unfreezeSingleApp` (§6).

3. **Auto-freeze** (`AutoFreezeManager.kt`). It already injects `PreferenceRepository` and observes `userPreferences` in `startObserving()` (`:135`). Capture the current mode (e.g. a `@Volatile var currentMode` updated inside the existing `collectLatest`) and branch the per-package call in the screen-off loop (~`:100`) from `setAppDisabled(pkg, true)` to `setAppSuspended(pkg, true)` when `SUSPEND`. The existing keyguard-locked, privilege, and UAD/unsafe-skip guards are unchanged.

4. **Launcher bulk shortcuts** (`viewModel.pinBulkShortcut(freeze = …)`). The pinned shortcut's *action* should also honor the mode. **Flag for implementation:** confirm what the shortcut invokes when tapped and route it through the same mode branch; if that expands scope materially, it can be a fast-follow (documented, not silently dropped).

## 8. State-aware unfreeze (mixed sets)

`unfreezeSingleApp` handles the single case (§6). For the batch "Unfreeze all" / multi-select restore, split the frozen set and dispatch the correct inverse per app. Preferred implementation (least new surface, reuses `MainViewModel`):

- In `FreezerScreen`, compute:
  ```kotlin
  val frozenApps   = state.freezerApps.filter { !it.enabled || it.isSuspended }
  val toEnable     = frozenApps.filter { !it.enabled }      // disabled → pm enable
  val toUnsuspend  = frozenApps.filter { it.enabled && it.isSuspended }
  ```
- Restore = `MultiAppAction.UnFreeze(toEnable)` and/or `MultiAppAction.UnSuspend(toUnsuspend)`.

Because both stream progress through the single `TermLoggerDialog`, dispatch them so they don't overlap (sequential, or a thin `MainViewModel` "restore" entry that iterates once and picks the inverse per app). **Recommended:** add a small state-aware restore in `MainViewModel` (`Restore(appList)` that, per app, calls `setAppSuspended(false)` if suspended else `setAppDisabled(false)`) — one action, one log stream, future-proof. Confirm during implementation whether to add `Restore` vs. sequential dispatch.

## 9. Frozen-predicate fix (`FreezerScreen.kt:120-128`)

```kotlin
val hasActive = remember(state.freezerApps) { state.freezerApps.any { it.enabled && !it.isSuspended } }
val hasFrozen = remember(state.freezerApps) { state.freezerApps.any { !it.enabled || it.isSuspended } }

val appsToFreeze   = remember(state.freezerApps) { state.freezerApps.filter { it.enabled && !it.isSuspended } }
val appsToUnfreeze = remember(state.freezerApps) { state.freezerApps.filter { !it.enabled || it.isSuspended } }
```
Update the toolbar's Freeze/Unfreeze button enablement to use `hasActive`/`hasFrozen`. (This keeps behavior identical in FREEZE mode, since a frozen app is `!enabled` and `isSuspended` is false.)

## 10. UI

**A. `FreezerSettingsSheet.kt`** — it already imports `ConnectedButtonGroup` + `ConnectedButtonGroupItem`. Add params:
```kotlin
freezerMode: FreezerMode,
onFreezerModeChange: (FreezerMode) -> Unit,
```
Render a labeled mode selector next to the Auto Freeze switch (`~:68`), gated on `hasPrivilege`:
```kotlin
ConnectedButtonGroup(
    items = listOf(
        ConnectedButtonGroupItem.Label(stringResource(R.string.freeze)),
        ConnectedButtonGroupItem.Label(stringResource(R.string.suspend)),
    ),
    selectedIndex = freezerMode.ordinal,
    onItemSelected = { onFreezerModeChange(FreezerMode.entries[it]) },
    enabled = hasPrivilege,
)
```
Wire the new params at the `FreezerScreen.kt` call site (`:456`): `freezerMode = state.freezerMode`, `onFreezerModeChange = viewModel::setFreezerMode`.

**B. Main Settings screen** (`presentation/settings/SettingsScreen.kt` + `SettingsViewModel`). Add a **"Suspend instead of freeze"** switch following the existing switch pattern (biometric lock / dynamic color), backed by the same preference:
```kotlin
checked = freezerMode == FreezerMode.SUSPEND
onCheckedChange = { setFreezerMode(if (it) FreezerMode.SUSPEND else FreezerMode.FREEZE) }
```
Because both controls read/write the one `freezerMode` preference (single source of truth), they stay in sync automatically.

**C. Strings** (`res/values/strings.xml`, + translations later): `freeze`, `suspend`, `freezer_mode` (section label), `suspend_instead_of_freeze`, `suspend_instead_of_freeze_desc`.

## 11. Error handling & edge cases

- **Backend suspend failure.** Gateways already return `Result.failure`; errors surface through the existing action/log pipeline (`TermLoggerDialog`, `actionMessage`) exactly like a freeze failure. **No silent fallback to disable** — the user picked suspend; a failure is reported, not masked.
- **UAD/unsafe skip preserved.** The Freeze multi-action skips unsafe/UAD-failed system apps (`MainViewModel.kt:416-419, 495-496`). Ensure the **Suspend** multi-path applies the same skip. If `performLoggedMultiAction` (`:429`) does not currently filter, add the same eligibility guard so switching to suspend doesn't bypass the safety check. **(Verify during implementation.)**
- **Mixed states** after a mode switch — handled by the frozen predicate (§9) and state-aware restore (§8).
- **Auto-freeze mode read** — snapshot the latest mode in `AutoFreezeManager`'s existing `collectLatest`; the screen-off receiver reads that volatile value.

## 12. Testing

**Unit**
- `PreferenceRepositoryImpl`: `freezerMode` write→read round-trip; absent key defaults to `FREEZE`; invalid stored string falls back to `FREEZE`.
- `FreezerViewModel`: `freezeSingleApp`/`unfreezeSingleApp` call `setAppSuspended` vs `setAppDisabled` per mode; state-aware unfreeze picks the correct inverse.

**Manual / on-device (per backend: Root, Shizuku, Dhizuku)**
- Toggle mode via both controls; confirm they stay in sync.
- Suspend mode: single freeze, multi "Freeze all", and screen-off auto-freeze all *suspend* (app greyed/paused, still in launcher).
- Unfreeze a mixed set (some disabled, some suspended) → all become active.
- Freeze mode unchanged from today.
- Suspend failure on an unsupported device surfaces an error (no disable fallback).

## 13. Out of scope (YAGNI)

- Per-app freeze/suspend choice.
- Scheduled/timed auto-freeze (auto-freeze stays screen-off triggered).
- Changing the default to Suspend (explicitly kept as Freeze).

## 14. Build sequence

1. `FreezerMode` enum + `UserPreferences` field + `PreferenceRepository`(+`Impl`) key/hydration/setter.
2. `FreezerViewModel`: state field, hydration, `setFreezerMode`, mode-aware single freeze/unfreeze.
3. Frozen-predicate fix in `FreezerScreen` (§9) + mode-aware toolbar dispatch (§7.1) + state-aware batch restore (§8).
4. `AutoFreezeManager` mode branch (§7.3).
5. `MainViewModel`: ensure Suspend multi-path has the UAD/unsafe skip; add `Restore` if chosen (§8, §11).
6. UI: `FreezerSettingsSheet` `ConnectedButtonGroup` + Settings switch + strings (§10).
7. Launcher-shortcut mode routing (§7.4) or document as fast-follow.
8. Tests (§12); build `assembleFossDebug`; on-device pass per backend.
