// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.backup

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.data.backup.PassphraseVault
import com.valhalla.thor.data.backup.PassphraseVaultStore
import com.valhalla.thor.data.backup.VaultKeyProvider
import com.valhalla.thor.data.backup.job.JobRegistry
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveBundleInfo
import com.valhalla.thor.domain.model.ArchiveCompression
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveKdf
import com.valhalla.thor.domain.model.ArchiveMember
import com.valhalla.thor.domain.model.ArchiveRestoreRefusal
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.ArchiveRestoreWarning
import com.valhalla.thor.domain.model.ClassEntries
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.THORBAK_HEADER_ENTRY
import com.valhalla.thor.domain.model.TarOutcome
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.domain.repository.AppDataArchiveGateway
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.domain.repository.ArchiveBreadcrumb
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import com.valhalla.thor.domain.repository.ArchiveJobLauncher
import com.valhalla.thor.domain.repository.ArchiveSource
import com.valhalla.thor.domain.repository.ArchiveSourceFactory
import com.valhalla.thor.domain.repository.ThorJobStatus
import com.valhalla.thor.domain.usecase.OpenArchiveUseCase
import com.valhalla.thor.domain.usecase.ReadInstalledAppFactsUseCase
import com.valhalla.thor.presentation.FakeAppRepository
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveRestoreViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val cipher = AppArchiveCipher()
    private val salt = ByteArray(16) { it.toByte() }
    private val passphrase = "correct horse"

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // --- the archive under test ------------------------------------------------------------------

    /**
     * `iterations = 1000`, not [com.valhalla.thor.data.backup.KDF_ITERATIONS].
     *
     * `unlock` derives with whatever the header declares, so a low count keeps this suite fast — at
     * 210 000 rounds the derivations in these tests would add several seconds. The production floor is
     * Task 4's concern; what this file tests is which *answer* a derivation produces.
     */
    private fun header(
        packageName: String = "com.example.game",
        versionCode: Long = 100L,
        signer: String = SIGNER,
        classes: List<DataClass> = listOf(DataClass.CE, DataClass.DE),
        bundle: ArchiveBundleInfo? = ArchiveBundleInfo(
            bytes = 4_096L,
            obbCapture = "present",
            obbCount = 2,
        ),
        schemaVersion: Int = 1,
    ): ArchiveHeader {
        val key = cipher.deriveKey(passphrase.toCharArray(), salt, 1000)
        return ArchiveHeader(
            schemaVersion = schemaVersion,
            createdAt = 1_700_000_000_000L,
            thorVersionCode = 1950,
            packageName = packageName,
            versionCode = versionCode,
            versionName = "1.0",
            userId = 0,
            signerSha256 = signer,
            appBundle = bundle,
            kdf = ArchiveKdf(iterations = 1000, salt = Base64.getEncoder().encodeToString(salt)),
            verifier = Base64.getEncoder().encodeToString(cipher.verifier(key)),
            members = classes.map {
                ArchiveMember(
                    dataClass = it.id,
                    fileName = "${it.id}.tar.gz.enc",
                    nonce = Base64.getEncoder().encodeToString(ByteArray(8)),
                    plainBytes = 2_048L,
                    chunkCount = 1,
                    compression = ArchiveCompression.GZIP.id,
                )
            },
        )
    }

    private companion object {
        const val SIGNER = "ABABABABABABABABABABABABABABABABABABABABABABABABABABABABABABABAB"
        const val URI = "content://com.example.docs/document/1"
    }

    // --- doubles -------------------------------------------------------------------------------

    private class FakeSource(
        private val headerJson: String?,
        override val displayName: String = "com.example.game-100.thorbak",
    ) : ArchiveSource {
        var closed = false
        override fun entryNames(): List<String> = listOfNotNull(headerJson?.let { THORBAK_HEADER_ENTRY })
        override fun openEntry(name: String): InputStream? =
            if (name == THORBAK_HEADER_ENTRY && headerJson != null) {
                ByteArrayInputStream(headerJson.encodeToByteArray())
            } else {
                null
            }

        override fun close() {
            closed = true
        }
    }

    private class FakeSources(val source: FakeSource?) : ArchiveSourceFactory {
        override suspend fun open(uriString: String): ArchiveSource? = source
    }

    /**
     * Two files behind two URIs, recording every open in order.
     *
     * [FakeSources] cannot express either half of what the second file test needs: it ignores the URI
     * it is handed, so a view model that re-reads the *wrong* file and one that reads the right one
     * look identical through it, and it counts nothing, so "opened once" is unobservable.
     */
    private class CountingSources(private val byUri: Map<String, FakeSource>) : ArchiveSourceFactory {
        val opens = mutableListOf<String>()
        override suspend fun open(uriString: String): ArchiveSource? {
            opens += uriString
            return byUri[uriString]
        }
    }

    private class FakeProbe(val capable: Boolean = true) : AppDataProbe {
        override suspend fun probeDataArchiveCapability(): Boolean = capable
        override suspend fun measureDataClass(
            packageName: String,
            dataClass: DataClass,
        ): DataClassSize = DataClassSize.Undetermined
    }

    /**
     * Only [signerSha256] is exercised here; the rest are inert.
     *
     * A third hand-written copy of this fake (Tasks 9 and 14 have the others). Task 18 carries a
     * follow-up row to hoist one shared double — deliberately not done mid-plan, because it would
     * reopen two already-green test files.
     */
    private class FakeArchiveGateway(private val signer: String?) : AppDataArchiveGateway {
        override suspend fun thorUserId(): Int = 0
        override suspend fun externalStorageDir(): String = "/storage/emulated/0"
        override suspend fun stagingFile(name: String): File = File("/tmp/$name")
        override suspend fun forceStop(packageName: String) = Unit
        override suspend fun listClass(packageName: String, dataClass: DataClass) =
            ClassEntries(kept = emptyList(), skipped = emptyList(), rootAbsent = true)

        override suspend fun tarClass(
            packageName: String,
            dataClass: DataClass,
            entries: List<String>,
            out: File,
            compress: Boolean,
        ): TarOutcome = TarOutcome.Failed("not used in this test")

        override suspend fun extractInto(
            packageName: String,
            dataClass: DataClass,
            tar: File,
            compressed: Boolean,
        ): Boolean = false

        override suspend fun swapStaged(packageName: String, dataClass: DataClass): Boolean = false

        override suspend fun chownClass(
            packageName: String,
            dataClass: DataClass,
            uid: Int,
        ): Boolean = false

        override suspend fun relabelClass(packageName: String, dataClass: DataClass): Boolean = false

        override suspend fun appUid(packageName: String): Int? = null
        override suspend fun signerSha256(packageName: String): String? = signer
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
     * `copyOf()`, not the argument itself — the copy is load-bearing.
     *
     * `PassphraseVault.remember` zeroes the plaintext array in a `finally` **before** it writes the
     * store. A provider that hands the same array back as its "wrapped" blob therefore stores a run of
     * NUL bytes, and every recall returns a passphrase of NULs that opens nothing. The real
     * Keystore-backed provider returns fresh ciphertext, so only a double that aliases has the
     * problem.
     */
    private class PlainKeyProvider : VaultKeyProvider {
        override fun wrap(plaintext: ByteArray): ByteArray = plaintext.copyOf()
        override fun unwrap(blob: ByteArray): ByteArray = blob.copyOf()
    }

    /**
     * [enqueues] false is a launcher that accepts the request and returns no id — what
     * `ThorJobLauncher` does when key derivation or `enqueue()` itself throws.
     */
    private class FakeLauncher(
        val jobId: UUID = UUID.fromString("00000000-0000-0000-0000-0000deadbeef"),
        val statuses: MutableStateFlow<ThorJobStatus> = MutableStateFlow(ThorJobStatus.Running),
        val running: MutableStateFlow<UUID?> = MutableStateFlow(null),
        val enqueues: Boolean = true,
    ) : ArchiveJobLauncher {
        var started: ArchiveRestoreRequest? = null
        var startedSalt: ByteArray? = null

        override suspend fun startBackup(request: ArchiveBackupRequest, passphrase: CharArray): UUID? = null

        override suspend fun startRestore(
            request: ArchiveRestoreRequest,
            passphrase: CharArray,
            salt: ByteArray,
        ): UUID? {
            started = request
            startedSalt = salt
            return if (enqueues) jobId else null
        }

        override fun status(jobId: UUID): Flow<ThorJobStatus> = statuses
        override fun runningJobFor(kind: ThorJobKind, target: String): Flow<UUID?> = running
    }

    /**
     * [writeSucceeds] false is the §8.5 store that could not write — the case
     * `RestoreAppArchiveUseCase` turns into a warning rather than a failure.
     */
    private class FakeBreadcrumbs(
        var current: ArchiveBreadcrumb? = null,
        private val writeSucceeds: Boolean = true,
    ) : ArchiveBreadcrumbStore {
        var cleared = false
        override suspend fun write(packageName: String, appLabel: String): Boolean {
            if (!writeSucceeds) return false
            current = ArchiveBreadcrumb(packageName, appLabel, startedAt = 1L)
            return true
        }

        override suspend fun read(): ArchiveBreadcrumb? = current

        // Re-reads on collection rather than replaying a captured value. Nothing here collects it —
        // the view model takes the one-shot `read()` in `init` on purpose, so that a restore's own
        // breadcrumb does not appear on the screen that is writing it — but a fake that would go
        // stale is a fake that would make the next test lie.
        override fun observe(): Flow<ArchiveBreadcrumb?> = flow { emit(read()) }

        override suspend fun clear() {
            cleared = true
            current = null
        }
    }

    private fun viewModel(
        head: ArchiveHeader? = header(),
        installedApps: List<AppInfo> = listOf(
            AppInfo(packageName = "com.example.game", appName = "Game", versionCode = 100L)
        ),
        signer: String? = SIGNER,
        probe: AppDataProbe = FakeProbe(),
        launcher: ArchiveJobLauncher = FakeLauncher(),
        vaultStore: PassphraseVaultStore = FakeVaultStore(),
        breadcrumbs: ArchiveBreadcrumbStore = FakeBreadcrumbs(),
        registry: JobRegistry = JobRegistry(),
        sources: ArchiveSourceFactory = FakeSources(FakeSource(head?.encode())),
    ) = ArchiveRestoreViewModel(
        sources = sources,
        openArchive = OpenArchiveUseCase(cipher, dispatcher),
        probe = probe,
        installedFacts = ReadInstalledAppFactsUseCase(
            appRepository = FakeAppRepository(installedApps),
            gateway = FakeArchiveGateway(signer),
        ),
        vault = PassphraseVault(vaultStore, PlainKeyProvider()),
        launcher = launcher,
        registry = registry,
        breadcrumbs = breadcrumbs,
    )

    // --- opening the file -----------------------------------------------------------------------

    @Test
    fun `a second file replaces the first, and the same file is never read twice`() =
        runTest(dispatcher) {
            // Both halves of one guard. `LaunchedEffect(uriString)` and a recomposition can both call
            // `open` with the file already open, and re-reading would restart the gate under the user
            // — but the screen's own picker calls it with a *different* URI, and a flat "already
            // opened" guard turns "choose a different file" into a button that does nothing for the
            // rest of the screen's life.
            val other = "content://com.example.docs/document/2"
            val sources = CountingSources(
                mapOf(
                    URI to FakeSource(header().encode()),
                    other to FakeSource(
                        header(packageName = "com.example.other", versionCode = 5L).encode(),
                        displayName = "com.example.other-5.thorbak",
                    ),
                )
            )
            val vm = viewModel(sources = sources)

            vm.open(URI)
            testScheduler.advanceUntilIdle()
            vm.open(URI)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(URI), sources.opens)
            assertEquals("com.example.game", vm.uiState.value.header?.packageName)

            vm.open(other)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(URI, other), sources.opens)
            assertEquals("com.example.other", vm.uiState.value.header?.packageName)
            assertEquals("com.example.other-5.thorbak", vm.uiState.value.fileName)
        }

    @Test
    fun `the header is read and every class it holds is selected`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("com.example.game", state.header?.packageName)
        assertEquals(setOf(DataClass.CE, DataClass.DE), state.selected)
        assertEquals("com.example.game-100.thorbak", state.fileName)
        assertNull(state.error)
    }

    @Test
    fun `the source is closed once the header has been read`() = runTest(dispatcher) {
        // An unclosed ArchiveSource is a leaked ParcelFileDescriptor. The screen only needs the
        // header; the worker opens the container again for itself.
        val source = FakeSource(header().encode())
        val vm = viewModel(sources = FakeSources(source))

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertTrue(source.closed)
    }

    @Test
    fun `a file that is not a thorbak is reported without a gate decision`() = runTest(dispatcher) {
        val vm = viewModel(sources = FakeSources(FakeSource(headerJson = null)))

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.error!!.contains(THORBAK_HEADER_ENTRY))
        assertNull(vm.uiState.value.header)
        assertEquals(false, vm.uiState.value.canStart)
    }

    @Test
    fun `a file that cannot be opened at all is reported`() = runTest(dispatcher) {
        val vm = viewModel(sources = FakeSources(source = null))

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.error!!.isNotBlank())
        assertEquals(false, vm.uiState.value.loading)
    }

    @Test
    fun `an incapable privilege state is reported and nothing can start`() = runTest(dispatcher) {
        val vm = viewModel(probe = FakeProbe(capable = false))

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.supported)
        assertEquals(false, vm.uiState.value.canStart)
    }

    // --- the gate, shown before anything destructive ---------------------------------------------

    @Test
    fun `a signer mismatch refuses and no passphrase is even asked for`() = runTest(dispatcher) {
        val vm = viewModel(signer = "CD".repeat(32))

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertEquals(ArchiveRestoreRefusal.SIGNER_MISMATCH, vm.uiState.value.refusal)
        assertEquals(false, vm.uiState.value.canStart)
        // Asking for a passphrase for an archive that will never be read is a question with no
        // purpose, and it invites the user to believe the refusal is about the passphrase.
        assertEquals(false, vm.uiState.value.passphraseNeeded)
    }

    @Test
    fun `an unreadable signer refuses rather than being treated as a match`() = runTest(dispatcher) {
        val vm = viewModel(signer = null)

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertEquals(ArchiveRestoreRefusal.SIGNER_UNVERIFIABLE, vm.uiState.value.refusal)
    }

    @Test
    fun `an absent app with a bundle is allowed and says it will install first`() = runTest(dispatcher) {
        val vm = viewModel(installedApps = emptyList(), signer = null)

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        // signer = null does not matter here: an app that is not installed has no signer to compare,
        // and the gate tests absence *before* the signer for exactly this case.
        assertNull(vm.uiState.value.refusal)
        assertEquals(true, vm.uiState.value.installFirst)
    }

    @Test
    fun `an absent app and a data-only archive refuses`() = runTest(dispatcher) {
        val vm = viewModel(head = header(bundle = null), installedApps = emptyList())

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertEquals(ArchiveRestoreRefusal.DATA_ONLY_AND_APP_ABSENT, vm.uiState.value.refusal)
    }

    @Test
    fun `an older installed version warns without refusing`() = runTest(dispatcher) {
        val vm = viewModel(
            head = header(versionCode = 200L),
            installedApps = listOf(AppInfo(packageName = "com.example.game", versionCode = 100L)),
        )

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.refusal)
        assertEquals(
            listOf(ArchiveRestoreWarning.INSTALLED_VERSION_OLDER),
            vm.uiState.value.warnings,
        )
    }

    @Test
    fun `deselecting DE raises the CE-without-DE warning as soon as the box is unticked`() =
        runTest(dispatcher) {
            // The gate is re-run on every selection change, not once at open: its warnings are about
            // the *selection*, and a warning that only appears after the destructive step begins is
            // not a warning.
            val vm = viewModel()
            vm.open(URI)
            testScheduler.advanceUntilIdle()
            assertTrue(vm.uiState.value.warnings.isEmpty())

            vm.toggleClass(DataClass.DE)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(ArchiveRestoreWarning.CE_WITHOUT_DE), vm.uiState.value.warnings)
        }

    @Test
    fun `unticking everything refuses instead of quietly disabling the button`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.open(URI)
        testScheduler.advanceUntilIdle()

        vm.toggleClass(DataClass.CE)
        vm.toggleClass(DataClass.DE)
        testScheduler.advanceUntilIdle()

        assertEquals(ArchiveRestoreRefusal.NOTHING_SELECTED, vm.uiState.value.refusal)
    }

    @Test
    fun `a refusal takes the warnings it supersedes with it`() = runTest(dispatcher) {
        // A refused restore has no warnings to heed — there is nothing to heed them *about*. Left on
        // screen beside the refusal they read as two problems where there is one, and the stale one
        // is about a selection the user has since emptied.
        val vm = viewModel(
            head = header(versionCode = 200L),
            installedApps = listOf(AppInfo(packageName = "com.example.game", versionCode = 100L)),
        )
        vm.open(URI)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(ArchiveRestoreWarning.INSTALLED_VERSION_OLDER), vm.uiState.value.warnings)

        vm.toggleClass(DataClass.CE)
        vm.toggleClass(DataClass.DE)
        testScheduler.advanceUntilIdle()

        assertEquals(ArchiveRestoreRefusal.NOTHING_SELECTED, vm.uiState.value.refusal)
        assertTrue(vm.uiState.value.warnings.isEmpty())
    }

    @Test
    fun `an archive from a newer Thor refuses`() = runTest(dispatcher) {
        val vm = viewModel(head = header(schemaVersion = 99))

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertEquals(ArchiveRestoreRefusal.SCHEMA_TOO_NEW, vm.uiState.value.refusal)
    }

    // --- passphrase ----------------------------------------------------------------------------

    @Test
    fun `an empty vault asks for the passphrase`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertEquals(true, vm.uiState.value.passphraseNeeded)
        assertEquals(false, vm.uiState.value.unlocked)
    }

    @Test
    fun `a remembered passphrase that opens the archive unlocks it without a prompt`() =
        runTest(dispatcher) {
            val store = FakeVaultStore()
            PassphraseVault(store, PlainKeyProvider()).remember(passphrase.toCharArray())
            val vm = viewModel(vaultStore = store)

            vm.open(URI)
            testScheduler.advanceUntilIdle()

            assertEquals(true, vm.uiState.value.unlocked)
            assertEquals(false, vm.uiState.value.passphraseNeeded)
        }

    @Test
    fun `a remembered passphrase that does not open this archive prompts, and says nothing about corruption`() =
        runTest(dispatcher) {
            // §5.4. The vault is a cache: the archive was made with whatever passphrase was current
            // then, and "wrong stored passphrase" must never be reported as a damaged backup.
            val store = FakeVaultStore()
            PassphraseVault(store, PlainKeyProvider()).remember("some other one".toCharArray())
            val vm = viewModel(vaultStore = store)

            vm.open(URI)
            testScheduler.advanceUntilIdle()

            assertEquals(true, vm.uiState.value.passphraseNeeded)
            assertEquals(false, vm.uiState.value.unlocked)
            assertNull(vm.uiState.value.error)
        }

    @Test
    fun `a wrong typed passphrase reports itself and leaves the screen usable`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.open(URI)
        testScheduler.advanceUntilIdle()

        vm.submitPassphrase("wrong one".toCharArray())
        testScheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.unlocked)
        assertTrue(vm.uiState.value.passphraseError!!.isNotBlank())
        assertEquals(true, vm.uiState.value.passphraseNeeded)
    }

    @Test
    fun `the right typed passphrase unlocks it`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.open(URI)
        testScheduler.advanceUntilIdle()

        vm.submitPassphrase(passphrase.toCharArray())
        testScheduler.advanceUntilIdle()

        assertEquals(true, vm.uiState.value.unlocked)
        assertNull(vm.uiState.value.passphraseError)
    }

    @Test
    fun `a rejected passphrase is wiped rather than left in the heap`() = runTest(dispatcher) {
        val typed = "wrong one".toCharArray()
        val vm = viewModel()
        vm.open(URI)
        testScheduler.advanceUntilIdle()

        vm.submitPassphrase(typed)
        testScheduler.advanceUntilIdle()

        // Nothing retains a rejected passphrase, so the array the screen handed over is the view
        // model's to clear — the same contract `AppBackupViewModel.beginBackup` keeps.
        assertTrue(typed.all { it == ' ' })
    }

    @Test
    fun `a superseded passphrase is wiped when the next one is accepted`() = runTest(dispatcher) {
        val first = passphrase.toCharArray()
        val second = passphrase.toCharArray()
        val vm = viewModel()
        vm.open(URI)
        testScheduler.advanceUntilIdle()

        vm.submitPassphrase(first)
        testScheduler.advanceUntilIdle()
        vm.submitPassphrase(second)
        testScheduler.advanceUntilIdle()

        assertTrue(first.all { it == ' ' })
        // The live one is still needed — `beginRestore` has not run yet.
        assertFalse(second.all { it == ' ' })
    }

    @Test
    fun `choosing a different passphrase wipes the one that was held`() = runTest(dispatcher) {
        val typed = passphrase.toCharArray()
        val vm = viewModel()
        vm.open(URI)
        testScheduler.advanceUntilIdle()
        vm.submitPassphrase(typed)
        testScheduler.advanceUntilIdle()

        vm.useDifferentPassphrase()

        assertTrue(typed.all { it == ' ' })
        assertEquals(false, vm.uiState.value.unlocked)
    }

    // --- starting ------------------------------------------------------------------------------

    @Test
    fun `an unlocked archive still cannot start until the replacement is confirmed`() =
        runTest(dispatcher) {
            // Restore replaces a class wholesale. The confirmation is the only place the user is told
            // that in those words, so it gates the button rather than decorating it.
            val launcher = FakeLauncher()
            val vm = viewModel(launcher = launcher)
            vm.open(URI)
            testScheduler.advanceUntilIdle()
            vm.submitPassphrase(passphrase.toCharArray())
            testScheduler.advanceUntilIdle()

            assertEquals(false, vm.uiState.value.canStart)
            vm.beginRestore()
            testScheduler.advanceUntilIdle()
            assertNull(launcher.started)

            vm.setConfirmed(true)
            assertEquals(true, vm.uiState.value.canStart)
        }

    @Test
    fun `the request carries the selection, the OBB choice, and the archive's own salt`() =
        runTest(dispatcher) {
            val launcher = FakeLauncher()
            val vm = viewModel(launcher = launcher)
            vm.open(URI)
            testScheduler.advanceUntilIdle()
            vm.submitPassphrase(passphrase.toCharArray())
            testScheduler.advanceUntilIdle()
            vm.toggleClass(DataClass.DE)
            vm.setRestoreObb(false)
            vm.setConfirmed(true)

            vm.beginRestore()
            testScheduler.advanceUntilIdle()

            val request = launcher.started!!
            assertEquals(URI, request.uriString)
            assertEquals("com.example.game", request.packageName)
            assertEquals(setOf(DataClass.CE), request.classes)
            assertEquals(false, request.restoreObb)
            // The archive's salt, not a fresh one — a restore derives the key the backup used or it
            // derives the wrong key.
            assertTrue(salt.contentEquals(launcher.startedSalt))
        }

    @Test
    fun `an enqueue that fails leaves the passphrase usable for a second attempt`() =
        runTest(dispatcher) {
            // Deliberately *not* wiped after `startRestore` returns. A launcher that could not
            // enqueue leaves the user on a usable screen, and a wiped array would make the retry
            // derive a key from spaces — a wrong-passphrase failure with no wrong passphrase in it.
            val typed = passphrase.toCharArray()
            val launcher = FakeLauncher(enqueues = false)
            val vm = viewModel(launcher = launcher)
            vm.open(URI)
            testScheduler.advanceUntilIdle()
            vm.submitPassphrase(typed)
            testScheduler.advanceUntilIdle()
            vm.setConfirmed(true)

            vm.beginRestore()
            testScheduler.advanceUntilIdle()

            // `workerRan = false`: the enqueue threw, so there is no job and nothing on the device was
            // touched. The screen renders the damage sentence off this flag, and the damage sentence
            // ends by telling the user to run a destructive operation again.
            assertEquals(RestoreFinish.Failed(null, workerRan = false), vm.uiState.value.finished)
            assertEquals(false, vm.uiState.value.running)
            assertFalse(typed.all { it == ' ' })
        }

    @Test
    fun `OBB defaults on only when the archive actually holds some`() = runTest(dispatcher) {
        val withObb = viewModel()
        withObb.open(URI)
        testScheduler.advanceUntilIdle()
        assertEquals(true, withObb.uiState.value.restoreObb)

        val withoutObb = viewModel(
            head = header(bundle = ArchiveBundleInfo(bytes = 10L, obbCapture = "none", obbCount = 0))
        )
        withoutObb.open(URI)
        testScheduler.advanceUntilIdle()
        assertEquals(false, withoutObb.uiState.value.restoreObb)
        assertEquals(false, withoutObb.uiState.value.obbOffered)
    }

    @Test
    fun `a refused archive cannot be started even if everything else is ticked`() = runTest(dispatcher) {
        // Every other clause of `canStart` deliberately left true, so `refusal == null` is the only
        // one holding the button. Unlocked through the vault rather than a signer mismatch: a
        // mismatch returns early before the passphrase is ever tried, which leaves `unlocked` false —
        // and a version of this test that never unlocks passes with `refusal == null` deleted, which
        // is how the clause went unconstrained in the first place.
        val store = FakeVaultStore()
        PassphraseVault(store, PlainKeyProvider()).remember(passphrase.toCharArray())
        val launcher = FakeLauncher()
        val vm = viewModel(vaultStore = store, launcher = launcher)
        vm.open(URI)
        testScheduler.advanceUntilIdle()
        vm.setConfirmed(true)
        assertEquals(true, vm.uiState.value.canStart)

        // NOTHING_SELECTED: reachable in one interaction, and the refusal a user is most likely to
        // create by hand.
        vm.toggleClass(DataClass.CE)
        vm.toggleClass(DataClass.DE)
        testScheduler.advanceUntilIdle()

        assertEquals(ArchiveRestoreRefusal.NOTHING_SELECTED, vm.uiState.value.refusal)
        assertEquals(true, vm.uiState.value.unlocked)
        assertEquals(true, vm.uiState.value.confirmed)
        assertEquals(false, vm.uiState.value.canStart)

        vm.beginRestore()
        testScheduler.advanceUntilIdle()

        assertNull(launcher.started)
    }

    // --- progress and outcome ------------------------------------------------------------------

    @Test
    fun `progress reaches the state and a success is reported`() = runTest(dispatcher) {
        val registry = JobRegistry()
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher, registry = registry)
        vm.open(URI)
        testScheduler.advanceUntilIdle()
        vm.submitPassphrase(passphrase.toCharArray())
        testScheduler.advanceUntilIdle()
        vm.setConfirmed(true)
        vm.beginRestore()
        testScheduler.advanceUntilIdle()

        registry.publish(
            launcher.jobId,
            ThorJobProgress(ThorJobStage.RESTORING, "Game", completed = 1L, total = 4L),
        )
        testScheduler.advanceUntilIdle()
        assertEquals(25, vm.uiState.value.progress?.percent)

        launcher.statuses.value = ThorJobStatus.Succeeded()
        testScheduler.advanceUntilIdle()
        assertEquals(RestoreFinish.Succeeded(), vm.uiState.value.finished)
        assertEquals(false, vm.uiState.value.running)
    }

    @Test
    fun `a restore that finished with warnings carries them to the screen`() = runTest(dispatcher) {
        // The use case's warnings — a failed OBB placement, an archive with no game data in it, a
        // breadcrumb that could not be written — used to reach the log and nothing else. A restore
        // whose game data silently did not land is a game that starts and then crashes.
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher)
        vm.open(URI)
        testScheduler.advanceUntilIdle()
        vm.submitPassphrase(passphrase.toCharArray())
        testScheduler.advanceUntilIdle()
        vm.setConfirmed(true)
        vm.beginRestore()
        testScheduler.advanceUntilIdle()

        launcher.statuses.value = ThorJobStatus.Succeeded(
            listOf("the game data could not be placed: no space left on device")
        )
        testScheduler.advanceUntilIdle()

        assertEquals(
            RestoreFinish.Succeeded(listOf("the game data could not be placed: no space left on device")),
            vm.uiState.value.finished,
        )
    }

    @Test
    fun `a failure carries the worker's sentence, and says the device was touched`() =
        runTest(dispatcher) {
            // FAILED is reachable only through `doWork` returning `Result.failure()`, so the damage
            // sentence applies whether or not this watcher happened to see RUNNING first. The fake
            // starts at PENDING precisely so it never does: `workerRan` is read off the status, not
            // off what this screen was lucky enough to observe.
            val launcher = FakeLauncher(statuses = MutableStateFlow(ThorJobStatus.Pending))
            val vm = viewModel(launcher = launcher)
            vm.open(URI)
            testScheduler.advanceUntilIdle()
            vm.submitPassphrase(passphrase.toCharArray())
            testScheduler.advanceUntilIdle()
            vm.setConfirmed(true)
            vm.beginRestore()
            testScheduler.advanceUntilIdle()

            launcher.statuses.value = ThorJobStatus.Failed("ce was already replaced")
            testScheduler.advanceUntilIdle()

            assertEquals(
                RestoreFinish.Failed("ce was already replaced", workerRan = true),
                vm.uiState.value.finished,
            )
        }

    @Test
    fun `a job cancelled by the chain is its own outcome, not a failure with nothing to say`() =
        runTest(dispatcher) {
            // Nothing in the app calls `cancel`, so a CANCELLED restore is in practice the chain
            // case: `APPEND_OR_REPLACE` queued this behind another job, that job returned
            // `Result.failure()`, and WorkManager cancelled its dependents. Folded into
            // `Failed(null)` it renders as "it stopped without saying why", which is the one thing
            // that is not true here. `workerRan` false is what earns it the "nothing was changed"
            // copy: the job sat PENDING and was cancelled from there, so `doWork` was never called.
            // PENDING is not RUNNING — reading a queue entry as proof of work would put the damage
            // sentence on the one cancel where nothing happened.
            val launcher = FakeLauncher(statuses = MutableStateFlow(ThorJobStatus.Pending))
            val vm = viewModel(launcher = launcher)
            vm.open(URI)
            testScheduler.advanceUntilIdle()
            vm.submitPassphrase(passphrase.toCharArray())
            testScheduler.advanceUntilIdle()
            vm.setConfirmed(true)
            vm.beginRestore()
            testScheduler.advanceUntilIdle()
            assertEquals(true, vm.uiState.value.queued)

            launcher.statuses.value = ThorJobStatus.Cancelled
            testScheduler.advanceUntilIdle()

            assertEquals(RestoreFinish.Cancelled(workerRan = false), vm.uiState.value.finished)
            assertEquals(false, vm.uiState.value.running)
        }

    @Test
    fun `a cancel that followed a running job does not claim nothing was changed`() =
        runTest(dispatcher) {
            // The other cancel. A job that reached RUNNING was inside `doWork`, which deletes each
            // class before it writes it — so this one gets the damage sentence, not the reassurance
            // the chain case gets. The two are one observed status apart and read as opposites.
            val launcher = FakeLauncher(statuses = MutableStateFlow(ThorJobStatus.Pending))
            val vm = viewModel(launcher = launcher)
            vm.open(URI)
            testScheduler.advanceUntilIdle()
            vm.submitPassphrase(passphrase.toCharArray())
            testScheduler.advanceUntilIdle()
            vm.setConfirmed(true)
            vm.beginRestore()
            testScheduler.advanceUntilIdle()

            launcher.statuses.value = ThorJobStatus.Running
            testScheduler.advanceUntilIdle()
            launcher.statuses.value = ThorJobStatus.Cancelled
            testScheduler.advanceUntilIdle()

            assertEquals(RestoreFinish.Cancelled(workerRan = true), vm.uiState.value.finished)
        }

    @Test
    fun `a restore already running for this app is picked up on open`() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        launcher.running.value = launcher.jobId
        val vm = viewModel(launcher = launcher)

        vm.open(URI)
        testScheduler.advanceUntilIdle()

        assertEquals(true, vm.uiState.value.running)
        assertEquals(false, vm.uiState.value.canStart)
    }

    // --- the interruption breadcrumb -------------------------------------------------------------

    @Test
    fun `an interrupted restore is surfaced and is not cleared just by being read`() =
        runTest(dispatcher) {
            // Task 15's launch sweep deliberately leaves the breadcrumb alone. If merely reading it
            // cleared it, a user who rotated the screen would never see the warning again.
            val crumbs = FakeBreadcrumbs(ArchiveBreadcrumb("com.example.other", "Other", startedAt = 7L))
            val vm = viewModel(breadcrumbs = crumbs)

            vm.open(URI)
            testScheduler.advanceUntilIdle()

            assertEquals("com.example.other", vm.uiState.value.interrupted?.packageName)
            assertFalse(crumbs.cleared)
        }

    @Test
    fun `acknowledging the interruption clears it`() = runTest(dispatcher) {
        val crumbs = FakeBreadcrumbs(ArchiveBreadcrumb("com.example.other", "Other", startedAt = 7L))
        val vm = viewModel(breadcrumbs = crumbs)
        vm.open(URI)
        testScheduler.advanceUntilIdle()

        vm.acknowledgeInterruption()
        testScheduler.advanceUntilIdle()

        assertTrue(crumbs.cleared)
        assertNull(vm.uiState.value.interrupted)
    }

    @Test
    fun `opening no file still reports an interruption`() = runTest(dispatcher) {
        // The Settings entry point arrives with no URI. That is the most likely way a user reaches
        // this screen after a crash, so the notice cannot depend on a file having been picked.
        val crumbs = FakeBreadcrumbs(ArchiveBreadcrumb("com.example.other", "Other", startedAt = 7L))
        val vm = viewModel(breadcrumbs = crumbs)

        testScheduler.advanceUntilIdle()

        assertEquals("com.example.other", vm.uiState.value.interrupted?.packageName)
        assertNull(vm.uiState.value.header)
    }

    // --- the ambiguity in `Gone`, and what a live job closes ---------------------------------------

    @Test
    fun `a job that has not landed yet is not the same as a job that is over`() = runTest(dispatcher) {
        // `Gone` is a null `WorkInfo`, and a row WorkManager has not written yet is null too:
        // `ThorJobLauncher` does not await `enqueue()`. Only the order tells them apart. Reported as
        // terminal, the first one takes the progress bar down a frame after the tap and re-arms a
        // button over a job that is about to start writing.
        val launcher = FakeLauncher(statuses = MutableStateFlow(ThorJobStatus.Gone))
        val vm = viewModel(launcher = launcher)
        vm.open(URI)
        testScheduler.advanceUntilIdle()
        vm.submitPassphrase(passphrase.toCharArray())
        testScheduler.advanceUntilIdle()
        vm.setConfirmed(true)
        vm.beginRestore()
        testScheduler.advanceUntilIdle()

        assertEquals(true, vm.uiState.value.running)
        assertNull(vm.uiState.value.finished)

        launcher.statuses.value = ThorJobStatus.Running
        testScheduler.advanceUntilIdle()
        launcher.statuses.value = ThorJobStatus.Gone
        testScheduler.advanceUntilIdle()

        // Same value, opposite meaning: the record went away underneath a live watcher.
        assertEquals(false, vm.uiState.value.running)
        assertNull(vm.uiState.value.finished)
    }

    @Test
    fun `a live restore closes the button behind it`() = runTest(dispatcher) {
        // Everything else the button asks for stays true while the job runs — unlocked, confirmed, a
        // header, no refusal — so `running` is the only thing standing between a double tap and a
        // second enqueue against the same package.
        val launcher = FakeLauncher()
        val vm = viewModel(launcher = launcher)
        vm.open(URI)
        testScheduler.advanceUntilIdle()
        vm.submitPassphrase(passphrase.toCharArray())
        testScheduler.advanceUntilIdle()
        vm.setConfirmed(true)
        assertEquals(true, vm.uiState.value.canStart)

        vm.beginRestore()
        testScheduler.advanceUntilIdle()

        assertEquals(true, vm.uiState.value.running)
        assertEquals(true, vm.uiState.value.unlocked)
        assertEquals(true, vm.uiState.value.confirmed)
        assertEquals(false, vm.uiState.value.canStart)
    }

    @Test
    fun `a job that was queued stops being queued when it settles`() = runTest(dispatcher) {
        // `queued` drives copy that must not promise a run, so it cannot outlive the job it describes:
        // left set, a finished restore reads as one still waiting its turn.
        val launcher = FakeLauncher(statuses = MutableStateFlow(ThorJobStatus.Pending))
        val vm = viewModel(launcher = launcher)
        vm.open(URI)
        testScheduler.advanceUntilIdle()
        vm.submitPassphrase(passphrase.toCharArray())
        testScheduler.advanceUntilIdle()
        vm.setConfirmed(true)
        vm.beginRestore()
        testScheduler.advanceUntilIdle()

        assertEquals(true, vm.uiState.value.queued)

        launcher.statuses.value = ThorJobStatus.Succeeded()
        testScheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.queued)
        assertEquals(RestoreFinish.Succeeded(), vm.uiState.value.finished)
    }
}
