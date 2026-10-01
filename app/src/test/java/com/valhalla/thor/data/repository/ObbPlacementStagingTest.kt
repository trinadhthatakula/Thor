// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.app.Application
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
class ObbPlacementStagingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `root callbacks persist before caller observation and preserve execution context`() = runTest {
        val fixture = fixture()
        val session = fixture.staging.open(PACKAGE, execution())
        val source = File(session.directory, "main.obb").apply { writeText("source bytes") }
        val calls = mutableListOf<String>()
        val external = object : RootExecutionObserver {
            override suspend fun beforeSubmit() {
                assertTrue(fixture.receipt().submitted)
                assertNull(fixture.receipt().kind)
                assertEquals(BOOT_A, fixture.receipt().bootId)
                assertEquals(WORK.toString(), fixture.receipt().workRequestId)
                assertEquals(SWEEP.toString(), fixture.receipt().sweepRequestId)
                calls += "before"
            }

            override suspend fun onOutcome(outcome: RootJobOutcome) {
                assertEquals(outcome.kind.name, fixture.receipt().kind)
                assertTrue(session.canCleanup)
                assertTrue(source.exists())
                assertTrue(fixture.staging.hasUnresolvedPlacement(PACKAGE))
                calls += "outcome"
            }
        }
        val original = execution().copy(rootExecutionObserver = external)
        original.provenance.recordDegradedRootFallback()
        val routed = session.observe(original)
        assertEquals(RootExecutionPolicy.ISOLATED, routed.rootExecutionPolicy)
        assertEquals(original.lane, routed.lane)
        assertEquals(original.commandClass, routed.commandClass)
        assertEquals(original.packageName, routed.packageName)
        assertEquals(original.workRequestId, routed.workRequestId)
        assertEquals(original.sweepRequestId, routed.sweepRequestId)
        assertEquals(original.commandTimeout, routed.commandTimeout)
        assertSame(original.provenance, routed.provenance)
        routed.rootExecutionObserver!!.beforeSubmit()
        assertFalse(session.canCleanup)
        routed.rootExecutionObserver!!.onOutcome(outcome())
        session.close()
        assertEquals(listOf("before", "outcome"), calls)
        assertFalse(source.exists())
        assertFalse(fixture.packageDirectory.exists())
        assertFalse(fixture.staging.hasUnresolvedPlacement(PACKAGE))
    }

    @Test fun `non-root dispatch keeps ordinary cleanup semantics and each session owns a unique directory`() {
        val fixture = fixture()
        val first = fixture.staging.open(PACKAGE, execution())
        first.observe(execution()) // A non-root provider does not invoke Odin's observer.
        File(first.directory, "main.obb").writeText("complete")
        assertTrue(first.canCleanup)
        first.close()
        first.close()
        val second = fixture.staging.open(PACKAGE, execution())
        assertNotEquals(first.directory, second.directory)
        assertFalse(first.directory.exists())
        second.close()
    }

    @Test fun `only acknowledged termination and drain permit source deletion`() = runTest {
        val outcomes = listOf(
            outcome(),
            outcome().copy(exitCode = 1, shellReusable = false),
            outcome().copy(kind = RootJobOutcomeKind.CANCELLED, exitCode = null),
            outcome().copy(kind = RootJobOutcomeKind.FAILED, started = false, exitCode = null),
            outcome().copy(terminationConfirmed = false),
            outcome().copy(outputDrained = false),
            outcome().copy(kind = RootJobOutcomeKind.TERMINATION_UNCONFIRMED),
        )
        for (terminal in outcomes) {
            val fixture = fixture()
            val session = fixture.staging.open(PACKAGE, execution())
            val observer = session.observe(execution()).rootExecutionObserver!!
            val payload = File(session.directory, "main.obb").apply { writeText("partial or complete") }
            observer.beforeSubmit()
            observer.onOutcome(terminal)
            assertEquals(terminal.cleanupConfirmed, session.canCleanup)
            session.close()
            assertEquals(!terminal.cleanupConfirmed, payload.exists())
            assertEquals(!terminal.cleanupConfirmed, fixture.staging.hasUnresolvedPlacement(PACKAGE))
        }
    }

    @Test fun `a completed first command cannot mask a missing second acknowledgement`() = runTest {
        val fixture = fixture()
        val session = fixture.staging.open(PACKAGE, execution())
        val first = session.observe(execution()).rootExecutionObserver!!
        first.beforeSubmit()
        first.onOutcome(outcome())
        val second = session.observe(execution()).rootExecutionObserver!!
        second.beforeSubmit()
        File(session.directory, "main.obb").writeText("root may still be reading")
        assertNull(fixture.receipt().kind)
        assertFalse(session.canCleanup)
        assertTrue(runCatching { session.observe(execution()) }.exceptionOrNull() is IOException)
        session.close()
        assertTrue(session.directory.exists())
        assertTrue(runCatching { fixture.staging.open(PACKAGE, execution()) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { fixture.staging.checkAvailable(PACKAGE) }.exceptionOrNull() is IOException)
        fixture.staging.open(OTHER_PACKAGE, execution()).close()
    }

    @Test fun `active ownership excludes another instance even if its boot identity differs`() {
        val fixture = fixture()
        val session = fixture.staging.open(PACKAGE, execution())
        val other = fixture.withBoot(BOOT_B)
        assertTrue(other.hasUnresolvedPlacement(PACKAGE))
        assertTrue(runCatching { other.checkAvailable(PACKAGE) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { other.open(PACKAGE, execution()) }.exceptionOrNull() is IOException)
        assertTrue(session.directory.exists())
        session.close()
        other.checkAvailable(PACKAGE)
    }

    @Test fun `restart retains pending work until a different known canonical boot`() {
        for (boot in listOf(BOOT_A, null, "not-a-boot-identity", BOOT_A.uppercase())) {
            val fixture = fixture()
            val source = seed(fixture, record().copy(submitted = true))
            val restarted = fixture.withBoot(boot)
            assertTrue(restarted.hasUnresolvedPlacement(PACKAGE))
            assertTrue(runCatching { restarted.checkAvailable(PACKAGE) }.exceptionOrNull() is IOException)
            assertTrue(source.exists())
        }
        val fixture = fixture()
        val source = seed(fixture, record().copy(submitted = true))
        val finalObb = File(fixture.parent, "final.obb").apply { writeText("do not roll back") }
        val unrelated = File(fixture.sources, UUID.randomUUID().toString()).apply { mkdirs() }
        val restarted = fixture.withBoot(BOOT_B)
        assertFalse(restarted.hasUnresolvedPlacement(PACKAGE))
        assertTrue(source.exists()) // The query is read-only.
        restarted.checkAvailable(PACKAGE)
        assertFalse(source.exists())
        assertFalse(fixture.packageDirectory.exists())
        assertTrue(unrelated.exists())
        assertEquals("do not roll back", finalObb.readText())
    }

    @Test fun `a missing original boot keeps pending work but acknowledged work needs no boot evidence`() {
        val pending = fixture()
        val source = seed(pending, record().copy(bootId = null, submitted = true))
        assertTrue(pending.withBoot(BOOT_B).hasUnresolvedPlacement(PACKAGE))
        assertTrue(source.exists())

        val acknowledged = fixture()
        val completeSource = seed(acknowledged, record().copy(bootId = null).withOutcome(outcome()))
        acknowledged.withBoot(null).checkAvailable(PACKAGE)
        assertFalse(completeSource.exists())
    }

    @Test fun `prepared work is recoverable without submitting or allocating replacement source`() {
        val fixture = fixture()
        val source = seed(fixture, record().copy(bootId = null))
        fixture.withBoot(null).checkAvailable(PACKAGE)
        assertFalse(source.exists())
        assertTrue(fixture.sources.listFiles().orEmpty().isEmpty())
        val unopened = fixture()
        unopened.staging.checkAvailable(PACKAGE)
        assertFalse(unopened.sources.exists())
        assertFalse(unopened.receipts.exists())
    }

    @Test fun `corrupt incompatible or mismatched records never authorize recovery`() {
        val variants = listOf(
            record().copy(id = "../outside"),
            record().copy(packageName = OTHER_PACKAGE),
            record().copy(version = 2),
            record().copy(workRequestId = "bad-id"),
            record().copy(sweepRequestId = "bad-id"),
            record().copy(bootId = "bad-boot"),
            record().copy(submitted = true, kind = "FUTURE", terminationConfirmed = true, outputDrained = true),
            record().copy(started = true),
            record().withOutcome(outcome()).copy(submitted = false),
        )
        for (invalid in variants) {
            val fixture = fixture()
            val valid = record()
            val source = seed(fixture, valid)
            File(fixture.packageDirectory, ObbPlacementStaging.RECEIPT_NAME).writeText(Json.encodeToString(invalid))
            val restarted = fixture.withBoot(BOOT_B)
            assertTrue(restarted.hasUnresolvedPlacement(PACKAGE))
            assertTrue(runCatching { restarted.checkAvailable(PACKAGE) }.exceptionOrNull() is IOException)
            assertTrue(source.exists())
        }
    }

    @Test fun `missing unreadable and symlink receipts fail closed without following links`() {
        for (mode in listOf("missing", "corrupt", "directory", "symlink")) {
            val fixture = fixture()
            val source = seed(fixture, record())
            val receipt = File(fixture.packageDirectory, ObbPlacementStaging.RECEIPT_NAME)
            receipt.delete()
            val outside = File(fixture.parent, "outside-receipt").apply { writeText("keep") }
            when (mode) {
                "corrupt" -> receipt.writeText("not json")
                "directory" -> receipt.mkdir()
                "symlink" -> Files.createSymbolicLink(receipt.toPath(), outside.toPath())
            }
            assertTrue(fixture.staging.hasUnresolvedPlacement(PACKAGE))
            assertTrue(runCatching { fixture.withBoot(BOOT_B).checkAvailable(PACKAGE) }.exceptionOrNull() is IOException)
            assertTrue(source.exists())
            assertEquals("keep", outside.readText())
        }
    }

    @Test fun `a rejected before-submit hook allows cleanup and preserves cancellation`() = runTest {
        val fixture = fixture()
        val cancelled = CancellationException("cancel before dispatch")
        val session = fixture.staging.open(PACKAGE, execution())
        val routed = session.observe(execution().copy(rootExecutionObserver = object : RootExecutionObserver {
            override suspend fun beforeSubmit() { throw cancelled }
            override suspend fun onOutcome(outcome: RootJobOutcome) = Unit
        }))
        assertSame(cancelled, runCatching { routed.rootExecutionObserver!!.beforeSubmit() }.exceptionOrNull())
        assertTrue(session.canCleanup)
        session.close()
        assertFalse(session.directory.exists())
        assertFalse(fixture.packageDirectory.exists())
    }

    @Test fun `pending receipt failure prevents dispatch and safely cleans an unsubmitted source`() = runTest {
        val fixture = fixture()
        val session = fixture.staging.open(PACKAGE, execution())
        File(fixture.packageDirectory, ObbPlacementStaging.RECEIPT_NAME + ".new").mkdir()
        var externalCalled = false
        val observer = session.observe(execution().copy(rootExecutionObserver = object : RootExecutionObserver {
            override suspend fun beforeSubmit() { externalCalled = true }
            override suspend fun onOutcome(outcome: RootJobOutcome) = Unit
        })).rootExecutionObserver!!
        assertTrue(runCatching { observer.beforeSubmit() }.exceptionOrNull() is IOException)
        assertFalse(externalCalled)
        assertTrue(session.canCleanup)
        session.close()
        assertFalse(session.directory.exists())
    }

    @Test fun `terminal receipt failure retains ownership and source even with an acknowledged outcome`() = runTest {
        val fixture = fixture()
        val session = fixture.staging.open(PACKAGE, execution())
        val observer = session.observe(execution()).rootExecutionObserver!!
        observer.beforeSubmit()
        File(fixture.packageDirectory, ObbPlacementStaging.RECEIPT_NAME + ".bak").mkdir()
        assertTrue(runCatching { observer.onOutcome(outcome()) }.exceptionOrNull() is IOException)
        assertFalse(session.canCleanup)
        session.close()
        assertTrue(session.directory.exists())
        assertTrue(fixture.staging.hasUnresolvedPlacement(PACKAGE))
    }

    @Test fun `caller outcome failure happens after durable cleanup acknowledgement`() = runTest {
        val fixture = fixture()
        val session = fixture.staging.open(PACKAGE, execution())
        val failure = IOException("caller record failed")
        val observer = session.observe(execution().copy(rootExecutionObserver = object : RootExecutionObserver {
            override suspend fun onOutcome(outcome: RootJobOutcome) { throw failure }
        })).rootExecutionObserver!!
        observer.beforeSubmit()
        assertSame(failure, runCatching { observer.onOutcome(outcome()) }.exceptionOrNull())
        assertTrue(session.canCleanup)
        session.close()
        assertFalse(session.directory.exists())
    }

    @Test fun `a stale observer cannot replace the current pending receipt`() = runTest {
        val fixture = fixture()
        val session = fixture.staging.open(PACKAGE, execution())
        val first = session.observe(execution()).rootExecutionObserver!!
        first.beforeSubmit()
        first.onOutcome(outcome())
        val second = session.observe(execution()).rootExecutionObserver!!
        second.beforeSubmit()
        assertTrue(runCatching { first.onOutcome(outcome()) }.exceptionOrNull() is IOException)
        assertNull(fixture.receipt().kind)
        assertFalse(session.canCleanup)
        second.onOutcome(outcome())
        session.close()
    }

    @Test fun `metadata excludes source path output and failure content`() = runTest {
        val fixture = fixture()
        val session = fixture.staging.open(PACKAGE, execution())
        val observer = session.observe(execution()).rootExecutionObserver!!
        observer.beforeSubmit()
        observer.onOutcome(outcome().copy(
            kind = RootJobOutcomeKind.TERMINATION_UNCONFIRMED,
            stdout = listOf("private output"),
            stderr = listOf("private diagnostic"),
            failure = "private failure text",
        ))
        session.close()
        val text = File(fixture.packageDirectory, ObbPlacementStaging.RECEIPT_NAME).readText()
        for (secret in listOf(session.directory.absolutePath, "private output", "private diagnostic", "private failure text", "stdout", "stderr", "command")) {
            assertFalse("Receipt leaked $secret", text.contains(secret))
        }
        assertTrue(fixture.receipt().hasFailure)
    }

    @Test fun `source reclamation unlinks symlinks without traversing their targets`() {
        val fixture = fixture()
        val source = seed(fixture, record())
        val outside = File(fixture.parent, "outside").apply { mkdir() }
        val valuable = File(outside, "keep").apply { writeText("unrelated") }
        Files.createSymbolicLink(File(source, "linked").toPath(), outside.toPath())
        fixture.staging.checkAvailable(PACKAGE)
        assertFalse(source.exists())
        assertEquals("unrelated", valuable.readText())
    }

    private fun fixture(): Fixture = Fixture(temporary.newFolder())

    private class Fixture(val parent: File) {
        val receipts = File(parent, "private-receipts")
        val sources = File(parent, "external-sources")
        val packageDirectory = File(receipts, PACKAGE)
        val staging = withBoot(BOOT_A)
        fun withBoot(boot: String?) = ObbPlacementStaging(receipts, sources) { boot }
        fun receipt(): ObbPlacementRecord = Json.decodeFromString(
            File(packageDirectory, ObbPlacementStaging.RECEIPT_NAME).readText(),
        )
    }

    private fun seed(fixture: Fixture, record: ObbPlacementRecord): File {
        fixture.packageDirectory.mkdirs()
        File(fixture.packageDirectory, ObbPlacementStaging.RECEIPT_NAME).writeText(Json.encodeToString(record))
        return File(fixture.sources, record.id).apply {
            mkdirs()
            File(this, "main.obb").writeText("retained source")
        }
    }

    private fun record() = ObbPlacementRecord(
        UUID.randomUUID().toString(), PACKAGE, WORK.toString(), SWEEP.toString(), BOOT_A,
    )

    private fun execution() = PrivilegeExecutionContext(
        lane = PrivilegeExecutionLane.ARCHIVE,
        commandClass = PrivilegeCommandClass("obb.place"),
        packageName = PACKAGE,
        workRequestId = WORK,
        sweepRequestId = SWEEP,
        commandTimeout = 2.minutes,
    )

    private fun outcome() = RootJobOutcome(
        RootJobOutcomeKind.EXITED, 0, emptyList(), emptyList(), true, true, true, true, null,
    )

    companion object {
        private const val PACKAGE = "com.example.game"
        private const val OTHER_PACKAGE = "com.example.other"
        private const val BOOT_A = "ae8f781d-0b12-40c1-8303-17ff7f9121e7"
        private const val BOOT_B = "24173a01-57ad-4738-9838-670edac53b52"
        private val WORK = UUID.fromString("d0b33c84-24a4-47b1-8690-a23110ea0087")
        private val SWEEP = UUID.fromString("fa60fb7d-25f1-4a8c-a10e-e47e6684605d")
    }
}
