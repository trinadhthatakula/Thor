// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation

import android.content.ContextWrapper
import com.valhalla.thor.domain.model.AnimationIntensity
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DetailedAppInfo
import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.SortBy
import com.valhalla.thor.domain.model.SortOrder
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.repository.AppBundleBuilder
import com.valhalla.thor.domain.repository.AppBundleFileStore
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.SystemRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File

// Hand-written fakes, matching the rest of the suite — no mocking library. The point of a
// privileged-action fake is that the *call it was asked to make* is the assertion, so these record
// calls rather than simulating a device.

/**
 * Records every privileged call in order and answers success unless the test planted a failure.
 *
 * The recorded strings are the whole contract: "was `setAppDisabled` reached at all for this
 * package" is exactly the question a tier gate has to answer, and a list of strings compares by
 * value without a matcher DSL.
 */
class FakeSystemRepository : SystemRepository {

    /** Every call reaching the privilege layer, in order, as `"method:arg[:arg]"`. */
    val calls = mutableListOf<String>()

    private val failures = mutableMapOf<String, Throwable>()

    /** Make every call recorded as [call] fail with [error]. Keys are the [calls] format. */
    fun failWith(call: String, error: Throwable) {
        failures[call] = error
    }

    private fun record(call: String): Result<Unit> {
        calls += call
        return failures[call]?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    override suspend fun isRootAvailable(): Boolean = true
    override suspend fun isShizukuAvailable(): Boolean = false
    override suspend fun isDhizukuAvailable(): Boolean = false

    override suspend fun forceStopApp(packageName: String) = record("forceStopApp:$packageName")

    override suspend fun clearCache(packageName: String) = record("clearCache:$packageName")

    override suspend fun clearAppData(packageName: String) = record("clearAppData:$packageName")

    override suspend fun setAppDisabled(packageName: String, isDisabled: Boolean) =
        record("setAppDisabled:$packageName:$isDisabled")

    override suspend fun setAppSuspended(packageName: String, isSuspended: Boolean) =
        record("setAppSuspended:$packageName:$isSuspended")

    override suspend fun setAppRestricted(packageName: String, isRestricted: Boolean) =
        record("setAppRestricted:$packageName:$isRestricted")

    override suspend fun uninstallApp(packageName: String) = record("uninstallApp:$packageName")

    override suspend fun rebootDevice(reason: String) = record("rebootDevice:$reason")

    override suspend fun aggressiveCleanup(packageName: String) =
        record("aggressiveCleanup:$packageName")

    override suspend fun reinstallAppWithGoogle(packageName: String) =
        record("reinstallAppWithGoogle:$packageName")

    override suspend fun copyFileWithRoot(sourcePath: String, destinationPath: String) =
        record("copyFileWithRoot:$sourcePath:$destinationPath")

    override suspend fun grantPermission(packageName: String, permissionName: String) =
        record("grantPermission:$packageName:$permissionName")

    override suspend fun revokePermission(packageName: String, permissionName: String) =
        record("revokePermission:$packageName:$permissionName")

    override suspend fun getAppPaths(packageName: String): Result<List<String>> {
        calls += "getAppPaths:$packageName"
        return Result.success(emptyList())
    }

    override suspend fun executeShellCommand(command: String): Result<Pair<Int, String?>> {
        calls += "executeShellCommand:$command"
        return Result.success(0 to null)
    }
}

/** Backed by a [MutableStateFlow] so a test can push a rescan mid-run if it needs one. */
class FakeAppRepository(initialApps: List<AppInfo> = emptyList()) : AppRepository {

    val apps = MutableStateFlow(initialApps)
    val installSizeWrites = mutableListOf<Map<String, Long>>()

    override fun getAllApps(): Flow<List<AppInfo>> = apps

    override suspend fun getAppDetails(packageName: String): AppInfo? =
        apps.value.firstOrNull { it.packageName == packageName }

    override suspend fun getDetailedAppInfo(packageName: String): DetailedAppInfo? = null

    override suspend fun getApkDetails(apkPath: String): AppInfo? = null

    override suspend fun updateInstallSizes(sizes: Map<String, Long>) {
        installSizeWrites += sizes
    }
}

/**
 * The freezer watchlist. [added] / [removed] keep the write history even when the write is a no-op
 * on the set, because "did this path touch membership at all" is what the membership gates decide.
 */
class FakeFreezerRepository(initial: Set<String> = emptySet()) : FreezerRepository {

    private val packages = MutableStateFlow(initial)

    val added = mutableListOf<String>()
    val removed = mutableListOf<String>()

    override fun getAll(): Flow<List<String>> = packages.map { it.toList() }

    override suspend fun getAllPackageNames(): List<String> = packages.value.toList()

    override suspend fun add(packageName: String) {
        added += packageName
        packages.update { it + packageName }
    }

    override suspend fun remove(packageName: String) {
        removed += packageName
        packages.update { it - packageName }
    }

    override suspend fun contains(packageName: String): Boolean = packageName in packages.value
}

/**
 * A real (in-memory) preference store rather than a stub: every setter writes the field it names,
 * so a test can flip a preference mid-run and watch the view model react through its own flow —
 * which is how the preference is delivered in production too.
 */
class FakePreferenceRepository(
    initial: UserPreferences = UserPreferences()
) : PreferenceRepository {

    private val prefs = MutableStateFlow(initial)

    override val userPreferences: Flow<UserPreferences> = prefs

    override suspend fun updateAppSort(sortBy: SortBy) {
        prefs.update { it.copy(appSortBy = sortBy) }
    }

    override suspend fun updateAppSortOrder(sortOrder: SortOrder) {
        prefs.update { it.copy(appSortOrder = sortOrder) }
    }

    override suspend fun updateAppFilter(filterType: FilterType, selectedFilter: String) {
        prefs.update { it.copy(appFilterType = filterType, appSelectedFilter = selectedFilter) }
    }

    override suspend fun setReinstallAllCardVisibility(isVisible: Boolean) {
        prefs.update { it.copy(showReinstallAllCard = isVisible) }
    }

    override suspend fun setThemeMode(themeMode: ThemeMode) {
        prefs.update { it.copy(themeMode = themeMode) }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        prefs.update { it.copy(useDynamicColor = enabled) }
    }

    override suspend fun setUseAmoled(enabled: Boolean) {
        prefs.update { it.copy(useAmoled = enabled) }
    }

    override suspend fun setBiometricLock(enabled: Boolean) {
        prefs.update { it.copy(biometricLockEnabled = enabled) }
    }

    override suspend fun setPrivilegeMode(mode: PrivilegeMode?) {
        prefs.update { it.copy(preferredPrivilegeMode = mode) }
    }

    override suspend fun setLanguage(language: String?) {
        prefs.update { it.copy(language = language) }
    }

    override suspend fun setExportDirUri(uri: String?) {
        prefs.update { it.copy(exportDirUri = uri) }
    }

    override suspend fun setAutoFreezeEnabled(enabled: Boolean) {
        prefs.update { it.copy(autoFreezeEnabled = enabled) }
    }

    override suspend fun setAddFreezerToLauncher(enabled: Boolean) {
        prefs.update { it.copy(addFreezerToLauncher = enabled) }
    }

    override suspend fun setFreezerMode(mode: FreezerMode) {
        prefs.update { it.copy(freezerMode = mode) }
    }

    override suspend fun setHasShownDisabledAppsPrompt(hasShown: Boolean) {
        prefs.update { it.copy(hasShownDisabledAppsPrompt = hasShown) }
    }

    override suspend fun setHasShownSupportDeveloperPrompt(hasShown: Boolean) {
        prefs.update { it.copy(hasShownSupportDeveloperPrompt = hasShown) }
    }

    override suspend fun setAnimationIntensity(intensity: AnimationIntensity) {
        prefs.update { it.copy(animationIntensity = intensity) }
    }

    override suspend fun setAppListIsGrid(isGrid: Boolean) {
        prefs.update { it.copy(appListIsGrid = isGrid) }
    }

    override suspend fun setFreezerIsGrid(isGrid: Boolean) {
        prefs.update { it.copy(freezerIsGrid = isGrid) }
    }

    override suspend fun toggleAppListIsGrid() {
        prefs.update { it.copy(appListIsGrid = !it.appListIsGrid) }
    }

    override suspend fun toggleFreezerIsGrid() {
        prefs.update { it.copy(freezerIsGrid = !it.freezerIsGrid) }
    }

    override suspend fun setExtensionsUnlocked(unlocked: Boolean) {
        prefs.update { it.copy(extensionsUnlocked = unlocked) }
    }

    override suspend fun setExtensionConsentAccepted(accepted: Boolean) {
        prefs.update { it.copy(extensionConsentAccepted = accepted) }
    }

    override suspend fun setAutoReinstallEnabled(enabled: Boolean) {
        prefs.update { it.copy(autoReinstallEnabled = enabled) }
    }

    override suspend fun getInstallerArg(): String = ""
}

// The share pipeline is out of reach on a plain JVM: ShareAppUseCase finishes by turning the file
// store's path into an android.net.Uri, and Uri.parse is one of the android.jar stubs that throws
// in unit tests. These two exist so MainViewModel can be constructed; they fail loudly rather than
// quietly, so a future share test reports the real reason instead of a confusing null.

class FakeAppBundleBuilder : AppBundleBuilder {
    // No default values on the override — Kotlin takes them from the interface.
    override suspend fun build(
        appInfo: AppInfo,
        cacheSubDir: String,
        format: BundleFormat
    ): Result<File> =
        Result.failure(UnsupportedOperationException("bundle building needs a device"))
}

class FakeAppBundleFileStore : AppBundleFileStore {
    override suspend fun writeToDownloads(file: File, mime: String): String = unsupported()
    override suspend fun writeToTree(file: File, treeUriStr: String, mime: String): String = unsupported()
    override suspend fun isTreeWritable(treeUriStr: String?): Boolean = unsupported()
    override suspend fun currentTargetLabel(savedTreeUriStr: String?): String = unsupported()
    override fun shareUri(file: File): String = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("file store needs a device")
}

/**
 * A [android.content.Context] that answers `getCacheDir()` and nothing else.
 *
 * Needed because `BackupRunner` is a final Kotlin class like everything else in `data/`, so there
 * is nothing to substitute for it, and a view model that takes one cannot be built without a
 * Context. The only Android thing the runner touches is the cache dir it hands the use case as a
 * staging root, and [ContextWrapper] is the one concrete Context in android.jar — its constructor
 * survives the mockable-jar rewrite as a bare `super()` call, so subclassing it needs no mocking
 * library. Every other method still throws "not mocked", which is the right answer: a test that
 * reaches one is asking for a device.
 *
 * Not a general-purpose Context. Widen it only for something equally narrow.
 */
class FakeContext(private val cache: File) : ContextWrapper(null) {
    override fun getCacheDir(): File = cache
}

/**
 * A user app. `freezeTierOf` short-circuits on `!isSystem -> NORMAL`, so this is never blocked
 * whatever else it carries — which is what makes it the control in every tier-gate test.
 */
fun userApp(
    packageName: String,
    enabled: Boolean = true,
    isSuspended: Boolean = false,
    isDebuggable: Boolean = false,
    appName: String? = null,
): AppInfo = AppInfo(
    appName = appName,
    packageName = packageName,
    isSystem = false,
    enabled = enabled,
    isSuspended = isSuspended,
    isDebuggable = isDebuggable
)

/**
 * A system app whose tier follows from [recommendation] and [uadLoadFailed], the two inputs
 * `freezeTierOf` reads. Defaults to NORMAL: a system app with UAD data that says nothing about it.
 */
fun systemApp(
    packageName: String,
    recommendation: String? = null,
    uadLoadFailed: Boolean = false,
    enabled: Boolean = true,
    isSuspended: Boolean = false,
    appName: String? = null,
): AppInfo = AppInfo(
    appName = appName,
    packageName = packageName,
    isSystem = true,
    enabled = enabled,
    isSuspended = isSuspended,
    bloatRecommendation = recommendation,
    isUadLoadFailed = uadLoadFailed
)

/**
 * A system app the UAD list calls unsafe, i.e. `FreezeTier.BLOCKED`. Named for what it means rather
 * than for the recommendation string, so a test reads as the rule it is checking.
 */
fun blockedSystemApp(packageName: String, enabled: Boolean = true, isSuspended: Boolean = false): AppInfo =
    systemApp(packageName, recommendation = "unsafe", enabled = enabled, isSuspended = isSuspended)
