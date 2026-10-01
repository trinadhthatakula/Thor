// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.app.Application
import com.valhalla.thor.domain.model.IsolatedRootExecutionException
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.RootExecutionObserver
import com.valhalla.thor.domain.model.RootExecutionPolicy
import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class RootExportStagingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `confirmed copy preserves execution identity and records before external observers`() = runTest {
        val fixture = fixture()
        fixture.destination.writeText("previous partial copy")
        val events = mutableListOf<String>()
        val external = object : RootExecutionObserver {
            override suspend fun beforeSubmit() {
                val receipt = fixture.receipt()
                assertNull(receipt.kind)
                assertEquals(BOOT_A, receipt.bootId)
                assertEquals(PACKAGE, receipt.packageName)
                assertEquals(WORK.toString(), receipt.workRequestId)
                events += "external-before"
            }

            override suspend fun onOutcome(outcome: RootJobOutcome) {
                assertEquals(outcome.kind.name, fixture.receipt().kind)
                assertEquals("previous partial copy", fixture.destination.readText())
                assertEquals(0, RootExportStaging(fixture.root) { BOOT_B }.sweep())
                assertTrue(fixture.payload().exists())
                events += "external-outcome"
            }
        }
        val execution = execution().copy(rootExecutionObserver = external)
        execution.provenance.recordDegradedRootFallback()
        fixture.staging.copy(SECRET_SOURCE, fixture.destination, execution) { source, path, routed ->
            assertEquals(SECRET_SOURCE, source)
            assertEquals(RootExecutionPolicy.ISOLATED, routed.rootExecutionPolicy)
            assertEquals(execution.lane, routed.lane)
            assertEquals(execution.commandClass, routed.commandClass)
            assertEquals(execution.packageName, routed.packageName)
            assertEquals(execution.workRequestId, routed.workRequestId)
            assertEquals(execution.sweepRequestId, routed.sweepRequestId)
            assertEquals(execution.commandTimeout, routed.commandTimeout)
            assertSame(execution.provenance, routed.provenance)
            assertTrue(File(path).isFile)
            assertNotEquals(fixture.destination.absolutePath, path)
            routed.rootExecutionObserver!!.beforeSubmit()
            File(path).writeText("complete bytes")
            events += "copied"
            routed.rootExecutionObserver!!.onOutcome(outcome().copy(shellReusable = false))
            events += "acknowledged"
            Result.success(Unit)
        }
        assertEquals(listOf("external-before", "copied", "external-outcome", "acknowledged"), events)
        assertEquals("complete bytes", fixture.destination.readText())
        assertTrue(fixture.directories().isEmpty())
    }

    @Test fun `unconfirmed termination or drain cannot publish even when gateway returns success`() = runTest {
        val outcomes = listOf(false to false, false to true, true to false, true to true).map { (terminated, drained) ->
            outcome().copy(
                kind = RootJobOutcomeKind.TERMINATION_UNCONFIRMED,
                terminationConfirmed = terminated,
                outputDrained = drained,
            )
        } + listOf(
            outcome().copy(terminationConfirmed = false),
            outcome().copy(outputDrained = false),
        )
        for (terminal in outcomes) {
            val fixture = fixture()
            val failure = runCatching {
                fixture.staging.copy(SECRET_SOURCE, fixture.destination, execution()) { _, path, routed ->
                    routed.rootExecutionObserver!!.beforeSubmit()
                    File(path).writeText("unconfirmed bytes")
                    routed.rootExecutionObserver!!.onOutcome(terminal)
                    Result.success(Unit)
                }
            }.exceptionOrNull()
            assertTrue(failure is IsolatedRootExecutionException)
            assertEquals(terminal, (failure as IsolatedRootExecutionException).outcome)
            assertFalse(fixture.destination.exists())
            assertEquals("unconfirmed bytes", fixture.payload().readText())
            assertEquals(terminal.kind.name, fixture.receipt().kind)
            assertEquals(0, RootExportStaging(fixture.root) { BOOT_A }.sweep())
        }
    }

    @Test fun `confirmed unsuccessful outcomes clean up without publishing`() = runTest {
        for (terminal in listOf(
            outcome().copy(exitCode = 1),
            outcome().copy(kind = RootJobOutcomeKind.CANCELLED, exitCode = null),
            outcome().copy(kind = RootJobOutcomeKind.FAILED, exitCode = null, started = false),
            outcome().copy(started = false),
        )) {
            val fixture = fixture()
            val failure = runCatching {
                fixture.staging.copy(SECRET_SOURCE, fixture.destination, execution()) { _, path, routed ->
                    routed.rootExecutionObserver!!.beforeSubmit()
                    File(path).writeText("not publishable")
                    routed.rootExecutionObserver!!.onOutcome(terminal)
                    Result.success(Unit)
                }
            }.exceptionOrNull()
            assertTrue(failure is IsolatedRootExecutionException)
            assertFalse(fixture.destination.exists())
            assertTrue(fixture.directories().isEmpty())
        }
    }

    @Test fun `cancellation identity and attached outcome survive with cleanup only after confirmation`() = runTest {
        for (confirmed in listOf(false, true)) {
            val fixture = fixture()
            val terminal = outcome().copy(
                kind = if (confirmed) RootJobOutcomeKind.CANCELLED else RootJobOutcomeKind.TERMINATION_UNCONFIRMED,
                exitCode = null,
                terminationConfirmed = confirmed,
                outputDrained = confirmed,
            )
            val cancelled = CancellationException("cancel export").apply {
                addSuppressed(IsolatedRootExecutionException(terminal))
            }
            val failure = runCatching {
                fixture.staging.copy(SECRET_SOURCE, fixture.destination, execution()) { _, path, routed ->
                    routed.rootExecutionObserver!!.beforeSubmit()
                    File(path).writeText("partial")
                    routed.rootExecutionObserver!!.onOutcome(terminal)
                    throw cancelled
                }
            }.exceptionOrNull()
            assertSame(cancelled, failure)
            assertEquals(terminal, (failure!!.suppressed.single() as IsolatedRootExecutionException).outcome)
            assertFalse(fixture.destination.exists())
            assertEquals(confirmed, fixture.directories().isEmpty())
        }
    }

    @Test fun `absence of terminal callback leaves pending receipt and never promotes or reuses payload`() = runTest {
        val fixture = fixture()
        val paths = mutableListOf<String>()
        repeat(2) {
            val result = runCatching {
                fixture.staging.copy(SECRET_SOURCE, fixture.destination, execution()) { _, path, routed ->
                    paths += path
                    routed.rootExecutionObserver!!.beforeSubmit()
                    File(path).writeText("pending-$it")
                    Result.success(Unit)
                }
            }
            assertTrue(result.exceptionOrNull() is IOException)
        }
        assertEquals(2, paths.distinct().size)
        assertEquals(listOf("pending-0", "pending-1"), paths.map { File(it).readText() })
        assertFalse(fixture.destination.exists())
        assertTrue(fixture.directories().all { readReceipt(it).kind == null })
        assertEquals(0, RootExportStaging(fixture.root) { BOOT_A }.sweep())
    }

    @Test fun `failure before submission and failed submission hook release only their own workspace`() = runTest {
        val fixture = fixture()
        val refusal = IOException("root unavailable")
        assertSame(refusal, runCatching {
            fixture.staging.copy(SECRET_SOURCE, fixture.destination, execution()) { _, _, _ -> Result.failure(refusal) }
        }.exceptionOrNull())
        assertTrue(fixture.directories().isEmpty())

        val rejectedHook = CancellationException("cancel before submit")
        val external = object : RootExecutionObserver {
            override suspend fun beforeSubmit() { throw rejectedHook }
            override suspend fun onOutcome(outcome: RootJobOutcome) = Unit
        }
        assertSame(rejectedHook, runCatching {
            fixture.staging.copy(SECRET_SOURCE, fixture.destination, execution().copy(rootExecutionObserver = external)) { _, _, routed ->
                routed.rootExecutionObserver!!.beforeSubmit()
                fail("A failed observer must not dispatch work")
                Result.success(Unit)
            }
        }.exceptionOrNull())
        assertTrue(fixture.directories().isEmpty())
    }

    @Test fun `pending persistence failure prevents submission and terminal persistence failure retains resources`() = runTest {
        val initial = fixture()
        var submitted = false
        val initialFailure = runCatching {
            initial.staging.copy(SECRET_SOURCE, initial.destination, execution()) { _, path, routed ->
                File(File(path).parentFile, RootExportStaging.RECEIPT_NAME).mkdir()
                routed.rootExecutionObserver!!.beforeSubmit()
                submitted = true
                Result.success(Unit)
            }
        }.exceptionOrNull()
        assertTrue(initialFailure is IOException)
        assertFalse(submitted)
        assertTrue(initial.directories().isEmpty())

        val terminal = fixture()
        val terminalFailure = runCatching {
            terminal.staging.copy(SECRET_SOURCE, terminal.destination, execution()) { _, path, routed ->
                routed.rootExecutionObserver!!.beforeSubmit()
                File(path).writeText("completed but not durably acknowledged")
                val receipt = File(File(path).parentFile, RootExportStaging.RECEIPT_NAME)
                assertTrue(receipt.delete())
                assertTrue(receipt.mkdir())
                routed.rootExecutionObserver!!.onOutcome(outcome())
                Result.success(Unit)
            }
        }.exceptionOrNull()
        assertTrue(terminalFailure is IOException)
        assertFalse(terminal.destination.exists())
        assertTrue(terminal.payload().exists())
        assertEquals(0, RootExportStaging(terminal.root) { BOOT_B }.sweep())
    }

    @Test fun `external acknowledgement failure remains primary after safe cleanup`() = runTest {
        val fixture = fixture()
        val failure = CancellationException("external outcome cancelled")
        val external = object : RootExecutionObserver {
            override suspend fun onOutcome(outcome: RootJobOutcome) { throw failure }
        }
        val actual = runCatching {
            fixture.staging.copy(SECRET_SOURCE, fixture.destination, execution().copy(rootExecutionObserver = external)) { _, _, routed ->
                routed.rootExecutionObserver!!.beforeSubmit()
                routed.rootExecutionObserver!!.onOutcome(outcome())
                Result.success(Unit)
            }
        }.exceptionOrNull()
        assertSame(failure, actual)
        assertFalse(fixture.destination.exists())
        assertTrue(fixture.directories().isEmpty())
    }

    @Test fun `sweep requires an acknowledged outcome or a different known canonical boot`() = runTest {
        for (recordedBoot in listOf(BOOT_A, null, "not-a-boot", "0-0-0-0-1", BOOT_A.uppercase())) {
            for (currentBoot in listOf(BOOT_A, BOOT_B, null, "not-a-boot", BOOT_A.uppercase())) {
                val fixture = fixture()
                val directory = seed(fixture, record(bootId = recordedBoot))
                val expected = recordedBoot == BOOT_A && currentBoot == BOOT_B
                assertEquals(if (expected) 1 else 0, RootExportStaging(fixture.root) { currentBoot }.sweep())
                assertEquals(!expected, directory.exists())
            }
        }
        val fixture = fixture()
        seed(fixture, record(bootId = null).withOutcome(outcome().copy(shellReusable = false)))
        assertEquals(1, RootExportStaging(fixture.root) { null }.sweep())
    }

    @Test fun `sweep retains missing corrupt unknown and mismatched receipts even after reboot`() {
        val fixture = fixture()
        val missing = File(fixture.root, UUID.randomUUID().toString()).apply { mkdirs() }
        File(missing, RootExportStaging.PAYLOAD_NAME).writeText("missing receipt")
        val corrupt = seed(fixture, record())
        File(corrupt, RootExportStaging.RECEIPT_NAME).writeText("not json")
        val mismatch = seed(fixture, record())
        File(mismatch, RootExportStaging.RECEIPT_NAME).writeText(Json.encodeToString(record()))
        seed(fixture, record().copy(version = 2))
        seed(fixture, record().copy(kind = "FUTURE_OUTCOME", terminationConfirmed = true, outputDrained = true))
        seed(fixture, record().copy(workRequestId = "bad identity"))
        val before = fixture.directories().toSet()
        assertEquals(0, RootExportStaging(fixture.root) { BOOT_B }.sweep())
        assertEquals(before, fixture.directories().toSet())
    }

    @Test fun `receipt stores identities and terminal flags without source output or failure text`() = runTest {
        val fixture = fixture()
        val terminal = outcome().copy(
            kind = RootJobOutcomeKind.TERMINATION_UNCONFIRMED,
            terminationConfirmed = false,
            outputDrained = false,
            stdout = listOf("private-stdout"),
            stderr = listOf("private-stderr"),
            failure = "private-failure-text",
        )
        runCatching {
            fixture.staging.copy(SECRET_SOURCE, fixture.destination, execution()) { _, _, routed ->
                routed.rootExecutionObserver!!.beforeSubmit()
                routed.rootExecutionObserver!!.onOutcome(terminal)
                Result.failure(IOException("refused"))
            }
        }
        val receipt = fixture.receipt()
        assertEquals(PACKAGE, receipt.packageName)
        assertEquals(WORK.toString(), receipt.workRequestId)
        assertEquals(true, receipt.started)
        assertEquals(false, receipt.terminationConfirmed)
        assertEquals(false, receipt.outputDrained)
        assertTrue(receipt.hasFailure)
        val text = File(fixture.directories().single(), RootExportStaging.RECEIPT_NAME).readText()
        for (secret in listOf(SECRET_SOURCE, "private-stdout", "private-stderr", "private-failure-text", "stdout", "stderr", "command")) {
            assertFalse("Receipt leaked $secret", text.contains(secret))
        }
    }

    @Test fun `atomic promotion failure leaves existing destination intact and releases confirmed payload`() = runTest {
        val fixture = fixture()
        assertTrue(fixture.destination.mkdir())
        val existing = File(fixture.destination, "keep").apply { writeText("original") }
        val failure = runCatching {
            fixture.staging.copy(SECRET_SOURCE, fixture.destination, execution()) { _, path, routed ->
                routed.rootExecutionObserver!!.beforeSubmit()
                File(path).writeText("complete")
                routed.rootExecutionObserver!!.onOutcome(outcome())
                Result.success(Unit)
            }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("original", existing.readText())
        assertTrue(fixture.directories().isEmpty())
    }

    @Test fun `sweep never follows a receipt symlink`() {
        val fixture = fixture()
        val outside = temporary.newFile("external-receipt").apply {
            writeText(Json.encodeToString(record().withOutcome(outcome())))
        }
        val directory = File(fixture.root, UUID.randomUUID().toString()).apply { mkdirs() }
        Files.createSymbolicLink(File(directory, RootExportStaging.RECEIPT_NAME).toPath(), outside.toPath())
        val before = outside.readText()
        assertEquals(0, RootExportStaging(fixture.root) { BOOT_B }.sweep())
        assertTrue(directory.exists())
        assertEquals(before, outside.readText())
    }

    private fun fixture(): Fixture {
        val parent = temporary.newFolder()
        return Fixture(File(parent, "root_export_staging"), File(parent, "complete.apk"))
    }

    private class Fixture(val root: File, val destination: File) {
        val staging = RootExportStaging(root) { BOOT_A }
        fun directories() = root.listFiles()?.toList().orEmpty()
        fun payload() = File(directories().single(), RootExportStaging.PAYLOAD_NAME)
        fun receipt() = readReceipt(directories().single())
    }

    private fun seed(fixture: Fixture, record: RootExportStagingRecord): File =
        File(fixture.root, record.id).apply {
            mkdirs()
            File(this, RootExportStaging.PAYLOAD_NAME).writeText("retained bytes")
            File(this, RootExportStaging.RECEIPT_NAME).writeText(Json.encodeToString(record))
        }

    private fun record(bootId: String? = BOOT_A) = RootExportStagingRecord(
        id = UUID.randomUUID().toString(), packageName = PACKAGE,
        workRequestId = WORK.toString(), sweepRequestId = null, bootId = bootId,
    )

    private fun execution() = PrivilegeExecutionContext(
        lane = PrivilegeExecutionLane.ARCHIVE,
        commandClass = PrivilegeCommandClass("file.copy"),
        packageName = PACKAGE,
        workRequestId = WORK,
        commandTimeout = 2.minutes,
    )

    private fun outcome() = RootJobOutcome(
        RootJobOutcomeKind.EXITED, 0, emptyList(), emptyList(), true, true, true, true, null,
    )

    companion object {
        private const val BOOT_A = "ae8f781d-0b12-40c1-8303-17ff7f9121e7"
        private const val BOOT_B = "24173a01-57ad-4738-9838-670edac53b52"
        private const val PACKAGE = "com.example.exported"
        private const val SECRET_SOURCE = "/private/source-must-not-be-recorded.apk"
        private val WORK = UUID.fromString("d0b33c84-24a4-47b1-8690-a23110ea0087")
        internal fun readReceipt(directory: File): RootExportStagingRecord =
            Json.decodeFromString(File(directory, RootExportStaging.RECEIPT_NAME).readText())
    }
}
