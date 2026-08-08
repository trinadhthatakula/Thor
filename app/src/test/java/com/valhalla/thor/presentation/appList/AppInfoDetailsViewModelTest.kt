// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import com.valhalla.thor.domain.model.DetailedAppInfo
import com.valhalla.thor.domain.usecase.FreezeAppUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.presentation.FakeAppRepository
import com.valhalla.thor.presentation.FakeAppShortcutController
import com.valhalla.thor.presentation.FakeFreezerRepository
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.presentation.FakeSystemRepository
import com.valhalla.thor.presentation.MainDispatcherRule
import com.valhalla.thor.presentation.userApp
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The app-info sheet's half of the freezer-watchlist contract.
 *
 * One rule is under test, and it is the asymmetric one: **adding never freezes, removing always
 * restores.** Three surfaces can take an app off the watchlist — [FreezerViewModel.removeFromFreezer],
 * [AppListViewModel.toggleFreezerMembership] and [AppInfoDetailsViewModel.addOrRemoveFromFreezer] —
 * and if they disagree, whether your app comes back depends on which screen you happened to tap.
 * Leaving it frozen strands it: the freezer screen no longer lists it, so the only route back is the
 * import-already-disabled flow.
 *
 * `FakeSystemRepository` records the privileged calls in order, which is the whole assertion here —
 * "did `setAppSuspended` and `setAppDisabled` actually get reached for this package" is exactly what
 * a restore means at the privilege boundary, and a mixed disabled-*and*-suspended app needs both.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppInfoDetailsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private lateinit var appRepository: FakeAppRepository
    private lateinit var system: FakeSystemRepository
    private lateinit var freezer: FakeFreezerRepository
    private lateinit var shortcuts: FakeAppShortcutController
    private lateinit var preferences: FakePreferenceRepository

    @Before
    fun setUp() {
        appRepository = FakeAppRepository()
        system = FakeSystemRepository()
        freezer = FakeFreezerRepository()
        shortcuts = FakeAppShortcutController()
        preferences = FakePreferenceRepository()
    }

    private fun viewModel(): AppInfoDetailsViewModel {
        val manageAppUseCase = ManageAppUseCase(system)
        return AppInfoDetailsViewModel(
            appRepository = appRepository,
            systemRepository = system,
            manageAppUseCase = manageAppUseCase,
            freezeAppUseCase = FreezeAppUseCase(appRepository, manageAppUseCase),
            freezerRepository = freezer,
            appShortcuts = shortcuts,
            preferenceRepository = preferences,
            ioDispatcher = mainDispatcherRule.dispatcher
        )
    }

    /** Collects one-off events for the duration of the test, as the screen does. */
    private fun TestScope.events(vm: AppInfoDetailsViewModel): List<UiText> {
        val seen = mutableListOf<UiText>()
        backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.events.collect { seen += it } }
        return seen
    }

    /** Publishes [app] as the loaded detail, so the restore resolves against real state. */
    private fun loaded(app: com.valhalla.thor.domain.model.AppInfo) {
        appRepository.apps.value = listOf(app)
        appRepository.details[app.packageName] = DetailedAppInfo(appInfo = app)
    }

    @Test
    fun `removing a disabled and suspended app undoes both halves`() = runTest {
        loaded(userApp("a", enabled = false, isSuspended = true))
        freezer.add("a")
        val vm = viewModel()
        vm.loadAppDetails("a")
        runCurrent()

        vm.addOrRemoveFromFreezer("a")
        runCurrent()

        assertEquals(
            "a removal has to unsuspend and re-enable, not just drop the row",
            listOf("setAppSuspended:a:false", "setAppDisabled:a:false"),
            system.calls
        )
        assertFalse("the app is off the watchlist", freezer.contains("a"))
        assertFalse("and the sheet stops showing it as tracked", vm.uiState.value.isInFreezer)
    }

    /**
     * The fallback path. `restoreApp` needs the app's current state to know which halves to undo;
     * before the first detail load there is none, so the code falls through to `forceUnfreeze`,
     * which does both unconditionally. That is safe rather than merely tolerable: unsuspending a
     * non-suspended app and enabling an enabled one are both no-ops at the privilege layer.
     */
    @Test
    fun `removing before details load still restores, unconditionally`() = runTest {
        freezer.add("a")
        val vm = viewModel() // no loadAppDetails — detailedInfo is null

        vm.addOrRemoveFromFreezer("a")
        runCurrent()

        assertEquals(
            listOf("setAppSuspended:a:false", "setAppDisabled:a:false"),
            system.calls
        )
        assertFalse(freezer.contains("a"))
    }

    @Test
    fun `a failed restore is reported instead of the removal message`() = runTest {
        loaded(userApp("a", enabled = false))
        freezer.add("a")
        system.failWith("setAppDisabled:a:false", IllegalStateException("shell died"))
        val vm = viewModel()
        val seen = events(vm)
        vm.loadAppDetails("a")
        runCurrent()

        vm.addOrRemoveFromFreezer("a")
        runCurrent()

        assertEquals("exactly one event, and it is not the success", 1, seen.size)
        assertTrue(
            "a failed thaw must not report itself as a successful removal",
            seen.single() is UiText.StringResource
        )
        // The watchlist row is the only route back to a frozen app: the freezer screen lists it,
        // and Unfreeze-all reaches it from there. Dropping the row on a failed thaw would leave the
        // app frozen with nothing pointing at it, so the removal has to be all-or-nothing.
        assertTrue("a failed thaw must keep the watchlist entry", freezer.contains("a"))
        assertTrue(
            "and the toggle must still read as tracked",
            vm.uiState.value.isInFreezer
        )
        assertTrue(
            "the launcher shortcut outlives a failed removal too",
            shortcuts.disabled.isEmpty()
        )
    }

    /**
     * The other half of the asymmetry. Adding is a bookkeeping change only — it must never reach the
     * privilege layer, or opening the sheet and tapping "track this" would silently freeze the app.
     */
    @Test
    fun `adding to the watchlist freezes nothing`() = runTest {
        loaded(userApp("a"))
        val vm = viewModel()
        vm.loadAppDetails("a")
        runCurrent()

        vm.addOrRemoveFromFreezer("a")
        runCurrent()

        assertTrue("no privileged call belongs on the add path", system.calls.isEmpty())
        assertTrue(freezer.contains("a"))
    }

    /**
     * A pinned launcher shortcut outlives the watchlist entry — Android won't let an app delete a
     * shortcut the user pinned, so the ceiling is greying it out. If that were skipped, the home
     * screen would keep a live-looking icon for an app Thor no longer tracks.
     */
    @Test
    fun `removal retires the launcher shortcut`() = runTest {
        loaded(userApp("a", enabled = false))
        freezer.add("a")
        val vm = viewModel()
        vm.loadAppDetails("a")
        runCurrent()

        vm.addOrRemoveFromFreezer("a")
        runCurrent()

        assertEquals(listOf("a"), shortcuts.disabled)
    }

    // ── the freeze-confirmation setting ──────────────────────────────────────
    //
    // This view model carries the flag for both freeze surfaces (see AppInfoDetailsUiState). What
    // has to hold is *when* it is right, not what it means — the rule itself is pinned in
    // FreezePolicyTest, where it needs no Android at all.

    @Test
    fun `the confirmation setting is known before any detail load`() = runTest {
        // The freeze action sits in the action row, which AppInfoSheet shows at its partial detent —
        // before anything has expanded the sheet and so before loadAppDetails has ever run. Sourcing
        // the flag from that load would leave the first freeze of every session asking, whatever the
        // user chose.
        preferences.setSkipRoutineFreezeConfirmation(true)

        val vm = viewModel()
        runCurrent()

        assertTrue(vm.uiState.value.skipRoutineFreezeConfirmation)
        assertEquals(null, vm.uiState.value.detailedInfo)
    }

    @Test
    fun `flipping the setting reaches a sheet that is already open`() = runTest {
        loaded(userApp("a"))
        val vm = viewModel()
        vm.loadAppDetails("a")
        runCurrent()
        assertFalse(vm.uiState.value.skipRoutineFreezeConfirmation)

        preferences.setSkipRoutineFreezeConfirmation(true)
        runCurrent()

        assertTrue(vm.uiState.value.skipRoutineFreezeConfirmation)
    }

    @Test
    fun `a later detail load does not undo the setting`() = runTest {
        // loadAppDetails rewrites the state with a copy() built off a snapshot it took earlier. The
        // flag has to survive that, or the setting would silently revert to "always ask" on every
        // reload — the failure would look exactly like the user never set it.
        preferences.setSkipRoutineFreezeConfirmation(true)
        loaded(userApp("a"))
        val vm = viewModel()
        runCurrent()

        vm.loadAppDetails("a")
        runCurrent()

        assertTrue(vm.uiState.value.skipRoutineFreezeConfirmation)
    }
}
