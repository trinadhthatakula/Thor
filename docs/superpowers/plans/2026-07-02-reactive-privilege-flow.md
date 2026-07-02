# Reactive Privilege Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace per-ViewModel one-shot privilege probes with a single reactive source of truth that updates automatically on Shizuku grant and preference changes.

**Architecture:** A `@Single PrivilegeManager` (data layer) exposes `StateFlow<PrivilegeState>` — root/shizuku/dhizuku availability + a resolved `active: PrivilegeMode` — re-probing off the main thread on init, on Shizuku binder/permission events (it owns those listeners), and whenever `preferredPrivilegeMode` changes. Home/AppList/Freezer ViewModels observe it instead of probing.

**Tech Stack:** Kotlin, Coroutines/Flow (`StateFlow`, `combine`), Koin Annotations (`@Single`, `@KoinViewModel`, KSP), Shizuku (`rikka.shizuku.Shizuku`), JUnit4.

## Global Constraints

- Koin DI is annotation-based, scanned via `@ComponentScan("com.valhalla.thor")` in `di/Modules.kt` — a `@Single`-annotated class anywhere under `com.valhalla.thor` is auto-registered. No manual module edits needed.
- Privilege probes (`isRootAvailable` runs a root shell; Shizuku/Dhizuku do binder IPC) MUST run off the main thread (`Dispatchers.IO`).
- `preferredPrivilegeMode: PrivilegeMode?` in prefs stays nullable (`null` = auto). `PrivilegeMode.NONE` is used only by the reactive `active` value and is NEVER persisted as a preference.
- `SystemRepositoryImpl.getActiveGateway()` (the imperative operation path) is OUT OF SCOPE — do not modify it.
- Build/verify the `fossDebug` variant: `./gradlew :app:compileFossDebugKotlin`.

---

### Task 1: PrivilegeMode + PrivilegeState + pure resolver (with tests)

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/domain/model/PrivilegeMode.kt`
- Create: `app/src/main/java/com/valhalla/thor/domain/model/PrivilegeState.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/model/PrivilegeResolverTest.kt`

**Interfaces:**
- Produces:
  - `enum class PrivilegeMode { NONE, ROOT, SHIZUKU, DHIZUKU }`
  - `data class PrivilegeState(root: Boolean, shizuku: Boolean, dhizuku: Boolean, active: PrivilegeMode, isReady: Boolean)` with `val hasAnyPrivilege: Boolean`
  - `fun resolvePrivilegeMode(preferred: PrivilegeMode?, root: Boolean, shizuku: Boolean, dhizuku: Boolean): PrivilegeMode`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/valhalla/thor/domain/model/PrivilegeResolverTest.kt`:

```kotlin
package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeResolverTest {

    @Test
    fun preferred_isUsed_whenAvailable() {
        assertEquals(
            PrivilegeMode.SHIZUKU,
            resolvePrivilegeMode(PrivilegeMode.SHIZUKU, root = true, shizuku = true, dhizuku = false)
        )
    }

    @Test
    fun preferred_fallsBack_whenUnavailable() {
        // Preferred SHIZUKU not available -> auto fallback picks ROOT (highest available).
        assertEquals(
            PrivilegeMode.ROOT,
            resolvePrivilegeMode(PrivilegeMode.SHIZUKU, root = true, shizuku = false, dhizuku = true)
        )
    }

    @Test
    fun noPreference_usesFallbackChain_rootFirst() {
        assertEquals(
            PrivilegeMode.ROOT,
            resolvePrivilegeMode(null, root = true, shizuku = true, dhizuku = true)
        )
    }

    @Test
    fun noPreference_shizukuBeforeDhizuku() {
        assertEquals(
            PrivilegeMode.SHIZUKU,
            resolvePrivilegeMode(null, root = false, shizuku = true, dhizuku = true)
        )
    }

    @Test
    fun noneAvailable_isNone() {
        assertEquals(
            PrivilegeMode.NONE,
            resolvePrivilegeMode(PrivilegeMode.ROOT, root = false, shizuku = false, dhizuku = false)
        )
    }

    @Test
    fun preferredNone_isTreatedAsAuto() {
        assertEquals(
            PrivilegeMode.DHIZUKU,
            resolvePrivilegeMode(PrivilegeMode.NONE, root = false, shizuku = false, dhizuku = true)
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.domain.model.PrivilegeResolverTest"`
Expected: FAIL — compilation error (`resolvePrivilegeMode` / `PrivilegeMode.NONE` unresolved).

- [ ] **Step 3: Add `NONE` to the enum**

Replace the entire contents of `app/src/main/java/com/valhalla/thor/domain/model/PrivilegeMode.kt`:

```kotlin
package com.valhalla.thor.domain.model

enum class PrivilegeMode {
    /** No privilege available (reactive/active value only — never persisted as a preference). */
    NONE,
    ROOT,
    SHIZUKU,
    DHIZUKU
}
```

- [ ] **Step 4: Create `PrivilegeState` + `resolvePrivilegeMode`**

Create `app/src/main/java/com/valhalla/thor/domain/model/PrivilegeState.kt`:

```kotlin
package com.valhalla.thor.domain.model

/**
 * Snapshot of privilege availability + the resolved active mode.
 *
 * [isReady] is false until the first probe completes, so consumers can tell
 * "not probed yet" apart from "probed, nothing available" (avoids a flash of the
 * no-privilege UI on cold start).
 */
data class PrivilegeState(
    val root: Boolean = false,
    val shizuku: Boolean = false,
    val dhizuku: Boolean = false,
    val active: PrivilegeMode = PrivilegeMode.NONE,
    val isReady: Boolean = false
) {
    val hasAnyPrivilege: Boolean get() = active != PrivilegeMode.NONE
}

/**
 * Resolve the effective privilege mode: the user's [preferred] mode when it is
 * actually available, otherwise the first available in Root -> Shizuku -> Dhizuku
 * order, otherwise [PrivilegeMode.NONE]. A null or NONE [preferred] means "auto".
 */
fun resolvePrivilegeMode(
    preferred: PrivilegeMode?,
    root: Boolean,
    shizuku: Boolean,
    dhizuku: Boolean
): PrivilegeMode {
    when (preferred) {
        PrivilegeMode.ROOT -> if (root) return PrivilegeMode.ROOT
        PrivilegeMode.SHIZUKU -> if (shizuku) return PrivilegeMode.SHIZUKU
        PrivilegeMode.DHIZUKU -> if (dhizuku) return PrivilegeMode.DHIZUKU
        PrivilegeMode.NONE, null -> Unit // fall through to auto
    }
    return when {
        root -> PrivilegeMode.ROOT
        shizuku -> PrivilegeMode.SHIZUKU
        dhizuku -> PrivilegeMode.DHIZUKU
        else -> PrivilegeMode.NONE
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.domain.model.PrivilegeResolverTest"`
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/model/PrivilegeMode.kt \
        app/src/main/java/com/valhalla/thor/domain/model/PrivilegeState.kt \
        app/src/test/java/com/valhalla/thor/domain/model/PrivilegeResolverTest.kt
git commit -m "feat(privilege): add PrivilegeMode.NONE, PrivilegeState + resolver

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: PrivilegeManager (reactive source of truth)

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/manager/PrivilegeManager.kt`

**Interfaces:**
- Consumes: `SystemRepository` (`isRootAvailable`/`isShizukuAvailable`/`isDhizukuAvailable`), `PreferenceRepository.userPreferences` (`preferredPrivilegeMode`), `resolvePrivilegeMode(...)`, `PrivilegeState`.
- Produces:
  - `@Single class PrivilegeManager(SystemRepository, PreferenceRepository)`
  - `val state: StateFlow<PrivilegeState>`
  - `fun refresh()`

- [ ] **Step 1: Create the manager**

Create `app/src/main/java/com/valhalla/thor/data/manager/PrivilegeManager.kt`:

```kotlin
package com.valhalla.thor.data.manager

import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.model.resolvePrivilegeMode
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import rikka.shizuku.Shizuku

/**
 * Single reactive source of truth for privilege availability + the active mode.
 *
 * Re-probes root/Shizuku/Dhizuku off the main thread on init, on [refresh], on
 * Shizuku binder/permission events (it owns those listeners), and whenever the
 * preferred mode changes. As a process-lifetime @Single it never unregisters its
 * Shizuku listeners (they live for the app), so consumers created before a
 * first-launch grant still see it once granted.
 */
@Single
class PrivilegeManager(
    private val systemRepository: SystemRepository,
    private val preferenceRepository: PreferenceRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(PrivilegeState())
    val state: StateFlow<PrivilegeState> = _state.asStateFlow()

    // Bumped to force a re-probe; StateFlow<Int> emits on every distinct value.
    private val refreshTrigger = MutableStateFlow(0)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    init {
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        scope.launch {
            combine(availabilityFlow(), preferenceRepository.userPreferences) { avail, prefs ->
                PrivilegeState(
                    root = avail.root,
                    shizuku = avail.shizuku,
                    dhizuku = avail.dhizuku,
                    active = resolvePrivilegeMode(
                        prefs.preferredPrivilegeMode,
                        avail.root,
                        avail.shizuku,
                        avail.dhizuku
                    ),
                    isReady = true
                )
            }.collect { _state.value = it }
        }
    }

    /** Force a re-probe (e.g. after the user enables root outside the app). */
    fun refresh() {
        refreshTrigger.value += 1
    }

    private data class Availability(val root: Boolean, val shizuku: Boolean, val dhizuku: Boolean)

    private fun availabilityFlow(): Flow<Availability> =
        refreshTrigger
            .map {
                Availability(
                    root = safeProbe { systemRepository.isRootAvailable() },
                    shizuku = safeProbe { systemRepository.isShizukuAvailable() },
                    dhizuku = safeProbe { systemRepository.isDhizukuAvailable() }
                )
            }
            .flowOn(Dispatchers.IO)

    private suspend fun safeProbe(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: Exception) {
        Logger.e("PrivilegeManager", "privilege probe failed", e)
        false
    }
}
```

- [ ] **Step 2: Verify it compiles and Koin can construct it**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL. (The class is under `com.valhalla.thor`, so `@ComponentScan("com.valhalla.thor")` registers it; no `di/Modules.kt` change is needed.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/data/manager/PrivilegeManager.kt
git commit -m "feat(privilege): add PrivilegeManager reactive StateFlow<PrivilegeState>

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Migrate FreezerViewModel to observe PrivilegeManager

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt`

**Interfaces:**
- Consumes: `PrivilegeManager.state: StateFlow<PrivilegeState>`.

- [ ] **Step 1: Swap the dependency and imports**

In `FreezerViewModel.kt`, replace the import line:

```kotlin
import com.valhalla.thor.presentation.common.ShizukuPermissionHandler
```

with:

```kotlin
import com.valhalla.thor.data.manager.PrivilegeManager
```

Then remove the now-unused import `import com.valhalla.thor.domain.repository.SystemRepository` (it will be flagged unused after Step 2).

In the constructor, replace:

```kotlin
    private val systemRepository: SystemRepository,
    private val preferenceRepository: PreferenceRepository
) : ViewModel() {
```

with:

```kotlin
    private val privilegeManager: PrivilegeManager,
    private val preferenceRepository: PreferenceRepository
) : ViewModel() {
```

- [ ] **Step 2: Replace the shim + one-shot probe with an observer**

Replace this block:

```kotlin
    // Re-check privileges when Shizuku's binder connects or the user grants permission.
    // This ViewModel is created at app start (before the first-launch Shizuku grant), so
    // its init-time loadPrivileges() would otherwise stay stale until an app restart.
    private val shizukuHandler = ShizukuPermissionHandler(
        onPermissionGranted = { loadPrivileges() }
    )

    init {
        observeApps()
        observePreferences()
        loadPrivileges()
        shizukuHandler.register()
    }

    override fun onCleared() {
        shizukuHandler.unregister()
        super.onCleared()
    }
```

with:

```kotlin
    init {
        observeApps()
        observePreferences()
        observePrivileges()
    }
```

Then delete the `loadPrivileges()` function entirely:

```kotlin
    private fun loadPrivileges() {
        viewModelScope.launch(Dispatchers.IO) {
            val hasRoot = systemRepository.isRootAvailable()
            val hasShizuku = systemRepository.isShizukuAvailable()
            val hasDhizuku = systemRepository.isDhizukuAvailable()
            _uiState.update {
                it.copy(
                    isRoot = hasRoot,
                    isShizuku = hasShizuku,
                    isDhizuku = hasDhizuku
                )
            }
        }
    }
```

and add the observer (place it where `loadPrivileges` was):

```kotlin
    private fun observePrivileges() {
        viewModelScope.launch {
            privilegeManager.state.collect { p ->
                _uiState.update {
                    it.copy(isRoot = p.root, isShizuku = p.shizuku, isDhizuku = p.dhizuku)
                }
            }
        }
    }
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL, with no "unused import" for `SystemRepository` or `Dispatchers` — if `Dispatchers` is now unused (it was only used by `loadPrivileges`), remove `import kotlinx.coroutines.Dispatchers`. (Note: `observeApps` uses `flowOn(Dispatchers.Default)`, so `Dispatchers` IS still used — keep it.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt
git commit -m "refactor(freezer): observe PrivilegeManager instead of one-shot probe + Shizuku shim

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Migrate AppListViewModel to observe PrivilegeManager

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/appList/AppListViewModel.kt`

**Interfaces:**
- Consumes: `PrivilegeManager.state`.

- [ ] **Step 1: Swap the dependency**

In `AppListViewModel.kt`, add the import:

```kotlin
import com.valhalla.thor.data.manager.PrivilegeManager
```

In the constructor, replace the parameter:

```kotlin
    private val systemRepository: SystemRepository,
```

with:

```kotlin
    private val privilegeManager: PrivilegeManager,
```

Remove the now-unused `import com.valhalla.thor.domain.repository.SystemRepository` (verify with a search that `systemRepository` has no other references after Step 2; it does not).

- [ ] **Step 2: Combine privilege state into the app collection**

Replace this block in `loadApps()`:

```kotlin
            // Availability probes include non-suspend binder IPC (Shizuku / Dhizuku);
            // keep them off the Main thread so app-list load never janks.
            val (hasRoot, hasShizuku, hasDhizuku) = withContext(Dispatchers.IO) {
                Triple(
                    systemRepository.isRootAvailable(),
                    systemRepository.isShizukuAvailable(),
                    systemRepository.isDhizukuAvailable()
                )
            }
            getInstalledAppsUseCase().collect { (user, system) ->
                _rawState.update {
                    it.copy(
                        isLoading = false,
                        isRoot = hasRoot,
                        isShizuku = hasShizuku,
                        isDhizuku = hasDhizuku,
                        allUserApps = user,
                        allSystemApps = system
                    )
                }
            }
```

with:

```kotlin
            // Privilege availability now comes from the shared reactive PrivilegeManager,
            // so a Shizuku grant reflects here without reloading the list.
            combine(
                getInstalledAppsUseCase(),
                privilegeManager.state
            ) { (user, system), priv ->
                Triple(user, system, priv)
            }.collect { (user, system, priv) ->
                _rawState.update {
                    it.copy(
                        isLoading = false,
                        isRoot = priv.root,
                        isShizuku = priv.shizuku,
                        isDhizuku = priv.dhizuku,
                        allUserApps = user,
                        allSystemApps = system
                    )
                }
            }
```

Ensure these imports exist (add any missing): `import kotlinx.coroutines.flow.combine`. If `withContext`/`Dispatchers` are now unused elsewhere in the file, remove their imports; otherwise leave them.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/appList/AppListViewModel.kt
git commit -m "refactor(applist): source privileges from reactive PrivilegeManager

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Migrate HomeViewModel to observe PrivilegeManager

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/home/HomeViewModel.kt`

**Interfaces:**
- Consumes: `PrivilegeManager.state`, `PrivilegeMode`.

- [ ] **Step 1: Swap the dependency**

Add import:

```kotlin
import com.valhalla.thor.data.manager.PrivilegeManager
```

In the constructor, replace:

```kotlin
    private val systemRepository: SystemRepository,
    private val preferenceRepository: PreferenceRepository // Injected
```

with:

```kotlin
    private val privilegeManager: PrivilegeManager,
    private val preferenceRepository: PreferenceRepository // Injected
```

Remove `import com.valhalla.thor.domain.repository.SystemRepository` (unused after this task).

- [ ] **Step 2: Derive privilege fields from the manager in the state combine**

Replace the `state` combine:

```kotlin
    val state = combine(_internalState, preferenceRepository.userPreferences) { internal, prefs ->
        val activeMode = prefs.preferredPrivilegeMode ?: when {
            internal.isRootAvailable -> PrivilegeMode.ROOT
            internal.isShizukuAvailable -> PrivilegeMode.SHIZUKU
            internal.isDhizukuAvailable -> PrivilegeMode.DHIZUKU
            else -> null
        }
        internal.copy(
            showReinstallCard = prefs.showReinstallAllCard,
            activePrivilegeMode = activeMode,
            extensionsUnlocked = prefs.extensionsUnlocked
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        HomeUiState()
    )
```

with:

```kotlin
    val state = combine(
        _internalState,
        preferenceRepository.userPreferences,
        privilegeManager.state
    ) { internal, prefs, priv ->
        internal.copy(
            showReinstallCard = prefs.showReinstallAllCard,
            isRootAvailable = priv.root,
            isShizukuAvailable = priv.shizuku,
            isDhizukuAvailable = priv.dhizuku,
            // Keep the existing "null = no privilege" contract for the UI.
            activePrivilegeMode = priv.active.takeIf { it != PrivilegeMode.NONE },
            extensionsUnlocked = prefs.extensionsUnlocked
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        HomeUiState()
    )
```

- [ ] **Step 3: Stop probing/threading privileges through dashboard loading**

Replace `loadDashboardData()`:

```kotlin
    fun loadDashboardData() {
        // Cancel any existing job to ensure we restart with fresh system status
        dashboardJob?.cancel()

        dashboardJob = viewModelScope.launch(Dispatchers.IO) {
            _internalState.update { it.copy(isLoading = true) }
            val hasRoot = systemRepository.isRootAvailable()
            val hasShizuku = systemRepository.isShizukuAvailable()
            val hasDhizuku = systemRepository.isDhizukuAvailable()

            getInstalledAppsUseCase().collect { (userApps, systemApps) ->
                lastUserApps = userApps
                lastSystemApps = systemApps
                processData(
                    userApps,
                    systemApps,
                    _internalState.value.selectedType,
                    hasRoot,
                    hasShizuku,
                    hasDhizuku
                )
            }
        }
    }
```

with:

```kotlin
    fun loadDashboardData() {
        // Cancel any existing job to ensure we restart with fresh data
        dashboardJob?.cancel()

        dashboardJob = viewModelScope.launch(Dispatchers.IO) {
            _internalState.update { it.copy(isLoading = true) }
            getInstalledAppsUseCase().collect { (userApps, systemApps) ->
                lastUserApps = userApps
                lastSystemApps = systemApps
                processData(userApps, systemApps, _internalState.value.selectedType)
            }
        }
    }
```

Replace `onTypeChanged()`:

```kotlin
    fun onTypeChanged(type: AppListType) {
        _internalState.update { it.copy(selectedType = type) }
        typeChangeJob?.cancel()
        typeChangeJob = viewModelScope.launch(Dispatchers.IO) {
            val s = _internalState.value
            processData(
                lastUserApps,
                lastSystemApps,
                type,
                s.isRootAvailable,
                s.isShizukuAvailable,
                s.isDhizukuAvailable
            )
        }
    }
```

with:

```kotlin
    fun onTypeChanged(type: AppListType) {
        _internalState.update { it.copy(selectedType = type) }
        typeChangeJob?.cancel()
        typeChangeJob = viewModelScope.launch(Dispatchers.IO) {
            processData(lastUserApps, lastSystemApps, type)
        }
    }
```

Replace `onPrivilegeModeChanged()`:

```kotlin
    fun onPrivilegeModeChanged(mode: PrivilegeMode) {
        viewModelScope.launch {
            preferenceRepository.setPrivilegeMode(mode)
            loadDashboardData() // Refresh everything
        }
    }
```

with:

```kotlin
    fun onPrivilegeModeChanged(mode: PrivilegeMode) {
        // PrivilegeManager observes the preference and recomputes `active` reactively;
        // no dashboard reload needed (app stats don't depend on the privilege mode).
        viewModelScope.launch {
            preferenceRepository.setPrivilegeMode(mode)
        }
    }
```

- [ ] **Step 4: Drop the privilege params from `processData`**

Replace the `processData` signature + its trailing `_internalState.update` block.

Signature — replace:

```kotlin
    private fun processData(
        userApps: List<AppInfo>,
        systemApps: List<AppInfo>,
        selectedType: AppListType,
        hasRoot: Boolean,
        hasShizuku: Boolean,
        hasDhizuku: Boolean
    ) {
```

with:

```kotlin
    private fun processData(
        userApps: List<AppInfo>,
        systemApps: List<AppInfo>,
        selectedType: AppListType
    ) {
```

Final update — replace:

```kotlin
        _internalState.update {
            it.copy(
                isLoading = false,
                selectedType = selectedType,
                activeAppCount = activeCount,
                frozenAppCount = frozenCount,
                suspendedAppCount = suspendedCount,
                unknownInstallerCount = unknownCount,
                distributionData = distribution,
                isRootAvailable = hasRoot,
                isShizukuAvailable = hasShizuku,
                isDhizukuAvailable = hasDhizuku
            )
        }
```

with:

```kotlin
        _internalState.update {
            it.copy(
                isLoading = false,
                selectedType = selectedType,
                activeAppCount = activeCount,
                frozenAppCount = frozenCount,
                suspendedAppCount = suspendedCount,
                unknownInstallerCount = unknownCount,
                distributionData = distribution
                // Privilege fields are populated by the state combine from PrivilegeManager.
            )
        }
```

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL. `combine` with 3 flows is already imported (`kotlinx.coroutines.flow.combine`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/home/HomeViewModel.kt
git commit -m "refactor(home): source privileges from reactive PrivilegeManager

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Full build, tests, and manual verification

**Files:** none (verification only).

- [ ] **Step 1: Full compile + unit tests**

Run: `./gradlew :app:testFossDebugUnitTest`
Expected: BUILD SUCCESSFUL; `PrivilegeResolverTest` (6) + existing `BundleAnalysisTest`/`BundleZipTest` all pass.

- [ ] **Step 2: Assemble the debug APK**

Run: `./gradlew assembleFossDebug`
Expected: BUILD SUCCESSFUL (confirms Koin can resolve `PrivilegeManager` into all three ViewModels at runtime wiring/KSP).

- [ ] **Step 3: Manual verification on device (checklist)**

- Fresh install, do NOT pre-grant Shizuku. Launch app → when prompted, grant Shizuku.
  - Home: privilege status reflects Shizuku immediately.
  - Navigate to Freezer WITHOUT restarting: the toolbar/FAB are enabled (privilege recognized) — this is the original bug, now fixed reactively.
  - App List: privilege-gated actions available.
- Settings → change preferred privilege mode → Home's active mode updates live (no restart).
- If root is available, confirm the fallback still selects ROOT when no preference is set.

- [ ] **Step 4: Push the branch**

```bash
git push -u origin feat/reactive-privilege-flow
```

---

## Notes for the implementer

- Do NOT touch `SystemRepositoryImpl.getActiveGateway()` — the imperative operation path stays as-is.
- The `HomeActivity` `ShizukuPermissionHandler` is unchanged: it still *requests* permission; `PrivilegeManager` independently observes the *result* via its own listeners.
- `PrivilegeManager` is created when the first consumer ViewModel is injected (app start), so its Shizuku listeners are registered before a first-launch grant.
