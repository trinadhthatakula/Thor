// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.backup

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.data.backup.DataArchiveCapabilityCache
import com.valhalla.thor.data.backup.PassphraseVault
import com.valhalla.thor.data.backup.PassphraseVaultStore
import com.valhalla.thor.data.backup.VaultKeyProvider
import com.valhalla.thor.data.backup.job.JobRegistry
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.KDF_SALT_BYTES
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.domain.repository.AppArchiveStore
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.domain.repository.ArchiveDestination
import com.valhalla.thor.domain.repository.ArchiveJobLauncher
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import com.valhalla.thor.domain.repository.ThorJobStatus
import com.valhalla.thor.domain.usecase.MeasureAppDataUseCase
import com.valhalla.thor.presentation.MainDispatcherRule
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The backup sheet's view model.
 *
 * `StandardTestDispatcher`, not the rule's default `UnconfinedTestDispatcher`: the first test asserts
 * an *intermediate* state — `supported` is null while the probe is still in flight — which an eager
 * dispatcher would have already resolved by the time `start` returns. That is the swap
 * [MainDispatcherRule]'s own KDoc names.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppBackupViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    // --- doubles -------------------------------------------------------------------------------

    /**
     * @param capable what `probeDataArchiveCapability` answers. Only ever reached through
     *   [DataArchiveCapabilityCache], and only when the privilege state has something to probe
     *   through — see [makeMeasure].
     */
    private class FakeProbe(
        val capable: Boolean = true,
        val sizes: Map<DataClass, DataClassSize> = DataClass.entries.associateWith {
            DataClassSize.Known(1024L)
        },
    ) : AppDataProbe {
        override suspend fun probeDataArchiveCapability(): Boolean = capable
        override suspend fun measureDataClass(
            packageName: String,
            dataClass: DataClass,
        ): DataClassSize = sizes[dataClass] ?: DataClassSize.Undetermined
    }

    private class FakePrivilege(initial: PrivilegeState) : PrivilegeStateProvider {
        override val state: StateFlow<PrivilegeState> = MutableStateFlow(initial)
    }

    private class FakeStore(val label: String = "Downloads/Thor") : AppArchiveStore {
        override suspend fun openArchive(fileName: String): ArchiveDestination? = null
        override suspend fun currentTargetLabel(): String = label
        override suspend fun discardOrphans(names: Set<String>): Set<String> = emptySet()
    }

    private class FakeVaultStore(initial: String? = null) : PassphraseVaultStore {
        private val state = MutableStateFlow(initial)
        override val isSet: Flow<Boolean> = state.map { it != null }
        override suspend fun read(): String? = state.value
        override suspend fun write(value: String?) {
            state.value = value
        }
    }

    /**
     * Reversible and trivial: the vault's own wrapping is Task 5's subject, not this one's.
     *
     * `copyOf()` rather than handing the argument straight back, and that is load-bearing.
     * `PassphraseVault.remember` zeroes the plaintext array in a `finally` **after** `wrap` returns,
     * so an identity provider that returns the same array has its own output wiped before it is
     * encoded: the vault then stores a blob of NULs and recalls a passphrase of NULs. Both
     * passphrase tests below failed exactly that way before the copy. It is a property of this
     * double, not of the vault — `AndroidKeystoreVaultKeyProvider.wrap` returns `iv + doFinal(...)`,
     * which is always a fresh array.
     */
    private class PlainKeyProvider : VaultKeyProvider {
        override fun wrap(plaintext: ByteArray): ByteArray = plaintext.copyOf()
        override fun unwrap(blob: ByteArray): ByteArray = blob.copyOf()
    }

    private class FakeLauncher(
        val jobId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000beef"),
        val statuses: MutableStateFlow<ThorJobStatus> = MutableStateFlow(ThorJobStatus.Running),
        val running: MutableStateFlow<UUID?> = MutableStateFlow(null),
        val fail: Boolean = false,
    ) : ArchiveJobLauncher {
        var started: ArchiveBackupRequest? = null
        var startedWith: String? = null

        override suspend fun startBackup(
            request: ArchiveBackupRequest,
            passphrase: CharArray,
        ): UUID? {
            started = request
            startedWith = passphrase.concatToString()
            return if (fail) null else jobId
        }

        override suspend fun startRestore(
            request: ArchiveRestoreRequest,
            passphrase: CharArray,
            salt: ByteArray,
        ): UUID? = null

        override fun status(jobId: UUID): Flow<ThorJobStatus> = statuses
        override fun runningJobFor(kind: ThorJobKind, target: String): Flow<UUID?> = running
    }

    /** A rooted, resolved state: `hasAnyPrivilege` is true, so the cache calls through to the probe. */
    private fun rooted() = PrivilegeState(root = true, active = PrivilegeMode.ROOT, isReady = true)

    /** Nothing granted: `hasAnyPrivilege` is false, so the cache refuses without probing at all. */
    private fun noPrivilege() = PrivilegeState(isReady = true)

    /**
     * A real [DataArchiveCapabilityCache] over fake parts, matching `MeasureAppDataUseCaseTest`.
     *
     * Not a stubbed use case: the cache holds the `hasAnyPrivilege` short-circuit that keeps a sheet
     * open from raising an `su` prompt on an ungranted-Magisk device, and stubbing it away would let
     * this suite pass over a sheet that does raise one.
     */
    private fun makeMeasure(
        probe: AppDataProbe,
        privilegeState: PrivilegeState = rooted(),
    ) = MeasureAppDataUseCase(
        DataArchiveCapabilityCache(probe, FakePrivilege(privilegeState)),
        probe,
    )

    private fun viewModel(
        probe: AppDataProbe = FakeProbe(),
        privilegeState: PrivilegeState = rooted(),
        launcher: ArchiveJobLauncher = FakeLauncher(),
        vaultStore: PassphraseVaultStore = FakeVaultStore(),
        registry: JobRegistry = JobRegistry(),
        archiveStore: AppArchiveStore = FakeStore(),
    ) = AppBackupViewModel(
        measure = makeMeasure(probe, privilegeState),
        archiveStore = archiveStore,
        vault = PassphraseVault(vaultStore, PlainKeyProvider()),
        cipher = AppArchiveCipher(),
        launcher = launcher,
        registry = registry,
    )

    // --- measurement ---------------------------------------------------------------------------

    @Test
    fun `supported is null until the probe answers`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.start("com.example.app", "Example")

        // Not `false`. False means "Thor asked and cannot" and hides the whole sheet body; null means
        // "still asking". Collapsing the two shows the refusal for a frame on every open.
        assertNull(vm.uiState.value.supported)
    }

    @Test
    fun `every class is selected once the measurement lands`() = runTest(dispatcher) {
        // §4.2: "All default on."
        val vm = viewModel()

        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        assertEquals(DataClass.entries.toSet(), vm.uiState.value.selected)
        assertEquals(true, vm.uiState.value.includeBundle)
        assertEquals(true, vm.uiState.value.supported)
    }

    @Test
    fun `an unmeasurable class is still offered and still selected`() = runTest(dispatcher) {
        // Undetermined is not "empty" and not "absent" — `du` may have failed on a directory holding
        // gigabytes. Dropping the checkbox would silently narrow the backup.
        val probe = FakeProbe(sizes = mapOf(DataClass.CE to DataClassSize.Undetermined))
        val vm = viewModel(probe = probe)

        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        assertTrue(DataClass.CE in vm.uiState.value.selected)
        assertEquals(DataClassSize.Undetermined, vm.uiState.value.sizes[DataClass.CE])
    }

    @Test
    fun `an incapable privilege state reports unsupported and measures nothing`() =
        runTest(dispatcher) {
            val vm = viewModel(privilegeState = noPrivilege())

            vm.start("com.example.app", "Example")
            testScheduler.advanceUntilIdle()

            assertEquals(false, vm.uiState.value.supported)
            assertEquals(emptyMap<DataClass, DataClassSize>(), vm.uiState.value.sizes)
        }

    @Test
    fun `a privileged surface that cannot read private data is also unsupported`() =
        runTest(dispatcher) {
            // The other half of the refusal, and the one that needs a probe: plain Shizuku runs as
            // `shell` and holds real privilege, yet cannot read `/data/user/0/<pkg>`. "Has privilege"
            // and "can archive data" are different questions (§6).
            val vm = viewModel(probe = FakeProbe(capable = false))

            vm.start("com.example.app", "Example")
            testScheduler.advanceUntilIdle()

            assertEquals(false, vm.uiState.value.supported)
            assertEquals(emptyMap<DataClass, DataClassSize>(), vm.uiState.value.sizes)
        }

    @Test
    fun `the destination label comes from the store, not from a hardcoded folder`() =
        runTest(dispatcher) {
            val vm = viewModel(archiveStore = FakeStore(label = "SD card/Backups"))

            vm.start("com.example.app", "Example")
            testScheduler.advanceUntilIdle()

            assertEquals("SD card/Backups", vm.uiState.value.destinationLabel)
        }

    // --- selection -----------------------------------------------------------------------------

    @Test
    fun `unticking a class removes it and re-ticking puts it back`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        vm.toggleClass(DataClass.EXTERNAL_MEDIA)
        assertTrue(DataClass.EXTERNAL_MEDIA !in vm.uiState.value.selected)

        vm.toggleClass(DataClass.EXTERNAL_MEDIA)
        assertTrue(DataClass.EXTERNAL_MEDIA in vm.uiState.value.selected)
    }

    @Test
    fun `a backup with nothing ticked cannot be started`() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()
        DataClass.entries.forEach { vm.toggleClass(it) }
        vm.setIncludeBundle(false)

        assertEquals(false, vm.uiState.value.canStart)
        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()

        assertNull(launcher.started)
    }

    @Test
    fun `the bundle alone is a valid backup`() = runTest(dispatcher) {
        // A data-only archive is explicitly supported (§4.2), so its mirror image has to be too: an
        // installer-only archive is a perfectly good "let me reinstall this app later".
        val vm = viewModel()
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()
        DataClass.entries.forEach { vm.toggleClass(it) }

        assertEquals(true, vm.uiState.value.canStart)
    }

    // --- passphrase ----------------------------------------------------------------------------

    @Test
    fun `an empty vault asks for a passphrase`() = runTest(dispatcher) {
        val vm = viewModel(vaultStore = FakeVaultStore(initial = null))

        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        assertEquals(true, vm.uiState.value.passphraseNeeded)
    }

    @Test
    fun `a filled vault does not ask`() = runTest(dispatcher) {
        val vm = viewModel(vaultStore = FakeVaultStore(initial = "d29yZA"))

        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.passphraseNeeded)
    }

    @Test
    fun `use a different passphrase asks again even with a filled vault`() = runTest(dispatcher) {
        val vm = viewModel(vaultStore = FakeVaultStore(initial = "d29yZA"))
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        vm.useDifferentPassphrase()

        assertEquals(true, vm.uiState.value.passphraseNeeded)
    }

    @Test
    fun `the remembered passphrase is what reaches the launcher`() = runTest(dispatcher) {
        val store = FakeVaultStore()
        PassphraseVault(store, PlainKeyProvider()).remember("stored one".toCharArray())
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher, vaultStore = store)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        // No passphrase from the UI: the field was not shown, so there is nothing to pass.
        vm.beginBackup(CharArray(0), remember = false)
        testScheduler.advanceUntilIdle()

        assertEquals("stored one", launcher.startedWith)
    }

    @Test
    fun `a vault blob that cannot be decoded prompts instead of failing the backup`() =
        runTest(dispatcher) {
            // §5.4: a stored passphrase that no longer works means *ask*, never "this archive is
            // broken". The blob here is not valid Base64, so `recall()` fails in the decoder and the
            // key provider is never reached — the sibling test below covers the unwrap branch.
            val store = FakeVaultStore(initial = "not base64 at all !!")
            val launcher = FakeLauncher()
            val vm = viewModel(launcher = launcher, vaultStore = store)
            vm.start("com.example.app", "Example")
            testScheduler.advanceUntilIdle()

            vm.beginBackup(CharArray(0), remember = false)
            testScheduler.advanceUntilIdle()

            assertNull(launcher.started)
            assertEquals(true, vm.uiState.value.passphraseNeeded)
            assertEquals(false, vm.uiState.value.running)
        }

    @Test
    fun `a vault that cannot be unwrapped prompts instead of failing the backup`() =
        runTest(dispatcher) {
            // The Keystore-key-is-gone case: the blob decodes, and `unwrap` is what throws. Valid
            // Base64 on purpose — with a malformed blob the provider is never called and this test
            // would pass over an unwrap failure that was mishandled.
            val store = FakeVaultStore(initial = "d29yZA==")
            val failing = object : VaultKeyProvider {
                override fun wrap(plaintext: ByteArray) = plaintext
                override fun unwrap(blob: ByteArray): ByteArray =
                    throw java.security.GeneralSecurityException()
            }
            val launcher = FakeLauncher()
            val vm = AppBackupViewModel(
                measure = makeMeasure(FakeProbe()),
                archiveStore = FakeStore(),
                vault = PassphraseVault(store, failing),
                cipher = AppArchiveCipher(),
                launcher = launcher,
                registry = JobRegistry(),
            )
            vm.start("com.example.app", "Example")
            testScheduler.advanceUntilIdle()

            vm.beginBackup(CharArray(0), remember = false)
            testScheduler.advanceUntilIdle()

            assertNull(launcher.started)
            assertEquals(true, vm.uiState.value.passphraseNeeded)
            assertEquals(false, vm.uiState.value.running)
        }

    @Test
    fun `remember stores the typed passphrase and not remembering leaves the vault empty`() =
        runTest(dispatcher) {
            val store = FakeVaultStore()
            val vm = viewModel(vaultStore = store)
            vm.start("com.example.app", "Example")
            testScheduler.advanceUntilIdle()

            vm.beginBackup("typed one".toCharArray(), remember = true)
            testScheduler.advanceUntilIdle()
            assertEquals(
                "typed one",
                PassphraseVault(store, PlainKeyProvider()).recall()?.concatToString(),
            )

            val other = FakeVaultStore()
            val vm2 = viewModel(vaultStore = other)
            vm2.start("com.example.app", "Example")
            testScheduler.advanceUntilIdle()
            vm2.beginBackup("not stored".toCharArray(), remember = false)
            testScheduler.advanceUntilIdle()
            assertNull(other.read())
        }

    // --- the request ---------------------------------------------------------------------------

    @Test
    fun `the request carries the selection, the bundle choice and a fresh salt`() =
        runTest(dispatcher) {
            val launcher = FakeLauncher()
            val vm = viewModel(launcher = launcher)
            vm.start("com.example.app", "Example")
            testScheduler.advanceUntilIdle()
            vm.toggleClass(DataClass.EXTERNAL_MEDIA)
            vm.setIncludeBundle(false)

            vm.beginBackup("correct horse".toCharArray(), remember = false)
            testScheduler.advanceUntilIdle()

            val request = launcher.started!!
            assertEquals("com.example.app", request.packageName)
            assertEquals(DataClass.entries.toSet() - DataClass.EXTERNAL_MEDIA, request.classes)
            assertEquals(false, request.includeBundle)
            assertEquals(KDF_SALT_BYTES, request.salt.size)
        }

    @Test
    fun `two backups of the same app get different salts`() = runTest(dispatcher) {
        // One passphrase reused across every archive must not mean one key reused across every
        // archive — the salt is the only thing standing between those two sentences.
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()
        val first = launcher.started!!.salt.toList()

        // The first job has to *finish* before a second is startable — `canStart` is false while
        // `running`, which is the same refusal a second tap gets in the sheet. `dismissResult` alone
        // would leave `running` true and silently make this a comparison of one salt with itself.
        launcher.statuses.value = ThorJobStatus.Succeeded
        testScheduler.advanceUntilIdle()
        vm.dismissResult()
        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()

        assertTrue(first != launcher.started!!.salt.toList())
    }

    // --- progress and outcome ------------------------------------------------------------------

    @Test
    fun `progress published by the job reaches the state`() = runTest(dispatcher) {
        val registry = JobRegistry()
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher, registry = registry)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()
        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()

        registry.publish(
            launcher.jobId,
            ThorJobProgress(ThorJobStage.WRITING, "Example", completed = 5L, total = 10L),
        )
        testScheduler.advanceUntilIdle()

        assertEquals(50, vm.uiState.value.progress?.percent)
        assertEquals(true, vm.uiState.value.running)
    }

    @Test
    fun `an indeterminate stage reports a null percent rather than zero`() = runTest(dispatcher) {
        val registry = JobRegistry()
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher, registry = registry)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()
        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()

        registry.publish(launcher.jobId, ThorJobProgress(ThorJobStage.MEASURING, "Example"))
        testScheduler.advanceUntilIdle()

        // The stage assertion is what makes the percent assertion mean anything: `progress?.percent`
        // is also null when no progress arrived at all, so on its own it would pass over a view model
        // that never collected the registry.
        assertEquals(ThorJobStage.MEASURING, vm.uiState.value.progress?.stage)
        // A determinate bar sitting at 0% for the whole of `tar` is how a working backup gets
        // reported as hung. The sheet renders an indeterminate bar for null.
        assertNull(vm.uiState.value.progress?.percent)
    }

    @Test
    fun `a succeeded job stops the running state and reports success`() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()
        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()

        launcher.statuses.value = ThorJobStatus.Succeeded
        testScheduler.advanceUntilIdle()

        assertEquals(BackupFinish.Succeeded, vm.uiState.value.finished)
        assertEquals(false, vm.uiState.value.running)
    }

    @Test
    fun `a failed job carries the worker's own sentence`() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()
        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()

        launcher.statuses.value = ThorJobStatus.Failed("choose a folder for Thor's backups first")
        testScheduler.advanceUntilIdle()

        assertEquals(
            BackupFinish.Failed("choose a folder for Thor's backups first"),
            vm.uiState.value.finished,
        )
    }

    @Test
    fun `a failure with no reason still reports a failure`() = runTest(dispatcher) {
        // `Result.failure()` with no output data is reachable — WorkManager's own cancellation path
        // produces it. A null reason must not read as "no failure".
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()
        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()

        launcher.statuses.value = ThorJobStatus.Failed(null)
        testScheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.finished is BackupFinish.Failed)
    }

    @Test
    fun `a pruned job is not reported as a failure`() = runTest(dispatcher) {
        // WorkManager prunes finished work, so `Gone` is the ordinary answer for an old id — a sheet
        // reopened long after its backup finished asks about a job that no longer exists. Reporting
        // that as "Backup failed" invents a failure out of a success nobody was watching.
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()
        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()

        launcher.statuses.value = ThorJobStatus.Gone
        testScheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.finished)
        assertEquals(false, vm.uiState.value.running)
    }

    @Test
    fun `dismissing a result does not claim a running job stopped`() = runTest(dispatcher) {
        // `dismissResult` clears the banner, not the job. If it cleared `running` too, `canStart`
        // would go true under a backup that is still writing and the next tap would queue a second
        // one against the same package.
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()
        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()
        assertEquals(true, vm.uiState.value.running)

        vm.dismissResult()

        assertEquals(true, vm.uiState.value.running)
        assertEquals(false, vm.uiState.value.canStart)
    }

    @Test
    fun `a second backup does not open on the first one's progress bar`() = runTest(dispatcher) {
        val registry = JobRegistry()
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher, registry = registry)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()
        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()
        registry.publish(
            launcher.jobId,
            ThorJobProgress(ThorJobStage.WRITING, "Example", completed = 6L, total = 10L),
        )
        launcher.statuses.value = ThorJobStatus.Succeeded
        testScheduler.advanceUntilIdle()
        assertEquals(60, vm.uiState.value.progress?.percent)

        // Asserted before the scheduler runs, on purpose: this is about the frame between the tap and
        // the new job's first publish. A bar still sitting at the last job's 60% in that frame is a
        // progress report for work that has not started.
        vm.beginBackup("correct horse".toCharArray(), remember = false)

        assertNull(vm.uiState.value.progress)
    }

    @Test
    fun `an enqueue that fails does not leave the sheet spinning`() = runTest(dispatcher) {
        val launcher = FakeLauncher(fail = true)
        val vm = viewModel(launcher = launcher)
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        vm.beginBackup("correct horse".toCharArray(), remember = false)
        testScheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.running)
        assertTrue(vm.uiState.value.finished is BackupFinish.Failed)
    }

    @Test
    fun `a job already running for this app is picked up on open`() = runTest(dispatcher) {
        // The rotation case. `jobTag` exists for exactly this; without it the sheet reopens showing
        // an idle Start button over a backup that is still writing, and a second tap queues a
        // duplicate.
        val launcher = FakeLauncher()
        launcher.running.value = launcher.jobId
        val vm = viewModel(launcher = launcher)

        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        assertEquals(true, vm.uiState.value.running)
        assertEquals(false, vm.uiState.value.canStart)
        assertNull(launcher.started)
    }

    @Test
    fun `start is idempotent across recomposition`() = runTest(dispatcher) {
        // `LaunchedEffect(packageName)` re-runs after a configuration change; measuring twice is a
        // pair of `du` sweeps over gigabytes for nothing.
        //
        // Counting `du` sweeps rather than capability probes, and that is load-bearing:
        // `DataArchiveCapabilityCache` memoises `probeDataArchiveCapability` per privilege state, so
        // a probe counter reads 1 whether or not `start` guards itself — it would pass over exactly
        // the bug this test names. `measureDataClass` is not cached, so it counts the real cost.
        var sweeps = 0
        val probe = object : AppDataProbe {
            override suspend fun probeDataArchiveCapability(): Boolean = true

            override suspend fun measureDataClass(
                packageName: String,
                dataClass: DataClass,
            ): DataClassSize {
                sweeps++
                return DataClassSize.Known(1L)
            }
        }
        val vm = viewModel(probe = probe)

        vm.start("com.example.app", "Example")
        vm.start("com.example.app", "Example")
        testScheduler.advanceUntilIdle()

        assertEquals(DataClass.entries.size, sweeps)
    }
}
