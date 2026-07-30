// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import com.valhalla.thor.domain.model.AnimationIntensity
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.usecase.FreezeAppUseCase
import com.valhalla.thor.domain.usecase.GetAppDetailsUseCase
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.presentation.FakeAppRepository
import com.valhalla.thor.presentation.FakeAppShortcutController
import com.valhalla.thor.presentation.FakeFreezerRepository
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.presentation.FakePrivilegeStateProvider
import com.valhalla.thor.presentation.FakeStorageStatsProvider
import com.valhalla.thor.presentation.FakeSystemRepository
import com.valhalla.thor.presentation.FakeUsageAccessGate
import com.valhalla.thor.presentation.MainDispatcherRule
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

    @Before
    fun setUp() {
        appRepository = FakeAppRepository()
        system = FakeSystemRepository()
        freezer = FakeFreezerRepository()
        privilege = FakePrivilegeStateProvider()
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
        intensity: AnimationIntensity = AnimationIntensity.MEDIUM
    ): AppListViewModel {
        val prefs = FakePreferenceRepository(UserPreferences(animationIntensity = intensity))
        val manageAppUseCase = ManageAppUseCase(system)
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
            storageStats = FakeStorageStatsProvider(),
            usageAccess = FakeUsageAccessGate(),
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
}
