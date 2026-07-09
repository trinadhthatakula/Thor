# Freezer Suspend-vs-Freeze Mode — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a global Freezer "action mode" (Freeze | Suspend) so the Freezer — including screen-off auto-freeze — can suspend apps (`pm suspend`) instead of disabling them (`pm disable`), with Freeze as the default.

**Architecture:** `setAppSuspended` is already implemented across all gateways/usecase/actions; this feature adds one `FreezerMode` preference and routes every freeze entry point (single-app, batch toolbar, auto-freeze) through it. The Freezer's "frozen == disabled" assumption is generalized to "disabled **or** suspended," and unfreeze becomes state-aware (restore each app by its actual state). The freeze/restore decision is extracted into a pure, unit-tested helper.

**Tech Stack:** Kotlin, Jetpack Compose (Material3-Expressive), Koin, DataStore Preferences, Asgard 2.0 UI (`ConnectedButtonGroup`), JUnit4 (pure unit tests).

## Global Constraints

- **Default mode is `FreezerMode.FREEZE`** — existing users see no behavior change on update (absent DataStore key → `FREEZE`).
- **Work on branch `feat/freezer-suspend-mode`** (off `dev`).
- **Named-argument Compose calls** (Asgard `modifier` is the 2nd param).
- **Theme-agnostic** — no hardcoded colors; `ConnectedButtonGroup` inherits `MaterialTheme`.
- **Enums persist as strings** via `stringPreferencesKey` with a `runCatching { valueOf }` fallback (existing `ThemeMode`/`AnimationIntensity` pattern).
- **Per-task verification:** `./gradlew :app:compileFossDebugKotlin`; full `./gradlew assembleFossDebug` + unit tests at the end.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `domain/model/FreezerMode.kt` | Enum + pure `isFrozen`/`isActive`/`restoreActionFor` decision logic + `AppInfo` ergonomic extensions | **Create** |
| `test/.../domain/model/FreezerModeTest.kt` | Pure JUnit tests for the decision logic | **Create** |
| `domain/model/UserPreferences.kt` | Add `freezerMode` field | Modify |
| `domain/repository/PreferenceRepository.kt` | Add `setFreezerMode` | Modify |
| `data/repository/PreferenceRepositoryImpl.kt` | Persist/hydrate `freezerMode` | Modify |
| `domain/model/MultiAppAction.kt` | `Freeze` carries `useSuspend` | Modify |
| `presentation/main/MainViewModel.kt` | `performCountedFreeze` mode-aware + state-aware restore | Modify |
| `presentation/freezer/FreezerViewModel.kt` | State field, hydration, `setFreezerMode`, mode-aware single freeze/unfreeze | Modify |
| `presentation/freezer/FreezerScreen.kt` | Frozen predicate, toolbar dispatch, settings-sheet wiring | Modify |
| `data/service/AutoFreezeManager.kt` | Suspend branch in the screen-off loop | Modify |
| `presentation/freezer/FreezerSettingsSheet.kt` | `ConnectedButtonGroup` mode selector | Modify |
| `presentation/settings/SettingsViewModel.kt` | `setFreezerMode` | Modify |
| `presentation/settings/SettingsScreen.kt` | "Suspend instead of freeze" switch | Modify |
| `res/values/strings.xml` | 3 new strings | Modify |

---

## Task 1: `FreezerMode` enum + pure decision logic (TDD)

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/model/FreezerMode.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/model/FreezerModeTest.kt`

**Interfaces:**
- Produces: `enum class FreezerMode { FREEZE, SUSPEND }`; `enum class FreezerRestore { ENABLE, UNSUSPEND, NONE }`; `fun isFrozen(enabled: Boolean, isSuspended: Boolean): Boolean`; `fun isActive(enabled: Boolean, isSuspended: Boolean): Boolean`; `fun restoreActionFor(enabled: Boolean, isSuspended: Boolean): FreezerRestore`; extension vals `AppInfo.isFrozen`, `AppInfo.isActive`, `AppInfo.restoreAction`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/valhalla/thor/domain/model/FreezerModeTest.kt`:
```kotlin
package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure decision logic for Freezer freeze/suspend mode (GH#239). No Android deps. */
class FreezerModeTest {

    @Test
    fun `enabled non-suspended app is active and not frozen`() {
        assertTrue(isActive(enabled = true, isSuspended = false))
        assertFalse(isFrozen(enabled = true, isSuspended = false))
    }

    @Test
    fun `disabled app is frozen`() {
        assertTrue(isFrozen(enabled = false, isSuspended = false))
        assertFalse(isActive(enabled = false, isSuspended = false))
    }

    @Test
    fun `suspended app is frozen even while still enabled`() {
        assertTrue(isFrozen(enabled = true, isSuspended = true))
        assertFalse(isActive(enabled = true, isSuspended = true))
    }

    @Test
    fun `restore of a suspended app unsuspends`() {
        assertEquals(FreezerRestore.UNSUSPEND, restoreActionFor(enabled = true, isSuspended = true))
    }

    @Test
    fun `restore of a disabled app enables`() {
        assertEquals(FreezerRestore.ENABLE, restoreActionFor(enabled = false, isSuspended = false))
    }

    @Test
    fun `restore of an already-active app is a no-op`() {
        assertEquals(FreezerRestore.NONE, restoreActionFor(enabled = true, isSuspended = false))
    }

    @Test
    fun `default freezer mode is FREEZE`() {
        assertEquals(FreezerMode.FREEZE, FreezerMode.entries.first())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.domain.model.FreezerModeTest"`
Expected: FAIL — unresolved references `isActive`, `isFrozen`, `FreezerMode`, etc.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/valhalla/thor/domain/model/FreezerMode.kt`:
```kotlin
package com.valhalla.thor.domain.model

/** What the Freezer does when it "freezes" an app. GH#239. */
enum class FreezerMode { FREEZE, SUSPEND }

/** How a frozen app is restored to active, based on its actual current state. */
enum class FreezerRestore { ENABLE, UNSUSPEND, NONE }

/** Freezer treats an app as "frozen" when it is disabled OR suspended. */
fun isFrozen(enabled: Boolean, isSuspended: Boolean): Boolean = !enabled || isSuspended

/** "Active" = not frozen: enabled and not suspended (i.e. freezable). */
fun isActive(enabled: Boolean, isSuspended: Boolean): Boolean = enabled && !isSuspended

/** Pick the inverse op to bring a frozen app back to active (suspended wins if somehow both). */
fun restoreActionFor(enabled: Boolean, isSuspended: Boolean): FreezerRestore = when {
    isSuspended -> FreezerRestore.UNSUSPEND
    !enabled -> FreezerRestore.ENABLE
    else -> FreezerRestore.NONE
}

// Ergonomic call-site helpers over AppInfo.
val AppInfo.isFrozen: Boolean get() = isFrozen(enabled, isSuspended)
val AppInfo.isActive: Boolean get() = isActive(enabled, isSuspended)
val AppInfo.restoreAction: FreezerRestore get() = restoreActionFor(enabled, isSuspended)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.domain.model.FreezerModeTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/valhalla/thor/domain/model/FreezerMode.kt \
        app/src/test/java/com/valhalla/thor/domain/model/FreezerModeTest.kt
git commit -m "feat(freezer): add FreezerMode enum + pure freeze/restore decision logic (#239)"
```

---

## Task 2: Persist `freezerMode` preference

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/domain/model/UserPreferences.kt:28`
- Modify: `app/src/main/java/com/valhalla/thor/domain/repository/PreferenceRepository.kt:41`
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/PreferenceRepositoryImpl.kt`

**Interfaces:**
- Consumes: `FreezerMode` (Task 1).
- Produces: `UserPreferences.freezerMode: FreezerMode`; `PreferenceRepository.setFreezerMode(mode: FreezerMode)`.

- [ ] **Step 1: Add the field to `UserPreferences`**

In `UserPreferences.kt`, after the `autoFreezeEnabled` line (currently line 28), add:
```kotlin
    // Auto Freeze
    val autoFreezeEnabled: Boolean = false,

    // Freezer action mode: FREEZE = pm disable, SUSPEND = pm suspend
    val freezerMode: FreezerMode = FreezerMode.FREEZE,
```
(`FreezerMode` is in the same package — no import needed.)

- [ ] **Step 2: Add the setter to the `PreferenceRepository` interface**

In `PreferenceRepository.kt`, under the `// --- Auto Freeze ---` group (after line 42 `setAddFreezerToLauncher`), add:
```kotlin
    suspend fun setFreezerMode(mode: FreezerMode)
```
Add the import near the other model imports at the top:
```kotlin
import com.valhalla.thor.domain.model.FreezerMode
```

- [ ] **Step 3: Persist + hydrate in `PreferenceRepositoryImpl`**

In `PreferenceRepositoryImpl.kt`:

(a) Add the key inside `object Keys`, after `AUTO_FREEZE` / `ADD_FREEZER_TO_LAUNCHER` (line 56):
```kotlin
        val FREEZER_MODE = stringPreferencesKey("freezer_mode")
```
(b) Add the hydration local before the `UserPreferences(` construction (near line 104, beside `freezerIsGrid`):
```kotlin
            val freezerMode = prefs[Keys.FREEZER_MODE]
                ?.let { runCatching { FreezerMode.valueOf(it) }.getOrNull() }
                ?: FreezerMode.FREEZE
```
(c) Pass it into the `UserPreferences(...)` builder, right after `autoFreezeEnabled = ...` (line 118):
```kotlin
                autoFreezeEnabled = prefs[Keys.AUTO_FREEZE] ?: false,
                freezerMode = freezerMode,
```
(d) Add the setter next to `setAutoFreezeEnabled` (after line 199):
```kotlin
    override suspend fun setFreezerMode(mode: FreezerMode) {
        context.dataStore.edit {
            it[Keys.FREEZER_MODE] = mode.name
        }
    }
```
(e) Add the import at the top, with the other model imports:
```kotlin
import com.valhalla.thor.domain.model.FreezerMode
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/valhalla/thor/domain/model/UserPreferences.kt \
        app/src/main/java/com/valhalla/thor/domain/repository/PreferenceRepository.kt \
        app/src/main/java/com/valhalla/thor/data/repository/PreferenceRepositoryImpl.kt
git commit -m "feat(freezer): persist freezerMode preference (default FREEZE) (#239)"
```

---

## Task 3: `MultiAppAction.Freeze.useSuspend` + mode-aware / state-aware batch freeze

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/domain/model/MultiAppAction.kt:6`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/main/MainViewModel.kt` (dispatch ~line 393; `performCountedFreeze` ~line 492-525)

**Interfaces:**
- Consumes: `FreezerRestore`, `AppInfo.restoreAction` (Task 1).
- Produces: `MultiAppAction.Freeze(appList, useSuspend: Boolean = false)`; `performCountedFreeze(apps, isFreeze, useSuspend)`.

**Rationale:** The Freezer toolbar's "Freeze all" uses `performCountedFreeze` (compact count UI) and skips unsafe/UAD system apps. Keep that UI + skip when suspending by branching *inside* `performCountedFreeze`, instead of routing to `MultiAppAction.Suspend` (which uses a different verbose logger and no skip). Unfreeze restores each app by its actual state, fixing mixed sets.

- [ ] **Step 1: Add `useSuspend` to `MultiAppAction.Freeze`**

In `MultiAppAction.kt`, change line 6:
```kotlin
    data class Freeze(val appList: List<AppInfo>, val useSuspend: Boolean = false) : MultiAppAction
```
(`UnFreeze` is unchanged — restore is state-driven.)

- [ ] **Step 2: Branch the dispatch**

In `MainViewModel.kt`, the `MultiAppAction.Freeze` case (currently line 393):
```kotlin
                is MultiAppAction.Freeze -> performCountedFreeze(action.appList, isFreeze = true, useSuspend = action.useSuspend)
```
Leave the `UnFreeze` case as-is:
```kotlin
                is MultiAppAction.UnFreeze -> performCountedFreeze(action.appList, isFreeze = false)
```

- [ ] **Step 3: Make `performCountedFreeze` mode-aware and restore-aware**

In `MainViewModel.kt`, update the signature (line 492) and the per-app call (line 516). Replace the signature line:
```kotlin
    private suspend fun performCountedFreeze(apps: List<AppInfo>, isFreeze: Boolean, useSuspend: Boolean = false) {
```
Replace the `targets.forEach { app -> ... }` body (lines 515-524) with:
```kotlin
            targets.forEach { app ->
                val result = if (isFreeze) {
                    if (useSuspend) manageAppUseCase.setAppSuspended(app.packageName, true)
                    else manageAppUseCase.setAppDisabled(app.packageName, true)
                } else {
                    // State-aware restore: unsuspend suspended apps, enable disabled apps.
                    when (app.restoreAction) {
                        FreezerRestore.UNSUSPEND -> manageAppUseCase.setAppSuspended(app.packageName, false)
                        FreezerRestore.ENABLE -> manageAppUseCase.setAppDisabled(app.packageName, false)
                        FreezerRestore.NONE -> Result.success(Unit)
                    }
                }
                processed++
                if (result.isFailure) failed++
                val p = processed
                val f = failed
                _uiState.update {
                    it.copy(freezeLoggerState = it.freezeLoggerState.copy(processed = p, failed = f))
                }
            }
```
Add imports at the top of `MainViewModel.kt` (with the other `domain.model` imports):
```kotlin
import com.valhalla.thor.domain.model.FreezerRestore
import com.valhalla.thor.domain.model.restoreAction
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/valhalla/thor/domain/model/MultiAppAction.kt \
        app/src/main/java/com/valhalla/thor/presentation/main/MainViewModel.kt
git commit -m "feat(freezer): batch freeze honors suspend mode + state-aware restore (#239)"
```

---

## Task 4: `FreezerViewModel` — state, hydration, setter, mode-aware single freeze/unfreeze

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt` (state `:31-49`; `freezeSingleApp` `:225`; `unfreezeSingleApp` `:263`; `observePreferences` `:296`; new setter after `:315`)

**Interfaces:**
- Consumes: `FreezerMode`, `FreezerRestore`, `AppInfo.restoreAction` (Task 1); `preferenceRepository.setFreezerMode` (Task 2).
- Produces: `FreezerUiState.freezerMode`; `FreezerViewModel.setFreezerMode(mode)`.

- [ ] **Step 1: Add `freezerMode` to `FreezerUiState`**

In `FreezerViewModel.kt`, add to the `FreezerUiState` data class (after `autoFreezeEnabled` at line 43):
```kotlin
    val autoFreezeEnabled: Boolean = false,
    val freezerMode: FreezerMode = FreezerMode.FREEZE,
```
Add imports near the top (with the other `domain.model` imports):
```kotlin
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.FreezerRestore
import com.valhalla.thor.domain.model.restoreAction
```

- [ ] **Step 2: Hydrate it in `observePreferences`**

In `observePreferences` (line 299-305), add the field to the `copy`:
```kotlin
                    it.copy(
                        autoFreezeEnabled = prefs.autoFreezeEnabled,
                        freezerMode = prefs.freezerMode,
                        hasShownDisabledAppsPrompt = prefs.hasShownDisabledAppsPrompt,
                        isGrid = prefs.freezerIsGrid,
                        addFreezerToLauncher = prefs.addFreezerToLauncher
                    )
```

- [ ] **Step 3: Add the setter (mirror `setAutoFreezeEnabled`)**

After `setAutoFreezeEnabled` (line 315), add:
```kotlin
    fun setFreezerMode(mode: FreezerMode) {
        viewModelScope.launch(Dispatchers.IO) {
            preferenceRepository.setFreezerMode(mode)
        }
    }
```

- [ ] **Step 4: Make `freezeSingleApp` mode-aware**

In `freezeSingleApp` (line 226-227), replace the first line inside the launch:
```kotlin
        viewModelScope.launch(Dispatchers.IO) {
            val freezeResult = if (_uiState.value.freezerMode == FreezerMode.SUSPEND)
                manageAppUseCase.setAppSuspended(packageName, true)
            else manageAppUseCase.setAppDisabled(packageName, true)
            freezeResult
                .onSuccess {
```
(The existing `.onSuccess { ... }.onFailure { ... }` chain — lines 228-259 — stays exactly the same; only the receiver changed from the inline `setAppDisabled(...)` call to `freezeResult`.)

- [ ] **Step 5: Make `unfreezeSingleApp` state-aware**

In `unfreezeSingleApp` (line 264-265), replace the first line inside the launch:
```kotlin
        viewModelScope.launch(Dispatchers.IO) {
            val app = _uiState.value.freezerApps.firstOrNull { it.packageName == packageName }
                ?: _uiState.value.allInstalledApps.firstOrNull { it.packageName == packageName }
            val restoreResult = when (app?.restoreAction) {
                FreezerRestore.UNSUSPEND -> manageAppUseCase.setAppSuspended(packageName, false)
                else -> manageAppUseCase.setAppDisabled(packageName, false)
            }
            restoreResult
                .onSuccess {
```
(The existing `.onSuccess { ... }.onFailure { ... }` chain — lines 266-286 — stays the same; receiver is now `restoreResult`. `null`/`NONE`/`ENABLE` all fall to `setAppDisabled(false)`, preserving today's behavior.)

- [ ] **Step 6: Verify it compiles**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**
```bash
git add app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt
git commit -m "feat(freezer): mode-aware single freeze + state-aware unfreeze in ViewModel (#239)"
```

---

## Task 5: `FreezerScreen` — frozen predicate, toolbar dispatch, settings wiring

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerScreen.kt` (filters `:120-128`; toolbar `:372`; settings-sheet call site `:456`)

**Interfaces:**
- Consumes: `AppInfo.isActive`, `AppInfo.isFrozen` (Task 1); `MultiAppAction.Freeze(..., useSuspend)` (Task 3); `state.freezerMode`, `viewModel.setFreezerMode` (Task 4); `FreezerSettingsSheet(freezerMode, onFreezerModeChange, ...)` (Task 7).

- [ ] **Step 1: Generalize the frozen predicate**

In `FreezerScreen.kt`, replace the four `remember` blocks at lines 120-128 with:
```kotlin
    // "Active" = freezable (enabled & not suspended); "frozen" = disabled OR suspended (GH#239).
    val hasEnabled = remember(state.freezerApps) { state.freezerApps.any { it.isActive } }
    val hasDisabled = remember(state.freezerApps) { state.freezerApps.any { it.isFrozen } }

    // Apps the "Freeze all" / "Unfreeze all" toolbar acts on. These route through the
    // shared batch action (MultiAppAction) so progress streams into the FreezeLoggerDialog;
    // the unsafe/UAD eligibility skip is applied centrally by MainViewModel.performCountedFreeze.
    val appsToFreeze = remember(state.freezerApps) { state.freezerApps.filter { it.isActive } }
    val appsToUnfreeze = remember(state.freezerApps) { state.freezerApps.filter { it.isFrozen } }
```
Add imports (with the other `domain.model` imports):
```kotlin
import com.valhalla.thor.domain.model.isActive
import com.valhalla.thor.domain.model.isFrozen
```

- [ ] **Step 2: Route the toolbar Freeze button through the mode**

In `FreezerScreen.kt`, the Freeze `IconButton` (line 372), change `onClick`:
```kotlin
                        IconButton(
                            onClick = {
                                onMultiAppAction(
                                    MultiAppAction.Freeze(
                                        appsToFreeze,
                                        useSuspend = state.freezerMode == FreezerMode.SUSPEND
                                    )
                                )
                            },
                            enabled = hasEnabled && hasPrivilege,
                            colors = iconButtonColors
                        ) {
```
(The Unfreeze button at line 391 is unchanged — `MultiAppAction.UnFreeze(appsToUnfreeze)` now restores mixed states via Task 3.)
Add the import:
```kotlin
import com.valhalla.thor.domain.model.FreezerMode
```

- [ ] **Step 3: Pass the mode into the settings sheet**

In `FreezerScreen.kt`, the `FreezerSettingsSheet(...)` call site (line 456), add two arguments (e.g. right after `autoFreezeEnabled = state.autoFreezeEnabled,`):
```kotlin
            autoFreezeEnabled = state.autoFreezeEnabled,
            freezerMode = state.freezerMode,
            onFreezerModeChange = viewModel::setFreezerMode,
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: FAIL initially at the `FreezerSettingsSheet` call (params don't exist yet) — that is wired in Task 7. If implementing strictly task-by-task, do Task 7 before re-running; otherwise the compile passes once Task 7 lands. (Tasks 5 and 7 form one compilable pair — commit both, then verify.)

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerScreen.kt
git commit -m "feat(freezer): frozen=disabled-or-suspended predicate + mode-aware toolbar (#239)"
```

---

## Task 6: `AutoFreezeManager` — suspend branch in the screen-off loop

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/data/service/AutoFreezeManager.kt` (fields; `startObserving` `:135`; freeze loop `:97-110`)

**Interfaces:**
- Consumes: `FreezerMode` (Task 1); `prefs.freezerMode` (Task 2); `manageAppUseCase.setAppSuspended` (existing).

- [ ] **Step 1: Track the current mode**

In `AutoFreezeManager.kt`, add a volatile field beside `isReceiverRegistered` (line ~39):
```kotlin
    @Volatile
    private var currentMode: FreezerMode = FreezerMode.FREEZE
```
Add the import:
```kotlin
import android.content.pm.ApplicationInfo
import com.valhalla.thor.domain.model.FreezerMode
```

- [ ] **Step 2: Update the mode inside the existing preference observer**

In `startObserving` (line 135-141), capture the mode before the enable check:
```kotlin
            preferenceRepository.userPreferences.collectLatest { prefs ->
                currentMode = prefs.freezerMode
                if (prefs.autoFreezeEnabled) {
                    registerReceiver()
                } else {
                    unregisterReceiver()
                }
            }
```

- [ ] **Step 3: Branch the per-package freeze in the screen-off loop**

In the loop, replace the `val appInfo = pm.getApplicationInfo(pkg, 0)` block (lines 97-110) with:
```kotlin
                                    val appInfo = pm.getApplicationInfo(pkg, 0)
                                    if (currentMode == FreezerMode.SUSPEND) {
                                        val alreadySuspended = (appInfo.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
                                        if (!alreadySuspended) {
                                            Logger.d("AutoFreezeManager", "Auto-suspending app: $pkg")
                                            val result = manageAppUseCase.setAppSuspended(pkg, true)
                                            if (result.isSuccess) {
                                                Logger.d("AutoFreezeManager", "Auto-suspended: $pkg")
                                                freezerShortcutManager.refreshAppShortcut(pkg)
                                            } else {
                                                Logger.e(
                                                    "AutoFreezeManager",
                                                    "Failed to suspend $pkg: ${result.exceptionOrNull()?.message}"
                                                )
                                            }
                                        }
                                    } else if (appInfo.enabled) {
                                        Logger.d("AutoFreezeManager", "Auto-freezing app: $pkg")
                                        val result = manageAppUseCase.setAppDisabled(pkg, true)
                                        if (result.isSuccess) {
                                            Logger.d("AutoFreezeManager", "Auto-froze: $pkg")
                                            freezerShortcutManager.refreshAppShortcut(pkg) // → grey the shortcut icon
                                        } else {
                                            Logger.e(
                                                "AutoFreezeManager",
                                                "Failed to freeze $pkg: ${result.exceptionOrNull()?.message}"
                                            )
                                        }
                                    }
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/valhalla/thor/data/service/AutoFreezeManager.kt
git commit -m "feat(freezer): auto-freeze suspends apps when suspend mode is active (#239)"
```

---

## Task 7: `FreezerSettingsSheet` — `ConnectedButtonGroup` mode selector + strings

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerSettingsSheet.kt` (params `:48-65`; insert selector before the Auto Freeze row at `:240`)
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `FreezerMode` (Task 1). Called by `FreezerScreen` (Task 5) with `freezerMode` + `onFreezerModeChange`.

- [ ] **Step 1: Add the strings**

In `strings.xml`, add near the other freezer strings (e.g. after `freezer_settings` at line 210). `action_freeze` ("Freeze") and `action_suspend` ("Suspend") already exist and are reused for the segmented labels:
```xml
    <string name="freeze_mode">Freeze Mode</string>
    <string name="suspend_instead_of_freeze">Suspend instead of freeze</string>
    <string name="suspend_instead_of_freeze_desc">When freezing (including auto-freeze), suspend apps instead of disabling them. Suspended apps stay visible but paused.</string>
```

- [ ] **Step 2: Add the two params to `FreezerSettingsSheet`**

In `FreezerSettingsSheet.kt`, add to the parameter list (after `autoFreezeEnabled: Boolean,` at line 50):
```kotlin
    autoFreezeEnabled: Boolean,
    freezerMode: FreezerMode,
    onFreezerModeChange: (FreezerMode) -> Unit,
```
Add the import:
```kotlin
import com.valhalla.thor.domain.model.FreezerMode
```

- [ ] **Step 3: Render the mode selector before the Auto Freeze row**

In `FreezerSettingsSheet.kt`, immediately before the Auto Freeze `Row` (the `Spacer(Modifier.height(24.dp))` at line 238 that precedes it), insert:
```kotlin
            Spacer(Modifier.height(24.dp))

            // Freeze vs Suspend mode (GH#239) — same segmented control style as the selectors above.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.freeze_mode),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                ConnectedButtonGroup(
                    items = listOf(
                        ConnectedButtonGroupItem.Label(stringResource(R.string.action_freeze)),
                        ConnectedButtonGroupItem.Label(stringResource(R.string.action_suspend))
                    ),
                    selectedIndex = freezerMode.ordinal,
                    onItemSelected = { onFreezerModeChange(FreezerMode.entries[it]) },
                    enabled = hasPrivilege,
                    modifier = Modifier.width(IntrinsicSize.Max)
                )
            }
```
(`ConnectedButtonGroup` / `ConnectedButtonGroupItem` are already imported at lines 43-44.)

- [ ] **Step 4: Verify it compiles (with Task 5 this is the full pair)**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerSettingsSheet.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(freezer): Freeze/Suspend segmented selector in the settings sheet (#239)"
```

---

## Task 8: Settings screen — "Suspend instead of freeze" switch

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/settings/SettingsViewModel.kt` (after `setAutoFreezeEnabled` `:136-139`)
- Modify: `app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt` (Freezer section, before the auto-freeze `SettingsSwitchRow` at `:388`)

**Interfaces:**
- Consumes: `FreezerMode` (Task 1); `preferenceRepository.setFreezerMode` (Task 2); `prefs.freezerMode` from the collected `preferences` flow.
- Produces: `SettingsViewModel.setFreezerMode(mode)`.

- [ ] **Step 1: Add the setter to `SettingsViewModel`**

In `SettingsViewModel.kt`, after `setAutoFreezeEnabled` (line 136-139), add:
```kotlin
    fun setFreezerMode(mode: FreezerMode) {
        viewModelScope.launch {
            preferenceRepository.setFreezerMode(mode)
        }
    }
```
Add the import (with the other `domain.model` imports):
```kotlin
import com.valhalla.thor.domain.model.FreezerMode
```

- [ ] **Step 2: Add the switch to the Freezer section**

In `SettingsScreen.kt`, immediately before the Auto Freeze `SettingsSwitchRow` (line 388, `checked = prefs.autoFreezeEnabled`), add:
```kotlin
            SettingsSwitchRow(
                title = stringResource(R.string.suspend_instead_of_freeze),
                subtitle = stringResource(R.string.suspend_instead_of_freeze_desc),
                checked = prefs.freezerMode == FreezerMode.SUSPEND,
                onCheckedChange = { viewModel.setFreezerMode(if (it) FreezerMode.SUSPEND else FreezerMode.FREEZE) }
            )
```
Add the import (with the other `domain.model` imports):
```kotlin
import com.valhalla.thor.domain.model.FreezerMode
```
Note: this switch and the sheet's `ConnectedButtonGroup` both read/write the single `freezerMode` preference, so they stay in sync automatically. Verify `prefs` in this scope is the collected `preferences` flow value (the same `prefs` used by `prefs.autoFreezeEnabled` on line 394).

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/com/valhalla/thor/presentation/settings/SettingsViewModel.kt \
        app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt
git commit -m "feat(freezer): add 'Suspend instead of freeze' switch to Settings (#239)"
```

---

## Task 9: Full build, unit tests, and on-device verification

**Files:** none (verification only).

- [ ] **Step 1: Full assemble + unit tests**

Run: `./gradlew :app:testFossDebugUnitTest assembleFossDebug`
Expected: BUILD SUCCESSFUL; `FreezerModeTest` green; no Asgard/Kotlin errors (pre-existing deprecation warnings OK).

- [ ] **Step 2: Install + on-device matrix**

Run: `adb install -r app/build/outputs/apk/fossDebug/*.apk` (or `foss/debug`), then verify per available backend (Root / Shizuku / Dhizuku):
- Toggle the mode via **both** the Freezer sheet segmented control and the Settings switch — confirm they stay in sync.
- **Suspend mode:** single-app freeze, multi "Freeze all", and screen-off auto-freeze (lock the device) all *suspend* (app greyed/paused, stays in launcher, shows the "suspended by Thor" dialog on tap).
- **Unfreeze a mixed set** (some disabled, some suspended) → all return to active.
- **Freeze mode** unchanged from today (apps `pm disable`, disappear from launcher).
- Suspend failure on a backend surfaces an error in the count dialog (no silent disable fallback).

- [ ] **Step 3 (optional, on release): bump version**

Per `CLAUDE.md`, releasing bumps `versionCode` in `gradle.properties` (version name auto-derives). Do this only when cutting the release, not per-feature.

- [ ] **Step 4: Request code review**

Use superpowers:requesting-code-review (or the `/code-review` skill) on the branch diff before opening the PR.

---

## Self-Review

**Spec coverage:** §5 data model → Tasks 1-2; §6 ViewModel → Task 4; §7 entry points → Tasks 3 (batch), 4 (single), 6 (auto-freeze); §7.4 launcher shortcuts → **see note below**; §8 state-aware restore → Tasks 3-4 (+Task 1 logic); §9 frozen predicate → Task 5; §10 UI (both controls) → Tasks 7-8; §11 error handling → inherited (Result pipeline) + Task 3 keeps the unsafe skip; §12 testing → Tasks 1 + 9; §13 out-of-scope respected.

**Launcher-shortcut note (spec §7.4):** the pinned "Freeze all"/"Unfreeze all" launcher shortcuts trigger via `FreezerShortcutContract` and are **not** re-routed through the mode in this plan (they remain disable-based). This is the documented fast-follow from the spec, intentionally deferred — not silently dropped. If it should honor the mode now, add a task to branch the shortcut handler on `freezerMode`.

**Placeholder scan:** none — every code step shows complete code.

**Type consistency:** `FreezerMode`, `FreezerRestore`, `isActive`/`isFrozen`/`restoreAction`, `MultiAppAction.Freeze(..., useSuspend)`, `performCountedFreeze(apps, isFreeze, useSuspend)`, `setFreezerMode` — names identical across Tasks 1-8.
