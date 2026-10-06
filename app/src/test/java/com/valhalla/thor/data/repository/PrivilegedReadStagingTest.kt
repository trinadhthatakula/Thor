// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.domain.model.IsolatedRootExecutionException
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.RootExecutionObserver
import com.valhalla.thor.domain.model.RootExecutionPolicy
import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import com.valhalla.thor.domain.model.ShellTransportDied
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PrivilegedReadStagingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `root read publishes only after durable acknowledgement and preserves caller identity`() = runTest {
        val fixture = fixture()
        fixture.destination.writeText("previous contents")
        val events = mutableListOf<String>()
        val caller = execution().copy(rootExecutionObserver = object : RootExecutionObserver {
            override suspend fun beforeSubmit() {
                assertEquals(null, fixture.receipt().kind)
                assertEquals(WORK.toString(), fixture.receipt().workRequestId)
                assertEquals(SWEEP.toString(), fixture.receipt().sweepRequestId)
                events += "before"
            }

            override suspend fun onOutcome(outcome: RootJobOutcome) {
                assertEquals(outcome.kind.name, fixture.receipt().kind)
                assertEquals("previous contents", fixture.destination.readText())
                events += "outcome"
            }
        })
        caller.provenance.recordDegradedRootFallback()
        var calls = 0

        fixture.staging.copy(SOURCE, fixture.destination, null, caller, isRoot = true) { command, routed ->
            calls++
            assertEquals(PrivilegeExecutionLane.ARCHIVE, routed.lane)
            assertEquals(RootExecutionPolicy.ISOLATED, routed.rootExecutionPolicy)
            assertEquals(caller.commandClass, routed.commandClass)
            assertEquals(caller.packageName, routed.packageName)
            assertEquals(caller.workRequestId, routed.workRequestId)
            assertEquals(caller.sweepRequestId, routed.sweepRequestId)
            assertEquals(2.minutes, routed.commandTimeout)
            assertSame(caller.provenance, routed.provenance)
            assertEquals("cat '$SOURCE' > '${fixture.payload().absolutePath}' 2>/dev/null", command)
            assertFalse(command.contains(fixture.destination.absolutePath))
            routed.rootExecutionObserver!!.beforeSubmit()
            fixture.payload().writeText("complete bytes")
            events += "write"
            routed.rootExecutionObserver!!.onOutcome(outcome())
            Result.success(0 to null)
        }

        assertEquals(1, calls)
        assertEquals(listOf("before", "write", "outcome"), events)
        assertEquals("complete bytes", fixture.destination.readText())
        assertTrue(fixture.directories().isEmpty())
        assertEquals(PrivilegeExecutionLane.INTERACTIVE, caller.lane)
        assertEquals(RootExecutionPolicy.PERSISTENT, caller.rootExecutionPolicy)
    }

    @Test fun `root reads cap archive deadlines while retaining a shorter caller deadline`() = runTest {
        for ((requested, expected) in listOf(null to 9.minutes, 20.minutes to 9.minutes, 10.seconds to 10.seconds)) {
            val fixture = fixture()
            fixture.staging.copy(
                SOURCE, fixture.destination, null, execution().copy(commandTimeout = requested), true,
            ) { _, routed ->
                assertEquals(expected, routed.commandTimeout)
                acknowledge(fixture, routed, "complete")
                Result.success(0 to null)
            }
            assertEquals("complete", fixture.destination.readText())
        }
    }

    @Test fun `nonzero root exit cannot publish or expose captured output and does not retry`() = runTest {
        val fixture = fixture()
        fixture.destination.writeText("previous contents")
        var calls = 0
        val failure = runCatching {
            fixture.staging.copy(SOURCE, fixture.destination, null, execution(), true) { _, routed ->
                calls++
                acknowledge(fixture, routed, "incomplete", outcome().copy(exitCode = 1))
                Result.success(1 to "secret file contents")
            }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertFalse(failure!!.message.orEmpty().contains("secret file contents"))
        assertEquals(1, calls)
        assertEquals("previous contents", fixture.destination.readText())
        assertTrue(fixture.directories().isEmpty())
    }

    @Test fun `missing or uncertain root acknowledgement retains private workspace without publication or retry`() = runTest {
        val terminals = listOf(
            null,
            outcome().copy(kind = RootJobOutcomeKind.TERMINATION_UNCONFIRMED),
            outcome().copy(terminationConfirmed = false),
            outcome().copy(outputDrained = false),
        )
        for (terminal in terminals) {
            val fixture = fixture()
            var calls = 0
            val failure = runCatching {
                fixture.staging.copy(SOURCE, fixture.destination, null, execution(), true) { _, routed ->
                    calls++
                    routed.rootExecutionObserver!!.beforeSubmit()
                    fixture.payload().writeText("unconfirmed bytes")
                    terminal?.let { routed.rootExecutionObserver!!.onOutcome(it) }
                    Result.success(0 to null)
                }
            }.exceptionOrNull()

            assertNotNull(failure)
            if (terminal != null) assertEquals(terminal, (failure as IsolatedRootExecutionException).outcome)
            assertEquals(1, calls)
            assertFalse(fixture.destination.exists())
            assertEquals("unconfirmed bytes", fixture.payload().readText())
            assertEquals(terminal?.kind?.name, fixture.receipt().kind)
        }
    }

    @Test fun `preview reads at most one excess byte and rejects oversized or empty payload before promotion`() = runTest {
        for (contents in listOf("", "1234", "12345")) {
            val fixture = fixture()
            fixture.destination.writeText("previous contents")
            var calls = 0
            val result = runCatching {
                fixture.staging.copy(SOURCE, fixture.destination, 4L, execution(), true) { command, routed ->
                    calls++
                    assertEquals("head -c 5 '$SOURCE' > '${fixture.payload().absolutePath}' 2>/dev/null", command)
                    acknowledge(fixture, routed, contents)
                    Result.success(0 to null)
                }
            }

            assertEquals(contents.length == 4, result.isSuccess)
            assertEquals(if (contents.length == 4) contents else "previous contents", fixture.destination.readText())
            assertEquals(1, calls)
            assertTrue(fixture.directories().isEmpty())
        }
    }

    @Test fun `root cancellation retains uncertain payload and original cancellation outcome`() = runTest {
        for (confirmed in listOf(false, true)) {
            val fixture = fixture()
            fixture.destination.writeText("previous contents")
            val terminal = outcome().copy(
                kind = if (confirmed) RootJobOutcomeKind.CANCELLED else RootJobOutcomeKind.TERMINATION_UNCONFIRMED,
                exitCode = null,
                terminationConfirmed = confirmed,
                outputDrained = confirmed,
            )
            val cancellation = CancellationException("cancel selected file read").apply {
                addSuppressed(IsolatedRootExecutionException(terminal))
            }
            var calls = 0
            val caught = runCatching {
                fixture.staging.copy(SOURCE, fixture.destination, null, execution(), true) { _, routed ->
                    calls++
                    acknowledge(fixture, routed, "partial bytes", terminal)
                    throw cancellation
                }
            }.exceptionOrNull()

            assertSame(cancellation, caught)
            assertEquals(terminal, (caught!!.suppressed.single() as IsolatedRootExecutionException).outcome)
            assertEquals(1, calls)
            assertEquals("previous contents", fixture.destination.readText())
            assertEquals(confirmed, fixture.directories().isEmpty())
            if (!confirmed) assertEquals("partial bytes", fixture.payload().readText())
        }
    }

    @Test fun `cancellation after root acknowledgement cannot replace caller-owned destination`() = runTest {
        val fixture = fixture()
        fixture.destination.writeText("previous contents")
        val read = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.staging.copy(SOURCE, fixture.destination, null, execution(), true) { _, routed ->
                acknowledge(fixture, routed, "complete bytes")
                currentCoroutineContext().cancel()
                Result.success(0 to null)
            }
        }
        read.join()

        assertTrue(read.isCancelled)
        assertEquals("previous contents", fixture.destination.readText())
        assertTrue(fixture.directories().isEmpty())
    }

    @Test fun `nonroot failures use persistent selected executor for one read and noncancellable cleanup`() = runTest {
        for (failure in listOf(
            IOException("ordinary failure"),
            ShellTransportDied(PrivilegeExecutionLane.ARCHIVE),
            CancellationException("cancel read"),
        )) {
            val fixture = fixture()
            fixture.destination.writeText("previous contents")
            val caller = execution().copy(rootExecutionPolicy = RootExecutionPolicy.ISOLATED)
            val commands = mutableListOf<String>()
            val contexts = mutableListOf<PrivilegeExecutionContext>()
            val cleanupFailure = IOException("cleanup failed")
            val caught = runCatching {
                fixture.staging.copy(SOURCE, fixture.destination, 4L, caller, false) { command, routed ->
                    commands += command
                    contexts += routed
                    assertEquals(RootExecutionPolicy.PERSISTENT, routed.rootExecutionPolicy)
                    assertEquals(PrivilegeExecutionLane.ARCHIVE, routed.lane)
                    assertSame(caller.provenance, routed.provenance)
                    if (commands.size == 1) Result.failure(failure) else {
                        currentCoroutineContext().ensureActive()
                        Result.failure(cleanupFailure)
                    }
                }
            }.exceptionOrNull()

            assertSame(failure, caught)
            assertSame(cleanupFailure, caught!!.suppressed.single())
            assertEquals(2, commands.size)
            assertSame(contexts[0], contexts[1])
            val temporaryPath = Regex("/data/local/tmp/thor_read_[a-f0-9-]+").find(commands.first())!!.value
            assertTrue(commands.first().startsWith("head -c 5 '$SOURCE' > '$temporaryPath'"))
            assertEquals("rm -f '$temporaryPath'", commands.last())
            assertEquals("previous contents", fixture.destination.readText())
            assertFalse(fixture.root.exists())
        }
    }

    @Test fun `cancelled nonroot coroutine still cleans up the same temporary path`() = runTest {
        val fixture = fixture()
        val commands = mutableListOf<String>()
        var cleanupCompleted = false
        val read = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.staging.copy(SOURCE, fixture.destination, null, execution(), false) { command, _ ->
                commands += command
                if (commands.size == 1) {
                    currentCoroutineContext().cancel()
                    currentCoroutineContext().ensureActive()
                }
                currentCoroutineContext().ensureActive()
                cleanupCompleted = true
                Result.success(0 to null)
            }
        }
        read.join()

        assertTrue(read.isCancelled)
        assertTrue(cleanupCompleted)
        assertEquals(2, commands.size)
        val temporaryPath = Regex("/data/local/tmp/thor_read_[a-f0-9-]+").find(commands.first())!!.value
        assertEquals("rm -f '$temporaryPath'", commands.last())
        assertFalse(fixture.destination.exists())
    }

    @Test fun `private copy accepts a payload exactly at the bound`() = runTest {
        val source = temporary.newFile("exact-bound-source")
        val destination = temporary.newFile("exact-bound-destination")
        val contents = ByteArray(20_000) { (it % 127).toByte() }
        source.writeBytes(contents)
        destination.writeText("previous contents")

        copyReadPayload(source, destination, contents.size.toLong())

        assertArrayEquals(contents, destination.readBytes())
    }

    @Test fun `private copy never writes past the destination byte bound`() = runTest {
        val source = temporary.newFile("oversized-source")
        val destination = temporary.newFile("oversized-destination")
        val contents = ByteArray(20_001) { (it % 127).toByte() }
        source.writeBytes(contents)
        val maxBytes = 20_000L

        val failure = runCatching { copyReadPayload(source, destination, maxBytes) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(destination.length() <= maxBytes)
        assertArrayEquals(contents.copyOf(destination.length().toInt()), destination.readBytes())
    }

    @Test fun `pre-cancelled private copy preserves the existing destination`() = runTest {
        val source = temporary.newFile("cancelled-source").apply { writeText("new contents") }
        val destination = temporary.newFile("cancelled-destination").apply { writeText("previous contents") }
        val read = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            copyReadPayload(source, destination, 100L)
        }
        read.join()

        assertTrue(read.isCancelled)
        assertEquals("previous contents", destination.readText())
    }

    private fun fixture(): Fixture {
        val parent = temporary.newFolder().canonicalFile
        val noBackup = File(parent, "no_backup")
        val base: Context = ApplicationProvider.getApplicationContext()
        val context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir(): File = noBackup
        }
        return Fixture(context, File(noBackup, PrivilegedReadStaging.DIRECTORY_NAME), File(parent, "selected.db"))
    }

    private class Fixture(context: Context, val root: File, val destination: File) {
        val staging = PrivilegedReadStaging(context)
        fun directories() = root.listFiles()?.toList().orEmpty()
        fun payload() = File(directories().single(), RootExportStaging.PAYLOAD_NAME)
        fun receipt(): RootExportStagingRecord = Json.decodeFromString(
            File(directories().single(), RootExportStaging.RECEIPT_NAME).readText(),
        )
    }

    private suspend fun acknowledge(
        fixture: Fixture,
        routed: PrivilegeExecutionContext,
        contents: String,
        terminal: RootJobOutcome = outcome(),
    ) {
        routed.rootExecutionObserver!!.beforeSubmit()
        fixture.payload().writeText(contents)
        routed.rootExecutionObserver!!.onOutcome(terminal)
    }

    private fun execution() = PrivilegeExecutionContext(
        commandClass = PrivilegeCommandClass("settings_editor.preview"),
        packageName = "com.example.selected",
        workRequestId = WORK,
        sweepRequestId = SWEEP,
        commandTimeout = 2.minutes,
    )

    private fun outcome() = RootJobOutcome(
        RootJobOutcomeKind.EXITED, 0, emptyList(), emptyList(), true, true, true, true, null,
    )

    companion object {
        private const val SOURCE = "/private/selected source.db"
        private val WORK = UUID.fromString("d0b33c84-24a4-47b1-8690-a23110ea0087")
        private val SWEEP = UUID.fromString("24173a01-57ad-4738-9838-670edac53b52")
    }
}
