// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation

import android.content.ContextWrapper
import com.valhalla.thor.domain.model.AnimationIntensity
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppPermission
import com.valhalla.thor.domain.model.BulkOutcome
import com.valhalla.thor.domain.model.BulkRequest
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DetailedAppInfo
import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.FreezeProfile
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.InstalledAppsPermission
import com.valhalla.thor.domain.model.PermissionIndex
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.model.SortBy
import com.valhalla.thor.domain.model.SortOrder
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.repository.AppBundleBuilder
import com.valhalla.thor.domain.repository.AppBundleFileStore
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.AppShortcutController
import com.valhalla.thor.domain.repository.AuthCapability
import com.valhalla.thor.domain.repository.BulkFreezeController
import com.valhalla.thor.domain.repository.FreezeProfileRepository
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.InstalledAppsPermissionGate
import com.valhalla.thor.domain.repository.PermissionRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import com.valhalla.thor.domain.repository.StorageStatsProvider
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.repository.UsageAccessGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File

// Hand-written fakes, matching the rest of the suite — no mocking library. The point of a
// privileged-action fake is that the *call it was asked to make* is the assertion, so these record
// calls rather than simulating a device.

/**
 * One ordered log shared by several fakes.
 *
 * Each fake already records what it was asked to do, which answers "did this happen". No collection
 * of those per-fake lists answers "did it happen *before* that", and some contracts are purely
 * ordering claims — GH#310's is exactly one: restore the app, *then* delete its watchlist row. Hand
 * the same list to several fakes and the interleaving across the privilege boundary, Room and the
 * launcher becomes a single list to compare by value.
 *
 * Entries are namespaced by the fake that wrote them, except [FakeSystemRepository], which keeps its
 * own `"method:arg"` format so a trace assertion reads the same as a `calls` one.
 */
typealias CallTrace = MutableList<String>

/**
 * Records every privileged call in order and answers success unless the test planted a failure.
 *
 * The recorded strings are the whole contract: "was `setAppDisabled` reached at all for this
 * package" is exactly the question a tier gate has to answer, and a list of strings compares by
 * value without a matcher DSL.
 */
class FakeSystemRepository(private val trace: CallTrace? = null) : SystemRepository {

    /** Every call reaching the privilege layer, in order, as `"method:arg[:arg]"`. */
    val calls = mutableListOf<String>()

    private val failures = mutableMapOf<String, Throwable>()

    /** Make every call recorded as [call] fail with [error]. Keys are the [calls] format. */
    fun failWith(call: String, error: Throwable) {
        failures[call] = error
    }

    private fun record(call: String): Result<Unit> {
        calls += call
        trace?.add(call)
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

    /**
     * Detail-screen answers, by package. Empty by default, so `getDetailedAppInfo` keeps returning
     * null — which is itself a state under test: the details screen has to behave sanely before its
     * first load lands, not only after.
     */
    val details = mutableMapOf<String, DetailedAppInfo>()

    override fun getAllApps(): Flow<List<AppInfo>> = apps

    override suspend fun getAppDetails(packageName: String): AppInfo? =
        apps.value.firstOrNull { it.packageName == packageName }

    override suspend fun getDetailedAppInfo(packageName: String): DetailedAppInfo? =
        details[packageName]

    override suspend fun getApkDetails(apkPath: String): AppInfo? = null

    override suspend fun updateInstallSizes(sizes: Map<String, Long>) {
        installSizeWrites += sizes
    }
}

/**
 * The freezer watchlist. [added] / [removed] keep the write history even when the write is a no-op
 * on the set, because "did this path touch membership at all" is what the membership gates decide.
 */
class FakeFreezerRepository(
    initial: Set<String> = emptySet(),
    private val trace: CallTrace? = null
) : FreezerRepository {

    private val packages = MutableStateFlow(initial)

    val added = mutableListOf<String>()
    val removed = mutableListOf<String>()

    private val addFailures = mutableMapOf<String, Throwable>()
    private val removeFailures = mutableMapOf<String, Throwable>()

    /**
     * Make the write of [packageName] raise.
     *
     * A throw rather than a `Result`, because that is how Room reports a failed write: a caller
     * that only checks the privileged call's `Result` never learns this one happened at all.
     */
    fun failAddWith(packageName: String, error: Throwable) {
        addFailures[packageName] = error
    }

    /** As [failAddWith], for the delete. */
    fun failRemoveWith(packageName: String, error: Throwable) {
        removeFailures[packageName] = error
    }

    override fun getAll(): Flow<List<String>> = packages.map { it.toList() }

    override suspend fun getAllPackageNames(): List<String> = packages.value.toList()

    override suspend fun add(packageName: String) {
        addFailures[packageName]?.let { throw it }
        added += packageName
        trace?.add("freezer.add:$packageName")
        packages.update { it + packageName }
    }

    override suspend fun remove(packageName: String) {
        // Before the bookkeeping: a delete that raised deleted nothing, so [removed] stays the
        // list of rows that actually went.
        removeFailures[packageName]?.let { throw it }
        removed += packageName
        trace?.add("freezer.remove:$packageName")
        packages.update { it - packageName }
    }

    override suspend fun contains(packageName: String): Boolean = packageName in packages.value
}

/**
 * Freeze profiles as an in-memory table, ids handed out in creation order like Room's autoincrement.
 *
 * No unique index on the name — the real table has one, and it is what makes a rename fail with
 * `SQLiteConstraintException`. That exception cannot be constructed on a plain JVM (android.jar
 * stubs throw from their constructors), so the name-taken path stays out of reach here rather than
 * being faked into something that only looks like it.
 */
class FakeFreezeProfileRepository(initial: List<FreezeProfile> = emptyList()) :
    FreezeProfileRepository {

    private val profiles = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    override fun observeProfiles(): Flow<List<FreezeProfile>> = profiles

    override suspend fun packagesOf(profileId: Long): List<String> =
        profiles.value.firstOrNull { it.id == profileId }?.packageNames.orEmpty()

    override suspend fun allProfilePackageNames(): Set<String> =
        profiles.value.flatMap { it.packageNames }.toSet()

    override suspend fun create(name: String, packageNames: List<String>): Long {
        val id = nextId++
        profiles.update { it + FreezeProfile(id, name, packageNames) }
        return id
    }

    override suspend fun update(profileId: Long, name: String, packageNames: List<String>) {
        profiles.update { list ->
            list.map { if (it.id == profileId) FreezeProfile(profileId, name, packageNames) else it }
        }
    }

    override suspend fun delete(profileId: Long) {
        profiles.update { list -> list.filterNot { it.id == profileId } }
    }
}

/**
 * A bulk runner that runs nothing.
 *
 * `BulkFreezeRunner` itself cannot be built on a JVM — see [BulkFreezeController], which exists for
 * that reason — and a view model only ever observes what is in flight and awaits what it launched.
 * Recording the request and answering with an already-completed [outcome] covers both members.
 */
class FakeBulkFreezeController(
    var outcome: BulkOutcome = BulkOutcome.NothingToDo
) : BulkFreezeController {

    val launched = mutableListOf<BulkRequest>()

    private val _runningRequests = MutableStateFlow<List<BulkRequest>>(emptyList())
    override val runningRequests: StateFlow<List<BulkRequest>> = _runningRequests

    /** Publish an in-flight chain, as the runner does while a batch is going. */
    fun setRunning(requests: List<BulkRequest>) {
        _runningRequests.value = requests
    }

    override fun launch(request: BulkRequest): Deferred<BulkOutcome> {
        launched += request
        return CompletableDeferred(outcome)
    }
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
        format: BundleFormat,
        fileName: String?
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

// The four ports below exist so a view model can be built without a Context. Each concrete
// implementation resolves a system service (or registers Shizuku listeners) from its initializer,
// which throws on a plain JVM — see the KDoc on each interface for what was left behind.

/**
 * A privilege probe a test can drive by hand: no Shizuku listeners, no probe coroutine, no Binder.
 *
 * Starts at the *cold-start* value — `isReady = false` — because that is the state every consumer
 * has to survive, and a fake that started ready would hide the loader-gating rule entirely.
 */
class FakePrivilegeStateProvider(
    initial: PrivilegeState = PrivilegeState()
) : PrivilegeStateProvider {

    private val _state = MutableStateFlow(initial)

    override val state: StateFlow<PrivilegeState> = _state

    /** Publish a new probe result, as the real manager does when a grant lands. */
    fun emit(next: PrivilegeState) {
        _state.value = next
    }
}

/** Answers from a fixed table and records what it was asked, so "was a size scan run" is assertable. */
class FakeStorageStatsProvider(
    private val sizes: Map<String, Long> = emptyMap()
) : StorageStatsProvider {

    val queries = mutableListOf<List<String>>()

    override suspend fun installSizes(packages: List<String>): Map<String, Long> {
        queries += packages
        // Mirrors the real one: an unreadable package is *absent*, not zero.
        return sizes.filterKeys { it in packages }
    }
}

/** Grant state is a plain flag a test can flip mid-run, matching how the app-op behaves. */
class FakeUsageAccessGate(var granted: Boolean = true) : UsageAccessGate {

    var autoGrantCalls = 0
        private set

    override fun isGranted(): Boolean = granted

    override suspend fun tryGrantViaPrivilege(): Boolean = granted

    override suspend fun maybeAutoGrant() {
        autoGrantCalls++
    }
}

/**
 * The GET_INSTALLED_APPS probe, as a plain mutable field.
 *
 * Defaults to [InstalledAppsPermission.Unsupported] because that is what the overwhelming majority
 * of devices Thor runs on answer — the permission is a Chinese-market standard and does not exist on
 * AOSP. A test that wants the banner has to ask for [InstalledAppsPermission.Denied] explicitly,
 * which keeps "a Pixel is never nagged" the behaviour you get by saying nothing.
 */
class FakeInstalledAppsPermissionGate(
    var permission: InstalledAppsPermission = InstalledAppsPermission.Unsupported
) : InstalledAppsPermissionGate {

    var stateCalls = 0
        private set

    override fun state(): InstalledAppsPermission {
        stateCalls++
        return permission
    }
}

/**
 * Answers the one call the app list makes, and counts it.
 *
 * The index sweep is a `getPackageInfo` per installed package on a real device, so "how many times
 * was it built" is the interesting question — the filter is driven off a preference *and* off the
 * installed-app set, and a rebuild per app-list emission would be a binder storm.
 */
class FakePermissionRepository(
    private val index: Result<PermissionIndex> = Result.success(PermissionIndex())
) : PermissionRepository {

    var indexBuilds = 0
        private set

    override suspend fun buildPermissionIndex(): Result<PermissionIndex> {
        indexBuilds++
        return index
    }

    override suspend fun getAppPermissions(packageName: String): Result<List<AppPermission>> =
        Result.success(emptyList())

    override suspend fun grantPermission(packageName: String, permissionName: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun revokePermission(packageName: String, permissionName: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun isPrivilegeActive(): Boolean = true
}

/** Records the packages whose launcher shortcut was retired, re-rendered or pinned. */
class FakeAppShortcutController(
    // Settable: the pin affordances are hidden on a launcher that refuses pin requests, and that
    // branch is only reachable if the answer can be false.
    var pinSupported: Boolean = true,
    private val trace: CallTrace? = null
) : AppShortcutController {

    val disabled = mutableListOf<String>()
    val refreshed = mutableListOf<String>()
    val pinned = mutableListOf<String>()
    val pinnedBulkActions = mutableListOf<String>()

    private val disableFailures = mutableMapOf<String, Throwable>()

    /**
     * Make disabling [packageName]'s shortcut raise, as `ShortcutManagerCompat` does — it reports a
     * refused or rate-limited request by throwing, never by returning anything a caller can check.
     */
    fun failDisableWith(packageName: String, error: Throwable) {
        disableFailures[packageName] = error
    }

    override fun disableAppShortcut(packageName: String) {
        disableFailures[packageName]?.let { throw it }
        disabled += packageName
        trace?.add("shortcut.disable:$packageName")
    }

    override fun refreshAppShortcut(packageName: String) {
        refreshed += packageName
    }

    override fun isPinSupported(): Boolean = pinSupported

    override fun pinAppShortcut(packageName: String, label: String) {
        pinned += packageName
    }

    override suspend fun pinAppShortcutSuspend(packageName: String, label: String) {
        pinned += packageName
    }

    override fun pinBulkShortcut(action: String) {
        pinnedBulkActions += action
    }
}

/**
 * The device's ability to authenticate, as a settable value.
 *
 * Mutable on purpose: the interesting cases are all *transitions* — the user leaves for system
 * Settings with no screen lock set and comes back with one — and there is no way to express that
 * against a fixed return value. Both default to true so every test written before [AuthCapability]
 * existed keeps its original meaning.
 */
class FakeAuthCapability(
    var capable: Boolean = true,
    var hardware: Boolean = true
) : AuthCapability {
    override fun canAuthenticate(): Boolean = capable
    override fun hasHardware(): Boolean = hardware
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
    // The permission index keys its invalidation on `packageName@lastUpdateTime`, so this is how a
    // test says "the same app, updated" as opposed to "the same app".
    lastUpdateTime: Long = 0L,
): AppInfo = AppInfo(
    appName = appName,
    packageName = packageName,
    isSystem = false,
    enabled = enabled,
    isSuspended = isSuspended,
    isDebuggable = isDebuggable,
    lastUpdateTime = lastUpdateTime
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
