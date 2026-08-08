// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AnimationIntensity
import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.InstalledAppsPermission
import com.valhalla.thor.domain.model.PermissionIndex
import com.valhalla.thor.domain.model.SortBy
import com.valhalla.thor.domain.model.SortOrder
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.usecase.ExportAppListUseCase
import com.valhalla.thor.domain.usecase.ExportAppUseCase
import com.valhalla.thor.domain.usecase.FreezeAppUseCase
import com.valhalla.thor.domain.usecase.GetAppDetailsUseCase
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.presentation.FakeAppBundleBuilder
import com.valhalla.thor.presentation.FakeAppBundleFileStore
import com.valhalla.thor.presentation.FakeAppRepository
import com.valhalla.thor.presentation.FakeAppShortcutController
import com.valhalla.thor.presentation.FakeFreezerRepository
import com.valhalla.thor.presentation.FakeInstalledAppsPermissionGate
import com.valhalla.thor.presentation.FakeInstallerLabelResolver
import com.valhalla.thor.presentation.FakePermissionRepository
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.presentation.FakePrivilegeStateProvider
import com.valhalla.thor.presentation.FakeStorageStatsProvider
import com.valhalla.thor.presentation.FakeSystemRepository
import com.valhalla.thor.presentation.FakeUsageAccessGate
import com.valhalla.thor.presentation.MainDispatcherRule
import com.valhalla.thor.presentation.userApp
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Behaviour tests for [AppListViewModel]'s **temporal** contract — the three rules from PR #278 that
 * `TransitionSettleDelayTest` cannot reach, because that suite pins the constants and these are
 * about who pays them and when.
 *
 * The rules under test:
 *
 * - a deliberate pull-to-refresh starts the package scan **immediately**; only navigation entry
 *   pays `settleDelayFor(intensity)`, and it pays exactly that;
 * - the pull-to-refresh indicator stays up for the whole `REFRESH_INDICATOR_MIN_VISIBLE` window;
 * - a refresh arriving mid-hold **extends** the indicator rather than letting the cancelled hold
 *   clear it at the original deadline — the subtlest line in that change;
 * - relaunching the scan tears the previous collector down first, so two refreshes can never leave
 *   two package scans running over each other.
 *
 * *How "the scan started" is observed:* `FakeAppRepository` hands out a `MutableStateFlow`, and its
 * `subscriptionCount` goes to 1 exactly when the view model's `combine` reaches the repository and
 * back to 0 when that collector is torn down. That is the real boundary — the point at which the
 * production flow would register its package receivers and call `pm.getInstalledPackages` — rather
 * than a flag the view model sets about itself.
 *
 * The dispatcher is **standard**, not unconfined (the rule's default): every assertion here is about
 * an intermediate state at a known instant, so work must queue on the virtual clock rather than run
 * eagerly at the call site. The same dispatcher is passed in for the view model's `default` and `io`
 * dispatchers, which puts the sort/filter pipeline behind `uiState` on the same scheduler as the
 * test body — otherwise it would run on a real thread pool and no `runCurrent()` would order it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private lateinit var appRepository: FakeAppRepository
    private lateinit var system: FakeSystemRepository
    private lateinit var freezer: FakeFreezerRepository
    private lateinit var privilege: FakePrivilegeStateProvider
    private lateinit var fileStore: FakeAppBundleFileStore

    @Before
    fun setUp() {
        appRepository = FakeAppRepository()
        system = FakeSystemRepository()
        freezer = FakeFreezerRepository()
        privilege = FakePrivilegeStateProvider()
        fileStore = FakeAppBundleFileStore()
    }

    /** Live collectors on the app list — 1 while a scan is running, 0 once it has been torn down. */
    private fun scanCollectors(): Int = appRepository.apps.subscriptionCount.value

    /**
     * Builds the view model at [intensity] and keeps [AppListViewModel.uiState] hot.
     *
     * `uiState` is `stateIn(WhileSubscribed(5000))`: with no collector it stays pinned to the
     * initial `AppListUiState()` and every indicator assertion below would pass for the wrong
     * reason. Note the view model's `init` already fires `loadApps(deferForTransition = true)`, so
     * every test starts with an entry load in flight — which is exactly the state a pull-to-refresh
     * arrives in.
     */
    private fun TestScope.viewModel(
        intensity: AnimationIntensity = AnimationIntensity.MEDIUM,
        filterType: FilterType = FilterType.Source,
        permissions: FakePermissionRepository = FakePermissionRepository(),
        installedApps: FakeInstalledAppsPermissionGate = FakeInstalledAppsPermissionGate()
    ): AppListViewModel {
        val prefs = FakePreferenceRepository(
            UserPreferences(animationIntensity = intensity, appFilterType = filterType)
        )
        val manageAppUseCase = ManageAppUseCase(system)
        val exportAppUseCase = ExportAppUseCase(
            FakeAppBundleBuilder(),
            prefs,
            fileStore,
            mainDispatcherRule.dispatcher
        )
        val vm = AppListViewModel(
            getInstalledAppsUseCase = GetInstalledAppsUseCase(appRepository),
            getAppDetailsUseCase = GetAppDetailsUseCase(appRepository),
            privilege = privilege,
            manageAppUseCase = manageAppUseCase,
            freezeAppUseCase = FreezeAppUseCase(appRepository, manageAppUseCase),
            preferenceRepository = prefs,
            freezerRepository = freezer,
            appShortcuts = FakeAppShortcutController(),
            appRepository = appRepository,
            permissionRepository = permissions,
            storageStats = FakeStorageStatsProvider(),
            usageAccess = FakeUsageAccessGate(),
            installedAppsPermission = installedApps,
            installerLabelResolver = FakeInstallerLabelResolver(),
            exportAppListUseCase = ExportAppListUseCase(
                exportAppUseCase,
                fileStore,
                mainDispatcherRule.dispatcher
            ),
            defaultDispatcher = mainDispatcherRule.dispatcher,
            ioDispatcher = mainDispatcherRule.dispatcher
        )
        backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.uiState.collect {} }
        return vm
    }

    // --- Who pays the settle delay ---------------------------------------------------------

    @Test
    fun `a manual refresh starts the scan without advancing the clock`() = runTest {
        // HIGH, so the entry load in `init` is parked on the longest delay there is. If the manual
        // path paid any part of it, the scan below could not have started at t = 0.
        val vm = viewModel(AnimationIntensity.HIGH)
        runCurrent()
        assertEquals("the entry load must still be waiting out its settle delay", 0, scanCollectors())

        vm.loadApps()
        runCurrent()

        assertEquals("a pull-to-refresh must reach the scan at once", 1, scanCollectors())
        assertEquals("no virtual time may have passed", 0L, currentTime)
    }

    @Test
    fun `screen entry at LOW starts the scan immediately`() = runTest {
        // LOW maps to Duration.ZERO, which delay() returns from without suspending, so the entry
        // load behaves like the manual one: no wait at all.
        viewModel(AnimationIntensity.LOW)
        runCurrent()

        assertEquals(1, scanCollectors())
        assertEquals(0L, currentTime)
    }

    @Test
    fun `screen entry at MEDIUM waits exactly 400ms before scanning`() = runTest {
        viewModel(AnimationIntensity.MEDIUM)
        runCurrent()
        assertEquals(0, scanCollectors())

        advanceTimeBy(399)
        runCurrent()
        assertEquals("the scan must not start early", 0, scanCollectors())

        advanceTimeBy(1)
        runCurrent()
        assertEquals("the scan must start as soon as the delay is paid", 1, scanCollectors())
    }

    @Test
    fun `screen entry at HIGH waits exactly 800ms before scanning`() = runTest {
        viewModel(AnimationIntensity.HIGH)
        runCurrent()
        assertEquals(0, scanCollectors())

        advanceTimeBy(799)
        runCurrent()
        assertEquals("the scan must not start early", 0, scanCollectors())

        advanceTimeBy(1)
        runCurrent()
        assertEquals("the scan must start as soon as the delay is paid", 1, scanCollectors())
    }

    // --- The pull-to-refresh indicator -----------------------------------------------------

    @Test
    fun `the indicator stays up for the whole minimum-visible window`() = runTest {
        val vm = viewModel(AnimationIntensity.LOW)
        runCurrent()

        vm.loadApps()
        runCurrent()
        assertTrue("the flag is raised by the call, not by the timer", vm.uiState.value.isManualRefreshing)

        advanceTimeBy(599)
        runCurrent()
        assertTrue("the indicator must not blink out early", vm.uiState.value.isManualRefreshing)

        advanceTimeBy(1)
        runCurrent()
        assertFalse("the indicator must clear once the window is paid", vm.uiState.value.isManualRefreshing)
    }

    @Test
    fun `a refresh mid-hold extends the indicator instead of clearing at the first deadline`() =
        runTest {
            val vm = viewModel(AnimationIntensity.LOW)
            runCurrent()

            vm.loadApps()
            runCurrent()

            advanceTimeBy(300)
            runCurrent()
            vm.loadApps() // second pull, 300 ms into the first hold
            runCurrent()

            advanceTimeBy(300)
            runCurrent()
            assertTrue(
                "the first hold's deadline is now; a cancelled hold must never lower the flag",
                vm.uiState.value.isManualRefreshing
            )

            advanceTimeBy(299)
            runCurrent()
            assertTrue("the second hold owns the window now", vm.uiState.value.isManualRefreshing)

            advanceTimeBy(1)
            runCurrent()
            assertFalse(
                "600 ms after the second pull, the indicator clears",
                vm.uiState.value.isManualRefreshing
            )
            assertEquals(900L, currentTime)
        }

    @Test
    fun `screen entry never raises the pull-to-refresh indicator`() = runTest {
        // The indicator belongs to direct manipulation. Entry has its own loader (`isLoading`), and
        // showing a pull-to-refresh spinner for a navigation the user did not pull would be a lie.
        val vm = viewModel(AnimationIntensity.LOW)
        runCurrent()

        assertFalse(vm.uiState.value.isManualRefreshing)

        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(vm.uiState.value.isManualRefreshing)
    }

    // --- Teardown ---------------------------------------------------------------------------

    @Test
    fun `a second load tears the previous scan down before starting the next`() = runTest {
        // The view model half of the prompt-cancellation rule: `AppRepositoryImpl`'s per-package
        // `ensureActive()` only helps if the collector is actually cancelled, and two live
        // collectors would mean two package scans mutating the same cache at once.
        val vm = viewModel(AnimationIntensity.LOW)
        runCurrent()
        assertEquals(1, scanCollectors())

        vm.loadApps()
        runCurrent()

        assertEquals("two live collectors means two overlapping package scans", 1, scanCollectors())
    }

    // --- The permission index ----------------------------------------------------------------

    /**
     * The chip row shows one of three sentences while the index is empty, and this is the state that
     * used to pick the wrong one: a returning user who left the filter on Permission was told "no
     * permission groups found on this device" — a claim about their *hardware* — for as long as the
     * package scan took, right beside the spinner that explained the real reason.
     */
    @Test
    fun `a persisted permission filter reads as loading until the app list arrives`() = runTest {
        val permissions = FakePermissionRepository()
        val vm = viewModel(AnimationIntensity.LOW, FilterType.Permission, permissions)
        runCurrent()

        assertTrue(
            "an empty index with no apps yet is 'not there yet', not 'not there at all'",
            vm.uiState.value.isLoadingPermissions
        )
        assertFalse("nothing was attempted, so nothing failed", vm.uiState.value.permissionIndexFailed)
        assertEquals("there is nothing to index yet", 0, permissions.indexBuilds)
    }

    /**
     * The sweep costs a `getInstalledPackages(GET_PERMISSIONS)` over every app, so *when* it reruns
     * is the whole design. It follows the app set — install, uninstall, update — and nothing else;
     * a search keystroke or a sort change must not touch it, or typing into the search box would
     * rebuild the index once per character.
     */
    @Test
    fun `the index is built once per app-set change and never for a sort or a search`() = runTest {
        val permissions = FakePermissionRepository(
            Result.success(PermissionIndex(packagesByGroup = mapOf("CAMERA" to setOf("a"))))
        )
        val vm = viewModel(AnimationIntensity.LOW, FilterType.Permission, permissions)
        runCurrent()

        appRepository.apps.value = listOf(userApp("a"))
        runCurrent()
        assertEquals("the first real app load builds it", 1, permissions.indexBuilds)
        assertFalse("and the wait is over", vm.uiState.value.isLoadingPermissions)

        vm.updateSearchQuery("cam")
        vm.updateSort(SortBy.NAME)
        vm.updateSortOrder(SortOrder.DESCENDING)
        runCurrent()
        assertEquals("neither the query nor the sort changes what any app declares", 1, permissions.indexBuilds)

        // The same package, updated. The key is `packageName@lastUpdateTime` precisely so this
        // counts and a freeze or a size arriving does not.
        appRepository.apps.value = listOf(userApp("a", lastUpdateTime = 1))
        runCurrent()
        assertEquals("an update can add or drop a permission, so it invalidates", 2, permissions.indexBuilds)
    }

    /**
     * A failed sweep drops the previous index rather than keeping it: a stale index filters silently
     * and wrongly. That leaves the chip row empty, so the failure has to be *stated* — both in the
     * moment, as a toast, and afterwards in the row itself, since the toast is long gone by the time
     * the user looks down at it.
     */
    @Test
    fun `a failed index is announced and then admitted to in the chip row`() = runTest {
        val permissions = FakePermissionRepository(Result.failure(IllegalStateException("no pm")))
        val vm = viewModel(AnimationIntensity.LOW, FilterType.Permission, permissions)
        val events = mutableListOf<AppListEvent>()
        backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.events.collect { events += it } }
        runCurrent()

        appRepository.apps.value = listOf(userApp("a"))
        runCurrent()

        assertEquals(
            listOf(AppListEvent.ShowMessage(UiText.StringResource(R.string.permission_filter_failed))),
            events
        )
        assertTrue(vm.uiState.value.permissionIndexFailed)
        assertFalse("a failure ends the wait too", vm.uiState.value.isLoadingPermissions)
        assertTrue("the stale index is dropped, not kept", vm.uiState.value.permissionIndex.isEmpty)
    }

    // --- Leaving the watchlist ------------------------------------------------------------------

    /**
     * Taking an app off the watchlist has to restore it. It is the asymmetric half of the toggle —
     * adding never freezes — and the reason is that this surface is the *only* one that can drop an
     * app the freezer screen then stops listing. Leave it frozen and the app is stranded: invisible
     * in the launcher, absent from the freezer, recoverable only through import-already-disabled.
     *
     * Asserted against the recorded privilege calls rather than the resulting state, because the
     * bug this pins was that no privileged call happened at all.
     */
    @Test
    fun `removing a suspended app from the watchlist unsuspends and enables it`() = runTest {
        freezer.add("a")
        val vm = viewModel(AnimationIntensity.LOW)
        runCurrent()
        appRepository.apps.value = listOf(userApp("a", enabled = false, isSuspended = true))
        runCurrent()
        system.calls.clear()

        vm.toggleFreezerMembership("a")
        runCurrent()

        assertEquals(
            "both halves of the mixed disabled+suspended state have to be undone",
            listOf("setAppSuspended:a:false", "setAppDisabled:a:false"),
            system.calls
        )
        assertFalse("and it leaves the watchlist", freezer.contains("a"))
    }

    /**
     * The optimistic patch matters as much as the privileged call: without it the row keeps reading
     * as frozen until the next full rescan, which is exactly long enough for someone to tap it again.
     */
    @Test
    fun `the row stops reading as frozen without waiting for a rescan`() = runTest {
        freezer.add("a")
        val vm = viewModel(AnimationIntensity.LOW)
        runCurrent()
        appRepository.apps.value = listOf(userApp("a", enabled = false, isSuspended = true))
        runCurrent()

        vm.toggleFreezerMembership("a")
        runCurrent()

        val app = vm.uiState.value.allUserApps.first { it.packageName == "a" }
        assertTrue("enabled again", app.enabled)
        assertFalse("and not suspended", app.isSuspended)
    }

    /**
     * A failed restore must not read as success, and must not half-apply. The app is still frozen at
     * this point, so a "removed from freezer" toast would be a lie — and dropping the watchlist entry
     * anyway would make it an expensive one, since the freezer screen is what offers the way back.
     * Removal is all-or-nothing: restore first, drop the row only once it worked.
     */
    @Test
    fun `a failed restore is reported instead of the removal message`() = runTest {
        freezer.add("a")
        val vm = viewModel(AnimationIntensity.LOW)
        val events = mutableListOf<AppListEvent>()
        backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.events.collect { events += it } }
        runCurrent()
        appRepository.apps.value = listOf(userApp("a", enabled = false))
        runCurrent()
        events.clear()
        system.failWith("setAppDisabled:a:false", IllegalStateException("no privilege"))

        vm.toggleFreezerMembership("a")
        runCurrent()

        assertEquals(
            listOf(
                AppListEvent.ShowMessage(
                    UiText.StringResource(R.string.error_format, "no privilege")
                )
            ),
            events
        )
        assertTrue(
            "the watchlist entry is the only route back to a still-frozen app",
            freezer.contains("a")
        )
    }

    // --- GET_INSTALLED_APPS banner ---------------------------------------------------------

    @Test
    fun `a device that does not define the permission never reaches the banner state`() = runTest {
        // The Pixel case, and the one that matters most: GET_INSTALLED_APPS is a Chinese-market
        // permission, so getPermissionInfo throws on the overwhelming majority of devices Thor runs
        // on. Because shouldShowRequestPermissionRationale() also returns a hard false for a
        // permission the ROM has never defined, the textbook "denied && !rationale => send them to
        // Settings" recipe reads this exact state as *permanently denied* and nags forever. Nothing
        // downstream is allowed to see anything but Unsupported here.
        val vm = viewModel(installedApps = FakeInstalledAppsPermissionGate())
        runCurrent()

        assertEquals(
            InstalledAppsPermission.Unsupported,
            vm.uiState.value.installedAppsPermission
        )
    }

    @Test
    fun `a denied grant on a ROM that defines the permission surfaces to the UI`() = runTest {
        val vm = viewModel(
            installedApps = FakeInstalledAppsPermissionGate(InstalledAppsPermission.Denied)
        )
        runCurrent()

        assertEquals(
            InstalledAppsPermission.Denied,
            vm.uiState.value.installedAppsPermission
        )
    }

    @Test
    fun `a re-read after the permission dialog picks up the new answer`() = runTest {
        // The activity-result callback ignores the boolean the launcher hands back and re-reads the
        // device instead, because a "while in use" grant reports true and stops being true the
        // moment Thor is backgrounded. This is that re-read.
        val gate = FakeInstalledAppsPermissionGate(InstalledAppsPermission.Denied)
        val vm = viewModel(installedApps = gate)
        runCurrent()
        assertEquals(InstalledAppsPermission.Denied, vm.uiState.value.installedAppsPermission)

        gate.permission = InstalledAppsPermission.Granted
        vm.refreshInstalledAppsPermission()
        runCurrent()

        assertEquals(
            InstalledAppsPermission.Granted,
            vm.uiState.value.installedAppsPermission
        )
    }

    @Test
    fun `a superseded permission read is dropped rather than published late`() = runTest {
        // The result callback and ON_RESUME both fire within milliseconds when the user answers the
        // dialog, so two reads are genuinely in flight at once. The stale one must not win: landing
        // an older Denied after the newer Granted puts the banner back over a permission the user
        // just granted, and it would stay there until the next resume.
        //
        // What is asserted is the supersede itself — the older read is cancelled before it ever
        // reaches the package manager, so it has nothing to publish. The narrower interleaving,
        // where the older read already got its answer and is descheduled just short of the state
        // write, is what ensureActive() covers; it needs two real threads to stage and cannot be
        // provoked on a single-threaded test dispatcher, where each job runs to completion.
        val gate = FakeInstalledAppsPermissionGate(InstalledAppsPermission.Denied)
        val vm = viewModel(installedApps = gate)
        runCurrent()
        val callsAfterInit = gate.stateCalls

        // Both queued before the dispatcher runs either — the in-flight case, not a sequential one.
        vm.refreshInstalledAppsPermission()
        gate.permission = InstalledAppsPermission.Granted
        vm.refreshInstalledAppsPermission()
        runCurrent()

        assertEquals(
            "the superseded read should never have reached the package manager",
            callsAfterInit + 1,
            gate.stateCalls
        )
        assertEquals(
            InstalledAppsPermission.Granted,
            vm.uiState.value.installedAppsPermission
        )
    }

    // --- Exporting the list ---------------------------------------------------------------------

    /**
     * The export writes what is on screen, not what is installed.
     *
     * This is the whole promise of the feature and the one place it could quietly go wrong: reading
     * `allUserApps` instead of `displayedApps` would still produce a valid-looking CSV, just of the
     * wrong list — and a user who filtered to "sideloaded" before exporting would have no way to
     * tell from the file that the filter had been ignored.
     */
    @Test
    fun `the export writes the displayed list, not the whole scan`() = runTest {
        val vm = viewModel(AnimationIntensity.LOW)
        runCurrent()
        appRepository.apps.value = listOf(userApp("com.a"), userApp("com.b"), userApp("com.c"))
        runCurrent()
        vm.updateSearchQuery("com.b")
        runCurrent()

        vm.exportList()
        runCurrent()

        val csv = fileStore.written.values.single()
        assertEquals(
            listOf("com.b"),
            csv.trimEnd('\n').lines().drop(1).map { it.split(",")[1] }
        )
    }

    @Test
    fun `a successful export names where it landed`() = runTest {
        val vm = viewModel(AnimationIntensity.LOW)
        val events = mutableListOf<AppListEvent>()
        backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.events.collect { events += it } }
        runCurrent()
        appRepository.apps.value = listOf(userApp("com.a"))
        runCurrent()
        events.clear()

        vm.exportList()
        runCurrent()

        assertEquals(
            listOf(
                AppListEvent.ShowMessage(
                    UiText.StringResource(R.string.export_saved, "Downloads/Thor")
                )
            ),
            events
        )
        assertEquals(listOf("text/csv"), fileStore.mimes)
    }

    /**
     * An empty list is not a failure, and must not be reported as one.
     *
     * The two states are reached the same way — a tap on Export — but they mean opposite things:
     * one says the filter is too narrow, the other says the write broke. Collapsing them into
     * "Couldn't save the list" sends a user looking for a storage problem that isn't there.
     */
    @Test
    fun `exporting an empty list says so instead of writing a header-only file`() = runTest {
        val vm = viewModel(AnimationIntensity.LOW)
        val events = mutableListOf<AppListEvent>()
        backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.events.collect { events += it } }
        runCurrent()
        appRepository.apps.value = listOf(userApp("com.a"))
        runCurrent()
        vm.updateSearchQuery("nothing matches this")
        runCurrent()
        events.clear()

        vm.exportList()
        runCurrent()

        assertEquals(
            listOf(AppListEvent.ShowMessage(UiText.StringResource(R.string.export_list_empty))),
            events
        )
        assertTrue("nothing should have been written", fileStore.written.isEmpty())
    }

    @Test
    fun `a failed write is reported as a failure`() = runTest {
        val vm = viewModel(AnimationIntensity.LOW)
        val events = mutableListOf<AppListEvent>()
        backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.events.collect { events += it } }
        runCurrent()
        appRepository.apps.value = listOf(userApp("com.a"))
        runCurrent()
        events.clear()
        fileStore.writeFailure = java.io.IOException("no space left on device")

        vm.exportList()
        runCurrent()

        assertEquals(
            listOf(AppListEvent.ShowMessage(UiText.StringResource(R.string.export_list_failed))),
            events
        )
    }

    /**
     * Share stages a copy and hands over a URI; it does **not** write to the export destination.
     *
     * A share is not a save. The user picked a messaging app, not a folder, and leaving a file in
     * Downloads on the way there is a side effect nobody asked for — and one they would only find
     * later, with no idea what put it there.
     */
    @Test
    fun `sharing hands over a uri without writing to the export folder`() = runTest {
        val vm = viewModel(AnimationIntensity.LOW)
        val events = mutableListOf<AppListEvent>()
        backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.events.collect { events += it } }
        runCurrent()
        appRepository.apps.value = listOf(userApp("com.a"))
        runCurrent()
        events.clear()

        vm.shareList()
        runCurrent()

        val shared = events.single() as AppListEvent.ShareList
        assertTrue(shared.uri.startsWith("content://fake/thor-apps-"))
        assertEquals("text/csv", shared.mime)
        assertTrue("share must not write to the export folder", fileStore.targets.isEmpty())
    }

    @Test
    fun `sharing an empty list says so rather than sending a header`() = runTest {
        val vm = viewModel(AnimationIntensity.LOW)
        val events = mutableListOf<AppListEvent>()
        backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.events.collect { events += it } }
        runCurrent()
        events.clear()

        vm.shareList()
        runCurrent()

        assertEquals(
            listOf(AppListEvent.ShowMessage(UiText.StringResource(R.string.export_list_empty))),
            events
        )
    }
}
