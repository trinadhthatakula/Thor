// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import com.valhalla.thor.R
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.usecase.FreezeAppUseCase
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.presentation.FakeAppRepository
import com.valhalla.thor.presentation.FakeAppShortcutController
import com.valhalla.thor.presentation.FakeBulkFreezeController
import com.valhalla.thor.presentation.FakeFreezeProfileRepository
import com.valhalla.thor.presentation.FakeFreezerRepository
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.presentation.FakePrivilegeStateProvider
import com.valhalla.thor.presentation.FakeSystemRepository
import com.valhalla.thor.presentation.MainDispatcherRule
import com.valhalla.thor.presentation.userApp
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.CancellationException
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
 * The Freezer screen's half of the watchlist-removal contract: **removing always restores, and a
 * row only goes once the app is actually back** (GH#310).
 *
 * The bug this pins was silent in every direction. `removeFromFreezer` deleted the durable Room row
 * first, then made the privileged call and dropped its `Result` on the floor — so an app that
 * refused to thaw kept its freeze *and* lost the only record pointing at it: the Freezer screen
 * stops listing it and Unfreeze-all, which iterates the watchlist, can no longer reach it. The user
 * was told N apps were removed, where N was the number they had selected rather than the number
 * that came back.
 *
 * Nothing here checks that the loop stops on a failure, because it never should: every gateway
 * failure arrives as `Result.failure` (`SystemRepositoryImpl.runGatewayAction` converts all of
 * them), so the whole selection is processed either way. The fix was to start *reading* those
 * results, and the assertions below are shaped accordingly — what happened to the other packages is
 * as much a part of the contract as what happened to the failing one.
 *
 * As in `AppInfoDetailsViewModelTest`, `FakeSystemRepository.calls` is the assertion: "was
 * `setAppSuspended` reached for this package at all" is exactly what a restore means at the
 * privilege boundary, and `FakeFreezerRepository` answers the other half — which rows survived.
 *
 * The dispatcher is standard rather than unconfined, and the same one is passed in for `default` and
 * `io`, so the app-list fold in `observeApps` and every action share the test's virtual clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FreezerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private lateinit var appRepository: FakeAppRepository
    private lateinit var system: FakeSystemRepository
    private lateinit var freezer: FakeFreezerRepository
    private lateinit var shortcuts: FakeAppShortcutController
    private lateinit var privilege: FakePrivilegeStateProvider
    private lateinit var profiles: FakeFreezeProfileRepository

    /**
     * The three fakes' calls in one list.
     *
     * `system.calls`, `freezer.removed` and `shortcuts.disabled` each say what happened to them;
     * none of them, alone or side by side, says what happened *first*. Restore-before-delete is
     * nothing but an ordering claim, so it needs an ordered assertion — and one that fails on the
     * happy path, where the per-fake lists are identical either way round.
     */
    private lateinit var trace: MutableList<String>

    @Before
    fun setUp() {
        trace = mutableListOf()
        appRepository = FakeAppRepository()
        system = FakeSystemRepository(trace)
        freezer = FakeFreezerRepository(trace = trace)
        shortcuts = FakeAppShortcutController(trace = trace)
        privilege = FakePrivilegeStateProvider()
        profiles = FakeFreezeProfileRepository()
    }

    private fun viewModel(): FreezerViewModel {
        val manageAppUseCase = ManageAppUseCase(system)
        return FreezerViewModel(
            freezerRepository = freezer,
            freezeProfileRepository = profiles,
            bulkFreeze = FakeBulkFreezeController(),
            getInstalledAppsUseCase = GetInstalledAppsUseCase(appRepository),
            manageAppUseCase = manageAppUseCase,
            freezeAppUseCase = FreezeAppUseCase(appRepository, manageAppUseCase),
            privilege = privilege,
            preferenceRepository = FakePreferenceRepository(),
            appShortcuts = shortcuts,
            defaultDispatcher = mainDispatcherRule.dispatcher,
            ioDispatcher = mainDispatcherRule.dispatcher
        )
    }

    /** Collects one-off events for the duration of the test, as the screen does. */
    private fun TestScope.events(vm: FreezerViewModel): List<FreezerEvent> {
        val seen = mutableListOf<FreezerEvent>()
        backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.events.collect { seen += it } }
        return seen
    }

    /** The single message the run reported — a removal that says two things has already failed. */
    private fun List<FreezerEvent>.onlyToast(): UiText =
        (single() as FreezerEvent.ShowToast).message

    @Test
    fun `a removal that thaws everything drops every row and counts what actually left`() = runTest {
        freezer.add("a")
        freezer.add("b")
        val vm = viewModel()
        val seen = events(vm)
        vm.selectAll(setOf("a", "b"))
        trace.clear() // the two rows above are scaffolding; the trace is about the run itself

        vm.removeFromFreezer(setOf("a", "b"))
        runCurrent()

        assertEquals(
            "every package is asked to unsuspend and to re-enable — a freeze can be either",
            listOf(
                "setAppSuspended:a:false", "setAppDisabled:a:false",
                "setAppSuspended:b:false", "setAppDisabled:b:false"
            ),
            system.calls
        )
        assertEquals("both rows go, because both apps came back", listOf("a", "b"), freezer.removed)
        assertEquals(listOf("a", "b"), shortcuts.disabled)
        // The ordering itself, which is the whole fix and which the lists above cannot show: the
        // row goes only after the app is back, one package finished before the next is started.
        assertEquals(
            listOf(
                "setAppSuspended:a:false", "setAppDisabled:a:false",
                "freezer.remove:a", "shortcut.disable:a",
                "setAppSuspended:b:false", "setAppDisabled:b:false",
                "freezer.remove:b", "shortcut.disable:b"
            ),
            trace
        )
        assertTrue("nothing is left selected", vm.uiState.value.multiSelection.isEmpty())
        // The count is what was removed, not what was asked for. They agree here and only here.
        assertEquals(
            UiText.PluralsResource(R.plurals.removed_from_freezer_success, 2),
            seen.onlyToast()
        )
    }

    /**
     * The GH#310 case itself, at its smallest: one package in the selection refuses to thaw.
     *
     * The row is the only route back to a frozen app — the screen lists it, and Unfreeze-all reaches
     * it from there — so a failed restore has to leave the watchlist exactly as it found it. The
     * message is the gateway's own, which post-PR#330 names the privilege still holding the app;
     * a count would say strictly less.
     */
    @Test
    fun `a package that will not thaw keeps its row, its shortcut and the gateway's own words`() =
        runTest {
            freezer.add("a")
            freezer.add("b")
            system.failWith("setAppSuspended:b:false", IllegalStateException("Shizuku is not running"))
            val vm = viewModel()
            val seen = events(vm)
            vm.selectAll(setOf("a", "b"))

            vm.removeFromFreezer(setOf("a", "b"))
            runCurrent()

            assertFalse("a came back, so its row goes", freezer.contains("a"))
            assertTrue("b is still frozen, so its row is all the user has left", freezer.contains("b"))
            assertEquals(listOf("a"), freezer.removed)
            assertEquals(
                "and the launcher shortcut outlives the failed removal too",
                listOf("a"),
                shortcuts.disabled
            )
            assertEquals(
                UiText.StringResource(R.string.error_format, "Shizuku is not running"),
                seen.onlyToast()
            )
        }

    /**
     * Past one failure there is no single gateway message to show, so the split is reported instead:
     * how many left, out of how many were asked for, and how many did not.
     */
    @Test
    fun `several failures report the split and delete only the rows that earned it`() = runTest {
        listOf("a", "b", "c").forEach { freezer.add(it) }
        system.failWith("setAppSuspended:b:false", IllegalStateException("binder died"))
        system.failWith("setAppSuspended:c:false", IllegalStateException("binder died"))
        val vm = viewModel()
        val seen = events(vm)
        vm.selectAll(setOf("a", "b", "c"))

        vm.removeFromFreezer(setOf("a", "b", "c"))
        runCurrent()

        assertEquals(
            UiText.StringResource(R.string.removed_from_freezer_partial_failure, 1, 3, 2),
            seen.onlyToast()
        )
        assertEquals("only the app that came back loses its row", listOf("a"), freezer.removed)
        assertTrue(freezer.contains("b"))
        assertTrue(freezer.contains("c"))
        assertEquals(listOf("a"), shortcuts.disabled)
        assertEquals(
            "c is still attempted after b failed — a per-package failure is not an abort",
            listOf(
                "setAppSuspended:a:false", "setAppDisabled:a:false",
                "setAppSuspended:b:false",
                "setAppSuspended:c:false"
            ),
            system.calls
        )
        assertEquals(
            "and both failures stay selected, not just the first",
            setOf("b", "c"),
            vm.uiState.value.multiSelection
        )
    }

    /**
     * What is left selected after a partial run.
     *
     * Clearing the whole selection was part of the original bug: it said "all done" in the one place
     * the user could have acted on the failures from. Keeping the failures selected makes the retry
     * one more tap instead of a hunt through the list.
     */
    @Test
    fun `a failed removal stays selected and the successful ones do not`() = runTest {
        listOf("a", "b", "c").forEach { freezer.add(it) }
        system.failWith("setAppSuspended:b:false", IllegalStateException("Shizuku is not running"))
        val vm = viewModel()
        vm.selectAll(setOf("a", "b", "c"))

        vm.removeFromFreezer(setOf("a", "b", "c"))
        runCurrent()

        assertEquals(setOf("b"), vm.uiState.value.multiSelection)
    }

    /**
     * The other way a step can fail: by throwing rather than by returning a `Result`.
     *
     * Room and `ShortcutManagerCompat` both report that way, and `:app` installs no
     * `CoroutineExceptionHandler` — so an escaping throw would abandon every package after it *and*
     * take the report down with it, which is the same silence GH#310 was about wearing a different
     * hat. The restore for `b` has already happened here, so the app is thawed; what is being pinned
     * is that its row survives the failed delete and that `c` is still processed.
     */
    @Test
    fun `a delete that raises neither abandons the rest of the selection nor goes unreported`() =
        runTest {
            listOf("a", "b", "c").forEach { freezer.add(it) }
            freezer.failRemoveWith("b", IllegalStateException("database is locked"))
            val vm = viewModel()
            val seen = events(vm)
            vm.selectAll(setOf("a", "b", "c"))

            vm.removeFromFreezer(setOf("a", "b", "c"))
            runCurrent()

            assertEquals("c ran after the throw", listOf("a", "c"), freezer.removed)
            assertEquals(listOf("a", "c"), shortcuts.disabled)
            assertTrue("the row whose delete raised is still there", freezer.contains("b"))
            assertEquals(
                UiText.StringResource(R.string.error_format, "database is locked"),
                seen.onlyToast()
            )
            assertEquals(
                "so it stays selected, like any other failure",
                setOf("b"),
                vm.uiState.value.multiSelection
            )
        }

    /**
     * The one throw that must *not* be handled.
     *
     * `CancellationException` is an `Exception`, so the catch that turns a failure into a toast
     * would swallow it too — and a cancelled coroutine that keeps going is a view model still
     * issuing privileged calls for a screen that is gone. Hence the rethrow ahead of it. The run
     * stops where it was: no report, no tidying of the selection, and nothing at all done for `c`.
     *
     * That leaving-things-mid-way is the point, and why cancellation cannot be treated as one more
     * per-package failure: a failure means "this one did not work, carry on", and cancellation means
     * "stop".
     */
    @Test
    fun `a cancellation stops the run instead of being reported as a failure`() = runTest {
        listOf("a", "b", "c").forEach { freezer.add(it) }
        freezer.failRemoveWith("b", CancellationException("the screen went away"))
        val vm = viewModel()
        val seen = events(vm)
        vm.selectAll(setOf("a", "b", "c"))

        vm.removeFromFreezer(setOf("a", "b", "c"))
        runCurrent()

        assertEquals("what finished before the cancellation still counts", listOf("a"), freezer.removed)
        assertEquals(
            "c is never begun — the loop ends rather than moving on",
            listOf(
                "setAppSuspended:a:false", "setAppDisabled:a:false",
                "setAppSuspended:b:false", "setAppDisabled:b:false"
            ),
            system.calls
        )
        assertTrue("nothing is reported, because there is nobody left to report to", seen.isEmpty())
        assertEquals(
            "and the selection is left as it was, not tidied up on the way out",
            setOf("a", "b", "c"),
            vm.uiState.value.multiSelection
        )
    }

    /**
     * The launcher step raising, which is the one failure that lands after the row is already gone.
     *
     * It counts as a failure even so. The alternative — treating the package as removed because the
     * part that mattered worked — would hide a stale live shortcut, and a live shortcut for an app
     * no longer in the freezer is a route back into freezing it from the launcher. Reporting is the
     * cheap side to be wrong on: the app is thawed and the row is gone, so nothing is stranded.
     */
    @Test
    fun `a shortcut that will not be disabled is still reported, though the row has gone`() =
        runTest {
            freezer.add("a")
            shortcuts.failDisableWith("a", IllegalStateException("shortcut rate limit exceeded"))
            val vm = viewModel()
            val seen = events(vm)
            vm.selectAll(setOf("a"))

            vm.removeFromFreezer(setOf("a"))
            runCurrent()

            assertEquals(
                "the restore ran and the row went — the invariant held all the way through",
                listOf("setAppSuspended:a:false", "setAppDisabled:a:false"),
                system.calls
            )
            assertEquals(listOf("a"), freezer.removed)
            assertEquals(
                UiText.StringResource(R.string.error_format, "shortcut rate limit exceeded"),
                seen.onlyToast()
            )
            assertEquals(
                "reported as failed, so it stays selected",
                setOf("a"),
                vm.uiState.value.multiSelection
            )
        }

    /**
     * The manage sheet's single-app path, which had the same ordering and reported the failure over
     * a row that was already gone.
     */
    @Test
    fun `the manage sheet keeps the row when the app will not come back`() = runTest {
        appRepository.apps.value = listOf(userApp("a", enabled = false))
        freezer.add("a")
        system.failWith("setAppDisabled:a:false", IllegalStateException("enabled-settings write refused"))
        val vm = viewModel()
        val seen = events(vm)
        runCurrent() // let the watchlist and the app list land, so the restore resolves against them

        vm.toggleManaged("a", add = false)
        runCurrent()

        assertEquals(
            "both halves, unconditionally — the same as the multi-select path",
            listOf("setAppSuspended:a:false", "setAppDisabled:a:false"),
            system.calls
        )
        assertTrue(freezer.contains("a"))
        assertTrue("the row is not deleted first and mourned afterwards", freezer.removed.isEmpty())
        assertTrue(shortcuts.disabled.isEmpty())
        assertEquals(
            UiText.StringResource(R.string.error_format, "enabled-settings write refused"),
            seen.onlyToast()
        )
    }

    /**
     * The manage sheet's half of the throw guard. The privileged call reports by returning, but the
     * durable steps after it report by throwing, and `:app` installs no `CoroutineExceptionHandler`
     * — unguarded, this escapes [androidx.lifecycle.viewModelScope] and takes the process, not the
     * toast. The same try/catch covers `add = true`, whose `freezerRepository.add` can throw for the
     * same reason.
     */
    @Test
    fun `the manage sheet reports a raising delete rather than taking the process with it`() =
        runTest {
            freezer.add("a")
            freezer.failRemoveWith("a", IllegalStateException("database is locked"))
            val vm = viewModel()
            val seen = events(vm)
            runCurrent()

            vm.toggleManaged("a", add = false)
            runCurrent()

            assertEquals(
                "the restore still ran and succeeded — it is the delete after it that raised",
                listOf("setAppSuspended:a:false", "setAppDisabled:a:false"),
                system.calls
            )
            assertTrue("the throw left the row in place", freezer.contains("a"))
            assertTrue(shortcuts.disabled.isEmpty())
            assertEquals(
                UiText.StringResource(R.string.error_format, "database is locked"),
                seen.onlyToast()
            )
        }

    /**
     * The other half of that guard, and the half the reported finding did not mention: `add = true`
     * writes a row too, so its `freezerRepository.add` can raise for exactly the same reason.
     *
     * The app is left frozen with no row, which is the GH#310 shape — but arrived at from the other
     * end, and self-correcting rather than silent: the freeze is what the user asked for, the
     * failure is reported, and the Freezer screen's import prompt offers a disabled app back. What
     * is being pinned here is only that the throw is caught at all.
     */
    @Test
    fun `the manage sheet reports a raising add rather than taking the process with it`() = runTest {
        appRepository.apps.value = listOf(userApp("a"))
        freezer.failAddWith("a", IllegalStateException("database is locked"))
        val vm = viewModel()
        val seen = events(vm)
        runCurrent()

        vm.toggleManaged("a", add = true)
        runCurrent()

        assertEquals("the freeze itself went through", listOf("setAppDisabled:a:true"), system.calls)
        assertFalse("it is the row that did not land", freezer.contains("a"))
        assertEquals(
            UiText.StringResource(R.string.error_format, "database is locked"),
            seen.onlyToast()
        )
    }

    /**
     * The vacuous success: a removal that reports success having made no privileged call at all.
     *
     * `allInstalledApps` is a rescan snapshot, and the suspend-mode freeze path never patches it, so
     * an app suspended moments ago still reads `enabled = true, isSuspended = false` here.
     * `restoreApp` with those flags plans *nothing* — `restorePlanFor` finds neither half to undo —
     * and returns success without touching the privilege layer, which under the restore-first
     * ordering is worse than the original bug: the row is deleted, the toast says it worked, and the
     * app is still suspended. `forceUnfreeze` asks unconditionally instead; the gateway
     * short-circuits whichever half is already true.
     */
    @Test
    fun `removal asks unconditionally rather than trusting the list snapshot`() = runTest {
        appRepository.apps.value = listOf(userApp("a", enabled = true, isSuspended = false))
        freezer.add("a")
        val vm = viewModel()
        val seen = events(vm)
        runCurrent()

        val snapshot = vm.uiState.value.allInstalledApps.single()
        assertTrue("precondition: the snapshot is the misleading one", snapshot.enabled)
        assertFalse("precondition: and it says the app is not suspended", snapshot.isSuspended)

        vm.removeFromFreezer(setOf("a"))
        runCurrent()

        assertEquals(
            "trusting the snapshot would remove the row without making a single privileged call",
            listOf("setAppSuspended:a:false", "setAppDisabled:a:false"),
            system.calls
        )
        assertFalse(freezer.contains("a"))
        assertEquals(
            UiText.PluralsResource(R.plurals.removed_from_freezer_success, 1),
            seen.onlyToast()
        )
    }

    /**
     * The same vacuous success, two taps away in the manage sheet.
     *
     * Its switch is drawn from the watchlist, which re-emits the moment a row lands, while the app
     * lists behind it only move on the next full rescan — so freezing an app and immediately
     * unfreezing it is an ordinary gesture with a stale snapshot in the middle of it. Restoring from
     * those flags plans nothing, reports success, and drops the row over an app that is still
     * suspended: GH#310 again, this time without even a failure to report.
     */
    @Test
    fun `the manage sheet asks unconditionally too, so freeze-then-unfreeze cannot strand an app`() =
        runTest {
            appRepository.apps.value = listOf(userApp("a", enabled = true, isSuspended = false))
            val vm = viewModel()
            vm.setFreezerMode(FreezerMode.SUSPEND)
            runCurrent()

            vm.toggleManaged("a", add = true)
            runCurrent()
            assertTrue("precondition: the row landed", freezer.contains("a"))
            val snapshot = vm.uiState.value.allInstalledApps.single()
            assertTrue("precondition: and the snapshot still calls the app active", snapshot.enabled)
            assertFalse("precondition: no rescan patched the suspend in", snapshot.isSuspended)

            vm.toggleManaged("a", add = false)
            runCurrent()

            assertEquals(
                "the unsuspend is asked for rather than planned away by the stale flags",
                listOf(
                    "setAppSuspended:a:true",
                    "setAppSuspended:a:false", "setAppDisabled:a:false"
                ),
                system.calls
            )
            assertFalse("and only then does the row go", freezer.contains("a"))
            assertEquals(listOf("a"), shortcuts.disabled)
            // "And only then" as an assertion rather than a comment: this path had the same
            // delete-first ordering as the bulk one, and reported its failure over a row that was
            // already gone.
            assertEquals(
                listOf(
                    "setAppSuspended:a:true", "freezer.add:a",
                    "setAppSuspended:a:false", "setAppDisabled:a:false",
                    "freezer.remove:a", "shortcut.disable:a"
                ),
                trace
            )
        }

    // --- The profile editor's dismiss-vs-save ordering ---

    /**
     * The other half of the same mistake this class exists for, in a different corner of the screen.
     *
     * `FreezerScreen` used to dismiss the profile editor in the same statement that issued the
     * write, so the two writes a database can legitimately refuse — a name the unique index already
     * holds, and a members-table foreign key — reported themselves as a toast floating over a sheet
     * that had already destroyed the draft. Deleting the row before knowing it worked, deleting the
     * draft before knowing it worked: the same shape.
     *
     * The screen's own state is not reachable from here, so what these pin is the contract it now
     * closes on. [FreezerEvent.ProfileSaveSucceeded] is emitted **only** by a write that landed, and
     * `profileSaveInFlight` covers exactly the window in which one is running.
     */
    @Test
    fun `a profile save that lands says so, and hands the screen the one thing that closes it`() =
        runTest {
            val vm = viewModel()
            val seen = events(vm)

            vm.createProfile("Night", listOf("a", "b"))
            assertTrue(
                "the flag is set on the caller's thread, so Save is down before the write starts",
                vm.uiState.value.profileSaveInFlight
            )
            runCurrent()

            assertEquals(
                listOf(
                    FreezerEvent.ShowToast(UiText.StringResource(R.string.profile_saved)),
                    FreezerEvent.ProfileSaveSucceeded
                ),
                seen
            )
            assertEquals(listOf("Night"), vm.uiState.value.profiles.map { it.name })
            assertFalse("and the button comes back", vm.uiState.value.profileSaveInFlight)
        }

    /**
     * The bug, at its smallest: the write is refused and the editor must stay up.
     *
     * The assertion is an absence, which is the only shape available — the sheet's open/closed state
     * lives in the screen, and it now closes on nothing but the success event. If a failed write
     * ever emits one again, the draft goes with it and the toast explaining why lands on a screen
     * that can no longer act on it.
     */
    @Test
    fun `a refused profile save reports itself and never announces success`() = runTest {
        // Not SQLiteConstraintException: it is Android-only, so constructing one here hits the
        // stubbed android.jar. Either branch of runProfileWrite's catch has to reach the same
        // conclusion, and this is the one a JVM can raise.
        profiles.writeFailure = IllegalStateException("disk is on fire")
        val vm = viewModel()
        val seen = events(vm)

        vm.createProfile("Night", listOf("a", "b"))
        runCurrent()

        assertEquals(
            listOf(FreezerEvent.ShowToast(UiText.StringResource(R.string.error_format, "disk is on fire"))),
            seen
        )
        assertTrue("nothing was written", vm.uiState.value.profiles.isEmpty())
        // `finally`, not the success path. A failed save that left the button dead would take the
        // retry away at the exact moment the sheet was kept open to offer one.
        assertFalse("and Save is usable again, because it is the retry", vm.uiState.value.profileSaveInFlight)
    }

    /** Two taps inside one frame are one write — the disabled button only covers what it can see. */
    @Test
    fun `a second save issued before the first lands is dropped rather than queued`() = runTest {
        val vm = viewModel()
        val seen = events(vm)

        vm.createProfile("Night", listOf("a"))
        vm.createProfile("Night", listOf("a"))
        runCurrent()

        assertEquals(
            "one profile, not two rows racing the same unique index",
            listOf("Night"),
            vm.uiState.value.profiles.map { it.name }
        )
        assertEquals(1, seen.count { it is FreezerEvent.ProfileSaveSucceeded })
    }
}
