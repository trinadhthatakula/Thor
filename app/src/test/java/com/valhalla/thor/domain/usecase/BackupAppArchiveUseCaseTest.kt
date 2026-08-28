// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator
import com.valhalla.thor.domain.model.ARCHIVE_SPACE_MARGIN_BYTES
import com.valhalla.thor.domain.model.ArchiveBackupOutcome
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveCompression
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveSkip
import com.valhalla.thor.domain.model.ClassEntries
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.PackageOperationBusy
import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.ShellLaneUnavailable
import com.valhalla.thor.domain.model.TarOutcome
import com.valhalla.thor.domain.model.THORBAK_BUNDLE_ENTRY
import com.valhalla.thor.domain.model.THORBAK_HEADER_ENTRY
import com.valhalla.thor.domain.model.thorbakFileName
import com.valhalla.thor.domain.repository.AppDataArchiveGateway
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.domain.repository.AppArchiveStore
import com.valhalla.thor.domain.repository.ArchiveDestination
import com.valhalla.thor.presentation.FakeSystemRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The use case is JVM-testable because every Android-specific thing it needs is behind
 * [AppDataArchiveGateway] or [AppArchiveStore]. `AppArchiveCipher` is used **for real** — PBKDF2 and
 * AES-GCM are JCE, not Android — so these tests exercise the actual framing.
 *
 * A four-iteration KDF keeps the suite fast; the shipped 210,000 is pinned by `AppArchiveCipherTest`.
 */
class BackupAppArchiveUseCaseTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val cipher = AppArchiveCipher()

    /** Collects the container in memory so a test can unzip it and see what was written. */
    private class RecordingDestination : ArchiveDestination {
        val bytes = ByteArrayOutputStream()
        var published = false
        var discarded = false

        /**
         * How many times the stream handed out was closed — which must stay zero.
         * [ArchiveDestination.output] says the destination owns closing it, and a
         * `ByteArrayOutputStream` alone cannot tell anyone: its `close()` does nothing at all, so
         * that contract was untestable until this wrapper existed.
         */
        var outputClosed = 0

        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = bytes.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = bytes.write(b, off, len)
            override fun close() {
                outputClosed++
            }
        }

        override suspend fun publish(): Boolean {
            published = true
            return true
        }

        override suspend fun discard() {
            discarded = true
        }
    }

    private class FakeStore(private val destination: ArchiveDestination?) : AppArchiveStore {
        var openedName: String? = null

        override suspend fun openArchive(fileName: String): ArchiveDestination? {
            openedName = fileName
            return destination
        }

        override suspend fun currentTargetLabel(): String = "Downloads/Thor"

        /**
         * Task 15 added this to the port. The backup use case never sweeps — the launch-time
         * [com.valhalla.thor.data.backup.ArchiveOrphanSweeper] does, and it has its own fake — so the
         * honest answer here is "removed nothing", not a recorded call nobody asserts on.
         */
        override suspend fun discardOrphans(names: Set<String>): Set<String> = emptySet()
    }

    /**
     * @param tarBehaviour what each class's `tar` does. A class absent from the map is reported as an
     *   absent root, which is how "this app has no external media" arrives.
     * @param entries what each class's listing keeps. **Absent from this map and mapped to an empty
     *   list are two different device states**, and the elvis below only fires on the first: absent
     *   is `rootAbsent = true`, the directory that could not be read, while
     *   `DataClass.CE to emptyList()` is a root that is present and readable and holds nothing the
     *   archive would keep. Only the second reaches §7.2 step 7a's empty-listing guard.
     */
    private inner class FakeGateway(
        private val entries: Map<DataClass, List<String>> = emptyMap(),
        private val tarBehaviour: Map<DataClass, TarOutcome> = emptyMap(),
        private val skips: List<ArchiveSkip> = emptyList(),
        private val beforeTar: suspend () -> Unit = {},
    ) : AppDataArchiveGateway {
        var forceStops = 0
        val tarCalls = mutableListOf<Pair<DataClass, Boolean>>()

        override suspend fun thorUserId(): Int = 0

        override suspend fun externalStorageDir(): String = "/storage/emulated/0"

        override suspend fun stagingFile(name: String): File = temp.newFile(name)

        override suspend fun forceStop(packageName: String) {
            forceStops++
        }

        override suspend fun listClass(packageName: String, dataClass: DataClass): ClassEntries {
            val kept = entries[dataClass] ?: return ClassEntries(emptyList(), skips, rootAbsent = true)
            return ClassEntries(kept = kept, skipped = skips, rootAbsent = false)
        }

        override suspend fun tarClass(
            packageName: String,
            dataClass: DataClass,
            entries: List<String>,
            out: File,
            compress: Boolean,
        ): TarOutcome {
            beforeTar()
            tarCalls += dataClass to compress
            val outcome = tarBehaviour[dataClass] ?: TarOutcome.Succeeded
            if (outcome !is TarOutcome.Failed) out.writeBytes(ByteArray(2048) { it.toByte() })
            return outcome
        }

        override suspend fun appUid(packageName: String): Int? = 10123

        override suspend fun signerSha256(packageName: String): String? = "AB".repeat(32)

        // The restore half of the port (Task 14). Backup never calls these; throwing rather than
        // returning `true` is what keeps that a fact rather than an assumption.
        override suspend fun extractInto(
            packageName: String,
            dataClass: DataClass,
            tar: File,
            compressed: Boolean,
        ): Boolean = error("backup must not extract")

        override suspend fun swapStaged(packageName: String, dataClass: DataClass): Boolean =
            error("backup must not swap")

        override suspend fun chownClass(packageName: String, dataClass: DataClass, uid: Int): Boolean =
            error("backup must not chown")

        override suspend fun relabelClass(packageName: String, dataClass: DataClass): Boolean =
            error("backup must not relabel")
    }

    /**
     * §7.4's only input beyond the scalar the caller measures.
     *
     * Defaults to `Undetermined` for every class, which is the fail-open answer — so every test that
     * predates the space check keeps passing unchanged, and the two that care pass a size explicitly.
     */
    private class FakeProbe(
        private val sizes: Map<DataClass, DataClassSize> = emptyMap(),
    ) : AppDataProbe {
        override suspend fun probePrivateDataCapability(): Boolean = true
        override suspend fun probeDataArchiveCapability(): Boolean = true
        // Brief named this sizeOf; real interface is measureDataClass — substituted for compilation.
        override suspend fun measureDataClass(packageName: String, dataClass: DataClass): DataClassSize =
            sizes[dataClass] ?: DataClassSize.Undetermined
    }

    private fun useCase(
        gateway: AppDataArchiveGateway,
        store: AppArchiveStore,
        probe: AppDataProbe = FakeProbe(),
        coordinator: DefaultPackageOperationCoordinator = DefaultPackageOperationCoordinator(),
    ) = BackupAppArchiveUseCase(gateway, store, cipher, probe, coordinator)

    private fun request(vararg classes: DataClass) = ArchiveBackupRequest(
        packageName = "com.example.app",
        classes = classes.toSet(),
        includeBundle = false,
        salt = cipher.newSalt(),
    )

    private fun key() = cipher.deriveKey("hunter2".toCharArray(), ByteArray(16), iterations = 4)

    /** Entry names in the order the container holds them. */
    private fun entryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names += entry.name
                entry = zip.nextEntry
            }
        }
        return names
    }

    private fun header(bytes: ByteArray): ArchiveHeader {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == THORBAK_HEADER_ENTRY) {
                    return ArchiveHeader.decode(zip.readBytes().decodeToString())
                }
                entry = zip.nextEntry
            }
        }
        error("no $THORBAK_HEADER_ENTRY in the container")
    }

    @Test
    fun `a selected class becomes one encrypted member and one header entry`() = runTest {
        val destination = RecordingDestination()
        val gateway = FakeGateway(entries = mapOf(DataClass.CE to listOf("databases", "files")))

        val outcome = useCase(gateway, FakeStore(destination))(request(DataClass.CE), key()) {}

        assertTrue(outcome.toString(), outcome is ArchiveBackupOutcome.Completed)
        assertTrue(destination.published)
        val names = entryNames(destination.bytes.toByteArray())
        assertEquals(listOf("ce.tar.gz.enc", THORBAK_HEADER_ENTRY), names)
        assertNotNull(header(destination.bytes.toByteArray()).member(DataClass.CE))
    }

    @Test
    fun `the header is the last entry in the container`() = runTest {
        // Load-bearing for the streaming design: the header names every member's nonce and chunk
        // count, and those are only known after the member is written. A reader seeks to it; it must
        // not be first.
        val destination = RecordingDestination()
        val gateway = FakeGateway(
            entries = mapOf(DataClass.CE to listOf("files"), DataClass.DE to listOf("files")),
        )

        useCase(gateway, FakeStore(destination))(request(DataClass.CE, DataClass.DE), key()) {}

        assertEquals(THORBAK_HEADER_ENTRY, entryNames(destination.bytes.toByteArray()).last())
    }

    @Test
    fun `the header is STORED and every streamed entry is deflated`() = runTest {
        // Plan deviation 2: `thorbak.json` alone is STORED, because it is built in memory so `size`
        // and `crc` are both known before `putNextEntry` demands them. Everything else is streamed as
        // it is generated, so its CRC is unknowable up front and STORED is impossible — level-0
        // deflate is the substitute. Without this test the two methods are indistinguishable to every
        // other assertion here (`ZipInputStream` decodes both transparently), so a revert to a plain
        // `ZipEntry(name)` for the header would stay green.
        val destination = RecordingDestination()
        val gateway = FakeGateway(entries = mapOf(DataClass.CE to listOf("files")))

        useCase(gateway, FakeStore(destination))(request(DataClass.CE), key()) {}

        val methods = mutableMapOf<String, Int>()
        ZipInputStream(destination.bytes.toByteArray().inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                zip.readBytes()
                methods[entry.name] = entry.method
                entry = zip.nextEntry
            }
        }

        assertEquals(ZipEntry.STORED, methods[THORBAK_HEADER_ENTRY])
        assertEquals(ZipEntry.DEFLATED, methods["ce.tar.gz.enc"])
    }

    @Test
    fun `the app is force-stopped exactly once no matter how many classes are selected`() = runTest {
        // §7.2 step 4. Stopping it per class gives it three chances to be restarted in between.
        val gateway = FakeGateway(
            entries = DataClass.entries.associateWith { listOf("files") },
        )

        useCase(gateway, FakeStore(RecordingDestination()))(
            request(*DataClass.entries.toTypedArray()),
            key(),
        ) {}

        assertEquals(1, gateway.forceStops)
    }

    @Test
    fun `a class whose root is absent produces no member`() = runTest {
        // An empty class must not produce an empty tar the restore side has to special-case.
        val destination = RecordingDestination()
        val gateway = FakeGateway(entries = mapOf(DataClass.CE to listOf("files")))

        useCase(gateway, FakeStore(destination))(request(DataClass.CE, DataClass.DE), key()) {}

        val parsed = header(destination.bytes.toByteArray())
        assertNotNull(parsed.member(DataClass.CE))
        assertEquals(null, parsed.member(DataClass.DE))
        assertEquals(listOf(DataClass.CE), parsed.heldClasses())
    }

    @Test
    fun `a gzip tar that fails is retried without compression and recorded as such`() = runTest {
        // §7.2 step 7c. Some toybox builds have no gzip; the member is then stored uncompressed and
        // the header says `none`, so the reader does not try to gunzip it.
        val destination = RecordingDestination()
        var firstCall = true
        // Records compress flag for each tarClass call so the assertion below can pin what the retry
        // sent. Without this check, an implementation that retried with compress=true while labelling
        // the member `none` would produce a member that looks plain but is actually gzip — unrestorable.
        val calls = mutableListOf<Boolean>()
        val gateway = object : AppDataArchiveGateway by FakeGateway(
            entries = mapOf(DataClass.CE to listOf("files")),
        ) {
            override suspend fun tarClass(
                packageName: String,
                dataClass: DataClass,
                entries: List<String>,
                out: File,
                compress: Boolean,
            ): TarOutcome {
                calls += compress
                return if (compress && firstCall) {
                    firstCall = false
                    TarOutcome.Failed("no gzip on this device")
                } else {
                    out.writeBytes(ByteArray(1024))
                    TarOutcome.Succeeded
                }
            }
        }

        useCase(gateway, FakeStore(destination))(request(DataClass.CE), key()) {}

        val member = header(destination.bytes.toByteArray()).member(DataClass.CE)!!
        assertEquals(ArchiveCompression.NONE.id, member.compression)
        assertEquals("ce.tar.enc", member.fileName)
        // The first attempt tried gzip; the retry switched to no compression.
        // If this list were [true, true], the label "none" and the gzip bytes would disagree.
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun `a tar exit of one with bytes on disk still produces a member, plus a warning`() = runTest {
        // §7.3. This is the common case on live data, not an edge case.
        val destination = RecordingDestination()
        val gateway = FakeGateway(
            entries = mapOf(DataClass.CE to listOf("files")),
            tarBehaviour = mapOf(DataClass.CE to TarOutcome.SucceededWithWarning("files changed")),
        )

        val outcome = useCase(gateway, FakeStore(destination))(request(DataClass.CE), key()) {}

        assertTrue(outcome is ArchiveBackupOutcome.Completed)
        val parsed = header(destination.bytes.toByteArray())
        assertNotNull(parsed.member(DataClass.CE))
        assertTrue(parsed.warnings.toString(), parsed.warnings.any { it.contains("changed") })
    }

    @Test
    fun `every class failing to tar discards the archive rather than publishing an empty one`() =
        runTest {
            val destination = RecordingDestination()
            val gateway = FakeGateway(
                entries = mapOf(DataClass.CE to listOf("files")),
                tarBehaviour = mapOf(DataClass.CE to TarOutcome.Failed("out of space")),
            )

            val outcome = useCase(gateway, FakeStore(destination))(request(DataClass.CE), key()) {}

            assertTrue(outcome.toString(), outcome is ArchiveBackupOutcome.Failed)
            assertFalse("an archive with no members must not be published", destination.published)
            assertTrue(destination.discarded)
        }

    @Test
    fun `refused entry names reach the header instead of vanishing`() = runTest {
        val destination = RecordingDestination()
        val skip = ArchiveSkip(DataClass.CE.id, "bad\nname", "name cannot be passed to the shell safely")
        val gateway = FakeGateway(
            entries = mapOf(DataClass.CE to listOf("files")),
            skips = listOf(skip),
        )

        useCase(gateway, FakeStore(destination))(request(DataClass.CE), key()) {}

        assertEquals(listOf(skip), header(destination.bytes.toByteArray()).skippedEntries)
    }

    @Test
    fun `nowhere to write is its own outcome, not a failure`() = runTest {
        // The sheet turns this into "choose a folder". Reporting it as a failed backup would send the
        // user looking for a problem with their data.
        val outcome = useCase(FakeGateway(), FakeStore(null))(request(DataClass.CE), key()) {}

        assertTrue(outcome.toString(), outcome is ArchiveBackupOutcome.NoDestination)
    }

    @Test
    fun `the staged tar is deleted before the next class is staged`() = runTest {
        // The property that keeps peak disk at one class. Asserted by recording how many previously
        // staged files are still alive (exist AND have length > 0) at the moment each new staging
        // file is requested. A collect-all-then-zip implementation would show 1 alive file when DE
        // is staged; the correct implementation shows 0 because CE was deleted first.
        val liveAtRequest = mutableListOf<Int>()
        val allStagedFiles = mutableListOf<File>()

        val gateway = object : AppDataArchiveGateway by FakeGateway(
            entries = mapOf(DataClass.CE to listOf("files"), DataClass.DE to listOf("files")),
        ) {
            override suspend fun stagingFile(name: String): File {
                // Count files that were written (length > 0) and not yet deleted.
                liveAtRequest += allStagedFiles.count { it.exists() && it.length() > 0L }
                val file = temp.newFile(name)
                allStagedFiles.add(file)
                return file
            }
        }

        useCase(gateway, FakeStore(RecordingDestination()))(
            request(DataClass.CE, DataClass.DE),
            key(),
        ) {}

        // At CE's request, no prior files exist — both must be 0. A collect-all-then-zip
        // implementation gives [0, 1] because CE is still live when DE is requested.
        assertEquals(
            "CE must be deleted before DE is staged",
            listOf(0, 0),
            liveAtRequest,
        )
    }

    @Test
    fun `progress is reported for each class and never as a fake zero percent`() = runTest {
        val seen = mutableListOf<Int?>()
        val gateway = FakeGateway(
            entries = mapOf(DataClass.CE to listOf("files"), DataClass.DE to listOf("files")),
        )

        useCase(gateway, FakeStore(RecordingDestination()))(
            request(DataClass.CE, DataClass.DE),
            key(),
        ) { progress -> seen += progress.percent }

        assertTrue(seen.size.toString(), seen.size >= 2)
        // Null is "indeterminate" and is allowed; a literal 0 while work is in flight is the bug the
        // tri-state discipline exists to prevent.
        assertTrue(seen.toString(), seen.none { it == 0 })
        // Progress must not claim 100 % before the last class has been captured: the total is sized
        // so that 100 % is unreachable during the class loop.
        val classPercents = seen.filterNotNull()
        assertTrue(
            "100% should not appear during the class loop: $classPercents",
            classPercents.none { it == 100 },
        )
    }

    // --- bundle and version parameters ----------------------------------------------------------

    @Test
    fun `the archive file name includes the version code`() = runTest {
        val store = FakeStore(RecordingDestination())
        val gateway = FakeGateway(entries = mapOf(DataClass.CE to listOf("files")))

        useCase(gateway, store)(
            request = request(DataClass.CE),
            key = key(),
            versionCode = 1941L,
        ) {}

        assertEquals(thorbakFileName("com.example.app", 1941L), store.openedName)
    }

    @Test
    fun `version code and version name appear in the header`() = runTest {
        val destination = RecordingDestination()
        val gateway = FakeGateway(entries = mapOf(DataClass.CE to listOf("files")))

        val outcome = useCase(gateway, FakeStore(destination))(
            request = request(DataClass.CE),
            key = key(),
            versionCode = 1941L,
            versionName = "1.94.1",
        ) as ArchiveBackupOutcome.Completed

        assertEquals("com.example.app-1941.thorbak", outcome.fileName)
        assertEquals(1941L, outcome.header.versionCode)
        assertEquals("1.94.1", outcome.header.versionName)
    }

    @Test
    fun `a bundle is written as the first container entry`() = runTest {
        // The bundle precedes encrypted data members so a restore can install the APK before it needs
        // a privileged unpacker — the install step is always possible, the data step may not be.
        val destination = RecordingDestination()
        val gateway = FakeGateway(entries = mapOf(DataClass.CE to listOf("files")))
        val bundleFile = temp.newFile("app.xapk").also { it.writeBytes(ByteArray(1024) { 3 }) }

        useCase(gateway, FakeStore(destination))(
            request = request(DataClass.CE),
            key = key(),
            bundle = bundleFile,
            bundleObbCapture = "captured",
            bundleObbCount = 2,
        ) {}

        assertEquals(THORBAK_BUNDLE_ENTRY, entryNames(destination.bytes.toByteArray()).first())
        val appBundle = header(destination.bytes.toByteArray()).appBundle!!
        assertEquals("captured", appBundle.obbCapture)
        assertEquals(2, appBundle.obbCount)
    }

    @Test
    fun `a bundle with no capturable data class produces an install-only archive`() = runTest {
        // §6: an install-only archive is explicitly supported for devices that have no privileged
        // data access. Discarding it would make the feature unavailable on exactly those devices.
        val destination = RecordingDestination()
        // No entries for any class → all roots absent, members will be empty.
        val gateway = FakeGateway()
        val bundleFile = temp.newFile("app.xapk").also { it.writeBytes(ByteArray(512)) }

        val outcome = useCase(gateway, FakeStore(destination))(
            request = request(DataClass.CE),
            key = key(),
            bundle = bundleFile,
        ) {}

        assertTrue(outcome.toString(), outcome is ArchiveBackupOutcome.Completed)
        assertTrue(destination.published)
        assertNotNull(header(destination.bytes.toByteArray()).appBundle)
        // No data members, only the bundle and the header.
        val names = entryNames(destination.bytes.toByteArray())
        assertEquals(listOf(THORBAK_BUNDLE_ENTRY, THORBAK_HEADER_ENTRY), names)
    }

    @Test
    fun `a run with neither bundle nor data discards rather than producing an empty archive`() =
        runTest {
            // Belt-and-suspenders: the §6 install-only case requires at least a bundle; nothing at
            // all is still a failure.
            val destination = RecordingDestination()

            val outcome = useCase(FakeGateway(), FakeStore(destination))(
                request = request(DataClass.CE),
                key = key(),
                bundle = null,
            ) {}

            assertTrue(outcome.toString(), outcome is ArchiveBackupOutcome.Failed)
            assertFalse(destination.published)
            assertTrue(destination.discarded)
        }

    /**
     * Round-1 review I5. `ThorJobNotifications.build` puts `progress.label` straight into
     * `setContentText`, so whatever this use case publishes is what the user reads on the lock screen.
     * Two of the four ticks used to carry an internal name — the staged bundle's file name, then the
     * `DataClass` id — which reached the user as "app.xapk" and "ce".
     */
    @Test
    fun `every progress tick carries the app label, never an internal name`() = runTest {
        val gateway = FakeGateway(entries = mapOf(DataClass.CE to listOf("files")))
        val bundleFile = temp.newFile("labelled.xapk").also { it.writeBytes(ByteArray(256)) }
        val labels = mutableListOf<String>()

        useCase(gateway, FakeStore(RecordingDestination()))(
            request = request(DataClass.CE),
            key = key(),
            bundle = bundleFile,
            appLabel = "Clash of Clans",
        ) { labels += it.label }

        assertEquals(listOf("Clash of Clans"), labels.distinct())
        // Not vacuous: the run has to have reached all four ticks — prepare, bundle, the class loop
        // and finish — for `distinct()` to have anything to disagree about.
        assertEquals(4, labels.size)
    }

    /**
     * The default exists so the sheet in Task 16 cannot leave the notification empty by forgetting the
     * parameter. It is a package name, which is worse than a label and better than a blank.
     */
    @Test
    fun `a caller that passes no label falls back to the package name`() = runTest {
        val labels = mutableListOf<String>()

        useCase(FakeGateway(entries = mapOf(DataClass.CE to listOf("files"))), FakeStore(RecordingDestination()))(
            request = request(DataClass.CE),
            key = key(),
        ) { labels += it.label }

        assertEquals(listOf("com.example.app"), labels.distinct())
    }

    // --- §7.4 pre-flight space -----------------------------------------------------------------

    @Test
    fun `a class that will not fit is skipped with a warning while the others are captured`() =
        runTest {
            // Per class, not per run: peak disk is one class at a time, so one class that cannot be
            // staged is no reason to abandon the one that can. BackupAppsUseCase rejects the whole
            // batch instead, and is right to — a batch of N apps has nothing partial to salvage.
            val destination = RecordingDestination()
            val gateway = FakeGateway(
                entries = mapOf(
                    DataClass.CE to listOf("files"),
                    DataClass.EXTERNAL_MEDIA to listOf("Pictures"),
                ),
            )
            val probe = FakeProbe(
                mapOf(DataClass.EXTERNAL_MEDIA to DataClassSize.Known(8L * 1024 * 1024 * 1024))
            )

            val outcome = useCase(gateway, FakeStore(destination), probe)(
                request = request(DataClass.CE, DataClass.EXTERNAL_MEDIA),
                key = key(),
                usableStagingBytes = 512L * 1024 * 1024,
            ) as ArchiveBackupOutcome.Completed

            assertEquals(listOf(DataClass.CE.id), outcome.header.members.map { it.dataClass })
            assertTrue(
                outcome.header.warnings.toString(),
                outcome.header.warnings.any { it.contains(DataClass.EXTERNAL_MEDIA.id) },
            )
            // Skipped, never silently omitted: a class the user ticked and did not get has to be
            // findable in the header.
            assertTrue(gateway.tarCalls.none { it.first == DataClass.EXTERNAL_MEDIA })
        }

    @Test
    fun `a partition that cannot be measured captures everything`() = runTest {
        // Fails open, exactly as BackupAppsUseCase does. Refusing on a number we could not read would
        // block devices that had room all along.
        val gateway = FakeGateway(entries = mapOf(DataClass.CE to listOf("files")))
        val probe = FakeProbe(mapOf(DataClass.CE to DataClassSize.Known(8L * 1024 * 1024 * 1024)))

        val outcome = useCase(gateway, FakeStore(RecordingDestination()), probe)(
            request = request(DataClass.CE),
            key = key(),
            usableStagingBytes = 0L,
        ) as ArchiveBackupOutcome.Completed

        assertEquals(listOf(DataClass.CE.id), outcome.header.members.map { it.dataClass })
    }

    @Test
    fun `a class that fits by exactly the margin is captured, and one byte short is refused`() =
        runTest {
            // The accept side, which nothing pinned: every size in this file was 8 GiB against
            // 512 MiB, so a rule that refused *every* class it could measure would have passed the
            // whole suite. Both runs sit on the threshold itself, one either side, which is also the
            // only way to say whether the comparison is `<` or `<=`.
            //
            // What is compared is the **packable** size — `measureDataClass` subtracts the volatile
            // children the archive drops — not the size of the class directory.
            val size = 128L * 1024 * 1024
            val probe = FakeProbe(mapOf(DataClass.CE to DataClassSize.Known(size)))

            val fits = RecordingDestination()
            val captured = useCase(
                FakeGateway(entries = mapOf(DataClass.CE to listOf("files"))),
                FakeStore(fits),
                probe,
            )(
                request = request(DataClass.CE),
                key = key(),
                usableStagingBytes = size + ARCHIVE_SPACE_MARGIN_BYTES,
            ) as ArchiveBackupOutcome.Completed

            assertEquals(listOf(DataClass.CE.id), captured.header.members.map { it.dataClass })
            assertEquals(emptyList<String>(), captured.header.warnings)

            val short = RecordingDestination()
            val refused = useCase(
                FakeGateway(entries = mapOf(DataClass.CE to listOf("files"))),
                FakeStore(short),
                probe,
            )(
                request = request(DataClass.CE),
                key = key(),
                usableStagingBytes = size + ARCHIVE_SPACE_MARGIN_BYTES - 1,
            )

            // Nothing else was selected, so the run has nothing at all to put in the container.
            assertTrue(refused.toString(), refused is ArchiveBackupOutcome.Failed)
        }

    @Test
    fun `a class root that is present and empty produces no member`() = runTest {
        // §7.2 step 7a, and the guard is not cosmetic: without it a zero-entry tar becomes a member,
        // and restore swaps that member over live data — with only `swapStagedEntriesCommand`'s
        // `[ -n "$(ls -A ...)" ]` between the user and an emptied class root.
        //
        // Distinct from an absent root, which is the class *missing* from `entries`. Here CE is
        // present, readable and holds nothing the archive keeps.
        val destination = RecordingDestination()
        val gateway = FakeGateway(
            entries = mapOf(
                DataClass.CE to emptyList(),
                DataClass.DE to listOf("shared_prefs"),
            ),
        )

        val outcome = useCase(gateway, FakeStore(destination))(
            request = request(DataClass.CE, DataClass.DE),
            key = key(),
        ) as ArchiveBackupOutcome.Completed

        assertEquals(listOf(DataClass.DE.id), outcome.header.members.map { it.dataClass })
        assertTrue(gateway.tarCalls.toString(), gateway.tarCalls.none { it.first == DataClass.CE })
        // Findable in the header, like every other class the user ticked and did not get.
        assertTrue(
            outcome.header.warnings.toString(),
            outcome.header.warnings.any { it.contains(DataClass.CE.id) },
        )
    }

    @Test
    fun `the container stream is never closed by the use case`() = runTest {
        // `ArchiveDestination.output` is the destination's to close, in `publish()`/`discard()`.
        // The `ZipOutputStream` over it *is* closed — that is the only thing that ends its
        // `Deflater`'s native buffers — so a close shield stands between the two. Remove the shield
        // and this fails; remove the `finish()` and the container has no central directory.
        val destination = RecordingDestination()

        val outcome = useCase(
            FakeGateway(entries = mapOf(DataClass.CE to listOf("files"))),
            FakeStore(destination),
        )(request = request(DataClass.CE), key = key()) as ArchiveBackupOutcome.Completed

        assertEquals(0, destination.outputClosed)
        assertTrue(
            entryNames(destination.bytes.toByteArray()).toString(),
            entryNames(destination.bytes.toByteArray()).contains(THORBAK_HEADER_ENTRY),
        )
        assertNotNull(outcome.header)
    }

    @Test
    fun `a class whose size is undetermined is captured rather than refused`() = runTest {
        // `du` declining to answer is not evidence the class is too big — the same discipline that
        // forbids rendering Undetermined as 0 B.
        val gateway = FakeGateway(entries = mapOf(DataClass.CE to listOf("files")))

        val outcome = useCase(gateway, FakeStore(RecordingDestination()), FakeProbe())(
            request = request(DataClass.CE),
            key = key(),
            usableStagingBytes = 1024L,
        ) as ArchiveBackupOutcome.Completed

        assertEquals(listOf(DataClass.CE.id), outcome.header.members.map { it.dataClass })
    }

    @Test
    fun `backup owns one package lease while an archive phase is blocked`() = runTest {
        val coordinator = DefaultPackageOperationCoordinator()
        val enteredTar = CompletableDeferred<Unit>()
        val releaseTar = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            entries = mapOf(DataClass.CE to listOf("files")),
            beforeTar = {
                enteredTar.complete(Unit)
                releaseTar.await()
            },
        )
        val backup = async {
            useCase(
                gateway,
                FakeStore(RecordingDestination()),
                coordinator = coordinator,
            )(
                request = request(DataClass.CE),
                key = key(),
            )
        }
        enteredTar.await()

        val mutations = ManageAppUseCase(FakeSystemRepository(), coordinator)
        val samePackage = mutations.forceStop("com.example.app")
        val otherPackage = mutations.forceStop("com.example.other")
        releaseTar.complete(Unit)
        val outcome = backup.await()

        val busy = samePackage.exceptionOrNull()
        assertTrue(busy.toString(), busy is PackageOperationBusy)
        assertEquals(PackageOperationOwner.ARCHIVE_BACKUP, (busy as PackageOperationBusy).owner)
        assertTrue(otherPackage.toString(), otherPackage.isSuccess)
        assertTrue(outcome.toString(), outcome is ArchiveBackupOutcome.Completed)
    }

    @Test
    fun `backup preserves a typed archive failure and releases its package lease`() = runTest {
        val coordinator = DefaultPackageOperationCoordinator()
        val failure = ShellLaneUnavailable(PrivilegeExecutionLane.ARCHIVE)
        val gateway = FakeGateway(
            entries = mapOf(DataClass.CE to listOf("files")),
            beforeTar = { throw failure },
        )

        val thrown = runCatching {
            useCase(
                gateway,
                FakeStore(RecordingDestination()),
                coordinator = coordinator,
            )(
                request = request(DataClass.CE),
                key = key(),
            )
        }.exceptionOrNull()

        assertSame(failure, thrown)
        val mutation = ManageAppUseCase(FakeSystemRepository(), coordinator)
            .forceStop("com.example.app")
        assertTrue(mutation.toString(), mutation.isSuccess)
    }
}
