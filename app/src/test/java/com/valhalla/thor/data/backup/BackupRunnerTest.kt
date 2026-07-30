// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.repository.AppBundleBuilder
import com.valhalla.thor.domain.repository.AppBundleFileStore
import com.valhalla.thor.domain.usecase.BackupAppsUseCase
import com.valhalla.thor.domain.usecase.BackupRunResult
import com.valhalla.thor.domain.usecase.ExportAppUseCase
import com.valhalla.thor.presentation.FakeContext
import com.valhalla.thor.presentation.FakePreferenceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import com.valhalla.thor.presentation.MainDispatcherRule
import java.io.File
import java.nio.file.Files

/**
 * What the runner tells the user when a run does not simply finish.
 *
 * The run itself belongs to `BackupAppsUseCaseTest`; everything here is about the three things only
 * the runner knows — that a run was replaced rather than cancelled, how much of it survived, and
 * whether an outcome has already been shown. Each of those was wrong once, and each wrong answer
 * ends as a sentence in front of the user.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupRunnerTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val temp = mutableListOf<File>()
    private lateinit var cache: File

    @Before
    fun setUp() {
        cache = tempDir("runner_cache_")
    }

    @After
    fun tearDown() {
        temp.forEach { it.deleteRecursively() }
    }

    @Test
    fun `a cancel reports the apps that were saved, not the ones that were attempted`() = runTest {
        val reachedSlow = CompletableDeferred<Unit>()
        val neverFinishes = CompletableDeferred<Unit>()
        val fastWritten = CompletableDeferred<Unit>()
        val builder = FakeBuilder { app, format ->
            if (app.packageName == SLOW) {
                reachedSlow.complete(Unit)
                neverFinishes.await()
            }
            bundle(app, format)
        }
        val store = RecordingStore { name -> if (name.startsWith(FAST)) fastWritten.complete(Unit) }
        val runner = runner(builder, store)
        val outcomes = outcomesOf(runner)

        runner.start(listOf(app(FAST), app(SLOW)))
        fastWritten.await()
        reachedSlow.await()
        runner.cancel()
        advanceUntilIdle()

        // One file landed, so the sentence "N of 2 apps were saved" has exactly one honest N. The
        // count used to come from the shared progress flow, which a *replacement* run zeroes on
        // its way in — so a cancel-by-replacement reported 0 while the folder held one bundle.
        assertEquals(BackupRunResult.Cancelled(saved = 1, total = 2), outcomes.single())
    }

    @Test
    fun `a run replaced by another one stays quiet and lets the replacement report`() = runTest {
        val reachedFirst = CompletableDeferred<Unit>()
        val neverFinishes = CompletableDeferred<Unit>()
        val builder = FakeBuilder { app, format ->
            if (app.packageName == SLOW) {
                reachedFirst.complete(Unit)
                neverFinishes.await()
            }
            bundle(app, format)
        }
        val runner = runner(builder, RecordingStore())
        val outcomes = outcomesOf(runner)

        runner.start(listOf(app(SLOW)))
        reachedFirst.await()
        runner.start(listOf(app(FAST)))
        advanceUntilIdle()

        // Two taps, one message. The first run is cancelled exactly like an explicit cancel, but
        // reporting it would put "Export stopped — 0 of 1 saved" in front of a user who is
        // watching their second export run perfectly well.
        assertEquals(1, outcomes.size)
        val finished = outcomes.single() as BackupRunResult.Finished
        assertEquals(1, finished.succeeded)
    }

    @Test
    fun `the replacement does not start staging until the run it replaced has unwound`() = runTest {
        val reachedFirst = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val builder = FakeBuilder { app, format ->
            order += "start:${app.packageName}"
            if (app.packageName == SLOW) {
                reachedFirst.complete(Unit)
                releaseFirst.await()
            }
            order += "end:${app.packageName}"
            bundle(app, format)
        }
        val runner = runner(builder, RecordingStore())
        outcomesOf(runner)

        runner.start(listOf(app(SLOW)))
        reachedFirst.await()
        runner.start(listOf(app(FAST)))
        advanceUntilIdle()

        // The cancelled run unwinds *before* the replacement touches the builder. Without the
        // NonCancellable handoff a third tap would strand the first run: run B parks in
        // `A.cancelAndJoin()`, B's own cancellation makes that join throw, B leaves without ever
        // waiting for A, and A carries on staging into the same cache as C.
        assertEquals(listOf("start:$SLOW", "start:$FAST", "end:$FAST"), order)
    }

    @Test
    fun `progress describes the whole batch while it runs and is cleared when it ends`() = runTest {
        val reachedSlow = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        val runner = runner(
            FakeBuilder { app, format ->
                if (app.packageName == SLOW) {
                    reachedSlow.complete(Unit)
                    releaseSlow.await()
                }
                bundle(app, format)
            },
            RecordingStore()
        )
        outcomesOf(runner)

        runner.start(listOf(app(FAST), app(SLOW)))
        reachedSlow.await()

        // The total is the batch, not the worker: a bar has to be able to draw itself from the
        // first frame, which is why start() publishes it before the coroutine gets a chance to.
        assertEquals(2, runner.progress.value?.total)

        releaseSlow.complete(Unit)
        advanceUntilIdle()

        // Null, not a stuck "2 of 2": the bar is bound to nullability, so a run that forgets to
        // clear leaves a full bar pinned to the bottom of the screen for the process lifetime.
        assertNull(runner.progress.value)
    }

    @Test
    fun `a consumed completion is not replayed to the next subscriber`() = runTest {
        val runner = runner(FakeBuilder { app, format -> bundle(app, format) }, RecordingStore())
        val first = outcomesOf(runner)

        runner.start(listOf(app(FAST)))
        advanceUntilIdle()
        assertEquals(1, first.size)
        runner.consumeCompletion()

        val second = outcomesOf(runner)
        advanceUntilIdle()

        assertTrue(second.isEmpty())
    }

    // --- Fixture ----------------------------------------------------------------------------

    private fun TestScope.runner(builder: FakeBuilder, store: RecordingStore): BackupRunner {
        val prefs = FakePreferenceRepository()
        val io = mainDispatcherRule.dispatcher
        return BackupRunner(
            // FakeContext answers getCacheDir() and refuses everything else, so the runner's
            // StorageManager probe throws and the free-space pre-flight falls back to "unknown",
            // which fails open. That is the behaviour a device with an unreadable volume gets too.
            context = FakeContext(cache),
            backupAppsUseCase = BackupAppsUseCase(
                exportAppUseCase = ExportAppUseCase(builder, prefs, store, io),
                ioDispatcher = io
            ),
            io = io
        )
    }

    /** The live list of everything the runner has reported, collected from before the first run. */
    private fun TestScope.outcomesOf(runner: BackupRunner): List<BackupRunResult> {
        val seen = mutableListOf<BackupRunResult>()
        backgroundScope.launch(mainDispatcherRule.dispatcher) {
            runner.completions.collect { seen += it }
        }
        return seen
    }

    private fun app(packageName: String): AppInfo {
        val base = File(tempDir("apk_"), "base.apk").apply { writeText("apk for $packageName") }
        return AppInfo(
            packageName = packageName,
            versionName = "1.0",
            versionCode = 1L,
            publicSourceDir = base.path
        )
    }

    private fun bundle(app: AppInfo, format: BundleFormat): Result<File> {
        val file = File(tempDir("export_temp_"), "${app.packageName}.${format.extension}")
        file.writeText("bundle")
        return Result.success(file)
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { temp += it }

    private companion object {
        const val FAST = "com.fast"
        const val SLOW = "com.slow"
    }
}

private class FakeBuilder(
    private val respond: suspend (AppInfo, BundleFormat) -> Result<File>,
) : AppBundleBuilder {
    override suspend fun build(
        appInfo: AppInfo,
        cacheSubDir: String,
        format: BundleFormat,
        fileName: String?,
    ): Result<File> = respond(appInfo, format)
}

private class RecordingStore(
    private val onWrite: (String) -> Unit = {},
) : AppBundleFileStore {
    override suspend fun writeToDownloads(file: File, mime: String): String {
        onWrite(file.name)
        return "Downloads/Thor"
    }

    override suspend fun writeToTree(file: File, treeUriStr: String, mime: String): String =
        error("no export tree is saved; the run should have resolved to Downloads")

    override suspend fun currentTargetLabel(savedTreeUriStr: String?): String =
        error("the runner never renders a target label")

    override suspend fun isTreeWritable(treeUriStr: String?): Boolean = false

    override fun shareUri(file: File): String = error("an export never shares")
}
