// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation

import android.content.ContextWrapper
import com.valhalla.thor.domain.gateway.ComponentEnabledState
import com.valhalla.thor.domain.model.AnimationIntensity
import com.valhalla.thor.domain.model.AppGridDensity
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppInfoActionId
import com.valhalla.thor.domain.model.AppPermission
import com.valhalla.thor.domain.model.BulkOutcome
import com.valhalla.thor.domain.model.BulkRequest
import com.valhalla.thor.domain.model.NoOpReason
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.ComponentSnapshot
import com.valhalla.thor.domain.model.DefaultTab
import com.valhalla.thor.domain.model.DetailedAppInfo
import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.FreezeProfile
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.InstalledAppsPermission
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.model.PermissionIndex
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
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
import com.valhalla.thor.domain.repository.InstallerLabelResolver
import com.valhalla.thor.domain.repository.PermissionRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import com.valhalla.thor.domain.repository.StorageStatsProvider
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.repository.UsageAccessGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.ContinuationInterceptor

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

    /** Context paired with every context-aware privilege call, in call order. */
    val executions = mutableListOf<Pair<String, PrivilegeExecutionContext>>()

    private val failures = mutableMapOf<String, Throwable>()

    /**
     * Runs as each call is recorded, before its result is returned.
     *
     * The only way a test can act *between* two apps of a batch. Everything here answers without
     * suspending and the view model's IO dispatcher is the test's own, so a batch started from a
     * test body runs to completion before control returns — leaving no moment to, say, ask it to
     * stop.
     */
    var onCall: ((String) -> Unit)? = null

    /** Make every call recorded as [call] fail with [error]. Keys are the [calls] format. */
    fun failWith(call: String, error: Throwable) {
        failures[call] = error
    }

    /**
     * The three shared side effects of any call reaching this fake.
     *
     * Split out of [record] for the overrides that do not return `Result<Unit>` — they used to
     * append to [calls] and nothing else, so a test observing the privilege layer through [trace] or
     * steering it through [onCall] could neither see nor intercept them.
     */
    private fun note(call: String, execution: PrivilegeExecutionContext? = null) {
        calls += call
        execution?.let { executions += call to it }
        trace?.add(call)
        onCall?.invoke(call)
    }

    private fun record(
        call: String,
        execution: PrivilegeExecutionContext? = null,
    ): Result<Unit> {
        note(call, execution)
        return failures[call]?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    /**
     * What the two cache clears report as freed on success. `null` is the real "the clear worked and
     * the measurement did not" answer, so that is the default: a test that wants a number says so.
     */
    var cacheFreedBytes: Long? = null

    /** [record] for the two operations that return a byte count; [failWith] still applies. */
    private fun recordBytes(
        call: String,
        execution: PrivilegeExecutionContext? = null,
    ): Result<Long?> = record(call, execution).map { cacheFreedBytes }

    override suspend fun isRootAvailable(): Boolean = true
    override suspend fun isShizukuAvailable(): Boolean = false
    override suspend fun isDhizukuAvailable(): Boolean = false

    override suspend fun forceStopApp(packageName: String, execution: PrivilegeExecutionContext) =
        record("forceStopApp:$packageName", execution)

    override suspend fun clearCache(packageName: String, execution: PrivilegeExecutionContext) =
        recordBytes("clearCache:$packageName", execution)

    override suspend fun clearAllCaches() = recordBytes("clearAllCaches")

    override suspend fun clearAppData(packageName: String, execution: PrivilegeExecutionContext) =
        record("clearAppData:$packageName", execution)

    override suspend fun setAppDisabled(
        packageName: String,
        isDisabled: Boolean,
        execution: PrivilegeExecutionContext,
    ) = record("setAppDisabled:$packageName:$isDisabled", execution)

    override suspend fun setAppSuspended(
        packageName: String,
        isSuspended: Boolean,
        execution: PrivilegeExecutionContext,
    ) = record("setAppSuspended:$packageName:$isSuspended", execution)

    override suspend fun setAppRestricted(
        packageName: String,
        isRestricted: Boolean,
        execution: PrivilegeExecutionContext,
    ) = record("setAppRestricted:$packageName:$isRestricted", execution)

    override suspend fun uninstallApp(packageName: String, execution: PrivilegeExecutionContext) =
        record("uninstallApp:$packageName", execution)

    override suspend fun rebootDevice(reason: String) = record("rebootDevice:$reason")

    override suspend fun reinstallAppWithGoogle(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ) = record("reinstallAppWithGoogle:$packageName", execution)

    override suspend fun copyFileWithRoot(
        sourcePath: String,
        destinationPath: String,
        execution: PrivilegeExecutionContext,
    ) = record("copyFileWithRoot:$sourcePath:$destinationPath", execution)

    override suspend fun grantPermission(
        packageName: String,
        permissionName: String,
        execution: PrivilegeExecutionContext,
    ) = record("grantPermission:$packageName:$permissionName", execution)

    override suspend fun revokePermission(
        packageName: String,
        permissionName: String,
        execution: PrivilegeExecutionContext,
    ) = record("revokePermission:$packageName:$permissionName", execution)

    override suspend fun getAppPaths(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<List<String>> {
        note("getAppPaths:$packageName", execution)
        return Result.success(emptyList())
    }

    override suspend fun executeShellCommand(
        command: String,
        execution: PrivilegeExecutionContext,
    ): Result<Pair<Int, String?>> {
        note("executeShellCommand:$command", execution)
        return Result.success(0 to null)
    }

    /**
     * What [probeObb] answers. `None` — a device where the app genuinely has no expansion files —
     * rather than `Undetermined`, because the honest default for a fake is the successful read of
     * an empty directory, not a privilege failure. A test that wants `Present` or `Undetermined`
     * assigns it; the two must stay distinguishable at every consumer, so a fake that only ever
     * says `None` would let a consumer that collapses them pass.
     */
    var obbProbe: ObbProbe = ObbProbe.None

    override suspend fun probeObb(packageName: String): ObbProbe {
        note("probeObb:$packageName")
        return obbProbe
    }

    override suspend fun setComponentEnabled(
        packageName: String,
        className: String,
        state: ComponentEnabledState,
        execution: PrivilegeExecutionContext,
    ) = record("setComponentEnabled:$packageName:$className:$state", execution)

    override suspend fun forceLaunchActivity(
        packageName: String,
        className: String,
        execution: PrivilegeExecutionContext,
    ) = record("forceLaunchActivity:$packageName:$className", execution)

    override suspend fun stopService(
        packageName: String,
        className: String,
        execution: PrivilegeExecutionContext,
    ) = record("stopService:$packageName:$className", execution)
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

    /**
     * Component reads, by package.
     *
     * Falls back to whatever [details] already holds for the package, so a test that plants a
     * `DetailedAppInfo` gets a consistent answer from both reads without saying it twice. `null` —
     * the read failed — stays reachable by planting neither.
     */
    val componentSnapshots = mutableMapOf<String, ComponentSnapshot>()

    override fun getAllApps(): Flow<List<AppInfo>> = apps

    override suspend fun getAppDetails(packageName: String): AppInfo? =
        apps.value.firstOrNull { it.packageName == packageName }

    private val detailFailures = mutableMapOf<String, Throwable>()

    /**
     * Make the detail read of [packageName] raise, as the package manager does.
     *
     * `getDetailedAppInfo` funnels into `PackageManager`, which reports by throwing — `DeadObjectException`
     * when `system_server` restarts under it, `NameNotFoundException` when the app is uninstalled
     * between the tap and the read. It is the seam `AppInfoDetailsViewModel.refreshDetails` cannot
     * degrade the way it degrades the watchlist read, and so the only remaining collaborator that can
     * throw *after* a freeze or unfreeze has already been applied — which is the case worth pinning,
     * because that is where a bare "Error: …" would tell a user their action failed over an app that is
     * demonstrably frozen.
     */
    fun failDetailsWith(packageName: String, error: Throwable) {
        detailFailures[packageName] = error
    }

    override suspend fun getDetailedAppInfo(packageName: String): DetailedAppInfo? {
        detailFailures[packageName]?.let { throw it }
        return details[packageName]
    }

    override suspend fun getComponentDetails(packageName: String): ComponentSnapshot? =
        componentSnapshots[packageName] ?: details[packageName]?.components

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

    /**
     * The dispatcher each write actually ran on, keyed the same way as [trace].
     *
     * Recorded because nothing else in the suite can see it. Every view model here is built with one
     * dispatcher injected everywhere, so a `launchGuarded(context = ioDispatcher)` that silently
     * lost its context would pass every test — and losing it is not a hypothetical: the production
     * comments at those call sites all say the same thing, that Room's suspend DAO functions
     * dispatch internally, so a write left on `Dispatchers.Main.immediate` keeps working and nothing
     * fails to say so. The only way to catch it is to inject a *distinct* dispatcher and ask the
     * write which one it woke up on.
     */
    val ranOn = mutableMapOf<String, ContinuationInterceptor?>()

    private val addFailures = mutableMapOf<String, Throwable>()
    private val removeFailures = mutableMapOf<String, Throwable>()
    private val containsFailures = mutableMapOf<String, Throwable>()

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

    /**
     * As [failAddWith], for the membership *read*.
     *
     * Worth a seam of its own even though nothing is written: Room raises
     * `SQLiteDiskIOException`/`SQLiteFullException` out of a `SELECT` as readily as out of an
     * `INSERT`, and the read is the more dangerous of the two here because it runs on paths that had
     * not touched the database yet — opening a details sheet, or deciding which way a toggle points.
     * A caller that treats "the query threw" as "not in the freezer" is making a claim, and only a
     * throwing read can tell whether it makes the right one.
     */
    fun failContainsWith(packageName: String, error: Throwable) {
        containsFailures[packageName] = error
    }

    override fun getAll(): Flow<List<String>> = packages.map { it.toList() }

    override suspend fun getAllPackageNames(): List<String> = packages.value.toList()

    override suspend fun add(packageName: String) {
        ranOn["freezer.add:$packageName"] = currentCoroutineContext()[ContinuationInterceptor]
        addFailures[packageName]?.let { throw it }
        added += packageName
        trace?.add("freezer.add:$packageName")
        packages.update { it + packageName }
    }

    override suspend fun remove(packageName: String) {
        ranOn["freezer.remove:$packageName"] = currentCoroutineContext()[ContinuationInterceptor]
        // Before the bookkeeping: a delete that raised deleted nothing, so [removed] stays the
        // list of rows that actually went.
        removeFailures[packageName]?.let { throw it }
        removed += packageName
        trace?.add("freezer.remove:$packageName")
        packages.update { it - packageName }
    }

    override suspend fun removeAll(packageNames: Set<String>) {
        if (packageNames.isEmpty()) return
        packageNames.forEach {
            removed += it
            trace?.add("freezer.removeAll:$it")
        }
        packages.update { it - packageNames }
    }

    // Deliberately not traced, unlike [add] and [remove]. Tests call `contains` directly as a
    // precondition assertion — `FreezerViewModelTest` does it twice inside a test that then asserts
    // the trace by exact list equality — so recording it here would make the assertion depend on how
    // many times the *test* happened to look, which is not a property of the code under test.
    override suspend fun contains(packageName: String): Boolean {
        containsFailures[packageName]?.let { throw it }
        return packageName in packages.value
    }
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

    /**
     * Set to make every write raise instead of landing.
     *
     * A real one has two ways to refuse — the unique index on the name, and the members table's
     * foreign key — and both arrive as `SQLiteConstraintException`. That class is Android-only, so
     * the tests that need "the database said no" hand this whatever the view model's `catch` is
     * meant to see, rather than the fake picking one.
     */
    var writeFailure: Exception? = null

    override fun observeProfiles(): Flow<List<FreezeProfile>> = profiles

    override suspend fun packagesOf(profileId: Long): List<String> =
        profiles.value.firstOrNull { it.id == profileId }?.packageNames.orEmpty()

    override suspend fun allProfilePackageNames(): Set<String> =
        profiles.value.flatMap { it.packageNames }.toSet()

    override suspend fun create(name: String, packageNames: List<String>): Long {
        writeFailure?.let { throw it }
        val id = nextId++
        profiles.update { it + FreezeProfile(id, name, packageNames) }
        return id
    }

    override suspend fun update(profileId: Long, name: String, packageNames: List<String>) {
        writeFailure?.let { throw it }
        profiles.update { list ->
            list.map { if (it.id == profileId) FreezeProfile(profileId, name, packageNames) else it }
        }
    }

    override suspend fun delete(profileId: Long) {
        writeFailure?.let { throw it }
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
    var outcome: BulkOutcome = BulkOutcome.NothingToDo(NoOpReason.NO_TARGETS)
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
 *
 * [firstReadDelayMs] models the one thing a `MutableStateFlow` cannot: DataStore reads from disk, so
 * in production a fresh collector gets **no** value at all for the first moments — and a gate that
 * treats "not read yet" as an answer is open for exactly that long. Left at 0 the flow emits
 * synchronously as it always has; above 0 it emits nothing until that much virtual time has passed,
 * then the current value and every later one.
 *
 * [writesFail] models the other thing it cannot: a disk that refuses. Every setter becomes a no-op
 * that reports failure, which is the state `PreferenceRepositoryImpl.guardedWrite` produces on an
 * `IOException`. The distinction that matters to a caller is that the flow does **not** re-emit, so
 * a screen driven off the preference cannot notice on its own.
 */
class FakePreferenceRepository(
    initial: UserPreferences = UserPreferences(),
    firstReadDelayMs: Long = 0,
    private val writesFail: Boolean = false
) : PreferenceRepository {

    private val prefs = MutableStateFlow(initial)

    private val _settingsWriteFailed = MutableStateFlow(false)
    override val settingsWriteFailed: Flow<Boolean> = _settingsWriteFailed

    override fun acknowledgeSettingsWriteFailure() {
        _settingsWriteFailed.value = false
    }

    /** Arms the latch the way a foregone failure in an earlier ViewModel would have left it. */
    fun latchWriteFailure() {
        _settingsWriteFailed.value = true
    }

    /** What a collector would see now — the assertion for "the notice was acknowledged". */
    val writeFailureLatched: Boolean get() = _settingsWriteFailed.value

    /**
     * The fake's half of `guardedWrite`, latch and all: applies [change] and answers `true`, or
     * skips it and answers `false` when [writesFail]. Kept in one place so a test cannot get a
     * setter that half-honours the flag.
     *
     * [announce] mirrors the production parameter — the two setters that report their own outcome
     * pass `false`, so a test asserting on a specific message does not also see the generic one.
     */
    private fun write(announce: Boolean = true, change: (UserPreferences) -> UserPreferences): Boolean {
        if (writesFail) {
            if (announce) _settingsWriteFailed.value = true
            return false
        }
        prefs.update(change)
        return true
    }

    override val userPreferences: Flow<UserPreferences> =
        if (firstReadDelayMs == 0L) {
            prefs
        } else {
            flow {
                delay(firstReadDelayMs)
                emitAll(prefs)
            }
        }

    override suspend fun updateAppSort(sortBy: SortBy) {
        write { it.copy(appSortBy = sortBy) }
    }

    override suspend fun updateAppSortOrder(sortOrder: SortOrder) {
        write { it.copy(appSortOrder = sortOrder) }
    }

    override suspend fun updateAppFilter(filterType: FilterType, selectedFilter: String) {
        write { it.copy(appFilterType = filterType, appSelectedFilter = selectedFilter) }
    }

    override suspend fun setReinstallAllCardVisibility(isVisible: Boolean) {
        write { it.copy(showReinstallAllCard = isVisible) }
    }

    override suspend fun setDefaultTab(tab: DefaultTab) {
        write { it.copy(defaultTab = tab) }
    }

    override suspend fun setInstallerTileVisibility(isVisible: Boolean) {
        write { it.copy(showInstallerTile = isVisible) }
    }

    override suspend fun setExtensionsTileVisibility(isVisible: Boolean) {
        write { it.copy(showExtensionsTile = isVisible) }
    }

    override suspend fun setThemeMode(themeMode: ThemeMode) {
        write { it.copy(themeMode = themeMode) }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        write { it.copy(useDynamicColor = enabled) }
    }

    override suspend fun setUseAmoled(enabled: Boolean) {
        write { it.copy(useAmoled = enabled) }
    }

    override suspend fun setBiometricLock(enabled: Boolean): Boolean =
        write(announce = false) { it.copy(biometricLockEnabled = enabled) }

    override suspend fun setPrivilegeMode(mode: PrivilegeMode?) {
        write { it.copy(preferredPrivilegeMode = mode) }
    }

    override suspend fun setLanguage(language: String?): Boolean =
        write(announce = false) { it.copy(language = language) }

    override suspend fun setExportDirUri(uri: String?) {
        write { it.copy(exportDirUri = uri) }
    }

    override suspend fun setAutoFreezeEnabled(enabled: Boolean) {
        write { it.copy(autoFreezeEnabled = enabled) }
    }

    override suspend fun setAddFreezerToLauncher(enabled: Boolean) {
        write { it.copy(addFreezerToLauncher = enabled) }
    }

    override suspend fun setFreezerMode(mode: FreezerMode) {
        write { it.copy(freezerMode = mode) }
    }

    override suspend fun setSkipRoutineFreezeConfirmation(enabled: Boolean) {
        write { it.copy(skipRoutineFreezeConfirmation = enabled) }
    }

    override suspend fun setHasShownDisabledAppsPrompt(hasShown: Boolean) {
        write { it.copy(hasShownDisabledAppsPrompt = hasShown) }
    }

    override suspend fun setHasShownSupportDeveloperPrompt(hasShown: Boolean) {
        write { it.copy(hasShownSupportDeveloperPrompt = hasShown) }
    }

    override suspend fun setAnimationIntensity(intensity: AnimationIntensity) {
        write { it.copy(animationIntensity = intensity) }
    }

    override suspend fun setAppListIsGrid(isGrid: Boolean) {
        write { it.copy(appListIsGrid = isGrid) }
    }

    override suspend fun setFreezerIsGrid(isGrid: Boolean) {
        write { it.copy(freezerIsGrid = isGrid) }
    }

    override suspend fun toggleAppListIsGrid() {
        write { it.copy(appListIsGrid = !it.appListIsGrid) }
    }

    override suspend fun toggleFreezerIsGrid() {
        write { it.copy(freezerIsGrid = !it.freezerIsGrid) }
    }

    override suspend fun setAppGridDensity(density: AppGridDensity) {
        write { it.copy(appGridDensity = density) }
    }

    override suspend fun setExtensionsUnlocked(unlocked: Boolean) {
        write { it.copy(extensionsUnlocked = unlocked) }
    }

    override suspend fun setExtensionConsentAccepted(accepted: Boolean) {
        write { it.copy(extensionConsentAccepted = accepted) }
    }

    override suspend fun setAutoReinstallEnabled(enabled: Boolean) {
        write { it.copy(autoReinstallEnabled = enabled) }
    }

    override suspend fun getInstallerArg(): String = ""

    override suspend fun setGrantAllPermissionsOnInstall(enabled: Boolean) {
        write { it.copy(grantAllPermissionsOnInstall = enabled) }
    }

    // Reads the stored value rather than returning a constant `false`: this is the seam GH#445 ran
    // through, so a fake that answers "no" whatever was written would let a caller that never
    // forwards the preference pass every test. Straight off `prefs` rather than through
    // `userPreferences`, which [firstReadDelayMs] can hold open — that delay models a slow *startup*
    // read and has nothing to say about a one-shot read on the install path.
    override suspend fun shouldGrantAllPermissionsOnInstall(): Boolean =
        prefs.value.grantAllPermissionsOnInstall

    override suspend fun setAppInfoActionsOrder(order: List<AppInfoActionId>) {
        write { it.copy(appInfoActionsOrder = order) }
    }

    override suspend fun setAppInfoActionVisibility(actionId: AppInfoActionId, isVisible: Boolean) {
        write {
            val hidden = it.hiddenAppInfoActions.toMutableSet()
            if (isVisible) hidden.remove(actionId) else hidden.add(actionId)
            it.copy(hiddenAppInfoActions = hidden)
        }
    }

    override suspend fun resetAppInfoActionsCustomization() {
        write {
            it.copy(
                appInfoActionsOrder = AppInfoActionId.DEFAULT_ORDER,
                hiddenAppInfoActions = emptySet()
            )
        }
    }
}

// Building a bundle is out of reach on a plain JVM — it copies an installed package's APKs off
// disk — so this one fails loudly rather than quietly, and a share test that reaches it reports the
// real reason instead of a confusing null.

class FakeAppBundleBuilder(
    /**
     * Run at the top of every [build], before it fails.
     *
     * The seam for a test that has to act *during* a batch rather than before or after it — tapping
     * Stop, say, which a view model only observes between apps. Nothing else can reach that moment:
     * the loop runs to completion inside one `withContext`, so a test body regains control only once
     * every app has had its turn.
     *
     * Defaulted, so the tests that predate it read unchanged.
     */
    private val onBuild: (AppInfo) -> Unit = {},
) : AppBundleBuilder {
    // No default values on the override — Kotlin takes them from the interface.
    override suspend fun build(
        appInfo: AppInfo,
        cacheSubDir: String,
        format: BundleFormat,
        fileName: String?
    ): Result<File> {
        onBuild(appInfo)
        return Result.failure(UnsupportedOperationException("bundle building needs a device"))
    }
}

/**
 * A file store backed by a real temp directory.
 *
 * Functional rather than throwing, because the text half of this port — [stageText] plus a write —
 * needs no Android at all: it is a string, a file and a destination label. Faking it as unsupported
 * would put the app-list export beyond unit test for no reason. The bundle half still never runs
 * here; [FakeAppBundleBuilder] fails before a bundle can reach a write.
 */
class FakeAppBundleFileStore : AppBundleFileStore {

    /** Created on first use, so a test that never exports leaves no temp directory behind. */
    private val stagingDir: File by lazy {
        Files.createTempDirectory("thor-fake-store").toFile().apply { deleteOnExit() }
    }

    /** File name → contents, as the destination folder would hold it after the run. */
    val written = linkedMapOf<String, String>()

    /** Where each write landed, in order, so a run that split across folders is visible. */
    val targets = mutableListOf<String>()

    /** MIME each write was labelled with, in the same order as [targets]. */
    val mimes = mutableListOf<String>()

    /** The one tree URI this store will accept; anything else reads as revoked. */
    var writableTree: String? = null

    /** Set to make the next destination write fail, as a full disk or a revoked tree would. */
    var writeFailure: Exception? = null

    override suspend fun writeToDownloads(file: File, mime: String): String =
        record(file, mime, "Downloads/Thor")

    override suspend fun writeToTree(file: File, treeUriStr: String, mime: String): String =
        record(file, mime, "Tree:$treeUriStr")

    override suspend fun isTreeWritable(treeUriStr: String?): Boolean =
        treeUriStr != null && treeUriStr == writableTree

    override suspend fun currentTargetLabel(savedTreeUriStr: String?): String =
        if (isTreeWritable(savedTreeUriStr)) "Tree:$savedTreeUriStr" else "Downloads/Thor"

    override fun shareUri(file: File): String = "content://fake/${file.name}"

    override suspend fun stageText(fileName: String, content: String): File {
        // Wipes on entry exactly as the real store does, so a test can assert that the previous
        // export's staged copy is gone once a new one starts.
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        return File(stagingDir, fileName).apply { writeText(content) }
    }

    private fun record(file: File, mime: String, target: String): String {
        writeFailure?.let { throw it }
        written[file.name] = file.readText()
        targets += target
        mimes += mime
        return target
    }
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

    /**
     * Successive answers from [cacheBytes] and [totalCacheBytes]. A cache measurement is taken twice
     * around one clear, so a single value cannot express "18 MB before, 2 MB after" — the queue can.
     * Empty means every reading is `null`, the real "no usage access" answer.
     */
    val cacheReadings = ArrayDeque<Long?>()

    /**
     * What [cacheTrimTargetBytes] answers. `null` is the no-usage-access case: Root still clears
     * (it skips the trim and sweeps the directories by name), Shizuku cannot.
     *
     * Deliberately ignores the cache total it is handed, even though the real helper adds it to free
     * space — this fake stands in for the *port*, and a test that wants "the target is unreadable
     * but the measurements are not" has to be able to say so.
     */
    var trimTarget: Long? = null

    override suspend fun cacheBytes(packageName: String): Long? =
        if (cacheReadings.isEmpty()) null else cacheReadings.removeFirst()

    override suspend fun totalCacheBytes(): Long? =
        if (cacheReadings.isEmpty()) null else cacheReadings.removeFirst()

    override suspend fun cacheTrimTargetBytes(totalCacheBytes: Long): Long? = trimTarget
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

    /**
     * Failures the *port* absorbed instead of handing to its caller — see [pinAppShortcut].
     *
     * Recorded rather than dropped so a test can still say "the launcher refused and the caller was
     * not told", which is a claim about the seam and not merely an absence.
     */
    val absorbedFailures = mutableListOf<Throwable>()

    private val disableFailures = mutableMapOf<String, Throwable>()
    private val pinFailures = mutableMapOf<String, Throwable>()
    private val refreshFailures = mutableMapOf<String, Throwable>()
    private var bulkPinFailure: Throwable? = null

    /**
     * Make disabling [packageName]'s shortcut raise, as `ShortcutManagerCompat` does — it reports a
     * refused or rate-limited request by throwing, never by returning anything a caller can check.
     */
    fun failDisableWith(packageName: String, error: Throwable) {
        disableFailures[packageName] = error
    }

    /**
     * As [failDisableWith], for a *pin* request.
     *
     * The pin path raises for reasons the disable path does not — the launcher declining the request
     * outright, and the rate limit that `ShortcutManagerCompat` enforces per app per interval — and it
     * is the one a user reaches by asking for many shortcuts at once, which is exactly the shape where
     * one refusal must not cost the whole run.
     *
     * Keyed on the package, so it covers both pin members — but only [pinAppShortcutSuspend] raises
     * it at the caller; the fire-and-forget [pinAppShortcut] absorbs it, as in production.
     */
    fun failPinWith(packageName: String, error: Throwable) {
        pinFailures[packageName] = error
    }

    /** As [failPinWith], for the one bulk tile, which has no package to key on. */
    fun failBulkPinWith(error: Throwable) {
        bulkPinFailure = error
    }

    /**
     * As [failDisableWith], for the refresh — but note that this failure never reaches the caller.
     *
     * `refreshAppShortcut` is fire-and-forget, so setting this asserts the opposite of what the other
     * hooks do: that a stale launcher icon is *not* reported to whoever asked for the freeze, and is
     * absorbed by the port instead. See [refreshAppShortcut] for why, and [absorbedFailures] for
     * where it lands. For a genuine post-success throw in `toggleFreezerState`, use
     * `FakeFreezerRepository.failContainsWith` — the watchlist read at the same point in that method
     * does raise into its caller.
     */
    fun failRefreshWith(packageName: String, error: Throwable) {
        refreshFailures[packageName] = error
    }

    override fun disableAppShortcut(packageName: String) {
        disableFailures[packageName]?.let { throw it }
        disabled += packageName
        trace?.add("shortcut.disable:$packageName")
    }

    /**
     * Absorbs its failure rather than raising it, because that is what the real one does.
     *
     * `FreezerShortcutManager.refreshAppShortcut` hands the work to the manager's own
     * `SupervisorJob` scope and returns the moment it is *scheduled*, so the throw arrives after the
     * caller's frame — and therefore after any `try`/`catch` or `launchGuarded` around the call —
     * has already completed successfully. `FreezerShortcutManager.launchSafely` is the only frame
     * that can see it, which is also the only place that covers the callers that are not view models
     * at all (`AutoFreezeManager`, `FreezerLaunchActivity`, `ThorApplication`).
     *
     * A fake that threw here would make every caller-side guard around this method look
     * load-bearing while catching nothing in production — the exact mistake the sweep in
     * `fix/freezer-bookkeeping-crashes` made first time round, green tests and all. [absorbedFailures]
     * keeps the refusal assertable without lying about who gets told.
     */
    override fun refreshAppShortcut(packageName: String) {
        refreshFailures[packageName]?.let { absorbedFailures += it; return }
        refreshed += packageName
    }

    override fun isPinSupported(): Boolean = pinSupported

    /** Fire-and-forget, so it absorbs rather than raises — see [refreshAppShortcut]. */
    override fun pinAppShortcut(packageName: String, label: String) {
        pinFailures[packageName]?.let { absorbedFailures += it; return }
        pinned += packageName
    }

    override suspend fun pinAppShortcutSuspend(packageName: String, label: String) {
        pinFailures[packageName]?.let { throw it }
        pinned += packageName
    }

    override fun pinBulkShortcut(action: String) {
        bulkPinFailure?.let { throw it }
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

class FakeApplication(private val cache: File = File("/tmp")) : android.app.Application() {
    override fun getCacheDir(): File = cache
}

/**
 * A user app. `freezeTierOf` short-circuits on `!isSystem -> NORMAL`, so this is never blocked
 * whatever else it carries — which is what makes it the control in every tier-gate test.
 */
/**
 * Names installers from a map the test supplies.
 *
 * An id that isn't in the map resolves to null, which is the real resolver's answer for a store
 * that is no longer installed — so the default, empty, fake is the "nothing is installed" device.
 */
class FakeInstallerLabelResolver(
    private val labels: Map<String, String> = emptyMap()
) : InstallerLabelResolver {
    override fun labelFor(packageName: String): String? = labels[packageName]
}

fun userApp(
    packageName: String,
    enabled: Boolean = true,
    isSuspended: Boolean = false,
    isDebuggable: Boolean = false,
    appName: String? = null,
    // The permission index keys its invalidation on `packageName@lastUpdateTime`, so this is how a
    // test says "the same app, updated" as opposed to "the same app".
    lastUpdateTime: Long = 0L,
    // Null is a real device state, not a missing default: Android records no installer for an app
    // that arrived by `adb install` or shipped with the image.
    installerPackageName: String? = null,
): AppInfo = AppInfo(
    appName = appName,
    packageName = packageName,
    isSystem = false,
    enabled = enabled,
    isSuspended = isSuspended,
    isDebuggable = isDebuggable,
    lastUpdateTime = lastUpdateTime,
    installerPackageName = installerPackageName
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
