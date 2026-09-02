// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.main

import androidx.lifecycle.SavedStateHandle
import com.valhalla.thor.R
import com.valhalla.thor.data.backup.BackupRunner
import com.valhalla.thor.data.backup.job.JobSheetTarget
import com.valhalla.thor.data.backup.job.JobSheetTargets
import com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.Installers
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchRejection
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchResult
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepPhase
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.PrivilegeSweepStatus
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.BackupAppsUseCase
import com.valhalla.thor.domain.usecase.ExportAppUseCase
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.domain.usecase.ShareAppUseCase
import com.valhalla.thor.presentation.FakeAppBundleBuilder
import com.valhalla.thor.presentation.FakeAppBundleFileStore
import com.valhalla.thor.presentation.FakeAppRepository
import com.valhalla.thor.presentation.FakeContext
import com.valhalla.thor.presentation.FakeFreezerRepository
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.presentation.FakePrivilegeSweepController
import com.valhalla.thor.presentation.FakeSystemRepository
import com.valhalla.thor.presentation.FakeUsageAccessGate
import com.valhalla.thor.presentation.MainDispatcherRule
import com.valhalla.thor.presentation.blockedSystemApp
import com.valhalla.thor.presentation.privilegeSweepResolver
import com.valhalla.thor.presentation.systemApp
import com.valhalla.thor.presentation.userApp
import com.valhalla.thor.util.UiText
import com.valhalla.thor.util.UiTextException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

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
 * - a system app uninstalled through Thor must land on the freezer watchlist, because a system app
 *   can only be removed for the current user (`pm uninstall --user`) and so is recoverable with
 *   `pm install-existing` — the watchlist row is the only record that it is;
 * - a refusal that carries its own message must be shown as that message.
 *
 * Every assertion is made against [FakeSystemRepository.calls] — the list of commands that reached
 * the privilege layer — rather than against the returned `Result`. A gate that refuses *after* the
 * command has reached that layer satisfies any assertion on the result alone, and the app is
 * already frozen or already gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var system: FakeSystemRepository
    private lateinit var appRepository: FakeAppRepository
    private lateinit var freezer: FakeFreezerRepository
    private lateinit var prefs: FakePreferenceRepository

    /** Stands in for `Context.cacheDir`: an export stages bundles and its manifest into it. */
    private lateinit var cache: File

    @Before
    fun setUp() {
        system = FakeSystemRepository()
        appRepository = FakeAppRepository()
        freezer = FakeFreezerRepository()
        // Default `UserPreferences` has hasShownSupportDeveloperPrompt = false, i.e. a user who has
        // never seen the donation prompt. The prompt tests below depend on that starting point.
        prefs = FakePreferenceRepository()
        cache = Files.createTempDirectory("thor_cache_").toFile()
    }

    @After
    fun tearDown() {
        cache.deleteRecursively()
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
        preferenceRepository: FakePreferenceRepository = prefs,
        runner: BackupRunner = backupRunner(preferenceRepository),
        systemRepository: SystemRepository = system,
        // Granted by default because that is the case every other test in this file is indifferent
        // to: with the op held, an unmeasured clear is described without mentioning permissions. The
        // one test that cares about the ungranted branch passes false.
        usageAccess: Boolean = true,
        // A real one, not a fake: `JobSheetTargets` is a plain in-memory holder with no Android type
        // on it, so a test can drive a notification tap by calling `requestOpen` on the same instance
        // the view model is watching. Defaulted so the tests that predate it read unchanged.
        sheetTargets: JobSheetTargets = JobSheetTargets(),
        // Only the two stop-reporting tests pass one: they need to act from *inside* the share loop,
        // which is the one moment a test body cannot otherwise reach. See FakeAppBundleBuilder.onBuild.
        bundleBuilder: FakeAppBundleBuilder = FakeAppBundleBuilder(),
        sweepController: FakePrivilegeSweepController = FakePrivilegeSweepController(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): MainViewModel {
        val vm = MainViewModel(
            manageAppUseCase = ManageAppUseCase(systemRepository, DefaultPackageOperationCoordinator()),
            getInstalledAppsUseCase = GetInstalledAppsUseCase(appRepository),
            shareAppUseCase = ShareAppUseCase(
                bundleBuilder,
                FakeAppBundleFileStore(),
                mainDispatcherRule.dispatcher
            ),
            preferenceRepository = preferenceRepository,
            freezerRepository = freezer,
            backupRunner = runner,
            usageAccessGate = FakeUsageAccessGate(usageAccess),
            jobSheetTargets = sheetTargets,
            sweepResolver = privilegeSweepResolver(
                freezerRepository = freezer,
                preferenceRepository = preferenceRepository,
            ),
            sweepController = sweepController,
            savedStateHandle = savedStateHandle,
            ioDispatcher = mainDispatcherRule.dispatcher
        )
        backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.uiState.collect {} }
        return vm
    }

    /**
     * The export runner the view model watches from `init`.
     *
     * A real runner over a fake Context rather than a fake runner: `BackupRunner` is a final Kotlin
     * class like everything else in `data/`, so there is nothing to substitute, and the one Android
     * thing it touches is the cache dir it stages into. Its scope is given the rule's dispatcher, so
     * a run a test starts shares the test's clock instead of escaping onto a real IO thread and
     * finishing after the assertions.
     *
     * Both bundle fakes refuse, so no export in this file can succeed — which is fine, because what
     * is under test here is the handover, not the export. `BackupAppsUseCaseTest` owns the run.
     */
    private fun backupRunner(preferenceRepository: FakePreferenceRepository) = BackupRunner(
        context = FakeContext(cache),
        backupAppsUseCase = BackupAppsUseCase(
            exportAppUseCase = ExportAppUseCase(
                FakeAppBundleBuilder(),
                preferenceRepository,
                FakeAppBundleFileStore(),
                mainDispatcherRule.dispatcher
            ),
            ioDispatcher = mainDispatcherRule.dispatcher
        ),
        io = mainDispatcherRule.dispatcher
    )

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

    // --- Durable selection sweeps -------------------------------------------------------------

    @Test
    fun `freeze selection launches one durable sweep`() = runTest {
        val controller = FakePrivilegeSweepController()
        val vm = viewModel(sweepController = controller)

        vm.onMultiAppAction(
            MultiAppAction.Freeze(
                listOf(
                    blockedSystemApp("blocked"),
                    userApp("already", enabled = false),
                    userApp("z"),
                    userApp("a"),
                ),
                useSuspend = true,
            )
        )
        advanceUntilIdle()

        assertEquals(1, controller.launched.size)
        assertEquals(PrivilegeSweepOperation.FREEZE, controller.launched.single().operation)
        assertEquals(listOf("a", "z"), controller.launched.single().packageNames)
        assertEquals(FreezerMode.SUSPEND, controller.launched.single().freezerMode)
        assertEquals(PrivilegeSweepSource.MAIN, controller.launched.single().source)
        assertTrue(system.calls.isEmpty())
    }

    @Test
    fun `unfreeze selection launches one durable sweep`() = runTest {
        val controller = FakePrivilegeSweepController()
        val vm = viewModel(sweepController = controller)

        vm.onMultiAppAction(
            MultiAppAction.UnFreeze(
                listOf(blockedSystemApp("blocked", enabled = false), userApp("active"))
            )
        )
        advanceUntilIdle()

        assertEquals(1, controller.launched.size)
        assertEquals(PrivilegeSweepOperation.UNFREEZE, controller.launched.single().operation)
        assertEquals(listOf("active", "blocked"), controller.launched.single().packageNames)
        assertEquals(null, controller.launched.single().freezerMode)
        assertTrue(system.calls.isEmpty())
    }

    @Test
    fun `notification rejection clears launch state and shows actionable error`() = runTest {
        val controller = FakePrivilegeSweepController().apply {
            nextLaunchResult = PrivilegeSweepLaunchResult.Rejected(
                PrivilegeSweepLaunchRejection.NotificationsRequired
            )
        }
        val savedState = SavedStateHandle(mapOf("main_sweep_request_id" to UUID(0L, 99L).toString()))
        val vm = viewModel(sweepController = controller, savedStateHandle = savedState)
        val effects = effectsOf(vm)

        vm.onMultiAppAction(MultiAppAction.Freeze(listOf(userApp("a"))))
        advanceUntilIdle()

        assertEquals(null, savedState.get<String>("main_sweep_request_id"))
        assertFalse(vm.uiState.value.freezeLoggerState.isVisible)
        assertEquals(
            listOf(MainSideEffect.Message(UiText.StringResource(R.string.notification_access_needed_subtitle))),
            effects,
        )
    }

    @Test
    fun `queued running partial cancelled and observer failure statuses reach UI`() = runTest {
        val controller = FakePrivilegeSweepController()
        val requestId = UUID(0L, 41L)
        controller.nextLaunchResult = PrivilegeSweepLaunchResult.Accepted(
            requestId,
            UUID(1L, 41L),
            coalesced = false,
        )
        val vm = viewModel(sweepController = controller)
        vm.onMultiAppAction(MultiAppAction.Freeze(listOf(userApp("a"), userApp("b"))))
        advanceUntilIdle()

        val phases = listOf(
            PrivilegeSweepPhase.QUEUED,
            PrivilegeSweepPhase.RUNNING,
            PrivilegeSweepPhase.PARTIAL,
            PrivilegeSweepPhase.CANCELLED,
            PrivilegeSweepPhase.OBSERVER_FAILURE,
        )
        phases.forEach { phase ->
            controller.emit(status(requestId, phase))
            advanceUntilIdle()
            assertEquals(phase, vm.uiState.value.sweepStatus?.phase)
        }
    }

    @Test
    fun `active retained request reconnects without launching duplicate work`() = runTest {
        val controller = FakePrivilegeSweepController()
        val requestId = UUID(0L, 52L)
        controller.emit(status(requestId, PrivilegeSweepPhase.RUNNING))
        val savedState = SavedStateHandle(mapOf("main_sweep_request_id" to requestId.toString()))

        val vm = viewModel(sweepController = controller, savedStateHandle = savedState)
        advanceUntilIdle()

        assertTrue(controller.launched.isEmpty())
        assertEquals(requestId, vm.uiState.value.sweepStatus?.requestId)
    }

    @Test
    fun `explicit suspend and unsuspend remain direct`() = runTest {
        val vm = viewModel()

        vm.onMultiAppAction(MultiAppAction.Suspend(listOf(userApp("a"))))
        vm.onMultiAppAction(MultiAppAction.UnSuspend(listOf(userApp("b"))))
        advanceUntilIdle()

        assertEquals(listOf("setAppSuspended:a:true", "setAppSuspended:b:false"), system.calls)
    }

    private fun status(requestId: UUID, phase: PrivilegeSweepPhase) = PrivilegeSweepStatus(
        requestId = requestId,
        workId = UUID(1L, requestId.leastSignificantBits),
        operation = PrivilegeSweepOperation.FREEZE,
        source = PrivilegeSweepSource.MAIN,
        phase = phase,
        total = 2,
        succeeded = if (phase == PrivilegeSweepPhase.PARTIAL) 1 else 0,
        failed = if (phase == PrivilegeSweepPhase.PARTIAL) 1 else 0,
        busy = 0,
        unresolved = if (phase == PrivilegeSweepPhase.QUEUED) 2 else 0,
        rootLaneDegraded = false,
    )

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
        // Uninstalling a system app can only remove it for the current user (`pm uninstall --user`),
        // so `pm install-existing` can bring it back — and the watchlist row is the only record
        // that it can be, so losing it strands the app. A user app is really gone, and a row for it
        // would offer a restore that cannot work.
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

    /**
     * The watchlist write that used to be process death (fix/freezer-bookkeeping-crashes).
     *
     * The uninstall has already happened when this runs and nothing can undo it. Room reports a full
     * or failing disk by throwing, `FreezerRepositoryImpl` does not catch, and `:app` installs no
     * `CoroutineExceptionHandler` — so unguarded, the row that makes a system app recoverable took
     * the process with it *after* the uninstall succeeded, leaving the logger open, never completed,
     * and a ✔ line as the user's last sight of it.
     *
     * Without the guard this does not fail an assertion, it kills the test's coroutine.
     */
    @Test
    fun `a system uninstall Thor cannot write down still closes its log`() = runTest {
        freezer.failAddWith("com.sys", IllegalStateException("disk is full"))
        val vm = viewModel()

        vm.onAppAction(AppClickAction.Uninstall(systemApp("com.sys")))
        advanceUntilIdle()

        val logs = vm.uiState.value.loggerState.logs
        // The uninstall did not fail, so the line that says it worked stands — and the shortfall is
        // named separately rather than rewriting that line into a failure.
        assertTrue(
            "the successful uninstall is still on the record: $logs",
            logs.contains(UiText.StringResource(R.string.log_uninstall_success))
        )
        assertTrue(
            "and the disk is named: $logs",
            logs.contains(UiText.StringResource(R.string.log_error, "disk is full"))
        )
        // The half that was process death: the run reaching its end at all.
        assertTrue("the logger completed", vm.uiState.value.loggerState.isComplete)
    }

    /**
     * The same hole at the batch entry point, which is what makes this a class and not a special
     * case. The middle app's row is the one that fails, so the test also pins that the run does not
     * abandon the apps behind it.
     *
     * **Two** failures, not one, and with different messages. One failure cannot tell a collapsed
     * report from a per-app one — both print the line once — so `assertEquals(1, …)` would have been
     * a statement about the rig rather than about the code. Distinct messages then say which of the
     * two survived: the production code takes `unrecorded.firstOrNull()`, so the second app's error
     * must be absent, not merely un-repeated.
     */
    @Test
    fun `a batch uninstall carries on past a row it cannot write and reports it once`() = runTest {
        freezer.failAddWith("com.b", IllegalStateException("disk is full"))
        freezer.failAddWith("com.c", IllegalStateException("database is locked"))
        val vm = viewModel()

        vm.onMultiAppAction(
            MultiAppAction.Uninstall(
                listOf(systemApp("com.a"), systemApp("com.b"), systemApp("com.c"))
            )
        )
        advanceUntilIdle()

        assertEquals(
            "all three are uninstalled — the throw is bookkeeping, not the act",
            listOf("uninstallApp:com.a", "uninstallApp:com.b", "uninstallApp:com.c"),
            system.calls
        )
        assertEquals("and the one that could be written down was", listOf("com.a"), freezer.added)
        val logs = vm.uiState.value.loggerState.logs
        assertEquals(
            "one closing line, not one per app: it is the same disk each time",
            1,
            logs.count { it == UiText.StringResource(R.string.log_error, "disk is full") }
        )
        assertEquals(
            "and it is the first throw's message that survives, not the last: $logs",
            0,
            logs.count { it == UiText.StringResource(R.string.log_error, "database is locked") }
        )
        // Where the line sits, which is the reason it is collected instead of printed in place: it
        // closes the run rather than interrupting a step, and the dialog auto-scrolls to it.
        assertTrue(
            "the shortfall is reported after the batch finishes, not spliced into it: $logs",
            logs.indexOf(UiText.StringResource(R.string.log_op_complete)) <
                logs.indexOf(UiText.StringResource(R.string.log_error, "disk is full"))
        )
        assertTrue("the logger completed", vm.uiState.value.loggerState.isComplete)
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

    // --- Bulk export -------------------------------------------------------------------------

    @Test
    fun `a bulk export is handed off and its outcome reported exactly once`() = runTest {
        val vm = viewModel()
        val effects = effectsOf(vm)

        vm.onMultiAppAction(MultiAppAction.Backup(listOf(userApp("com.a"), userApp("com.b"))))
        advanceUntilIdle()

        // An export only reads public APK paths. A batch that reached the privilege layer would be
        // doing something to apps it was asked to copy.
        assertTrue(system.calls.isEmpty())
        // And it does not open either log dialog. `TermLoggerDialog` refuses to dismiss before the
        // run completes, so reusing it here would pin the user to a screen for the length of a run
        // that is explicitly designed to outlive that screen.
        assertFalse(vm.uiState.value.loggerState.isVisible)
        assertFalse(vm.uiState.value.freezeLoggerState.isVisible)
        // Exactly one, whatever the outcome was: the completions collector is the only reporter.
        // Awaiting the Deferred `start` returns as well — the obvious way to "make sure" the result
        // is seen — toasts the same run twice.
        assertEquals(1, effects.size)
        assertTrue(effects.single() is MainSideEffect.Message)
    }

    @Test
    fun `a finished run is not re-reported to the next view model over the same runner`() = runTest {
        // The runner replays its last completion so a run that outlived its UI can still report.
        // Left unacknowledged, that same replay makes the outcome permanent: rotate the device an
        // hour later and the finished export announces itself again — support prompt included.
        // The runner is the singleton here and the view model is the thing being recreated, so
        // only the runner can know the outcome has already been shown.
        val runner = backupRunner(prefs)
        val first = viewModel(runner = runner)
        val firstEffects = effectsOf(first)

        first.onMultiAppAction(MultiAppAction.Backup(listOf(userApp("com.a"))))
        advanceUntilIdle()
        assertEquals(1, firstEffects.size)

        val second = viewModel(runner = runner)
        val secondEffects = effectsOf(second)
        advanceUntilIdle()

        assertEquals(emptyList<MainSideEffect>(), secondEffects)
    }

    @Test
    fun `an outcome nobody saw is kept for the next view model`() = runTest {
        // The other half of the same rule, and the reason acknowledgement is guarded rather than
        // unconditional. `effect` is a rendezvous channel: with nothing collecting it, a `send`
        // parks instead of buffering, so a run that finishes while the UI is detached has
        // delivered exactly nothing. Consuming in a plain `finally` would still mark it seen, and
        // the export the user started would end in silence — the failure mode the replay exists
        // to prevent, reintroduced by the fix for the opposite one.
        //
        // It is also the only test here that builds a view model with a completion *already*
        // waiting, which is the arrangement that caught `_effect` being declared below `init`:
        // the replayed outcome reaches the collector inline during construction, before the
        // channel field is assigned.
        val runner = backupRunner(prefs)
        // Deliberately no `effectsOf`: this view model is the detached UI.
        val first = viewModel(runner = runner)

        first.onMultiAppAction(MultiAppAction.Backup(listOf(userApp("com.a"))))
        advanceUntilIdle()

        val second = viewModel(runner = runner)
        val secondEffects = effectsOf(second)
        advanceUntilIdle()

        assertEquals(1, secondEffects.size)
        assertTrue(secondEffects.single() is MainSideEffect.Message)
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
        //
        // The carried message alone, with no `error_format` around it. This assertion used to expect
        // that StringResource nested *inside* error_format, which was the defect and not the fix:
        // String.format has no idea what a UiText is, so it called toString(), and the message this
        // test is named after reached the user as
        // "Error: com.valhalla.thor.util.UiText$StringResource@4f2a1c" — obfuscated further in
        // release. Unwrapped it is also the right sentence, a refusal being one already: prefixing it
        // would read "Error: Skipped: …".
        assertEquals(
            listOf(MainSideEffect.Message(UiText.StringResource(R.string.error_unsafe_skipped))),
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

    // --- Clear-all-cache ----------------------------------------------------------------------
    //
    // The old block here asserted a safe list — never Thor, never the Play Store — and a USER/SYSTEM
    // split. Both are gone with the per-package loop they described: `pm trim-caches` hands victim
    // selection to PackageManagerService, which evicts by LRU across the whole volume and takes no
    // package argument. A test asserting Thor still spares the Play Store would be asserting a
    // promise the platform never let Thor make.

    @Test
    fun `the tile asks before it clears anything`() = runTest {
        val vm = viewModel()

        vm.requestClearAllCaches()
        advanceUntilIdle()

        // The tap must not reach the repository. Every app on the device, system apps included, is
        // not something to start from a single tap on a home-screen tile.
        assertEquals(CacheClearState.Confirming, vm.uiState.value.cacheClear)
        assertTrue(system.calls.isEmpty())
    }

    @Test
    fun `confirming runs one whole-device clear, not a per-package walk`() = runTest {
        appRepository.apps.value = listOf(userApp("com.a"), userApp("com.b"), systemApp("com.c"))
        val vm = viewModel()

        vm.requestClearAllCaches()
        vm.confirmClearAllCaches()
        advanceUntilIdle()

        // One call regardless of how many apps are installed — and `clearAllCaches`, not
        // `clearCache`, which is the root-only per-app operation.
        assertEquals(listOf("clearAllCaches"), system.calls)
    }

    @Test
    fun `the result carries the bytes freed`() = runTest {
        system.cacheFreedBytes = 18_874_368L
        val vm = viewModel()

        vm.requestClearAllCaches()
        vm.confirmClearAllCaches()
        advanceUntilIdle()

        assertEquals(
            CacheClearState.Done(18_874_368L, hasUsageAccess = true),
            vm.uiState.value.cacheClear
        )
    }

    /**
     * A byte count arrived, so the op is held by definition — the gate is not consulted and the flag
     * does not go false behind a real measurement. Belt and braces: a `Done` carrying both a number
     * and `hasUsageAccess = false` would render as "grant usage access" *and* have a figure to show.
     */
    @Test
    fun `a measured clear does not ask the gate what it already knows`() = runTest {
        system.cacheFreedBytes = 4_096L
        val vm = viewModel(usageAccess = false)

        vm.requestClearAllCaches()
        vm.confirmClearAllCaches()
        advanceUntilIdle()

        assertEquals(CacheClearState.Done(4_096L, hasUsageAccess = true), vm.uiState.value.cacheClear)
    }

    @Test
    fun `an unmeasured clear still reports Done, with a null count`() = runTest {
        // `cacheFreedBytes` defaults to null: the clear worked and the measurement did not. Reporting
        // 0 there would read as "that freed nothing".
        val vm = viewModel()

        vm.requestClearAllCaches()
        vm.confirmClearAllCaches()
        advanceUntilIdle()

        assertEquals(CacheClearState.Done(null, hasUsageAccess = true), vm.uiState.value.cacheClear)
    }

    /**
     * The same empty result, and a different sentence on the sheet. With the op missing there is
     * something the user can do about it; with the op held — an app refilling its cache mid-clear,
     * say — telling them to grant what they already granted is the one piece of advice on screen and
     * it is unfollowable.
     */
    @Test
    fun `an unmeasured clear records whether usage access was the reason`() = runTest {
        val vm = viewModel(usageAccess = false)

        vm.requestClearAllCaches()
        vm.confirmClearAllCaches()
        advanceUntilIdle()

        assertEquals(CacheClearState.Done(null, hasUsageAccess = false), vm.uiState.value.cacheClear)
    }

    @Test
    fun `a failed clear closes the sheet and says why`() = runTest {
        system.failWith("clearAllCaches", IllegalStateException("no privileged gateway"))
        val vm = viewModel()
        val effects = effectsOf(vm)

        vm.requestClearAllCaches()
        vm.confirmClearAllCaches()
        advanceUntilIdle()

        // No Done(null): that is the "it worked, we could not measure it" answer, and showing it
        // here would report a failure as a success with a missing number.
        assertNull(vm.uiState.value.cacheClear)
        // The whole effect, not just its arity: a success message is also one effect, and the point
        // of this path is that the reason reaches the user rather than a silent sheet close.
        //
        // The reason goes in as a String. It used to go in as a DynamicString, and DynamicString is a
        // data class, so String.format's toString() fallback turned the sentence that "says why" into
        // "Error: DynamicString(value=no privileged gateway)".
        assertEquals(
            listOf(
                MainSideEffect.Message(
                    UiText.StringResource(R.string.error_format, "no privileged gateway")
                )
            ),
            effects
        )
    }

    @Test
    fun `confirming twice does not start a second clear`() = runTest {
        val vm = viewModel()

        vm.requestClearAllCaches()
        vm.confirmClearAllCaches()
        vm.confirmClearAllCaches()
        advanceUntilIdle()

        // The sheet's Proceed button is gone while Running, but the state is public and the coroutine
        // is not instantaneous; a double-tap landing in the same frame must not queue two trims.
        assertEquals(listOf("clearAllCaches"), system.calls)
    }

    @Test
    fun `dismissing the confirmation clears nothing and raises no prompt`() = runTest {
        val vm = viewModel()

        vm.requestClearAllCaches()
        vm.dismissCacheClear()
        advanceUntilIdle()

        assertNull(vm.uiState.value.cacheClear)
        assertTrue(system.calls.isEmpty())
        assertFalse(vm.uiState.value.showSupportDeveloperPrompt)
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

    // --- Fix Store: the picker ----------------------------------------------------------------

    @Test
    fun `fix store opens a picker instead of reinstalling everything it found`() = runTest {
        appRepository.apps.value = listOf(
            userApp("com.play", installerPackageName = Installers.PLAY_STORE),
            userApp("com.sideloaded", installerPackageName = null),
            userApp("com.fdroid", installerPackageName = Installers.F_DROID)
        )
        val vm = viewModel()

        vm.onAppAction(AppClickAction.ReinstallAll)
        advanceUntilIdle()

        // The old action ran the batch straight off the scan. Nothing may reach the privilege layer
        // until the picker is confirmed.
        assertTrue(system.calls.isEmpty())
        val picker = vm.uiState.value.fixStoreSelection!!
        assertEquals(
            setOf("com.sideloaded", "com.fdroid"),
            picker.candidates.map { it.packageName }.toSet()
        )
        // Everything starts ticked: the accident being prevented is not knowing what it would
        // touch, not tapping Confirm by mistake.
        assertEquals(picker.candidates.map { it.packageName }.toSet(), picker.selected)
    }

    @Test
    fun `fix store reinstalls only what is still ticked`() = runTest {
        appRepository.apps.value = listOf(
            userApp("com.keep", installerPackageName = null),
            userApp("com.fix", installerPackageName = null)
        )
        val vm = viewModel()

        vm.onAppAction(AppClickAction.ReinstallAll)
        advanceUntilIdle()
        vm.toggleFixStoreTarget("com.keep")
        vm.confirmFixStore()
        advanceUntilIdle()

        assertEquals(listOf("reinstallAppWithGoogle:com.fix"), system.calls)
        assertNull(vm.uiState.value.fixStoreSelection)
    }

    @Test
    fun `confirming an empty selection starts no run`() = runTest {
        appRepository.apps.value = listOf(userApp("com.a", installerPackageName = null))
        val vm = viewModel()

        vm.onAppAction(AppClickAction.ReinstallAll)
        advanceUntilIdle()
        vm.setAllFixStoreTargets(false)
        vm.confirmFixStore()
        advanceUntilIdle()

        // The confirm button is disabled at zero, so this is the state moving out from under a
        // click rather than a route a user can take on purpose — a batch of nothing is still wrong.
        assertTrue(system.calls.isEmpty())
        assertFalse(vm.uiState.value.loggerState.isVisible)
    }

    @Test
    fun `dismissing the picker leaves every app alone`() = runTest {
        appRepository.apps.value = listOf(userApp("com.a", installerPackageName = null))
        val vm = viewModel()

        vm.onAppAction(AppClickAction.ReinstallAll)
        advanceUntilIdle()
        vm.dismissFixStorePicker()
        advanceUntilIdle()

        assertNull(vm.uiState.value.fixStoreSelection)
        assertTrue(system.calls.isEmpty())
    }

    @Test
    fun `fix store finds nothing when every app came from Play`() = runTest {
        appRepository.apps.value = listOf(
            userApp("com.play", installerPackageName = Installers.PLAY_STORE)
        )
        val vm = viewModel()

        vm.onAppAction(AppClickAction.ReinstallAll)
        advanceUntilIdle()

        // No picker; the logger says so and stays up to be read.
        assertNull(vm.uiState.value.fixStoreSelection)
        assertTrue(vm.uiState.value.loggerState.isVisible)
        assertTrue(vm.uiState.value.loggerState.isComplete)
    }

    // --- Stopping a batch part-way ------------------------------------------------------------

    @Test
    fun `a stop request ends the batch after the app in flight`() = runTest {
        val vm = viewModel()
        // Asked for from inside the first app's call, which is the only moment a test has: the
        // batch would otherwise run to completion before control came back.
        system.onCall = { vm.requestStopBatch() }

        vm.onMultiAppAction(
            MultiAppAction.Kill(listOf(userApp("com.a"), userApp("com.b"), userApp("com.c")))
        )
        advanceUntilIdle()

        // com.a completed — stopping mid-command is what leaves a package half-written — and
        // nothing after it was started.
        assertEquals(listOf("forceStopApp:com.a"), system.calls)
        assertTrue(vm.uiState.value.loggerState.isComplete)
        assertFalse(vm.uiState.value.loggerState.isStopping)
    }

    @Test
    fun `a batch that was not stopped runs every app`() = runTest {
        val vm = viewModel()

        vm.onMultiAppAction(
            MultiAppAction.Kill(listOf(userApp("com.a"), userApp("com.b"), userApp("com.c")))
        )
        advanceUntilIdle()

        assertEquals(
            listOf("forceStopApp:com.a", "forceStopApp:com.b", "forceStopApp:com.c"),
            system.calls
        )
    }

    @Test
    fun `a one-app batch offers no stop`() = runTest {
        val vm = viewModel()

        vm.onMultiAppAction(MultiAppAction.Kill(listOf(userApp("com.a"))))
        advanceUntilIdle()

        // A single app has no halfway point: by the time the button could be pressed the work is
        // done, so offering it would only ever be a lie.
        assertFalse(vm.uiState.value.loggerState.canStop)
    }

    @Test
    fun `a stop asked for after the run finished is ignored`() = runTest {
        val vm = viewModel()

        vm.onMultiAppAction(MultiAppAction.Kill(listOf(userApp("com.a"), userApp("com.b"))))
        advanceUntilIdle()
        vm.requestStopBatch()

        // The completed dialog must not flip back into "stopping" — and, more importantly, the
        // flag must not survive to cut the *next* batch short at its first app.
        assertFalse(vm.uiState.value.loggerState.isStopping)

        vm.onMultiAppAction(MultiAppAction.Kill(listOf(userApp("com.c"), userApp("com.d"))))
        advanceUntilIdle()

        assertEquals(
            listOf("forceStopApp:com.a", "forceStopApp:com.b", "forceStopApp:com.c", "forceStopApp:com.d"),
            system.calls
        )
    }

    @Test
    fun `a stop that arrives during the last app does not report a stopped batch`() = runTest {
        val vm = viewModel()
        // The last app is the only one that can tell `processed < apps.size` apart from
        // `stopRequested`: the flag is read *after* the loop, so a stop arriving while the final call
        // is in flight sets it with every app already done.
        system.onCall = { if (it == "forceStopApp:com.b") vm.requestStopBatch() }

        vm.onMultiAppAction(MultiAppAction.Kill(listOf(userApp("com.a"), userApp("com.b"))))
        advanceUntilIdle()

        // Nothing was skipped, so nothing may say it was — "Stopped: 2 of 2" is a complete run
        // describing itself as an interrupted one, and the user's next question is what got lost.
        assertEquals(listOf("forceStopApp:com.a", "forceStopApp:com.b"), system.calls)
        assertFalse(
            "a batch that finished every app reported itself stopped",
            vm.uiState.value.loggerState.logs.any {
                it is UiText.StringResource && it.resId == R.string.log_stopped
            }
        )
    }

    @Test
    fun `a stop that skipped apps reports how many were done`() = runTest {
        val vm = viewModel()
        system.onCall = { if (it == "forceStopApp:com.a") vm.requestStopBatch() }

        vm.onMultiAppAction(
            MultiAppAction.Kill(listOf(userApp("com.a"), userApp("com.b"), userApp("com.c")))
        )
        advanceUntilIdle()

        // The other half of the gate: two apps really were skipped, and the count has to be the one
        // the loop reached, not the size of the selection.
        assertEquals(listOf("forceStopApp:com.a"), system.calls)
        assertTrue(
            vm.uiState.value.loggerState.logs.contains(
                UiText.StringResource(R.string.log_stopped, 1, 3)
            )
        )
    }

    @Test
    fun `a stop during the last shared app does not report a stopped batch either`() = runTest {
        // The share branch keeps its own copy of the batch loop, so it needs its own pin. Driven from
        // the bundle builder rather than the system fake because sharing never reaches the privilege
        // layer — it stages files.
        // Assigned after construction because the builder is a constructor argument of the thing it
        // has to call back into.
        var stopper: MainViewModel? = null
        val builder = FakeAppBundleBuilder { app ->
            if (app.packageName == "com.b") stopper?.requestStopBatch()
        }
        val vm = viewModel(bundleBuilder = builder)
        stopper = vm

        vm.onMultiAppAction(MultiAppAction.Share(listOf(userApp("com.a"), userApp("com.b"))))
        advanceUntilIdle()

        // Both bundles fail here (no device), so the logger stays up to be read instead of being
        // dismissed for a share sheet — which is what makes this assertion possible at all.
        assertFalse(
            vm.uiState.value.loggerState.logs.any {
                it is UiText.StringResource && it.resId == R.string.log_stopped
            }
        )
    }

    // --- Job sheets ---------------------------------------------------------------------------
    //
    // A tap on a running job's notification has to end with that job's sheet on screen. The tap
    // itself lands in a trampoline activity that needs an Android runtime, so what is pinned here is
    // the half that does not: a request published to `JobSheetTargets` becomes sheet state, the right
    // one, and it is not lost if it arrives before the view model exists.

    /** Stands in for the publishing worker's `WorkSpec` id. `JobSheetTargetsTest` covers what it is for. */
    private fun jobId(n: Int) = UUID(0L, n.toLong())

    @Test
    fun `a restore request opens the restore sheet on that archive`() = runTest {
        val targets = JobSheetTargets()
        targets.set(jobId(1), JobSheetTarget.Restore("content://docs/tree/1/thor.thorbak"))
        val vm = viewModel(sheetTargets = targets)

        targets.requestOpen(ThorJobKind.ARCHIVE_RESTORE)
        advanceUntilIdle()

        assertEquals(
            RestoreSheetState("content://docs/tree/1/thor.thorbak"),
            vm.uiState.value.restoreSheet
        )
        // Not the other sheet. Two nullable fields driven by one `when`, so a mis-mapped arm would
        // put a restore's URI behind a backup sheet with no package name to show.
        assertNull(vm.uiState.value.backupSheet)
    }

    @Test
    fun `a backup request opens the backup sheet with the label the worker resolved`() = runTest {
        val targets = JobSheetTargets()
        targets.set(jobId(1), JobSheetTarget.Backup("com.supercell.clashofclans", "Clash of Clans"))
        val vm = viewModel(sheetTargets = targets)

        targets.requestOpen(ThorJobKind.ARCHIVE_BACKUP)
        advanceUntilIdle()

        // The label is carried, not re-derived: `AppBackupViewModel.start` writes what it is handed
        // straight into its own state, so a package name arriving here is a package name on screen.
        assertEquals(
            BackupSheetState("com.supercell.clashofclans", "Clash of Clans"),
            vm.uiState.value.backupSheet
        )
        assertNull(vm.uiState.value.restoreSheet)
    }

    @Test
    fun `a request made before the view model exists is not lost`() = runTest {
        val targets = JobSheetTargets()
        targets.set(jobId(1), JobSheetTarget.Restore("content://docs/tree/1/thor.thorbak"))

        // The ordinary case, not an edge case: the tap is what brings Thor forward, so the request is
        // published while nothing is collecting. `JobSheetTargets` conflates rather than dropping —
        // a `replay = 0` SharedFlow here would send this to no one and the tap would do nothing.
        targets.requestOpen(ThorJobKind.ARCHIVE_RESTORE)

        val vm = viewModel(sheetTargets = targets)
        advanceUntilIdle()

        assertEquals(
            RestoreSheetState("content://docs/tree/1/thor.thorbak"),
            vm.uiState.value.restoreSheet
        )
    }

    @Test
    fun `a tap on a job that is no longer live opens nothing`() = runTest {
        val targets = JobSheetTargets()
        val vm = viewModel(sheetTargets = targets)

        // Nothing was ever published, or the worker's `finally` has already cleared it. The
        // trampoline reads the false and resumes the app without a sheet; here the point is that no
        // *empty* sheet is opened in its place.
        assertFalse(targets.requestOpen(ThorJobKind.ARCHIVE_RESTORE))
        advanceUntilIdle()

        assertNull(vm.uiState.value.restoreSheet)
        assertNull(vm.uiState.value.backupSheet)
    }

    @Test
    fun `the thorbak a launch was opened on opens the restore sheet once`() = runTest {
        val vm = viewModel()

        vm.openRestoreSheetForLaunchUri("content://docs/tree/1/thor.thorbak")
        advanceUntilIdle()

        assertEquals(
            RestoreSheetState("content://docs/tree/1/thor.thorbak"),
            vm.uiState.value.restoreSheet
        )

        // The recomposition case: `MainScreen`'s LaunchedEffect re-runs with the same non-null
        // `pendingRestoreUri` after the user has dismissed the sheet. It must not come back.
        vm.dismissRestoreSheet()
        vm.openRestoreSheetForLaunchUri("content://docs/tree/1/thor.thorbak")
        advanceUntilIdle()

        assertNull(vm.uiState.value.restoreSheet)
    }

    @Test
    fun `a fresh view model reopens the launch thorbak, because a killed process kept the intent`() = runTest {
        val first = viewModel()
        first.openRestoreSheetForLaunchUri("content://docs/tree/1/thor.thorbak")
        advanceUntilIdle()

        // Process death, then a return through Recents: the activity is recreated with the same VIEW
        // intent and a still-valid task-scoped read grant, and gets a new view model. The latch has to
        // come back with it, which is the whole reason it does not live in a `rememberSaveable` — that
        // survives process death while the sheet state does not, so the archive was silently dropped.
        val second = viewModel()
        second.openRestoreSheetForLaunchUri("content://docs/tree/1/thor.thorbak")
        advanceUntilIdle()

        assertEquals(
            RestoreSheetState("content://docs/tree/1/thor.thorbak"),
            second.uiState.value.restoreSheet
        )
    }

    @Test
    fun `dismissing a sheet closes it and leaves the other alone`() = runTest {
        val targets = JobSheetTargets()
        val vm = viewModel(sheetTargets = targets)

        vm.openRestoreSheet("content://docs/tree/1/thor.thorbak")
        targets.set(jobId(1), JobSheetTarget.Backup("com.a", "App A"))
        targets.requestOpen(ThorJobKind.ARCHIVE_BACKUP)
        advanceUntilIdle()

        vm.dismissRestoreSheet()
        advanceUntilIdle()

        assertNull(vm.uiState.value.restoreSheet)
        assertEquals(BackupSheetState("com.a", "App A"), vm.uiState.value.backupSheet)

        vm.dismissBackupSheet()
        advanceUntilIdle()

        assertNull(vm.uiState.value.backupSheet)
    }

    @Test
    fun `the Settings row opens the sheet with no archive chosen`() = runTest {
        val vm = viewModel()

        vm.openRestoreSheet()
        advanceUntilIdle()

        // Open, and open on the file picker. The outer null means "closed", so this has to be a
        // present state holding a null URI — collapsing the two would make the Settings row and a
        // dismissed sheet indistinguishable.
        assertEquals(RestoreSheetState(null), vm.uiState.value.restoreSheet)
    }
}
