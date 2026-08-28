// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import com.valhalla.thor.R
import com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator
import com.valhalla.thor.domain.model.DetailedAppInfo
import com.valhalla.thor.domain.model.ObbFile
import com.valhalla.thor.domain.model.ObbProbe
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val manageAppUseCase = ManageAppUseCase(system, DefaultPackageOperationCoordinator())
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
        // The detail load probes for expansion files, which is setup noise here: this assertion is
        // about what the removal reaches at the privilege boundary, not about what loading did.
        system.calls.clear()

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
        // The detail load probes for expansion files. Cleared rather than expected, because
        // threading a setup call into the expectation would turn "no privileged call belongs on the
        // add path" into "one particular list of them does" — which asserts nothing about the add.
        system.calls.clear()

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

    // ── the OBB probe ────────────────────────────────────────────────────────
    //
    // The app-info card used to be drawn from `AppInfo.obbFilePath`, computed with
    // `File(...).exists()` — false for another package's OBB directory on Android 11+ whether or not
    // one is there, so the card never appeared on a modern device. It now reads the probe, and what
    // has to hold here is that the load asks and that all three answers survive the trip.

    @Test
    fun `the detail load asks for the OBB verdict and publishes it`() = runTest {
        loaded(userApp("a"))
        system.obbProbe = ObbProbe.Present(listOf(ObbFile("main.obb", 1024)), otherEntryCount = 0)
        val vm = viewModel()
        vm.loadAppDetails("a")
        runCurrent()

        assertTrue(
            "the card cannot be right if the load never asked",
            "probeObb:a" in system.calls
        )
        assertEquals(
            "the verdict has to reach the state, or the card renders from nothing",
            ObbProbe.Present(listOf(ObbFile("main.obb", 1024)), otherEntryCount = 0),
            vm.uiState.value.obbProbe
        )
    }

    /**
     * The reason the verdict is a tri-state. `Undetermined` says "this privilege cannot read
     * `Android/obb`", which is not "the app has no expansion files" — folding the two back together
     * is precisely the GH#164 defect, and it fails in the direction that looks like success.
     */
    @Test
    fun `an unreadable OBB directory stays Undetermined`() = runTest {
        loaded(userApp("a"))
        system.obbProbe = ObbProbe.Undetermined("dhizuku cannot read external storage")
        val vm = viewModel()
        vm.loadAppDetails("a")
        runCurrent()

        assertEquals(
            "'cannot see it' must not arrive as 'there is none'",
            ObbProbe.Undetermined("dhizuku cannot read external storage"),
            vm.uiState.value.obbProbe
        )
    }

    @Test
    fun `the verdict is absent until the probe answers, not None`() = runTest {
        loaded(userApp("a"))
        val vm = viewModel()
        runCurrent()

        assertNull(
            "null is the absence of an answer; None is an answer, and the card says so",
            vm.uiState.value.obbProbe
        )

        vm.loadAppDetails("a")
        runCurrent()

        assertEquals(
            "the answer has to replace the absence once the probe returns",
            ObbProbe.None,
            vm.uiState.value.obbProbe
        )
    }

    /**
     * The probe deliberately resolves *after* the details, so on a second load there is a window
     * where the new app's details are on screen. A verdict carried into that window is read against
     * the wrong app: the card claims game data the app does not have, and the export sheet leaves
     * `.xapk` enabled for an app whose expansions Thor never managed to read.
     */
    @Test
    fun `a second load clears the previous verdict before the new probe answers`() = runTest {
        loaded(userApp("a"))
        appRepository.details["b"] = DetailedAppInfo(appInfo = userApp("b"))
        system.obbProbe = ObbProbe.Present(listOf(ObbFile("main.obb", 1024)), otherEntryCount = 0)
        val vm = viewModel()
        vm.loadAppDetails("a")
        runCurrent()
        assertEquals(
            ObbProbe.Present(listOf(ObbFile("main.obb", 1024)), otherEntryCount = 0),
            vm.uiState.value.obbProbe
        )

        system.obbProbe = ObbProbe.None
        vm.loadAppDetails("b")

        assertNull(
            "app a's expansions must not be attributed to app b while b's probe is still running",
            vm.uiState.value.obbProbe
        )

        runCurrent()

        assertEquals(ObbProbe.None, vm.uiState.value.obbProbe)
    }

    // --- The Room throws that used to be process death (fix/freezer-bookkeeping-crashes) ---
    //
    // This file had no `try`, no `catch` and no `runCatching` anywhere in it before that branch, and
    // every watchlist call in it sat in a bare `viewModelScope.launch`. Room reports a full or failing
    // disk by *throwing*, `FreezerRepositoryImpl` is a pass-through that does not catch, and `:app`
    // installs no `CoroutineExceptionHandler` — so opening this sheet on a bad disk killed the app.
    //
    // Each test below removes one guard's reason to exist. Without the guard they do not merely
    // assert wrongly, they take the test runner's coroutine with them, which is the correct shape for
    // a pin on a crash.

    /**
     * The two-facts case, and the one worth getting right: something throws *after* the freeze has
     * already succeeded.
     *
     * The detail read is the seam, and picking it took some elimination. `refreshAppShortcut` runs
     * first and looks like the obvious candidate, but it is fire-and-forget — it returns as soon as
     * its body is scheduled on `FreezerShortcutManager`'s own scope, so its throw arrives after this
     * frame is gone and no guard here could ever see it. The watchlist read next to it is wrapped in
     * `isInFreezer`, which deliberately degrades to false. That leaves `refreshDetails`' package
     * manager call, which reports a dead `system_server` or a package that vanished mid-tap by
     * throwing and has nothing to degrade to — so it is the one remaining collaborator that can land
     * a throw between the act and the report of it.
     *
     * A bare "Error: …" there would tell a user whose app is demonstrably frozen that their action
     * failed, so the guard leads with the truth and then names the throw.
     */
    @Test
    fun `a freeze that succeeds then throws says so before naming the failure`() = runTest {
        loaded(userApp("a", enabled = true))
        appRepository.failDetailsWith("a", IllegalStateException("package manager died"))
        val vm = viewModel()
        val seen = events(vm)

        vm.toggleFreezerState("a", freeze = true, appName = "App A")
        runCurrent()

        assertEquals(
            "the freeze really happened, so that is the first thing said",
            listOf(
                UiText.StringResource(R.string.frozen_success, "App A"),
                UiText.StringResource(R.string.error_format, "package manager died")
            ),
            seen
        )
    }

    /**
     * And the shortcut refresh's own failure, which by design reaches nobody.
     *
     * Worth pinning precisely because the fix here first tried to report it: a guard around
     * [AppShortcutController.refreshAppShortcut] catches nothing, so the freeze is announced exactly
     * once and the stale launcher icon is absorbed by `FreezerShortcutManager.launchSafely`. If this
     * test ever starts seeing a second event, the port has grown a throw its callers were not written
     * for.
     */
    @Test
    fun `a launcher icon that will not repaint is not the user's problem`() = runTest {
        loaded(userApp("a", enabled = true))
        freezer.add("a") // already tracked, so the freeze reports rather than prompting
        shortcuts.failRefreshWith("a", IllegalStateException("shortcut rate limit exceeded"))
        val vm = viewModel()
        val seen = events(vm)

        vm.toggleFreezerState("a", freeze = true, appName = "App A")
        runCurrent()

        assertEquals(
            UiText.StringResource(R.string.frozen_success, "App A"),
            seen.single()
        )
        assertEquals(
            "the refusal went to the port's own guard, not to the screen",
            1,
            shortcuts.absorbedFailures.size
        )
    }

    /**
     * The same shape on the removal path: restore succeeded, the row would not go.
     *
     * Also the only pin this surface has on the order of the two steps after the restore. The other
     * three removal surfaces pin it with a shared `CallTrace`; this file has none, so without the
     * `disabled` assertion below, swapping `disableAppShortcut` and `remove` back to row-first ships
     * green across the whole suite.
     */
    @Test
    fun `a removal whose delete raises still reports the unfreeze`() = runTest {
        // appName distinct from the package, so the label expression is pinned too — `?: packageName`
        // and bare `packageName` are indistinguishable when userApp() leaves appName null.
        loaded(userApp("a", enabled = false, appName = "App A"))
        freezer.add("a")
        freezer.failRemoveWith("a", IllegalStateException("disk is full"))
        val vm = viewModel()
        vm.loadAppDetails("a")
        runCurrent()
        val seen = events(vm)

        vm.addOrRemoveFromFreezer("a")
        runCurrent()

        assertEquals(
            "the app is back — say that, then say the record did not keep up",
            listOf(
                UiText.StringResource(R.string.unfrozen_success, "App A"),
                UiText.StringResource(R.string.error_format, "disk is full")
            ),
            seen
        )
        assertTrue("and the row is still there, so the next tap can retry it", freezer.contains("a"))
        assertEquals(
            "the shortcut is greyed before the row is dropped, so a row-first order fails here",
            listOf("a"),
            shortcuts.disabled
        )
        // The screen must not contradict the toast it just showed. `refreshDetails` never runs on
        // this path — the delete throws first — so the only thing that can clear the `frozen` chip is
        // the optimistic patch taken the moment `restoreApp` reported success.
        assertTrue(
            "the sheet shows the app running, as the toast says it is",
            vm.uiState.value.detailedInfo?.appInfo?.enabled == true
        )
    }

    /**
     * The other side of the same guard: a throw *before* anything irreversible.
     *
     * Adding to the watchlist freezes nothing, so a failed insert really is a failed action and there
     * is no success to lead with. One plain error, and no claim about the app.
     */
    @Test
    fun `an add that raises is reported as the plain failure it is`() = runTest {
        loaded(userApp("a"))
        freezer.failAddWith("a", IllegalStateException("disk is full"))
        val vm = viewModel()
        vm.loadAppDetails("a")
        runCurrent()
        val seen = events(vm)

        vm.addOrRemoveFromFreezer("a")
        runCurrent()

        assertEquals(
            listOf(UiText.StringResource(R.string.error_format, "disk is full")),
            seen
        )
        // Filtered rather than `isEmpty`: the `loadAppDetails` above leaves the three privilege
        // probes in `calls`, so "nothing was done to the app" has to be said about the calls that
        // would actually do something to it.
        assertTrue(
            "nothing was done to the app — adding a row never freezes",
            system.calls.none { it.startsWith("setAppDisabled") || it.startsWith("setAppSuspended") }
        )
        assertTrue("and no row landed", freezer.added.isEmpty())
    }

    /**
     * [AppInfoDetailsViewModel.addToFreezer] — the prompt's own confirm, which is a different method
     * from the toggle above and had no test at all.
     *
     * It only ever runs after a freeze already succeeded, so the insert raising costs the watchlist
     * row and nothing else: the app stays frozen and stops being tracked, which is precisely the
     * state the prompt appeared to offer a way out of. Two things therefore have to survive the
     * throw — the prompt, so the retry is one more tap, and `isInFreezer` staying false, because no
     * row was written and the sheet's toggle would otherwise claim one was.
     *
     * The prompt outliving the failure is load-bearing on this surface in a way it is not on the
     * others: `AppInfoDetailsScreen.kt` leaves dismissal to the view model, where `AppListScreen`
     * and `FreezerScreen` clear their own prompt state as the dialog goes. Whatever this method
     * leaves in `freezerPrompt` is what the user sees.
     */
    @Test
    fun `a prompt confirm whose insert raises keeps the prompt and stays untracked`() = runTest {
        loaded(userApp("a", enabled = true))
        freezer.failAddWith("a", IllegalStateException("disk is full"))
        val vm = viewModel()
        vm.loadAppDetails("a")
        runCurrent()
        val seen = events(vm)

        // The only way the prompt is raised: a freeze that lands on an app not yet tracked.
        vm.toggleFreezerState("a", freeze = true, appName = "App A")
        runCurrent()
        assertNotNull("precondition: the freeze raised the prompt", vm.uiState.value.freezerPrompt)
        assertTrue("precondition: and it said so by prompting, not by toasting", seen.isEmpty())

        vm.addToFreezer("a")
        runCurrent()

        assertEquals(
            "the freeze is not re-announced and the failure is not dressed up as one",
            listOf(UiText.StringResource(R.string.error_format, "disk is full")),
            seen
        )
        assertNotNull(
            "the prompt stands, so the retry is one tap — this screen has no other dismissal",
            vm.uiState.value.freezerPrompt
        )
        assertFalse("no row landed, so the sheet must not claim one", vm.uiState.value.isInFreezer)
        assertTrue(freezer.added.isEmpty())
    }

    /**
     * The membership read that picks between two opposite actions, which is deliberately *not*
     * degraded the way the display read is.
     *
     * `isInFreezer` answers "not in the freezer" when the query throws, which is right for a label.
     * It would be wrong here: it would answer a Remove tap by trying to Add. So this one reads the
     * repository directly and lets the guard report it — nothing has happened to the app, and that is
     * exactly what the user is told.
     */
    @Test
    fun `a membership read that raises refuses to guess which action was meant`() = runTest {
        loaded(userApp("a", enabled = false))
        freezer.add("a")
        freezer.failContainsWith("a", IllegalStateException("disk I O error"))
        val vm = viewModel()
        val seen = events(vm)

        vm.addOrRemoveFromFreezer("a")
        runCurrent()

        assertEquals(
            listOf(UiText.StringResource(R.string.error_format, "disk I O error")),
            seen
        )
        assertTrue("no restore, and no add: the tap did nothing", system.calls.isEmpty())
        // Deliberately not `freezer.contains("a")`: that is the very call rigged to raise here, so
        // asserting through it would throw out of the test body and report as a failure of the code
        // under test. `removed` says the same thing without asking the broken question.
        assertTrue("the row is untouched", freezer.removed.isEmpty())
    }

    /**
     * And the display read, which *is* degraded — the case that must not be turned into an error.
     *
     * This read is a passenger on a detail load the user asked for. Failing it loudly would blame the
     * load for a database fault, and crashing on it was the original bug: the sheet could not be
     * opened at all. It degrades to "not tracked" and says nothing, matching
     * `AppListViewModel.observeFreezerMembership`'s `Flow.catch`.
     */
    @Test
    fun `a failed membership read leaves the detail load standing and silent`() = runTest {
        loaded(userApp("a"))
        freezer.add("a")
        freezer.failContainsWith("a", IllegalStateException("disk I O error"))
        val vm = viewModel()
        val seen = events(vm)

        vm.loadAppDetails("a")
        runCurrent()

        assertNotNull("the details themselves landed", vm.uiState.value.detailedInfo)
        assertFalse(
            "degraded to not-tracked, which offers to add rather than hiding an untracked app",
            vm.uiState.value.isInFreezer
        )
        assertTrue("and the load is not blamed for the database's fault", seen.isEmpty())
    }
}
