# Add Freezer to Launcher — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in "Add Freezer to launcher" setting that places home-screen shortcuts for frozen apps (grayscale icon → enable-then-launch) plus Freeze-all / Unfreeze-all shortcuts, re-freezing via Thor's existing screen-off AutoFreeze.

**Architecture:** A Thor-owned pinned shortcut per frozen app trampolines through an invisible (translucent) `FreezerLaunchActivity`, which enables the app (via the active privilege gateway) then launches it — or launches directly if already enabled. Bulk Freeze-all / Unfreeze-all run through the same trampoline and are also long-press dynamic shortcuts. Re-freeze is unchanged (existing `AutoFreezeManager` screen-off path). All shortcut plumbing lives in a new `FreezerShortcutManager` (`@Single`).

**Tech Stack:** Kotlin, Jetpack Compose, Koin (annotation DI), `androidx.core` `ShortcutManagerCompat`/`IconCompat`, DataStore Preferences, coroutines.

Design spec: `docs/superpowers/specs/2026-07-04-add-freezer-to-launcher-design.md`.

## Global Constraints

- **minSdk 28**, targetSdk/compileSdk 37. `applicationId com.valhalla.thor` (debug suffix `.debug`).
- **DI is Koin annotation-based**, component-scanned over `com.valhalla.thor`: `@Single` for singletons, `@Factory` for use cases, `@KoinViewModel` for view models. Android `Activity`/`Service` are NOT Koin-instantiated — they retrieve deps via `org.koin.android.ext.android.inject`.
- **"Freeze" = disable**: `ManageAppUseCase.setAppDisabled(packageName, disabled): Result<Unit>`. A disabled app has no launcher entry and cannot be launched until re-enabled.
- **Privilege check** (copy verbatim from `FreezerTileService`): `systemRepository.isRootAvailable() || systemRepository.isShizukuAvailable() || systemRepository.isDhizukuAvailable()`, always on `Dispatchers.IO`.
- **No new dangerous permissions.** `requestPinShortcut` / dynamic shortcuts need none; `QUERY_ALL_PACKAGES` is already declared; no foreground service.
- **v1 scope: user apps only** (`AppInfo.isSystem == false`). No launcher shortcuts for system apps.
- Build command: `./gradlew assembleFossDebug`. Unit tests: `./gradlew :app:testFossDebugUnitTest --tests "<FQN>"`. Device: `adb install -r <apk>` + `adb shell` / `adb exec-out screencap`.
- Follow existing patterns: settings toggles mirror `autoFreezeEnabled` (4 files); trampoline mirrors `PortableInstallerActivity` (translucent) — **not** `ShortcutTriggerActivity` (`Theme.NoDisplay`), because our trampoline does async work and a NoDisplay activity must `finish()` synchronously.

---

### Task 1: "Add Freezer to launcher" preference + Settings toggle row

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/domain/model/UserPreferences.kt`
- Modify: `app/src/main/java/com/valhalla/thor/domain/repository/PreferenceRepository.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/PreferenceRepositoryImpl.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: `UserPreferences.addFreezerToLauncher: Boolean`; `PreferenceRepository.setAddFreezerToLauncher(enabled: Boolean)`; `SettingsViewModel.setAddFreezerToLauncher(enabled: Boolean)`.

- [ ] **Step 1: Add the model field.** In `UserPreferences.kt`, after the `autoFreezeEnabled` line (`// Auto Freeze` block):

```kotlin
    // Auto Freeze
    val autoFreezeEnabled: Boolean = false,

    // Add Freezer to launcher (home-screen shortcuts for frozen apps)
    val addFreezerToLauncher: Boolean = false,
```

- [ ] **Step 2: Add the DataStore key.** In `PreferenceRepositoryImpl.kt` `Keys`, after `AUTO_FREEZE`:

```kotlin
        // Auto Freeze
        val AUTO_FREEZE = booleanPreferencesKey("auto_freeze")
        val ADD_FREEZER_TO_LAUNCHER = booleanPreferencesKey("add_freezer_to_launcher")
```

- [ ] **Step 3: Map the key into `userPreferences`.** In the `UserPreferences(...)` constructor block, after `autoFreezeEnabled = prefs[Keys.AUTO_FREEZE] ?: false,`:

```kotlin
                autoFreezeEnabled = prefs[Keys.AUTO_FREEZE] ?: false,
                addFreezerToLauncher = prefs[Keys.ADD_FREEZER_TO_LAUNCHER] ?: false,
```

- [ ] **Step 4: Add the writer + interface method.** In `PreferenceRepositoryImpl.kt`, after `setAutoFreezeEnabled`:

```kotlin
    override suspend fun setAddFreezerToLauncher(enabled: Boolean) {
        context.dataStore.edit {
            it[Keys.ADD_FREEZER_TO_LAUNCHER] = enabled
        }
    }
```

In `PreferenceRepository.kt`, after `suspend fun setAutoFreezeEnabled(enabled: Boolean)`:

```kotlin
    // --- Auto Freeze ---
    suspend fun setAutoFreezeEnabled(enabled: Boolean)
    suspend fun setAddFreezerToLauncher(enabled: Boolean)
```

- [ ] **Step 5: Add the ViewModel setter.** In `SettingsViewModel.kt`, after `setAutoFreezeEnabled`:

```kotlin
    fun setAddFreezerToLauncher(enabled: Boolean) {
        viewModelScope.launch {
            preferenceRepository.setAddFreezerToLauncher(enabled)
        }
    }
```

- [ ] **Step 6: Add the string resources.** In `app/src/main/res/values/strings.xml`:

```xml
    <string name="add_freezer_to_launcher">Add Freezer to launcher</string>
    <string name="add_freezer_to_launcher_desc">Show frozen apps as home-screen shortcuts you can tap to launch</string>
    <string name="add_to_home_screen">Add to Home screen</string>
    <string name="pin_all_to_home_screen">Pin all to Home screen</string>
    <string name="freeze_all_apps">Freeze all</string>
    <string name="freezer_launch_failed">Couldn\'t launch this app</string>
```

- [ ] **Step 7: Render the toggle.** In `SettingsScreen.kt`, inside the Freezer `Column` (the one holding the `auto_freeze` `SettingsSwitchRow`), add after the `SettingsClickRow` for `unfreeze_all_apps` (before the Column closes at line ~408):

```kotlin
            SettingsSwitchRow(
                icon = R.drawable.frozen,
                title = stringResource(R.string.add_freezer_to_launcher),
                subtitle = if (hasPrivilege) stringResource(R.string.add_freezer_to_launcher_desc) else stringResource(
                    R.string.privilege_required_warning
                ),
                checked = prefs.addFreezerToLauncher,
                enabled = hasPrivilege,
                onCheckedChange = { viewModel.setAddFreezerToLauncher(it) }
            )
```

- [ ] **Step 8: Build.** Run: `./gradlew assembleFossDebug`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Device-verify.** `adb install -r app/build/outputs/apk/foss/debug/*.apk`; open Settings → Freezer; confirm the new "Add Freezer to launcher" switch appears, flips, and survives an app restart (`adb shell am force-stop com.valhalla.thor.debug` then relaunch).

- [ ] **Step 10: Commit.**

```bash
git add app/src/main/java/com/valhalla/thor/domain/model/UserPreferences.kt app/src/main/java/com/valhalla/thor/domain/repository/PreferenceRepository.kt app/src/main/java/com/valhalla/thor/data/repository/PreferenceRepositoryImpl.kt app/src/main/java/com/valhalla/thor/presentation/settings/SettingsViewModel.kt app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt app/src/main/res/values/strings.xml
git commit -m "feat(freezer): add 'Add Freezer to launcher' setting (#210)"
```

---

### Task 2: `FreezerShortcutContract` — pure action model (TDD)

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/launcher/FreezerShortcutContract.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/launcher/FreezerShortcutContractTest.kt`

**Interfaces:**
- Produces: `object FreezerShortcutContract` with `EXTRA_ACTION`, `EXTRA_PACKAGE`, `ACTION_LAUNCH`, `ACTION_FREEZE_ALL`, `ACTION_UNFREEZE_ALL`, `SHORTCUT_FREEZE_ALL`, `SHORTCUT_UNFREEZE_ALL` (String consts); `appShortcutId(packageName: String): String`; `parseAction(raw: String?): String?`.

- [ ] **Step 1: Write the failing test.** Create `FreezerShortcutContractTest.kt`:

```kotlin
package com.valhalla.thor.data.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreezerShortcutContractTest {

    @Test
    fun parseAction_returns_known_actions() {
        assertEquals(FreezerShortcutContract.ACTION_LAUNCH, FreezerShortcutContract.parseAction("launch"))
        assertEquals(FreezerShortcutContract.ACTION_FREEZE_ALL, FreezerShortcutContract.parseAction("freeze_all"))
        assertEquals(FreezerShortcutContract.ACTION_UNFREEZE_ALL, FreezerShortcutContract.parseAction("unfreeze_all"))
    }

    @Test
    fun parseAction_returns_null_for_unknown_or_missing() {
        assertNull(FreezerShortcutContract.parseAction(null))
        assertNull(FreezerShortcutContract.parseAction(""))
        assertNull(FreezerShortcutContract.parseAction("delete_all"))
    }

    @Test
    fun appShortcutId_is_stable_and_package_scoped() {
        assertEquals("freezer_app_com.amazon.mShop.android.shopping",
            FreezerShortcutContract.appShortcutId("com.amazon.mShop.android.shopping"))
        assertEquals(
            FreezerShortcutContract.appShortcutId("a"),
            FreezerShortcutContract.appShortcutId("a")
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails.** Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.data.launcher.FreezerShortcutContractTest"`. Expected: FAIL (unresolved `FreezerShortcutContract`).

- [ ] **Step 3: Write the implementation.** Create `FreezerShortcutContract.kt`:

```kotlin
package com.valhalla.thor.data.launcher

/** Pure contract shared between the shortcut publisher and the launch trampoline. */
object FreezerShortcutContract {
    const val EXTRA_ACTION = "com.valhalla.thor.extra.FREEZER_SHORTCUT_ACTION"
    const val EXTRA_PACKAGE = "com.valhalla.thor.extra.FREEZER_SHORTCUT_PACKAGE"

    const val ACTION_LAUNCH = "launch"
    const val ACTION_FREEZE_ALL = "freeze_all"
    const val ACTION_UNFREEZE_ALL = "unfreeze_all"

    const val SHORTCUT_FREEZE_ALL = "freezer_freeze_all"
    const val SHORTCUT_UNFREEZE_ALL = "freezer_unfreeze_all"
    private const val APP_SHORTCUT_PREFIX = "freezer_app_"

    /** Stable, package-scoped id for a per-app frozen-app shortcut. */
    fun appShortcutId(packageName: String): String = "$APP_SHORTCUT_PREFIX$packageName"

    /** Normalize a raw intent extra to a known action, or null if unrecognized. */
    fun parseAction(raw: String?): String? = when (raw) {
        ACTION_LAUNCH, ACTION_FREEZE_ALL, ACTION_UNFREEZE_ALL -> raw
        else -> null
    }
}
```

- [ ] **Step 4: Run test to verify it passes.** Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.data.launcher.FreezerShortcutContractTest"`. Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/valhalla/thor/data/launcher/FreezerShortcutContract.kt app/src/test/java/com/valhalla/thor/data/launcher/FreezerShortcutContractTest.kt
git commit -m "feat(freezer): pure shortcut contract for launcher shortcuts (#210)"
```

---

### Task 3: `FreezerShortcutManager` (`@Single`) — icons, pinning, dynamic shortcuts

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/launcher/FreezerShortcutManager.kt`
- Verify: `app/build.gradle.kts` (confirm `androidx.core` / `core-ktx` is a dependency — it is, transitively via Compose; add explicit `implementation(libs.androidx.core.ktx)` only if the build fails to resolve `ShortcutManagerCompat`).

**Interfaces:**
- Consumes: `FreezerShortcutContract` (Task 2); `FreezerRepository.getAllPackageNames()`; `ManageAppUseCase.setAppDisabled(pkg, disabled)`. The trampoline is referenced by **string class name** (`setClassName`), so this task builds independently of Task 4.
- Produces: `@Single class FreezerShortcutManager` with `isPinSupported(): Boolean`, `pinAppShortcut(packageName: String, label: String)`, `pinBulkShortcut(action: String)`, `syncDynamicShortcuts(enabled: Boolean)`, `disableAppShortcut(packageName: String, reason: CharSequence)`, `runBulk(disable: Boolean)`.

- [ ] **Step 1: Write the implementation.** Create `FreezerShortcutManager.kt`:

```kotlin
package com.valhalla.thor.data.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import com.valhalla.thor.R
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single

/** Owns all launcher-shortcut plumbing for the Freezer feature. */
@Single
class FreezerShortcutManager(
    private val context: Context,
    private val freezerRepository: FreezerRepository,
    private val manageAppUseCase: ManageAppUseCase,
) {
    // App-scoped: bulk work must survive the (finishing) trampoline activity.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val LAUNCH_ACTIVITY = "com.valhalla.thor.presentation.launcher.FreezerLaunchActivity"
    }

    fun isPinSupported(): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Ask the launcher to pin a home-screen shortcut for a frozen app (grayscale icon). */
    fun pinAppShortcut(packageName: String, label: String) {
        val shortcut = ShortcutInfoCompat.Builder(context, FreezerShortcutContract.appShortcutId(packageName))
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(grayscaleIcon(packageName))
            .setIntent(
                trampolineIntent(FreezerShortcutContract.ACTION_LAUNCH)
                    .putExtra(FreezerShortcutContract.EXTRA_PACKAGE, packageName)
            )
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    /** Ask the launcher to pin a Freeze-all / Unfreeze-all action shortcut. */
    fun pinBulkShortcut(action: String) {
        ShortcutManagerCompat.requestPinShortcut(context, bulkShortcut(action), null)
    }

    /** Publish (or remove) the Freeze-all + Unfreeze-all long-press dynamic shortcuts. */
    fun syncDynamicShortcuts(enabled: Boolean) {
        if (enabled) {
            ShortcutManagerCompat.setDynamicShortcuts(
                context,
                listOf(
                    bulkShortcut(FreezerShortcutContract.ACTION_FREEZE_ALL),
                    bulkShortcut(FreezerShortcutContract.ACTION_UNFREEZE_ALL),
                )
            )
        } else {
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        }
    }

    /** Grey out a per-app shortcut (the ceiling — pinned icons can't be silently removed). */
    fun disableAppShortcut(packageName: String, reason: CharSequence) {
        ShortcutManagerCompat.disableShortcuts(
            context,
            listOf(FreezerShortcutContract.appShortcutId(packageName)),
            reason
        )
    }

    /** Bulk freeze/unfreeze every package in the freezer, off the finishing activity. */
    fun runBulk(disable: Boolean) {
        scope.launch {
            freezerRepository.getAllPackageNames().forEach { pkg ->
                manageAppUseCase.setAppDisabled(pkg, disable)
            }
        }
    }

    private fun bulkShortcut(action: String): ShortcutInfoCompat {
        val id: String
        val labelRes: Int
        val iconRes: Int
        if (action == FreezerShortcutContract.ACTION_FREEZE_ALL) {
            id = FreezerShortcutContract.SHORTCUT_FREEZE_ALL
            labelRes = R.string.freeze_all_apps
            iconRes = R.drawable.frozen
        } else {
            id = FreezerShortcutContract.SHORTCUT_UNFREEZE_ALL
            labelRes = R.string.unfreeze_all_apps
            iconRes = R.drawable.unfreeze
        }
        val label = context.getString(labelRes)
        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, iconRes))
            .setIntent(trampolineIntent(action))
            .build()
    }

    // Explicit-component intent → our (non-exported) trampoline, targeted by string class name so
    // this class doesn't compile-depend on FreezerLaunchActivity. Shortcuts require an action.
    private fun trampolineIntent(action: String): Intent =
        Intent().apply {
            setClassName(context, LAUNCH_ACTIVITY)
            this.action = Intent.ACTION_VIEW
            putExtra(FreezerShortcutContract.EXTRA_ACTION, action)
        }

    // Saturation-0 grayscale of the app's own icon (loadable for a disabled-but-installed app).
    private fun grayscaleIcon(packageName: String): IconCompat {
        return try {
            val src = context.packageManager.getApplicationIcon(packageName).toBitmap()
            val gray = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            Canvas(gray).drawBitmap(
                src, 0f, 0f,
                Paint().apply {
                    colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                }
            )
            IconCompat.createWithBitmap(gray)
        } catch (e: Exception) {
            Logger.e("FreezerShortcut", "icon load failed for $packageName", e)
            IconCompat.createWithResource(context, R.drawable.frozen)
        }
    }
}
```

- [ ] **Step 2: Build.** Run: `./gradlew assembleFossDebug`. Expected: `BUILD SUCCESSFUL` (this task builds standalone — the trampoline is referenced by string class name). If `ShortcutManagerCompat` is unresolved, add `implementation(libs.androidx.core.ktx)` to `app/build.gradle.kts` and re-run.

- [ ] **Step 3: Commit.**

```bash
git add app/src/main/java/com/valhalla/thor/data/launcher/FreezerShortcutManager.kt
git commit -m "feat(freezer): FreezerShortcutManager — pin/dynamic/disable + grayscale icon (#210)"
```

---

### Task 4: `FreezerLaunchActivity` trampoline + manifest registration

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/presentation/launcher/FreezerLaunchActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `FreezerShortcutContract` (Task 2); `FreezerShortcutManager.runBulk` (Task 3); `SystemRepository.isRootAvailable()/isShizukuAvailable()/isDhizukuAvailable()`; `ManageAppUseCase.setAppDisabled(pkg, disabled)`; `PackageManager.getLaunchIntentForPackage`.
- Produces: `class FreezerLaunchActivity : Activity` (the shortcut trampoline target).

- [ ] **Step 1: Write the activity.** Create `FreezerLaunchActivity.kt`:

```kotlin
package com.valhalla.thor.presentation.launcher

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.valhalla.thor.R
import com.valhalla.thor.data.launcher.FreezerShortcutContract
import com.valhalla.thor.data.launcher.FreezerShortcutManager
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Invisible (translucent) trampoline for Freezer launcher shortcuts. Translucent — not
 * Theme.NoDisplay — because it does async work (enable-then-launch) and NoDisplay requires
 * finish() before onResume completes.
 */
class FreezerLaunchActivity : Activity() {

    private val systemRepository: SystemRepository by inject()
    private val manageAppUseCase: ManageAppUseCase by inject()
    private val freezerShortcutManager: FreezerShortcutManager by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (FreezerShortcutContract.parseAction(intent?.getStringExtra(FreezerShortcutContract.EXTRA_ACTION))) {
            FreezerShortcutContract.ACTION_FREEZE_ALL -> guardThenBulk(disable = true)
            FreezerShortcutContract.ACTION_UNFREEZE_ALL -> guardThenBulk(disable = false)
            FreezerShortcutContract.ACTION_LAUNCH -> {
                val pkg = intent?.getStringExtra(FreezerShortcutContract.EXTRA_PACKAGE)
                if (pkg.isNullOrEmpty()) finish() else launchApp(pkg)
            }
            else -> finish()
        }
    }

    // Bulk: privilege-guard, hand off to the app-scoped manager, finish immediately.
    private fun guardThenBulk(disable: Boolean) {
        scope.launch {
            if (!hasPrivilege()) {
                toast(getString(R.string.tile_grant_privilege_toast))
            } else {
                freezerShortcutManager.runBulk(disable)
            }
            finish()
        }
    }

    // Launch: stay foreground through startActivity (Android 10+ background-launch rule).
    private fun launchApp(pkg: String) {
        scope.launch {
            var launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent == null) {
                if (!hasPrivilege()) {
                    toast(getString(R.string.tile_grant_privilege_toast))
                    finish(); return@launch
                }
                withContext(Dispatchers.IO) { manageAppUseCase.setAppDisabled(pkg, false) }
                // Enabled state / launcher intent may not be visible instantly — retry briefly.
                repeat(10) {
                    launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) return@repeat
                    delay(150)
                }
            }
            val toStart = launchIntent
            if (toStart != null) startActivity(toStart)
            else toast(getString(R.string.freezer_launch_failed))
            finish()
        }
    }

    private suspend fun hasPrivilege(): Boolean = withContext(Dispatchers.IO) {
        systemRepository.isRootAvailable() ||
                systemRepository.isShizukuAvailable() ||
                systemRepository.isDhizukuAvailable()
    }

    private fun toast(msg: String) =
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
```

- [ ] **Step 2: Register in the manifest.** In `AndroidManifest.xml`, next to the `ShortcutTriggerActivity` block (~line 302), add:

```xml
        <activity
            android:name=".presentation.launcher.FreezerLaunchActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:noHistory="true"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
```

- [ ] **Step 3: Build.** Run: `./gradlew assembleFossDebug`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Device-verify.** With a privilege mode active and at least one user app in the freezer: `adb shell cmd shortcut` isn't needed — instead drive it directly:
  - `adb shell am start -n com.valhalla.thor.debug/com.valhalla.thor.presentation.launcher.FreezerLaunchActivity --es com.valhalla.thor.extra.FREEZER_SHORTCUT_ACTION launch --es com.valhalla.thor.extra.FREEZER_SHORTCUT_PACKAGE <frozen.pkg>` → the frozen app enables and launches; nothing from Thor flashes.
  - Repeat with `--es ...ACTION freeze_all` and `unfreeze_all` → the freezer set toggles (verify with `adb shell pm list packages -d`).
  - With no privilege active → a toast appears and nothing launches.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/launcher/FreezerLaunchActivity.kt app/src/main/AndroidManifest.xml
git commit -m "feat(freezer): FreezerLaunchActivity trampoline for launcher shortcuts (#210)"
```

---

### Task 5: Freezer UI actions — "Add to Home screen", "Pin all", bulk pins

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerScreen.kt` (and/or `ManageFreezerSheet.kt` / `FreezerSettingsSheet.kt` where per-app and bulk actions already live — follow the existing action layout).

**Interfaces:**
- Consumes: `FreezerShortcutManager` (Task 3); `FreezerShortcutContract` (Task 2); `AppInfo.packageName`, `AppInfo.appName`, `AppInfo.isSystem`.
- Produces: `FreezerUiState.addFreezerToLauncher: Boolean`; `FreezerViewModel.isPinSupported()`, `pinAppToLauncher(app: AppInfo)`, `pinAllToLauncher()`, `pinBulkShortcut(freeze: Boolean)`.

- [ ] **Step 1: Inject the manager + surface the setting.** In `FreezerViewModel.kt` constructor, add the dependency:

```kotlin
@KoinViewModel
class FreezerViewModel(
    private val freezerRepository: FreezerRepository,
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val manageAppUseCase: ManageAppUseCase,
    private val privilegeManager: PrivilegeManager,
    private val preferenceRepository: PreferenceRepository,
    private val freezerShortcutManager: com.valhalla.thor.data.launcher.FreezerShortcutManager
) : ViewModel() {
```

Add to `FreezerUiState`:

```kotlin
    val isGrid: Boolean = true,
    val addFreezerToLauncher: Boolean = false
```

In `observePreferences()` (where `autoFreezeEnabled` is copied from `preferenceRepository.userPreferences` into the state), also copy `addFreezerToLauncher = prefs.addFreezerToLauncher`.

- [ ] **Step 2: Add the ViewModel actions.** In `FreezerViewModel.kt`:

```kotlin
    fun isPinSupported(): Boolean = freezerShortcutManager.isPinSupported()

    fun pinAppToLauncher(app: AppInfo) {
        if (app.isSystem) return // v1: user apps only
        freezerShortcutManager.pinAppShortcut(app.packageName, app.appName)
    }

    fun pinAllToLauncher() {
        _uiState.value.freezerApps
            .filter { !it.isSystem }
            .forEach { freezerShortcutManager.pinAppShortcut(it.packageName, it.appName) }
    }

    fun pinBulkShortcut(freeze: Boolean) {
        freezerShortcutManager.pinBulkShortcut(
            if (freeze) FreezerShortcutContract.ACTION_FREEZE_ALL
            else FreezerShortcutContract.ACTION_UNFREEZE_ALL
        )
    }
```

Add the imports `com.valhalla.thor.data.launcher.FreezerShortcutContract`.

- [ ] **Step 3: Wire the per-app action.** In the freezer app's action UI (the per-app overflow/menu in `FreezerScreen.kt` / `ManageFreezerSheet.kt` — follow the existing per-app action items such as unfreeze/remove), add an "Add to Home screen" item shown only when `uiState.addFreezerToLauncher && viewModel.isPinSupported() && !app.isSystem`:

```kotlin
if (uiState.addFreezerToLauncher && !app.isSystem && viewModel.isPinSupported()) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.add_to_home_screen)) },
        leadingIcon = { Icon(painterResource(R.drawable.frozen), contentDescription = null) },
        onClick = { viewModel.pinAppToLauncher(app); /* close menu */ }
    )
}
```

- [ ] **Step 4: Wire the bulk actions.** In `FreezerSettingsSheet.kt` (where "Unfreeze all" and grid/auto-freeze controls live), gated on `uiState.addFreezerToLauncher && viewModel.isPinSupported()`, add three `SettingsClickRow`/button entries: "Pin all to Home screen" → `viewModel.pinAllToLauncher()`; "Freeze all" (pin) → `viewModel.pinBulkShortcut(freeze = true)`; "Unfreeze all" (pin) → `viewModel.pinBulkShortcut(freeze = false)`. Use `R.string.pin_all_to_home_screen`, `R.string.freeze_all_apps`, `R.string.unfreeze_all_apps`.

- [ ] **Step 5: Build.** Run: `./gradlew assembleFossDebug`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Device-verify.** Enable the setting; in the Freezer, use a user app's "Add to Home screen" → the system pin dialog appears → confirm → a **grayscale** shortcut with the app's icon lands on the home screen; tap it → the app enables + launches. Use "Pin all" and the Freeze all / Unfreeze all pin actions → dialogs appear per shortcut.

- [ ] **Step 7: Commit.**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/freezer/
git commit -m "feat(freezer): launcher-shortcut actions in the Freezer UI (#210)"
```

---

### Task 6: Dynamic shortcuts sync + cleanup wiring

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/ThorApplication.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt`

**Interfaces:**
- Consumes: `FreezerShortcutManager.syncDynamicShortcuts(enabled)`, `FreezerShortcutManager.disableAppShortcut(packageName, reason)`.

- [ ] **Step 1: Sync dynamic shortcuts at app start.** In `ThorApplication.kt`, add the injected manager and call sync inside the existing `MainScope().launch` block:

```kotlin
    private val autoFreezeManager: AutoFreezeManager by inject()
    private val freezerShortcutManager: com.valhalla.thor.data.launcher.FreezerShortcutManager by inject()
```

```kotlin
        MainScope().launch {
            val prefs = preferenceRepository.userPreferences.first()
            freezerShortcutManager.syncDynamicShortcuts(prefs.addFreezerToLauncher)
            withContext(Dispatchers.Main) {
                localeManager.applyLocale(prefs.language)
            }
        }
```

- [ ] **Step 2: Sync on setting toggle.** In `SettingsViewModel.kt`, inject the manager (add to constructor: `private val freezerShortcutManager: com.valhalla.thor.data.launcher.FreezerShortcutManager`) and update the setter from Task 1:

```kotlin
    fun setAddFreezerToLauncher(enabled: Boolean) {
        viewModelScope.launch {
            preferenceRepository.setAddFreezerToLauncher(enabled)
            freezerShortcutManager.syncDynamicShortcuts(enabled)
        }
    }
```

- [ ] **Step 3: Disable a shortcut on permanent unfreeze.** In `FreezerViewModel.kt`, in the code path that removes an app from the freezer (`removeFromFreezer` / the removal branch of `toggleManaged`), after the app is removed from the Room set, call:

```kotlin
        freezerShortcutManager.disableAppShortcut(
            packageName,
            "No longer frozen"
        )
```

(Use the actual removed package name variable in scope. Do this for each removed package in bulk-remove paths.)

- [ ] **Step 4: Build.** Run: `./gradlew assembleFossDebug`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Device-verify.**
  - With the setting ON, long-press Thor's launcher icon → "Freeze all" and "Unfreeze all" appear; tap each → freezer set toggles.
  - Turn the setting OFF → long-press Thor icon → the two dynamic shortcuts are gone.
  - Pin a frozen app, then unfreeze it permanently (remove from freezer) → its home-screen shortcut greys out / shows disabled.
  - Full loop: freeze user app → pin → tap (enables + launches) → lock screen (with auto-freeze on) → app re-freezes (`adb shell pm list packages -d` shows it disabled again).

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/com/valhalla/thor/ThorApplication.kt app/src/main/java/com/valhalla/thor/presentation/settings/SettingsViewModel.kt app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt
git commit -m "feat(freezer): sync dynamic shortcuts + cleanup on unfreeze/toggle-off (#210)"
```

---

## Release (after all tasks land + review)

- [ ] Bump `versionCode` in `gradle.properties` (per Thor's single-integer scheme; e.g. `1900 → 1910`).
- [ ] Update `CHANGELOG`/release notes as the repo requires.
- [ ] Full build: `./gradlew assembleFossRelease assembleStoreRelease`.

## Notes for the implementer

- **Testability reality:** Only the pure `FreezerShortcutContract` (Task 2) is JVM-unit-tested. The rest is Android-framework glue (ShortcutManagerCompat, Bitmap, Activity, PackageManager) and is verified by build + on-device steps — that matches how this area of Thor is currently exercised. Do not fake unit tests for the glue.
- **`AppInfo.appName`** is the label; **`AppInfo.isSystem`** gates user-apps-only. Confirm both against `domain/model/AppInfo.kt` before use.
- **`SystemRepository`** is already a constructor dep of `SettingsViewModel`/`FreezerTileService`; the trampoline injects it via `by inject()`.
- **String reuse:** `R.string.tile_grant_privilege_toast` (no-privilege) and `R.string.unfreeze_all_apps` already exist; only add the six strings in Task 1 Step 6.
- If `androidx.core`’s `ShortcutManagerCompat`/`IconCompat`/`toBitmap` don’t resolve, add `implementation(libs.androidx.core.ktx)` to `app/build.gradle.kts`.
