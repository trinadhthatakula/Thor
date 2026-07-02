# Sort Apps by Size (#57) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Size" sort option to the app list, ordering by **total install size** (app + data + cache), with a persisted stale-while-revalidate cache and a Usage-Access permission flow.

**Architecture:** A pure `sortApps()` function (unit-tested) gains a `SIZE` branch. `StorageStatsManager` computes sizes lazily when the user picks "Size"; results update the in-memory list and persist to Room (reused instantly next launch). The `GET_USAGE_STATS` app-op is silently granted via the active privilege gateway when available, always re-verified, with a Settings/dialog fallback.

**Tech Stack:** Kotlin, Coroutines/Flow, Room (KSP, schema export on, currently v4), Koin Annotations (`@Single`/`@KoinViewModel`), Jetpack Compose, `StorageStatsManager`, `AppOpsManager`, JUnit4.

## Global Constraints
- Build/verify the `fossDebug` variant: `./gradlew :app:testFossDebugUnitTest` (compiles + runs unit tests).
- Koin is annotation-based, scanned via `@ComponentScan("com.valhalla.thor")` — any `@Single`/`@KoinViewModel` under that package is auto-registered; no `di/Modules.kt` edit needed. `Context` is injectable (existing `@Single` classes take it).
- minSdk 28: `unsafeCheckOpNoThrow` is API 29+ — guard with a `checkOpNoThrow` fallback for API 28.
- Privileged shell runs via `SystemRepository.executeShellCommand(cmd): Result<Pair<Int, String?>>`.
- Never compute sizes in the sort comparator — compute on the scan-triggered path and store on `AppInfo`.

---

### Task 1: Pure `sortApps()` with a SIZE branch (TDD)

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/domain/model/SortBy.kt`
- Modify: `app/src/main/java/com/valhalla/thor/domain/model/AppInfo.kt`
- Create: `app/src/main/java/com/valhalla/thor/domain/model/AppSorting.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/appList/AppListViewModel.kt` (delegate `getSortedList` to `sortApps`)
- Test: `app/src/test/java/com/valhalla/thor/domain/model/AppSortingTest.kt`

**Interfaces:**
- Produces: `fun sortApps(apps: List<AppInfo>, sortBy: SortBy, order: SortOrder): List<AppInfo>`; `AppInfo.installSize: Long?`; `SortBy.SIZE`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/valhalla/thor/domain/model/AppSortingTest.kt`:

```kotlin
package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSortingTest {

    private fun app(pkg: String, size: Long?) = AppInfo(packageName = pkg, installSize = size)

    @Test
    fun size_ascending_putsNullsFirstThenSmallestToLargest() {
        val apps = listOf(app("b", 200), app("a", null), app("c", 100))
        val sorted = sortApps(apps, SortBy.SIZE, SortOrder.ASCENDING).map { it.packageName }
        assertEquals(listOf("a", "c", "b"), sorted)
    }

    @Test
    fun size_descending_putsLargestFirstNullsLast() {
        val apps = listOf(app("b", 200), app("a", null), app("c", 100))
        val sorted = sortApps(apps, SortBy.SIZE, SortOrder.DESCENDING).map { it.packageName }
        assertEquals(listOf("b", "c", "a"), sorted)
    }

    @Test
    fun name_ascending_stillWorks() {
        val apps = listOf(
            AppInfo(packageName = "p2", appName = "Beta"),
            AppInfo(packageName = "p1", appName = "alpha")
        )
        val sorted = sortApps(apps, SortBy.NAME, SortOrder.ASCENDING).map { it.packageName }
        assertEquals(listOf("p1", "p2"), sorted)
    }
}
```

- [ ] **Step 2: Run the test — verify it fails to compile**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.domain.model.AppSortingTest"`
Expected: FAIL — `sortApps` unresolved, `SortBy.SIZE` unresolved, `installSize` unresolved.

- [ ] **Step 3: Add `installSize` to `AppInfo`**

In `AppInfo.kt`, add a field as the last constructor property (after `isUadLoadFailed: Boolean = false,` on line 42):

```kotlin
    val isUadLoadFailed: Boolean = false,
    /** Total install size in bytes (app + data + cache). null = not yet computed. */
    val installSize: Long? = null,
```

- [ ] **Step 4: Uncomment `SIZE` in `SortBy`**

In `SortBy.kt`, change `//SIZE,` → `SIZE,` in the enum, and `//SIZE -> "Size"` → `SIZE -> "Size"` in `asGeneralName()`:

```kotlin
enum class SortBy {
    NAME,
    SIZE,
    INSTALL_DATE,
    LAST_UPDATED,
    VERSION_CODE,
    VERSION_NAME,
    TARGET_SDK_VERSION,
    MIN_SDK_VERSION;

    fun asGeneralName(): String = when (this) {
        NAME -> "Name"
        SIZE -> "Size"
        INSTALL_DATE -> "Install Date"
        LAST_UPDATED -> "Last Updated"
        VERSION_CODE -> "Version Code"
        VERSION_NAME -> "Version Name"
        TARGET_SDK_VERSION -> "Target SDK Version"
        MIN_SDK_VERSION -> "Min SDK Version"
    }
}
```

- [ ] **Step 5: Create the pure `sortApps()`**

Create `app/src/main/java/com/valhalla/thor/domain/model/AppSorting.kt`:

```kotlin
package com.valhalla.thor.domain.model

/**
 * Pure, side-effect-free app sorter (unit-tested). Unknown install sizes
 * (`null`) sort as smallest, so DESCENDING puts the biggest apps first.
 */
fun sortApps(apps: List<AppInfo>, sortBy: SortBy, order: SortOrder): List<AppInfo> {
    val comparator = when (sortBy) {
        SortBy.NAME -> compareBy<AppInfo> { it.appName?.lowercase() }
        SortBy.SIZE -> compareBy(nullsFirst<Long>()) { it.installSize }
        SortBy.INSTALL_DATE -> compareBy { it.firstInstallTime }
        SortBy.LAST_UPDATED -> compareBy { it.lastUpdateTime }
        SortBy.VERSION_CODE -> compareBy { it.versionCode }
        SortBy.VERSION_NAME -> compareBy { it.versionName }
        SortBy.TARGET_SDK_VERSION -> compareBy { it.targetSdk }
        SortBy.MIN_SDK_VERSION -> compareBy { it.minSdk }
    }
    return if (order == SortOrder.ASCENDING) apps.sortedWith(comparator)
    else apps.sortedWith(comparator).reversed()
}
```

- [ ] **Step 6: Run the test — verify it passes**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.domain.model.AppSortingTest"`
Expected: PASS (3 tests).

- [ ] **Step 7: Delegate the VM's private `getSortedList` to `sortApps`**

In `AppListViewModel.kt`, replace the entire body of `private fun getSortedList(list, sortBy, order)` (the `when` comparator + the ascending/descending return) with:

```kotlin
    private fun getSortedList(
        list: List<AppInfo>,
        sortBy: SortBy,
        order: SortOrder
    ): List<AppInfo> = sortApps(list, sortBy, order)
```

Add the import `import com.valhalla.thor.domain.model.sortApps` if the IDE doesn't infer it (same package prefix, likely already visible).

- [ ] **Step 8: Compile the app + rerun the test**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.domain.model.AppSortingTest"`
Expected: BUILD SUCCESSFUL, 3 tests pass, app compiles.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/model/SortBy.kt \
        app/src/main/java/com/valhalla/thor/domain/model/AppInfo.kt \
        app/src/main/java/com/valhalla/thor/domain/model/AppSorting.kt \
        app/src/main/java/com/valhalla/thor/presentation/appList/AppListViewModel.kt \
        app/src/test/java/com/valhalla/thor/domain/model/AppSortingTest.kt
git commit -m "feat(applist): add Size sort option + pure sortApps() (#57)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Persist install size in Room (cache)

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/data/source/local/room/AppEntity.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/source/local/room/AppDatabase.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/source/local/room/AppDao.kt`
- Modify: `app/src/main/java/com/valhalla/thor/domain/repository/AppRepository.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/AppRepositoryImpl.kt`

**Interfaces:**
- Consumes: `AppInfo.installSize` (Task 1).
- Produces: `AppRepository.updateInstallSizes(sizes: Map<String, Long>)`; `AppDao.updateInstallSizes(...)`; persisted `apps.installSize` column.

- [ ] **Step 1: Add the column + mappers to `AppEntity`**

In `AppEntity.kt`: add `val installSize: Long? = null` as the last constructor property (after `isSuspended: Boolean`); in `toDomain()` add `installSize = installSize`; in `fromDomain()` add `installSize = appInfo.installSize`:

```kotlin
    val isSuspended: Boolean,
    val installSize: Long? = null
) {
    fun toDomain(): AppInfo {
        return AppInfo(
            // ...existing fields unchanged...
            isSuspended = isSuspended,
            installSize = installSize
        )
    }

    companion object {
        fun fromDomain(appInfo: AppInfo): AppEntity {
            return AppEntity(
                // ...existing fields unchanged...
                isSuspended = appInfo.isSuspended,
                installSize = appInfo.installSize
            )
        }
    }
}
```

- [ ] **Step 2: Bump the DB version + add the auto-migration**

In `AppDatabase.kt`, change `version = 4` → `version = 5` and add `AutoMigration(from = 4, to = 5)` to the `autoMigrations` list:

```kotlin
    version = 5,
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5)
    ],
```

(Adding a **nullable** column is a valid Room auto-migration — no `defaultValue` needed.)

- [ ] **Step 3: Add the DAO update methods**

In `AppDao.kt`, add before `@Query("DELETE FROM apps") suspend fun clearAll()`:

```kotlin
    @Query("UPDATE apps SET installSize = :size WHERE packageName = :packageName")
    suspend fun updateInstallSize(packageName: String, size: Long?)

    @Transaction
    suspend fun updateInstallSizes(sizes: Map<String, Long>) {
        sizes.forEach { (pkg, size) -> updateInstallSize(pkg, size) }
    }
```

- [ ] **Step 4: Expose it on `AppRepository`**

In `AppRepository.kt` add to the interface:

```kotlin
    /** Persist freshly-computed total install sizes into the app cache. */
    suspend fun updateInstallSizes(sizes: Map<String, Long>)
```

In `AppRepositoryImpl.kt` add the override (the class already holds `private val appDao: AppDao`):

```kotlin
    override suspend fun updateInstallSizes(sizes: Map<String, Long>) {
        appDao.updateInstallSizes(sizes)
    }
```

- [ ] **Step 5: Compile (this also regenerates the Room schema `5.json`)**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL. A new `app/schemas/.../5.json` is generated (Room schema export).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/data/source/local/room/AppEntity.kt \
        app/src/main/java/com/valhalla/thor/data/source/local/room/AppDatabase.kt \
        app/src/main/java/com/valhalla/thor/data/source/local/room/AppDao.kt \
        app/src/main/java/com/valhalla/thor/domain/repository/AppRepository.kt \
        app/src/main/java/com/valhalla/thor/data/repository/AppRepositoryImpl.kt \
        app/schemas
git commit -m "feat(data): persist installSize in Room (v5 auto-migration) (#57)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `StorageStatsHelper` — compute total install sizes

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/manager/StorageStatsHelper.kt`

**Interfaces:**
- Produces: `@Single class StorageStatsHelper(Context)` with `suspend fun installSizes(packages: List<String>): Map<String, Long>`.

- [ ] **Step 1: Create the helper**

Create `app/src/main/java/com/valhalla/thor/data/manager/StorageStatsHelper.kt`:

```kotlin
package com.valhalla.thor.data.manager

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/**
 * Computes total install size (app + data + cache) per package via
 * StorageStatsManager. Requires the GET_USAGE_STATS app-op for other packages
 * (see UsageAccessManager) — a per-package failure is skipped, never thrown.
 */
@Single
class StorageStatsHelper(private val context: Context) {

    private val statsManager = context.getSystemService(StorageStatsManager::class.java)
    private val pm = context.packageManager
    private val user = Process.myUserHandle()

    suspend fun installSizes(packages: List<String>): Map<String, Long> =
        withContext(Dispatchers.IO) {
            val out = HashMap<String, Long>(packages.size)
            for (pkg in packages) {
                val size = runCatching {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    val stats = statsManager.queryStatsForPackage(ai.storageUuid, pkg, user)
                    stats.appBytes + stats.dataBytes + stats.cacheBytes
                }.getOrNull()
                if (size != null) out[pkg] = size
            }
            out
        }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/data/manager/StorageStatsHelper.kt
git commit -m "feat(data): StorageStatsHelper for total install size (#57)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Usage-access permission + silent grant

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/valhalla/thor/data/manager/UsageAccessManager.kt`

**Interfaces:**
- Consumes: `SystemRepository.executeShellCommand`.
- Produces: `@Single class UsageAccessManager(Context, SystemRepository)` with `fun isGranted(): Boolean`, `suspend fun tryGrantViaPrivilege(): Boolean`, `suspend fun maybeAutoGrant()`, `fun usageAccessIntent(): Intent`.

- [ ] **Step 1: Declare the permission**

In `AndroidManifest.xml`, add after the `RECEIVE_BOOT_COMPLETED` line:

```xml
    <uses-permission
        android:name="android.permission.PACKAGE_USAGE_STATS"
        tools:ignore="ProtectedPermissions" />
```

- [ ] **Step 2: Create `UsageAccessManager`**

Create `app/src/main/java/com/valhalla/thor/data/manager/UsageAccessManager.kt`:

```kotlin
package com.valhalla.thor.data.manager

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.valhalla.thor.domain.repository.SystemRepository
import org.koin.core.annotation.Single

/**
 * Manages the GET_USAGE_STATS (Usage Access) app-op needed by
 * StorageStatsManager. Tries a silent grant through the active privilege
 * gateway; always re-verifies; exposes the Settings deep-link for the fallback.
 */
@Single
class UsageAccessManager(
    private val context: Context,
    private val systemRepository: SystemRepository
) {
    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private val pkg = context.packageName

    @Volatile
    private var autoGrantAttempted = false

    fun isGranted(): Boolean {
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), pkg)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), pkg)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Best-effort silent grant via a privileged gateway; returns the verified result. */
    suspend fun tryGrantViaPrivilege(): Boolean {
        if (isGranted()) return true
        // Harmless if no privilege is active (command just fails); may also be
        // blocked on newer Android — hence we re-verify rather than assume success.
        systemRepository.executeShellCommand("appops set $pkg GET_USAGE_STATS allow")
        return isGranted()
    }

    /** One-shot per-process auto-grant, meant to run once a privilege is available. */
    suspend fun maybeAutoGrant() {
        if (autoGrantAttempted || isGranted()) return
        autoGrantAttempted = true
        tryGrantViaPrivilege()
    }

    /** Settings deep-link (best-effort per-app; OEMs may land on the list). */
    fun usageAccessIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.fromParts("package", pkg, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml \
        app/src/main/java/com/valhalla/thor/data/manager/UsageAccessManager.kt
git commit -m "feat(perm): UsageAccessManager + PACKAGE_USAGE_STATS (silent grant + fallback) (#57)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Wire the size-sort trigger into `AppListViewModel`

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/appList/AppListViewModel.kt`

**Interfaces:**
- Consumes: `StorageStatsHelper.installSizes`, `UsageAccessManager` (Task 3/4), `AppRepository.updateInstallSizes` (Task 2), `PrivilegeState.hasAnyPrivilege`.
- Produces: `AppListUiState.isComputingSizes`, `AppListUiState.needsUsageAccessPrompt`; `fun dismissUsageAccessPrompt()`.

- [ ] **Step 1: Add the two UI-state fields**

In `AppListUiState` (top of the file), add after `val isGrid: Boolean = true`:

```kotlin
    val isGrid: Boolean = true,
    val isComputingSizes: Boolean = false,
    val needsUsageAccessPrompt: Boolean = false
```

- [ ] **Step 2: Inject the new collaborators**

Add to the constructor (after `freezerRepository`) and the matching imports:

```kotlin
    private val freezerRepository: FreezerRepository,
    private val appRepository: AppRepository,
    private val storageStatsHelper: StorageStatsHelper,
    private val usageAccessManager: UsageAccessManager
) : ViewModel() {
```

Imports:
```kotlin
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.data.manager.StorageStatsHelper
import com.valhalla.thor.data.manager.UsageAccessManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
```

- [ ] **Step 3: Observe the size-sort trigger + attempt silent grant**

Add a `sizeJob` field next to `appsJob`, call `observeSizeSort()` from `init`, and add the functions. In `init`:

```kotlin
    init {
        loadApps()
        observeSizeSort()
    }
```

Add the field near `appsJob`:
```kotlin
    private var sizeJob: Job? = null
```

Add these functions (anywhere in the class body):

```kotlin
    // Recompute total install sizes when Size is the active sort AND apps are loaded
    // (fires both when the user picks Size and when the list finishes loading with
    // Size already selected). distinctUntilChanged prevents a self-trigger loop.
    private fun observeSizeSort() {
        viewModelScope.launch {
            combine(
                preferenceRepository.userPreferences.map { it.appSortBy }.distinctUntilChanged(),
                _rawState.map { it.allUserApps.size + it.allSystemApps.size }.distinctUntilChanged()
            ) { sortBy, appCount -> sortBy to appCount }
                .collect { (sortBy, appCount) ->
                    if (sortBy == SortBy.SIZE && appCount > 0) ensureInstallSizes()
                }
        }
    }

    private fun ensureInstallSizes() {
        sizeJob?.cancel()
        sizeJob = viewModelScope.launch {
            if (!usageAccessManager.isGranted() && !usageAccessManager.tryGrantViaPrivilege()) {
                _rawState.update { it.copy(needsUsageAccessPrompt = true) }
                return@launch
            }
            _rawState.update { it.copy(isComputingSizes = true) }
            val packages = (_rawState.value.allUserApps + _rawState.value.allSystemApps)
                .map { it.packageName }
            val sizes = storageStatsHelper.installSizes(packages)
            _rawState.update { state ->
                state.copy(
                    isComputingSizes = false,
                    allUserApps = state.allUserApps.map {
                        it.copy(installSize = sizes[it.packageName] ?: it.installSize)
                    },
                    allSystemApps = state.allSystemApps.map {
                        it.copy(installSize = sizes[it.packageName] ?: it.installSize)
                    }
                )
            }
            appRepository.updateInstallSizes(sizes)
        }
    }

    fun dismissUsageAccessPrompt() {
        _rawState.update { it.copy(needsUsageAccessPrompt = false) }
    }
```

- [ ] **Step 4: Silent-grant on first privilege**

In `loadApps()`, inside the `.collect { (user, system, priv) -> ... }` block, after the `_rawState.update { ... }`, add:

```kotlin
                if (priv.hasAnyPrivilege) {
                    launch { usageAccessManager.maybeAutoGrant() }
                }
```

(`maybeAutoGrant()` is idempotent — the `autoGrantAttempted` guard makes repeated emissions cheap.)

- [ ] **Step 5: Compile + rerun Task 1's test**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.domain.model.AppSortingTest"`
Expected: BUILD SUCCESSFUL; app compiles; 3 tests still pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/appList/AppListViewModel.kt
git commit -m "feat(applist): compute/persist install sizes on Size sort (#57)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: App-list UI — loading + Usage-Access dialog

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/appList/AppListScreen.kt`

**Interfaces:**
- Consumes: `AppListUiState.isComputingSizes`, `AppListUiState.needsUsageAccessPrompt`, `AppListViewModel.dismissUsageAccessPrompt()`, `UsageAccessManager.usageAccessIntent()` (via `koinInject`).

- [ ] **Step 1: Show the "computing sizes" affordance**

In `AppListScreen.kt`, where the loader is gated on `state.isLoading` (the top-of-list loading branch), also trigger it while sizes compute — change the condition to include `|| state.isComputingSizes` (only affects the first, uncached computation). Locate the existing `state.isLoading` usage in the list header and OR-in `state.isComputingSizes`.

- [ ] **Step 2: Show the Usage-Access dialog**

Add near the other dialogs in the screen (using `androidx.compose.material3.AlertDialog`), pulling the intent from Koin:

```kotlin
val ctx = LocalContext.current
val usageAccessManager = org.koin.compose.koinInject<com.valhalla.thor.data.manager.UsageAccessManager>()
if (state.needsUsageAccessPrompt) {
    AlertDialog(
        onDismissRequest = { viewModel.dismissUsageAccessPrompt() },
        title = { Text("Usage Access needed") },
        text = { Text("Thor needs Usage Access to read app sizes. Enable it for Thor, then pick \"Size\" again. You can also manage this under Settings → Permissions.") },
        confirmButton = {
            TextButton(onClick = {
                runCatching { ctx.startActivity(usageAccessManager.usageAccessIntent()) }
                viewModel.dismissUsageAccessPrompt()
            }) { Text("Open settings") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.dismissUsageAccessPrompt() }) { Text("Cancel") }
        }
    )
}
```

Add imports as needed: `androidx.compose.material3.AlertDialog`, `TextButton`, `Text`, `androidx.compose.ui.platform.LocalContext`.

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/appList/AppListScreen.kt
git commit -m "feat(applist): size-computing loader + Usage-Access dialog (#57)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: Settings → new "Permissions" section

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt`
- Modify (if it exists / drives the screen): `app/src/main/java/com/valhalla/thor/presentation/settings/SettingsViewModel.kt`

**Interfaces:**
- Consumes: `UsageAccessManager.isGranted()` / `usageAccessIntent()` (via `koinInject`).

- [ ] **Step 1: Add a PERMISSIONS section that reflects live grant state**

In `SettingsScreen.kt`, after the SECURITY section (before ABOUT), add a new section using the existing `SettingsSectionLabel` and a status/switch row. Re-check the grant on `ON_RESUME` so returning from settings updates it. Concretely:

```kotlin
// ── PERMISSIONS ─────────────────────────────────────────────────────
SettingsSectionLabel("PERMISSIONS")

val context = LocalContext.current
val usageAccessManager = org.koin.compose.koinInject<com.valhalla.thor.data.manager.UsageAccessManager>()
val lifecycleOwner = LocalLifecycleOwner.current
var usageGranted by remember { mutableStateOf(usageAccessManager.isGranted()) }
DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) usageGranted = usageAccessManager.isGranted()
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
}

SettingsSwitchRow(
    icon = R.drawable.<pick_an_existing_stats_or_info_icon>,   // e.g. an existing chart/info drawable
    title = "Usage Access",
    subtitle = if (usageGranted) "Granted — enables app-size sorting"
               else "Needed to read app sizes for the Size sort",
    checked = usageGranted,
    onCheckedChange = { desired ->
        // The app can't toggle this op directly; deep-link to system settings.
        if (!usageGranted) runCatching { context.startActivity(usageAccessManager.usageAccessIntent()) }
    }
)
```

Match the exact `SettingsSwitchRow(...)` parameter names to the existing definition in this file (it is used by the AMOLED / Dynamic Colors / Biometric rows — mirror one of those). Pick an existing drawable for the icon (grep `R.drawable.` usages in this file for a suitable one). Add imports: `LocalContext`, `LocalLifecycleOwner`, `DisposableEffect`, `remember`, `mutableStateOf`, `getValue`, `setValue`, `androidx.lifecycle.Lifecycle`, `androidx.lifecycle.LifecycleEventObserver`.

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt
git commit -m "feat(settings): Permissions section with Usage Access status (#57)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 8: (Optional) Show size in the App Info details sheet

**Files:**
- Modify: the App Info details composable (`app/src/main/java/com/valhalla/thor/presentation/appList/AppInfoDetailsScreen.kt` or the sheet that renders `AppInfo` metadata).

**Interfaces:**
- Consumes: `AppInfo.installSize`.

- [ ] **Step 1: Add a metadata row for size (only when known)**

Where the sheet renders metadata rows (version / SDK / installer), add, guarded by non-null:

```kotlin
appInfo.installSize?.let { bytes ->
    // Match the existing metadata-row composable used for Version / Installer etc.
    MetadataRow(label = "Size", value = android.text.format.Formatter.formatShortFileSize(context, bytes))
}
```

Match the existing metadata-row composable + `context` source in that file.

- [ ] **Step 2: Compile + commit**

Run: `./gradlew :app:compileFossDebugKotlin`
```bash
git add app/src/main/java/com/valhalla/thor/presentation/appList/AppInfoDetailsScreen.kt
git commit -m "feat(applist): show total install size in App Info sheet (#57)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 9: Full verification, manual test, push & PR

**Files:** none (verification only).

- [ ] **Step 1: Full unit tests + assemble**

Run: `./gradlew :app:testFossDebugUnitTest && ./gradlew assembleFossDebug`
Expected: BUILD SUCCESSFUL; `AppSortingTest` (3) + existing tests pass; APK assembles (confirms Koin resolves `StorageStatsHelper`/`UsageAccessManager` into the VM).

- [ ] **Step 2: Manual verification (device)**

- Root/Shizuku device: launch → open Apps → Sort → **Size**. First time: brief compute, list orders by total size; the App Info sheet size matches. On silent-grant-capable OS, no prompt appeared.
- Relaunch the app → Size sort shows the **cached** order instantly, then refreshes.
- Android 16+ / Dhizuku / no privilege where silent grant fails: picking Size shows the **Usage Access** dialog → Open settings → toggle Thor → return → pick Size again → works.
- Settings → **Permissions** → Usage Access shows "Granted" (or opens settings); state updates on resume.
- Toggle asc/desc; confirm largest-first on descending; confirm no jank on a large list.

- [ ] **Step 3: Push + open PR into `dev`**

```bash
git push -u origin feat/sort-by-size
gh pr create --base dev --head feat/sort-by-size \
  --title "feat: sort apps by total install size (#57)" \
  --body "Implements #57. See docs/superpowers/specs/2026-07-02-sort-by-size-design.md.

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```

---

## Notes for the implementer
- The `build-and-test` PR check (from `pr-ci.yml`) runs on the PR into `dev` — it should pass once the unit tests + assemble are green locally.
- Sizes refresh on Size-sort selection and on app-load-with-Size-active; a mid-session pull-to-refresh does **not** force a size recompute (acceptable for v1 — sizes update on next Size selection / relaunch).
- Do NOT compute sizes in `sortApps()` / `processList()` — only in `ensureInstallSizes()`.
