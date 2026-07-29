// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.main

import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.domain.usecase.ShareAppUseCase
import com.valhalla.thor.presentation.FakeAppBundleBuilder
import com.valhalla.thor.presentation.FakeAppBundleFileStore
import com.valhalla.thor.presentation.FakeAppRepository
import com.valhalla.thor.presentation.FakeFreezerRepository
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.presentation.FakeSystemRepository
import com.valhalla.thor.presentation.MainDispatcherRule
import com.valhalla.thor.presentation.blockedSystemApp
import com.valhalla.thor.presentation.systemApp
import com.valhalla.thor.presentation.userApp
import com.valhalla.thor.util.UiText
import com.valhalla.thor.util.UiTextException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Behaviour tests for [MainViewModel] — the one view model in `presentation/` that can be built on
 * a plain JVM, and the one that owns every *bulk* privileged action in the app.
 *
 * What is worth pinning here is not that a method forwards a call. It is the handful of rules that
 * stand between a tap and an unbootable phone, and that live nowhere else:
 *
 * - a `BLOCKED` app must not be frozen or uninstalled by a batch, and the *count the user sees*
 *   must describe what the run will actually attempt;
 * - unfreeze must never be gated — it is the way out of a bad freeze;
 * - a system app uninstalled through Thor must land on the freezer watchlist, because
 *   `pm uninstall --user` is how Thor freezes system apps and the watchlist row is the only record
 *   that the app is recoverable;
 * - a refusal that carries its own message must be shown as that message.
 *
 * Every assertion is made against [FakeSystemRepository.calls] — the list of commands that reached
 * the privilege layer — rather than against the returned `Result`. A gate that refuses *after*
 * running `pm uninstall` satisfies any assertion on the result alone, and the app is still gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var system: FakeSystemRepository
    private lateinit var appRepository: FakeAppRepository
    private lateinit var freezer: FakeFreezerRepository
    private lateinit var prefs: FakePreferenceRepository

    @Before
    fun setUp() {
        system = FakeSystemRepository()
        appRepository = FakeAppRepository()
        freezer = FakeFreezerRepository()
        // Default `UserPreferences` has hasShownSupportDeveloperPrompt = false, i.e. a user who has
        // never seen the donation prompt. The prompt tests below depend on that starting point.
        prefs = FakePreferenceRepository()
    }

    /**
     * Builds the view model and keeps [MainViewModel.uiState] hot.
     *
     * `uiState` is `stateIn(WhileSubscribed(5000))`, so with no collector it stays pinned to the
     * initial `MainUiState()` and every state assertion in this file would pass for the wrong
     * reason. The keeper runs on the rule's dispatcher so it starts collecting eagerly, before the
     * action under test.
     */
    private fun TestScope.viewModel(
        preferenceRepository: FakePreferenceRepository = prefs
    ): MainViewModel {
        val vm = MainViewModel(
            manageAppUseCase = ManageAppUseCase(system),
            getInstalledAppsUseCase = GetInstalledAppsUseCase(appRepository),
            shareAppUseCase = ShareAppUseCase(
                FakeAppBundleBuilder(),
                FakeAppBundleFileStore(),
                mainDispatcherRule.dispatcher
            ),
            preferenceRepository = preferenceRepository,
            freezerRepository = freezer,
            ioDispatcher = mainDispatcherRule.dispatcher
        )
        backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.uiState.collect {} }
        return vm
    }

    /**
     * Subscribes to [MainViewModel.effect] and returns the live list of everything it delivers.
     *
     * A plain collector rather than Turbine because the channel behind `effect` is **RENDEZVOUS**:
     * a `send` with no subscriber parks the emitting coroutine instead of buffering, and the
     * subscribe/emit ordering is itself under test below. A list makes "exactly one event" a
     * `size` assertion and keeps the ordering visible.
     */
    private fun TestScope.effectsOf(vm: MainViewModel): List<MainSideEffect> {
        val received = mutableListOf<MainSideEffect>()
        backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.effect.collect { received += it } }
        return received
    }

    // --- Bulk freeze: the tier filter ------------------------------------------------------

    @Test
    fun `a blocked system app is never disabled by a bulk freeze`() = runTest {
        val vm = viewModel()

        vm.onMultiAppAction(
            MultiAppAction.Freeze(listOf(blockedSystemApp("com.blocked"), userApp("com.ok")))
        )
        advanceUntilIdle()

        // Not "the result was a failure" — the package must never appear in a command at all.
        // Thor freezes a system app with `pm uninstall --user`, so a leaked call is not recoverable
        // by retrying with the right answer.
        assertEquals(listOf("setAppDisabled:com.ok:true"), system.calls)
    }

    @Test
    fun `the freeze counter counts only the apps the run will attempt`() = runTest {
        val vm = viewModel()

        vm.onMultiAppAction(
            MultiAppAction.Freeze(
                listOf(
                    blockedSystemApp("com.blocked"),
                    userApp("com.already.frozen", enabled = false),
                    userApp("com.a"),
                    userApp("com.b")
                )
            )
        )
        advanceUntilIdle()

        // The user watches `processed / total`. If `total` counted the whole selection, a run that
        // did everything it could would stop at 2/4 and read as a hang or a half-failure.
        val state = vm.uiState.value.freezeLoggerState
        assertEquals(2, state.total)
        assertEquals(2, state.processed)
        assertEquals(0, state.failed)
        assertTrue(state.isComplete)
    }

    @Test
    fun `an already frozen app is left out of a freeze run`() = runTest {
        val vm = viewModel()

        vm.onMultiAppAction(
            MultiAppAction.Freeze(
                listOf(
                    userApp("com.disabled", enabled = false),
                    userApp("com.suspended", isSuspended = true),
                    userApp("com.active")
                )
            )
        )
        advanceUntilIdle()

        // Freezing an app that is already suspended would stack `disable` on top of `suspend` and
        // leave a mixed state that only the two-step restore can undo.
        assertEquals(listOf("setAppDisabled:com.active:true"), system.calls)
    }

    @Test
    fun `suspend mode is not a way past the blocked tier`() = runTest {
        val vm = viewModel()

        vm.onMultiAppAction(
            MultiAppAction.Freeze(
                listOf(blockedSystemApp("com.blocked"), userApp("com.ok")),
                useSuspend = true
            )
        )
        advanceUntilIdle()

        // The tier filter runs before the mode is consulted, so flipping the Freezer to SUSPEND
        // cannot be used to reach an app that FREEZE refuses.
        assertEquals(listOf("setAppSuspended:com.ok:true"), system.calls)
    }

    @Test
    fun `a bulk unfreeze restores a blocked app instead of skipping it`() = runTest {
        val vm = viewModel()

        vm.onMultiAppAction(MultiAppAction.UnFreeze(listOf(blockedSystemApp("com.blocked", enabled = false))))
        advanceUntilIdle()

        // Reusing the freeze filter for unfreeze is the tempting simplification, and it would strand
        // every blocked app that is already frozen — the exact state a user needs a way out of.
        assertEquals(listOf("setAppDisabled:com.blocked:false"), system.calls)
        assertEquals(1, vm.uiState.value.freezeLoggerState.total)
    }

    @Test
    fun `the freeze counter reports every failure and still finishes the run`() = runTest {
        system.failWith("setAppDisabled:com.b:true", RuntimeException("denied"))
        val vm = viewModel()

        vm.onMultiAppAction(
            MultiAppAction.Freeze(listOf(userApp("com.a"), userApp("com.b"), userApp("com.c")))
        )
        advanceUntilIdle()

        // One refusal must not abort the batch: the apps after it still get their turn.
        assertEquals(
            listOf("setAppDisabled:com.a:true", "setAppDisabled:com.b:true", "setAppDisabled:com.c:true"),
            system.calls
        )
        val state = vm.uiState.value.freezeLoggerState
        assertEquals(3, state.processed)
        assertEquals(1, state.failed)
        assertTrue(state.isComplete)
    }

    @Test
    fun `a freeze run where every app failed does not ask the user for support`() = runTest {
        system.failWith("setAppDisabled:com.a:true", RuntimeException("denied"))
        system.failWith("setAppDisabled:com.b:true", RuntimeException("denied"))
        val vm = viewModel()

        vm.onMultiAppAction(MultiAppAction.Freeze(listOf(userApp("com.a"), userApp("com.b"))))
        advanceUntilIdle()

        // "processed - failed > 0" is the condition, and it has to be a strict inequality: asking
        // for a donation immediately after nothing worked is the worst possible moment to ask.
        assertFalse(vm.uiState.value.showSupportDeveloperPrompt)
    }

    // --- Bulk uninstall: the tier gate and the watchlist -----------------------------------

    @Test
    fun `a bulk uninstall refuses a blocked system app without calling the system`() = runTest {
        val vm = viewModel()

        vm.onMultiAppAction(MultiAppAction.Uninstall(listOf(blockedSystemApp("com.blocked"))))
        advanceUntilIdle()

        assertTrue(system.calls.isEmpty())
        // …and it does not quietly pretend the app is now managed.
        assertTrue(freezer.added.isEmpty())
    }

    @Test
    fun `a system app uninstalled in bulk is put on the watchlist and a user app is not`() = runTest {
        val vm = viewModel()

        vm.onMultiAppAction(
            MultiAppAction.Uninstall(listOf(systemApp("com.sys"), userApp("com.user")))
        )
        advanceUntilIdle()

        assertEquals(listOf("uninstallApp:com.sys", "uninstallApp:com.user"), system.calls)
        // `pm uninstall --user` on a system app is Thor's freeze; the watchlist row is the only
        // record that it can be brought back, so losing it strands the app. A user app is really
        // gone, and a row for it would offer a restore that cannot work.
        assertEquals(listOf("com.sys"), freezer.added)
    }

    @Test
    fun `a failed system uninstall does not put the app on the watchlist`() = runTest {
        system.failWith("uninstallApp:com.sys", RuntimeException("denied"))
        val vm = viewModel()

        vm.onMultiAppAction(MultiAppAction.Uninstall(listOf(systemApp("com.sys"))))
        advanceUntilIdle()

        // A row for an app that is still installed shows it in the Freezer as frozen when it is not.
        assertTrue(freezer.added.isEmpty())
    }

    @Test
    fun `a debuggable app that fails to reinstall is reported as debuggable, not as a raw error`() = runTest {
        system.failWith("reinstallAppWithGoogle:com.debuggable", RuntimeException("INSTALL_FAILED"))
        system.failWith("reinstallAppWithGoogle:com.normal", RuntimeException("INSTALL_FAILED"))
        val vm = viewModel()

        vm.onMultiAppAction(
            MultiAppAction.ReInstall(
                listOf(userApp("com.debuggable", isDebuggable = true), userApp("com.normal"))
            )
        )
        advanceUntilIdle()

        // Fix Store cannot work on a debuggable build, and "INSTALL_FAILED" tells the user nothing
        // actionable. Same underlying failure, two different lines.
        val logs = vm.uiState.value.loggerState.logs
        assertTrue(
            logs.contains(
                UiText.StringResource(
                    R.string.log_failed,
                    UiText.StringResource(R.string.error_debuggable_app)
                )
            )
        )
        assertTrue(logs.contains(UiText.StringResource(R.string.log_failed, "INSTALL_FAILED")))
    }

    // --- Single-app actions ----------------------------------------------------------------

    @Test
    fun `launching a frozen app restores it first and then launches it`() = runTest {
        val vm = viewModel()
        val effects = effectsOf(vm)

        vm.onAppAction(AppClickAction.Launch(userApp("com.frozen", enabled = false)))
        advanceUntilIdle()

        assertEquals(listOf("setAppDisabled:com.frozen:false"), system.calls)
        assertEquals(
            listOf(
                MainSideEffect.Message(UiText.StringResource(R.string.unfreezing_app, "com.frozen")),
                MainSideEffect.LaunchApp("com.frozen")
            ),
            effects
        )
    }

    @Test
    fun `launching a suspended app unsuspends it rather than enabling it`() = runTest {
        val vm = viewModel()
        val effects = effectsOf(vm)

        vm.onAppAction(AppClickAction.Launch(userApp("com.suspended", isSuspended = true)))
        advanceUntilIdle()

        // `pm enable` on a suspended app leaves it suspended, and the launch then opens the system
        // "app paused" dialog instead of the app. The restore has to reverse the dimension that was
        // actually set — and only that one.
        assertEquals(listOf("setAppSuspended:com.suspended:false"), system.calls)
        assertTrue(effects.contains(MainSideEffect.LaunchApp("com.suspended")))
    }

    @Test
    fun `a failed restore reports the error and does not launch the app`() = runTest {
        system.failWith("setAppDisabled:com.frozen:false", RuntimeException("denied"))
        val vm = viewModel()
        val effects = effectsOf(vm)

        vm.onAppAction(AppClickAction.Launch(userApp("com.frozen", enabled = false)))
        advanceUntilIdle()

        // Launching anyway would hand the user a disabled app and an ActivityNotFoundException.
        assertFalse(effects.contains(MainSideEffect.LaunchApp("com.frozen")))
        assertEquals(
            MainSideEffect.Message(UiText.StringResource(R.string.error_format, "denied")),
            effects.last()
        )
    }

    @Test
    fun `launching an active app does not touch the system`() = runTest {
        val vm = viewModel()
        val effects = effectsOf(vm)

        vm.onAppAction(AppClickAction.Launch(userApp("com.active")))
        advanceUntilIdle()

        // No speculative `pm enable` on every tap: the restore is conditional on the app being frozen.
        assertTrue(system.calls.isEmpty())
        assertEquals(listOf(MainSideEffect.LaunchApp("com.active")), effects)
    }

    @Test
    fun `a refusal carrying its own message is shown as that message, not as a bare error`() = runTest {
        // Planted at the gateway on purpose: MainViewModel's single-app Freeze branch calls the
        // ungated ManageAppUseCase primitive and does not read the tier itself, so a refusal it has
        // to render always arrives from below it. (See the note in single-app-freeze-tier-gate.md
        // about a fourth surface reaching this branch without the sheet's dialog in front of it.)
        system.failWith(
            "setAppDisabled:com.blocked:true",
            UiTextException(UiText.StringResource(R.string.error_unsafe_skipped))
        )
        val vm = viewModel()
        val effects = effectsOf(vm)

        vm.onAppAction(AppClickAction.Freeze(blockedSystemApp("com.blocked")))
        advanceUntilIdle()

        // UiTextException carries the whole message and has a null `message`, so the generic branch
        // would render it as "Error: " with nothing after the colon.
        assertEquals(
            listOf(
                MainSideEffect.Message(
                    UiText.StringResource(
                        R.string.error_format,
                        UiText.StringResource(R.string.error_unsafe_skipped)
                    )
                )
            ),
            effects
        )
    }

    @Test
    fun `one action produces exactly one effect`() = runTest {
        val vm = viewModel()
        val effects = effectsOf(vm)

        vm.onAppAction(AppClickAction.Kill(userApp("com.a")))
        advanceUntilIdle()

        // A duplicate here is a double toast, or — on a navigating effect — a screen opened twice.
        assertEquals(1, effects.size)
    }

    @Test
    fun `an effect emitted before the screen subscribes is held, not dropped`() = runTest {
        val vm = viewModel()

        // No collector yet: this is the config-change / early-lifecycle window.
        vm.onAppAction(AppClickAction.Kill(userApp("com.a")))
        advanceUntilIdle()
        assertEquals(listOf("forceStopApp:com.a"), system.calls)

        val effects = effectsOf(vm)
        advanceUntilIdle()

        // The channel is RENDEZVOUS, so the emitter parks rather than dropping the event; the
        // feedback arrives late instead of never. A `replay = 0` SharedFlow here would silently
        // lose it, which is the failure this pins.
        assertEquals(1, effects.size)
    }

    // --- Clear-all-cache safe list ----------------------------------------------------------

    @Test
    fun `clear all cache never clears Thor's own cache or the Play Store's`() = runTest {
        appRepository.apps.value = listOf(
            userApp("com.valhalla.thor"),
            userApp("com.android.vending"),
            userApp("com.example.a")
        )
        val vm = viewModel()

        vm.clearAllCache(AppListType.USER)
        advanceUntilIdle()

        // Clearing Thor's own cache mid-run pulls the rug out from under the run itself; clearing
        // the Play Store's is a known way to break app updates.
        assertEquals(listOf("clearCache:com.example.a"), system.calls)
    }

    @Test
    fun `clear all cache with nothing eligible touches nothing`() = runTest {
        appRepository.apps.value = listOf(userApp("com.valhalla.thor"), userApp("com.android.vending"))
        val vm = viewModel()

        vm.clearAllCache(AppListType.USER)
        advanceUntilIdle()

        // The empty selection must short-circuit rather than start an empty batch that never
        // finishes and leaves the log dialog spinning.
        assertTrue(system.calls.isEmpty())
        assertTrue(vm.uiState.value.loggerState.isComplete)
    }

    @Test
    fun `clear all cache reads the list the tab is showing`() = runTest {
        appRepository.apps.value = listOf(userApp("com.user.app"), systemApp("com.system.app"))
        val vm = viewModel()

        vm.clearAllCache(AppListType.SYSTEM)
        advanceUntilIdle()

        assertEquals(listOf("clearCache:com.system.app"), system.calls)
    }

    // --- The support prompt -----------------------------------------------------------------

    @Test
    fun `the support prompt waits for the log dialog to close`() = runTest {
        val vm = viewModel()

        vm.onMultiAppAction(MultiAppAction.Kill(listOf(userApp("com.a"))))
        advanceUntilIdle()

        // finishLogger() leaves the dialog on screen with a Close button. Raising the prompt now
        // would stack a second dialog over the log the user is still reading.
        assertTrue(vm.uiState.value.loggerState.isVisible)
        assertFalse(vm.uiState.value.showSupportDeveloperPrompt)

        vm.dismissLogger()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.loggerState.isVisible)
        assertTrue(vm.uiState.value.showSupportDeveloperPrompt)
    }

    @Test
    fun `a user who has already seen the support prompt is never asked again`() = runTest {
        val seen = FakePreferenceRepository(UserPreferences(hasShownSupportDeveloperPrompt = true))
        val vm = viewModel(preferenceRepository = seen)

        vm.onMultiAppAction(MultiAppAction.Kill(listOf(userApp("com.a"))))
        advanceUntilIdle()
        vm.dismissLogger()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.showSupportDeveloperPrompt)
    }

    @Test
    fun `marking the support prompt shown persists it so it does not return next launch`() = runTest {
        val vm = viewModel()

        vm.onMultiAppAction(MultiAppAction.Kill(listOf(userApp("com.a"))))
        advanceUntilIdle()
        vm.dismissLogger()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showSupportDeveloperPrompt)

        vm.markSupportDeveloperPromptShown()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.showSupportDeveloperPrompt)
        // Persisted, not just cleared in memory — otherwise it reappears after every process death.
        assertTrue(vm.uiState.value.prefs.hasShownSupportDeveloperPrompt)
    }
}
