// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.BackupIndex
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.repository.AppBundleBuilder
import com.valhalla.thor.domain.repository.AppBundleFileStore
import com.valhalla.thor.presentation.FakePreferenceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * What a multi-app export does when the batch does not go perfectly — which is the normal case.
 *
 * [BackupAppsUseCase] is a wrapper over the real [ExportAppUseCase] here, not over a fake of it:
 * the staged-copy delete that keeps `cacheDir` bounded across a 200-app run lives in that class,
 * and a fake would quietly certify a batch that leaks a gigabyte per app. Only the two ports below
 * the export path — the bundle builder and the file store — are faked, and they are the two the
 * device actually owns.
 *
 * The file store records `"name:mime"` per write and keeps the text of the JSON document it was
 * handed, so every assertion about the manifest is made against the bytes a foreign reader would
 * get rather than against an in-memory object the run happened to build.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupAppsUseCaseTest {

    private val temp = mutableListOf<File>()
    private val prefs = FakePreferenceRepository()

    @After
    fun cleanup() {
        temp.forEach { it.deleteRecursively() }
    }

    // --- Nothing to do -----------------------------------------------------------------------

    @Test
    fun `an empty selection is refused before anything is staged`() = runTest {
        val f = fixture()

        val result = f.useCase(emptyList(), staging(), ROOMY, StagingGate(1))

        assertEquals(BackupRunResult.Rejected(BackupRejection.NothingToExport), result)
        // Not an empty Finished: an empty batch would still resolve the export target, write a
        // manifest describing nothing, and report "0 exported to Downloads/Thor" as a success.
        assertTrue(f.store.writes.isEmpty())
    }

    // --- The pre-flight free-space check ------------------------------------------------------

    @Test
    fun `a batch that cannot be staged is refused before the first app is built`() = runTest {
        val f = fixture()
        val available = 1L * 1024 * 1024

        val result = f.useCase(listOf(app("com.alpha")), staging(), available, StagingGate(1))

        val reason = (result as BackupRunResult.Rejected).reason
        assertTrue("expected a space rejection, got $reason", reason is BackupRejection.InsufficientStagingSpace)
        val space = reason as BackupRejection.InsufficientStagingSpace
        assertEquals(available, space.availableBytes)
        assertTrue(space.requiredBytes > space.availableBytes)
        // The whole point of a pre-flight: discovering this half-way leaves the user a partial
        // folder, a filled cache partition and — once the platform starts evicting staging
        // mid-zip — an archive that was truncated but still reported as written.
        assertTrue(f.builder.builds.isEmpty())
        assertTrue(f.store.writes.isEmpty())
    }

    @Test
    fun `a partition that reports nothing usable is not treated as a full one`() = runTest {
        // usableSpace is 0 on a volume this process cannot stat. A measurement we cannot trust
        // must not become a refusal to run; a genuinely full disk still surfaces per app.
        val f = fixture()

        val result = f.useCase(listOf(app("com.alpha")), staging(), usableStagingBytes = 0L, gate = StagingGate(1))

        assertTrue(result is BackupRunResult.Finished)
        assertEquals(listOf("com.alpha"), f.builder.builds)
    }

    @Test
    fun `a split app has to fit twice over`() = runTest {
        // The builder keeps `splits_staging` alive until after the zip is closed, so the copies
        // and the archive are on disk at the same moment. Same payload both times — the only
        // difference is the container — and the room offered sits between the two requirements
        // with enough slack that the fixed margin's exact value does not decide the outcome.
        val payload = 512L * 1024 * 1024
        val room = 700L * 1024 * 1024

        val monolithic = fixture()
        assertTrue(
            monolithic.useCase(
                listOf(app("com.alpha", baseBytes = payload)),
                staging(),
                room,
                StagingGate(1)
            ) is BackupRunResult.Finished
        )

        val zipped = fixture()
        assertTrue(
            zipped.useCase(
                listOf(app("com.beta", baseBytes = payload, splitBytes = 0L)),
                staging(),
                room,
                StagingGate(1)
            ) is BackupRunResult.Rejected
        )
    }

    // --- One app failing is not the batch failing --------------------------------------------

    @Test
    fun `a mid-batch failure is recorded and the rest of the batch still runs`() = runTest {
        val f = fixture(build = { app, format ->
            if (app.packageName == "com.beta") Result.failure(IllegalStateException("No source path found"))
            else stagedBundle(app, format)
        })

        val result = f.useCase(
            listOf(app("com.alpha"), app("com.beta"), app("com.gamma")),
            staging(),
            ROOMY,
            StagingGate(1)
        )

        // The apps after the failure still got their turn. A batch that aborts on the first
        // unreadable system app exports two of a hundred and reports a single error.
        assertEquals(listOf("com.alpha", "com.beta", "com.gamma"), f.builder.builds)
        assertEquals(
            BackupRunResult.Finished(
                total = 3,
                succeeded = 2,
                failed = 1,
                location = LOCATION,
                indexWritten = true
            ),
            result
        )

        val entries = BackupIndex.decode(f.store.manifest!!).entries
        // …and the failure is *in* the manifest. An index listing only what worked is a silent lie
        // about the folder it sits in.
        assertEquals(listOf("com.alpha", "com.beta", "com.gamma"), entries.map { it.packageName })
        assertEquals("IllegalStateException: No source path found", entries[1].error)
        assertNull(entries[1].fileName)
        assertNull(entries[1].sizeBytes)
        assertNotNull(entries[0].fileName)
        assertNull(entries[0].error)
    }

    @Test
    fun `a run where nothing could be written reports no location and still describes the folder`() =
        runTest {
            // location == null is the ViewModel's "exported nowhere" branch, so it has to mean
            // "no file was written" and not "the last write happened to return nothing".
            val f = fixture(build = { _, _ -> Result.failure(IllegalStateException("no source")) })

            val result = f.useCase(listOf(app("com.alpha"), app("com.beta")), staging(), ROOMY, StagingGate(1))

            assertEquals(
                BackupRunResult.Finished(
                    total = 2,
                    succeeded = 0,
                    failed = 2,
                    location = null,
                    indexWritten = true
                ),
                result
            )
            assertEquals(listOf("$MANIFEST:${BackupIndex.MIME}"), stableNames(f.store.writes))
        }

    @Test
    fun `a manifest that cannot be written does not cost the user the bundles`() = runTest {
        val f = fixture(onWrite = { _, mime ->
            if (mime == BackupIndex.MIME) throw IOException("the tree went away mid-run")
        })
        val root = staging()

        val result = f.useCase(listOf(app("com.alpha")), root, ROOMY, StagingGate(1))

        assertEquals(
            BackupRunResult.Finished(
                total = 1,
                succeeded = 1,
                failed = 0,
                location = LOCATION,
                indexWritten = false
            ),
            result
        )
        // The bundle is still in the folder; only its description is missing.
        assertEquals(listOf("com.alpha_1.0.apk:${BundleFormat.APK.mime}"), f.store.writes)
        // And the staged manifest is cleaned up on the failure path too, not just the happy one.
        assertEquals(emptyList<String>(), root.list()!!.toList())
    }

    // --- Cancellation -------------------------------------------------------------------------

    @Test
    fun `cancelling stops the batch inside the run, not at the end of it`() = runTest {
        val reachedBeta = CompletableDeferred<Unit>()
        val neverFinishes = CompletableDeferred<Unit>()
        val f = fixture(build = { app, format ->
            if (app.packageName == "com.beta") {
                reachedBeta.complete(Unit)
                neverFinishes.await()
            }
            stagedBundle(app, format)
        })

        val run = launch {
            f.useCase(
                listOf(app("com.alpha"), app("com.beta"), app("com.gamma")),
                staging(),
                ROOMY,
                StagingGate(1)
            )
        }
        reachedBeta.await()
        run.cancelAndJoin()

        // com.gamma was queued behind the permit when the cancel landed and never started. That is
        // the `ensureActive()` *inside* the permit: without it a cancelled run keeps handing new
        // multi-gigabyte copies to the builder until the selection is exhausted.
        assertEquals(listOf("com.alpha", "com.beta"), f.builder.builds)
        assertTrue(run.isCancelled)
        // com.alpha's bundle is in the user's folder, so the folder still gets a manifest — a
        // cancelled run that leaves files behind and no index is the same lie as a success-only
        // one. Written under NonCancellable, which is what lets it happen at all.
        assertEquals(
            listOf("com.alpha_1.0.apk:${BundleFormat.APK.mime}", "$MANIFEST:${BackupIndex.MIME}"),
            stableNames(f.store.writes)
        )
        assertEquals(
            listOf("com.alpha"),
            BackupIndex.decode(f.store.manifest!!).entries.map { it.packageName }
        )
    }

    // --- What the manifest says ---------------------------------------------------------------

    @Test
    fun `each app is exported in its own default format and never as an xapk`() = runTest {
        val f = fixture()

        f.useCase(
            listOf(app("com.alpha"), app("com.beta", splitBytes = 128L)),
            staging(),
            ROOMY,
            StagingGate(1)
        )

        assertEquals(
            listOf(BundleFormat.APK, BundleFormat.APKS),
            BackupIndex.decode(f.store.manifest!!).entries.map { it.format }
        )
        // The container each one was actually written as, typed accordingly: a `.apks` handed to
        // the file store as a package-archive is what makes a receiver offer a doomed install.
        assertEquals(
            listOf(
                "com.alpha_1.0.apk:application/vnd.android.package-archive",
                "com.beta_1.0.apks:application/octet-stream",
                "$MANIFEST:application/json"
            ),
            stableNames(f.store.writes)
        )
    }

    @Test
    fun `the manifest keeps the selection order, not the completion order`() = runTest {
        val f = fixture(build = { app, format ->
            if (app.packageName == "com.slow") delay(50)
            stagedBundle(app, format)
        })

        f.useCase(listOf(app("com.slow"), app("com.fast")), staging(), ROOMY, StagingGate(2))

        // com.fast overtook com.slow…
        assertEquals(
            listOf("com.fast_1.0.apk", "com.slow_1.0.apk", MANIFEST),
            stableNames(f.store.writes).map { it.substringBefore(':') }
        )
        // …and the index still reads in the order the user picked. Indexed slots, not an append:
        // a manifest ordered by whoever finished first is unreadable next to a selection screen.
        assertEquals(
            listOf("com.slow", "com.fast"),
            BackupIndex.decode(f.store.manifest!!).entries.map { it.packageName }
        )
    }

    @Test
    fun `an entry records the payload measured at the source and the name the bundle was given`() =
        runTest {
            val f = fixture()

            f.useCase(
                listOf(app("com.alpha", label = "Alpha", baseBytes = 2_048L, splitBytes = 1_024L)),
                staging(),
                ROOMY,
                StagingGate(1)
            )

            val entry = BackupIndex.decode(f.store.manifest!!).entries.single()
            assertEquals("Alpha", entry.label)
            assertEquals(BundleFormat.APKS, entry.format)
            // Base + split, summed at the source. For a zip container this excludes the framing
            // and the sidecars: the export path returns a location label, not the file it wrote,
            // so the real on-disk length is not observable from here.
            assertEquals(3_072L, entry.sizeBytes)
            // The name the run *assigned* — handed to the builder and recorded in the index from
            // the same variable. Asserted on both sides, because an entry re-deriving its own name
            // is exactly how two same-labelled apps came to share one file and claim two.
            assertEquals("Alpha_1.0.apks", entry.fileName)
            assertTrue(f.store.files.containsKey("Alpha_1.0.apks"))
        }

    @Test
    fun `the staged manifest does not outlive the run`() = runTest {
        val root = staging()
        val f = fixture()

        f.useCase(listOf(app("com.alpha")), root, ROOMY, StagingGate(1))

        // Both the JSON and the directory it was staged in. Everything else in cacheDir belongs to
        // ExportAppUseCase, which deletes its own staged bundle after each write.
        assertEquals(emptyList<String>(), root.list()!!.toList())
    }

    // --- What a batch fixes once, for the whole batch ------------------------------------------

    @Test
    fun `two apps that share a label and version each get their own file`() = runTest {
        val f = fixture()

        val result = f.useCase(
            listOf(app("com.oem.chat", label = "Chat"), app("com.play.chat", label = "Chat")),
            staging(),
            ROOMY,
            StagingGate(1)
        )

        // Two apps in, two files out. Names are built from the label, and a device really does
        // carry two packages under one label — an OEM build and a Play build of the same app. Both
        // file-store paths write by name after deleting a collision, so with per-app naming the
        // second bundle replaced the first, the run still counted two successes, and the manifest
        // listed two entries pointing at one file.
        assertEquals(2, (result as BackupRunResult.Finished).succeeded)
        val bundles = f.store.files.filterKeys { !it.startsWith(BackupIndex.FILE_NAME_PREFIX) }
        assertEquals(setOf("Chat_1.0.apk", "Chat_1.0_com.play.chat.apk"), bundles.keys)
        // …and each file holds the app it is named for, rather than the second app under the
        // first one's name.
        assertEquals("bundle for com.oem.chat", bundles["Chat_1.0.apk"])
        assertEquals("bundle for com.play.chat", bundles["Chat_1.0_com.play.chat.apk"])
        // The discriminator is the package name, not a "_2" tail: someone restoring the folder has
        // to be able to tell which of two identically-labelled apps a file came from.
        assertEquals(
            listOf("Chat_1.0.apk", "Chat_1.0_com.play.chat.apk"),
            BackupIndex.decode(f.store.manifest!!).entries.map { it.fileName }
        )
    }

    @Test
    fun `the whole run stages under one scope of its own, and it is gone afterwards`() = runTest {
        val root = staging()
        val f = fixture()

        f.useCase(listOf(app("com.alpha"), app("com.beta")), root, ROOMY, StagingGate(2))

        // One scope for the batch, and never the one a single-app export uses. The builder wipes
        // its per-package staging directory on entry, so a one-off export of an app the batch is
        // also exporting deletes the batch's half-written copy — and the zip that was mid-stream
        // closes "successfully", truncated.
        val scope = f.builder.scopes.distinct().single()
        assertNotEquals(ExportAppUseCase.SINGLE_STAGING_DIR, scope)
        assertTrue("unexpected staging scope: $scope", scope.startsWith("export_batch_"))
        // And the run owns it outright, which is what makes taking the whole tree at the end safe.
        assertEquals(emptyList<String>(), root.list()!!.toList())
    }

    @Test
    fun `a destination change while the run is in flight does not split the batch`() = runTest {
        lateinit var f: Fixture
        f = fixture(build = { app, format ->
            if (app.packageName == "com.alpha") {
                // The export sheet is not modal: the user can pick a different folder — or have the
                // saved one revoked — while a long run is going.
                prefs.setExportDirUri("content://tree/picked-mid-run")
                f.store.treeWritable = true
            }
            stagedBundle(app, format)
        })

        f.useCase(listOf(app("com.alpha"), app("com.beta")), staging(), ROOMY, StagingGate(1))

        // The preference really did change under the run…
        assertEquals("content://tree/picked-mid-run", prefs.userPreferences.first().exportDirUri)
        // …and both bundles and the manifest still went to the folder that was current when the
        // run started. Re-reading it per app would put the first eighty bundles in one folder and
        // the rest in another, with one manifest in whichever the last write resolved to: it would
        // then describe files that are not there and omit files that are.
        assertEquals(List(3) { "Downloads/Thor" }, f.store.targets)
        assertEquals(3, f.store.files.size)
    }

    // --- Progress -----------------------------------------------------------------------------

    @Test
    fun `progress counts every app, including the ones that failed`() = runTest {
        val f = fixture(build = { app, format ->
            if (app.packageName == "com.beta") Result.failure(IllegalStateException("no source"))
            else stagedBundle(app, format)
        })
        val seen = mutableListOf<BackupProgress>()

        f.useCase(
            listOf(app("com.alpha"), app("com.beta"), app("com.gamma")),
            staging(),
            ROOMY,
            StagingGate(1),
            onProgress = { seen += it }
        )

        // A counter that skipped the failures would stall at 2 of 3 and read as a hang.
        assertEquals(
            BackupProgress(completed = 0, saved = 0, total = 3, current = "com.alpha"),
            seen.first()
        )
        // saved is 2, not 3: com.beta produced no file, and the cancel message counts files.
        assertEquals(
            BackupProgress(completed = 3, saved = 2, total = 3, current = "com.gamma"),
            seen.last()
        )
        assertTrue(seen.all { it.total == 3 })
        // Monotonic: incrementing under a lock but emitting outside it lets two workers publish
        // 2 then 1, and a progress bar that goes backwards reads as a restart.
        assertEquals(seen.map { it.completed }, seen.map { it.completed }.sorted())
    }

    // --- Fixture ------------------------------------------------------------------------------

    private class Fixture(
        val builder: FakeBundleBuilder,
        val store: RecordingFileStore,
        val useCase: BackupAppsUseCase,
    )

    /**
     * The use case over the real [ExportAppUseCase] and two fakes, all on one test dispatcher so
     * the batch's virtual clock is the test's own.
     */
    private fun TestScope.fixture(
        build: suspend (AppInfo, BundleFormat) -> Result<File> = { app, format ->
            stagedBundle(app, format)
        },
        onWrite: (File, String) -> Unit = { _, _ -> },
    ): Fixture {
        val io = StandardTestDispatcher(testScheduler)
        val builder = FakeBundleBuilder(build)
        val store = RecordingFileStore(onWrite)
        return Fixture(
            builder = builder,
            store = store,
            useCase = BackupAppsUseCase(
                exportAppUseCase = ExportAppUseCase(builder, prefs, store, io),
                ioDispatcher = io
            )
        )
    }

    private fun staging(): File = tempDir("backup_staging_")

    /**
     * An app whose declared APK paths are real files of a known length, because the pre-flight and
     * the recorded `sizeBytes` both stat them.
     */
    private fun app(
        packageName: String,
        label: String? = null,
        baseBytes: Long = 1_024L,
        splitBytes: Long? = null,
    ): AppInfo {
        val dir = tempDir("apk_")
        val base = sizedFile(File(dir, "base.apk"), baseBytes)
        return AppInfo(
            appName = label,
            packageName = packageName,
            versionName = "1.0",
            versionCode = 7L,
            publicSourceDir = base.path,
            splitPublicSourceDirs = splitBytes
                ?.let { listOf(sizedFile(File(dir, "split_config.arm64_v8a.apk"), it).path) }
                .orEmpty()
        )
    }

    /** The bundle a build "produced": a real file, so the export path's delete has something to do. */
    private fun stagedBundle(app: AppInfo, format: BundleFormat): Result<File> {
        val file = File(tempDir("export_temp_"), "${app.packageName}.${format.extension}")
        file.writeText("bundle for ${app.packageName}")
        return Result.success(file)
    }

    // setLength rather than a written array: a sparse file reports its length without costing the
    // half a gigabyte the free-space tests need it to claim.
    private fun sizedFile(file: File, size: Long): File {
        RandomAccessFile(file, "rw").use { it.setLength(size) }
        return file
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { temp += it }

    private companion object {
        const val LOCATION = "Downloads/Thor"

        /** More free space than any batch in this file asks for, so the pre-flight is a no-op. */
        const val ROOMY = 8L * 1024 * 1024 * 1024
    }
}

/** Stands in for the run's timestamp in an assertion. */
private const val MANIFEST = "thor-backup-*.json"

/**
 * `thor-backup-20260730-114233.json:application/json` → `thor-backup-*.json:application/json`.
 *
 * The manifest carries the run's clock time so a second export into the same folder cannot
 * overwrite the first one's description; that makes its exact name unassertable, and pinning the
 * shape is what is actually worth pinning.
 */
// internal, not private — reached from another class here; see SyntheticAccessor in app/lint.xml.
internal fun stableNames(writes: List<String>): List<String> = writes.map { entry ->
    val name = entry.substringBefore(':')
    val isManifest = name.startsWith(BackupIndex.FILE_NAME_PREFIX) &&
            name.endsWith(BackupIndex.FILE_NAME_SUFFIX)
    if (isManifest) entry.replaceFirst(name, MANIFEST) else entry
}

/**
 * Records what was asked for, and lets a test decide what each build answers — or whether it ever
 * does.
 *
 * It *honours* the requested name rather than inventing one, because the whole point of the batch
 * assigning names is that they reach the file store: a fake that named its own output would certify
 * a run whose unique names were computed and then discarded.
 */
private class FakeBundleBuilder(
    private val respond: suspend (AppInfo, BundleFormat) -> Result<File>,
) : AppBundleBuilder {

    /** Every package that reached the builder, in the order it got there. */
    val builds = mutableListOf<String>()

    /** The staging scope each build was told to use — one per run, never the single-export one. */
    val scopes = mutableListOf<String>()

    override suspend fun build(
        appInfo: AppInfo,
        cacheSubDir: String,
        format: BundleFormat,
        fileName: String?,
    ): Result<File> {
        builds += appInfo.packageName
        scopes += cacheSubDir
        return respond(appInfo, format).map { staged ->
            if (fileName == null) staged
            else File(staged.parentFile, fileName).also { staged.renameTo(it) }
        }
    }
}

/**
 * The export destination, modelled as a folder rather than as a log.
 *
 * [writes] is the ordered assertion surface — `"name:mime"` is enough to tell a bundle from the
 * manifest and to catch a container typed as something the system would try to install. [files] is
 * what a reader would *find*: both real file-store paths write by name after deleting a collision,
 * so a second bundle with a name the folder already holds replaces the first instead of sitting
 * beside it. A store that only appended to a list would report two successes for one file.
 * [manifest] keeps the JSON text because the staged copy is deleted before the run returns.
 */
private class RecordingFileStore(
    private val onWrite: (File, String) -> Unit = { _, _ -> },
    /** Flipped by the one test that changes the destination while a run is in flight. */
    var treeWritable: Boolean = false,
) : AppBundleFileStore {

    val writes = mutableListOf<String>()

    /** name → contents, last write of a name winning, exactly as the destination folder behaves. */
    val files = linkedMapOf<String, String>()

    /** Where each write landed, in order, so a batch that split across folders is visible. */
    val targets = mutableListOf<String>()

    var manifest: String? = null
        private set

    override suspend fun writeToDownloads(file: File, mime: String): String =
        record(file, mime, "Downloads/Thor")

    override suspend fun writeToTree(file: File, treeUriStr: String, mime: String): String =
        record(file, mime, "Tree:$treeUriStr")

    private fun record(file: File, mime: String, location: String): String {
        onWrite(file, mime)
        val text = file.readText()
        if (mime == BackupIndex.MIME) manifest = text
        writes += "${file.name}:$mime"
        targets += location
        files[file.name] = text
        return location
    }

    override suspend fun currentTargetLabel(savedTreeUriStr: String?): String =
        error("the batch never renders a target label")

    override suspend fun isTreeWritable(treeUriStr: String?): Boolean = treeWritable

    override fun shareUri(file: File): String = error("an export never shares")

    override suspend fun stageText(fileName: String, content: String): File =
        error("a batch stages its manifest itself; nothing here goes through stageText")
}
