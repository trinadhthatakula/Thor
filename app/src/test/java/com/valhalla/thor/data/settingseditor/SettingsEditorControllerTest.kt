// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor

import com.valhalla.thor.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsEditorControllerTest {
    private class IdentityCancellation(message: String) : CancellationException(message) {
        // An extra field prevents coroutine stack-trace recovery from copying this fixture.
        val identity = Any()
    }
    private class Fixture {
        var consent = true
        var current = SettingValue.ABSENT
        var refuse = false
        var transportFailure = false
        var historyFailure = false
        var finalHistoryFailure: Exception? = null
        var writeFailure: Exception? = null
        var writeAllowedFailure: Exception? = null
        var writeBarrier: CompletableDeferred<Unit>? = null
        val writeStarted = CompletableDeferred<Unit>()
        val writeAdmissions = mutableListOf<Triple<SettingsEditorView, Int, String>>()
        var reads = 0
        var writes = 0
        var records = emptyList<SettingsEditRecord>()
        val history = object : SettingsEditHistory {
            override fun load() = records
            override fun save(records: List<SettingsEditRecord>) {
                if (historyFailure) error("disk_full")
                if (records.first().outcome != SettingsEditOutcome.PENDING) finalHistoryFailure?.let { throw it }
                this@Fixture.records = records
            }
        }
        val session = object : SettingsEditorSession {
            override val provider = PrivilegeMode.SHIZUKU
            override suspend fun ensureWriteAllowed(view: SettingsEditorView, userId: Int, key: String) {
                writeAdmissions += Triple(view, userId, key)
                writeAllowedFailure?.let { throw it }
            }
            override suspend fun read(view: SettingsEditorView, userId: Int): List<SettingEntry> {
                reads++
                return if (current.present) listOf(SettingEntry("test_key", current.value)) else emptyList()
            }
            override suspend fun write(view: SettingsEditorView, userId: Int, key: String, expected: SettingValue, desired: SettingValue): SettingsBridgeResult {
                assertEquals(SettingsEditOutcome.PENDING, records.first().outcome)
                assertEquals(expected, records.first().before)
                writes++
                writeStarted.complete(Unit)
                writeBarrier?.await()
                writeFailure?.let { throw it }
                if (transportFailure) error("transport_died")
                if (!refuse) current = desired
                return SettingsBridgeResult(read(view, userId))
            }
        }
        val controller = SettingsEditorController({ 10 }, { consent }, { session }, history)
    }
    @Test fun `consent and durable original state are required before any write`() = runTest {
        val f = Fixture()
        f.consent = false
        assertTrue(runCatching { f.controller.change(SettingsEditorView.SECURE, "test_key", SettingValue.ABSENT, SettingValue(true, "value")) }.isFailure)
        assertEquals(0, f.writes)
        f.consent = true; f.historyFailure = true
        assertTrue(runCatching { f.controller.change(SettingsEditorView.SECURE, "test_key", SettingValue.ABSENT, SettingValue(true, "value")) }.isFailure)
        assertEquals(0, f.writes)
    }
    @Test fun `create update delete and undo retain exact values`() = runTest {
        val f = Fixture()
        val literal = SettingValue(true, "null=\n'\"$ value")
        val created = f.controller.change(SettingsEditorView.SECURE, "test_key", SettingValue.ABSENT, literal)
        assertEquals(SettingsEditOutcome.VERIFIED, created.outcome)
        assertEquals(10, created.userId)
        val updated = f.controller.change(SettingsEditorView.SECURE, "test_key", literal, SettingValue(true, ""))
        assertEquals(SettingsEditOutcome.VERIFIED, updated.outcome)
        assertEquals(SettingsEditOutcome.VERIFIED, f.controller.undo(updated.id).outcome)
        assertEquals(literal, f.current)
        val deleted = f.controller.change(SettingsEditorView.SECURE, "test_key", literal, SettingValue.ABSENT)
        assertEquals(SettingsEditOutcome.VERIFIED, f.controller.undo(deleted.id).outcome)
        assertEquals(literal, f.current)
    }
    @Test fun `external edits prevent stale form writes and undo`() = runTest {
        val f = Fixture()
        val first = f.controller.change(SettingsEditorView.GLOBAL, "test_key", SettingValue.ABSENT, SettingValue(true, "a"))
        assertEquals(0, first.userId)
        f.current = SettingValue(true, "external")
        assertTrue(runCatching { f.controller.undo(first.id) }.isFailure)
        assertTrue(runCatching { f.controller.change(SettingsEditorView.GLOBAL, "test_key", first.desired, SettingValue(true, "b")) }.isFailure)
        assertEquals(1, f.writes)
    }
    @Test fun `provider refusal and transport loss are distinct from verified writes`() = runTest {
        val f = Fixture(); f.refuse = true
        assertEquals(SettingsEditOutcome.REJECTED, f.controller.change(SettingsEditorView.SYSTEM, "test_key", SettingValue.ABSENT, SettingValue(true, "x")).outcome)
        f.transportFailure = true
        assertEquals(SettingsEditOutcome.UNKNOWN, f.controller.change(SettingsEditorView.SYSTEM, "test_key", SettingValue.ABSENT, SettingValue(true, "x")).outcome)
        assertEquals(2, f.writes)
        assertEquals(SettingsEditOutcome.UNKNOWN, f.records.first().outcome)
    }
    @Test fun `explicit cancellation records uncertainty then rethrows the same exception`() = runTest {
        val f = Fixture()
        val cancellation = IdentityCancellation("cancelled during dispatch")
        f.writeFailure = cancellation

        val result = runCatching {
            f.controller.change(SettingsEditorView.SECURE, "test_key", SettingValue.ABSENT, SettingValue(true, "x"))
        }

        assertSame(cancellation, result.exceptionOrNull())
        assertEquals(SettingsEditOutcome.UNKNOWN, f.records.single().outcome)
        assertEquals(1, f.writes)
        assertEquals(1, f.reads)
    }
    @Test fun `unconfirmed producer records a distinct outcome and cannot be undone`() = runTest {
        val f = Fixture()
        f.writeFailure = SettingsExecutionUncertain()

        val record = f.controller.change(SettingsEditorView.SECURE, "test_key", SettingValue.ABSENT, SettingValue(true, "x"))

        assertEquals(SettingsEditOutcome.UNCONFIRMED, record.outcome)
        assertEquals(SettingsEditOutcome.UNCONFIRMED, f.records.single().outcome)
        assertTrue(runCatching { f.controller.undo(record.id) }.isFailure)
        f.writeAllowedFailure = SettingsExecutionUncertain()
        assertTrue(runCatching {
            f.controller.change(SettingsEditorView.SECURE, "test_key", SettingValue.ABSENT, SettingValue(true, "another"))
        }.isFailure)
        assertEquals(listOf(record), f.records)
        assertEquals(1, f.reads)
        assertEquals(1, f.writes)
    }
    @Test fun `cancellation records unconfirmed termination or drain without changing its identity`() = runTest {
        for ((terminationConfirmed, outputDrained) in listOf(false to true, true to false, false to false, true to true)) {
            val f = Fixture()
            val cancellation = IdentityCancellation("cancelled during dispatch")
            cancellation.addSuppressed(IsolatedRootExecutionException(RootJobOutcome(
                kind = RootJobOutcomeKind.CANCELLED,
                exitCode = null,
                stdout = emptyList(),
                stderr = emptyList(),
                started = true,
                terminationConfirmed = terminationConfirmed,
                outputDrained = outputDrained,
                shellReusable = true,
                failure = null,
            )))
            f.writeFailure = cancellation

            val result = runCatching {
                f.controller.change(SettingsEditorView.SECURE, "test_key", SettingValue.ABSENT, SettingValue(true, "x"))
            }

            assertSame(cancellation, result.exceptionOrNull())
            val expected = if (terminationConfirmed && outputDrained) SettingsEditOutcome.UNKNOWN else SettingsEditOutcome.UNCONFIRMED
            assertEquals(expected, f.records.single().outcome)
            assertEquals(1, f.writes)
        }
    }
    @Test fun `journal failure is suppressed without replacing explicit cancellation`() = runTest {
        val f = Fixture()
        val cancellation = IdentityCancellation("cancelled during dispatch")
        val journalFailure = IllegalStateException("disk_full")
        f.writeFailure = cancellation
        f.finalHistoryFailure = journalFailure

        val result = runCatching {
            f.controller.change(SettingsEditorView.SECURE, "test_key", SettingValue.ABSENT, SettingValue(true, "x"))
        }

        assertSame(cancellation, result.exceptionOrNull())
        assertEquals(listOf(journalFailure), cancellation.suppressed.toList())
        assertEquals(SettingsEditOutcome.PENDING, f.records.single().outcome)
        assertEquals(1, f.writes)
    }
    @Test fun `recovered cancellation retains nested cleanup uncertainty and the original cause`() = runTest {
        val f = Fixture()
        val original = CancellationException("original cancellation")
        val acknowledgement = IsolatedRootExecutionException(RootJobOutcome(
            kind = RootJobOutcomeKind.TERMINATION_UNCONFIRMED,
            exitCode = null,
            stdout = emptyList(),
            stderr = emptyList(),
            started = true,
            terminationConfirmed = false,
            outputDrained = false,
            shellReusable = false,
            failure = "termination was not acknowledged",
        ))
        original.addSuppressed(acknowledgement)
        val recovered = CancellationException("recovered cancellation").apply { initCause(original) }
        f.writeFailure = recovered

        val result = runCatching {
            f.controller.change(SettingsEditorView.SECURE, "test_key", SettingValue.ABSENT, SettingValue(true, "x"))
        }

        assertTrue(recovered.suppressed.isEmpty())
        val thrown = result.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertTrue(generateSequence(thrown) { it.cause }.take(16).any { it === original })
        assertSame(acknowledgement, original.suppressed.single())
        assertEquals(SettingsEditOutcome.UNCONFIRMED, f.records.single().outcome)
        assertEquals(1, f.writes)
    }
    @Test fun `cancellation metadata traversal tolerates cause and suppressed cycles`() = runTest {
        val f = Fixture()
        val cancellation = IdentityCancellation("cyclic cancellation")
        val nested = IllegalStateException("nested failure")
        cancellation.initCause(nested)
        nested.initCause(cancellation)
        cancellation.addSuppressed(nested)
        nested.addSuppressed(cancellation)
        f.writeFailure = cancellation

        val result = runCatching {
            f.controller.change(SettingsEditorView.SECURE, "test_key", SettingValue.ABSENT, SettingValue(true, "x"))
        }

        assertSame(cancellation, result.exceptionOrNull())
        assertEquals(SettingsEditOutcome.UNKNOWN, f.records.single().outcome)
    }
    @Test fun `screen cancellation still completes mutation readback and journal once`() = runTest {
        val f = Fixture()
        val release = CompletableDeferred<Unit>()
        f.writeBarrier = release
        val desired = SettingValue(true, "finished")
        val job = launch {
            f.controller.change(SettingsEditorView.SECURE, "test_key", SettingValue.ABSENT, desired)
        }
        f.writeStarted.await()

        job.cancel()
        runCurrent()
        assertFalse(job.isCompleted)
        assertEquals(SettingsEditOutcome.PENDING, f.records.single().outcome)
        assertEquals(SettingValue.ABSENT, f.current)

        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(desired, f.current)
        assertEquals(SettingsEditOutcome.VERIFIED, f.records.single().outcome)
        assertEquals(1, f.writes)
        assertEquals(2, f.reads)
    }
    @Test fun `write admission denial happens before preflight read mutation or journal`() = runTest {
        for ((view, userId) in listOf(SettingsEditorView.SECURE to 10, SettingsEditorView.GLOBAL to 0)) {
            val f = Fixture()
            val denied = IllegalStateException("unconfirmed prior operation")
            f.writeAllowedFailure = denied

            val result = runCatching {
                f.controller.change(view, "test_key", SettingValue.ABSENT, SettingValue(true, "x"))
            }

            assertSame(denied, result.exceptionOrNull())
            assertEquals(listOf(Triple(view, userId, "test_key")), f.writeAdmissions)
            assertEquals(0, f.reads)
            assertEquals(0, f.writes)
            assertTrue(f.records.isEmpty())
        }
    }
    @Test fun `selected Dhizuku blocks the editor even when root is available`() {
        val state = PrivilegeState(root = true, shizuku = true, dhizuku = true, active = PrivilegeMode.ROOT, isReady = true)
        assertNull(settingsEditorMode(state, PrivilegeMode.DHIZUKU))
        assertNull(settingsEditorMode(state.copy(active = PrivilegeMode.DHIZUKU), PrivilegeMode.ROOT))
        assertNull(settingsEditorMode(state.copy(isReady = false), null))
        assertEquals(PrivilegeMode.ROOT, settingsEditorMode(state, null))
        assertEquals(PrivilegeMode.SHIZUKU, settingsEditorMode(state.copy(active = PrivilegeMode.SHIZUKU), PrivilegeMode.SHIZUKU))
    }
    @Test fun `absent SQL null empty and literal null remain distinct`() {
        val states = listOf(SettingValue.ABSENT, SettingValue(true, null), SettingValue(true, ""), SettingValue(true, "null"))
        assertEquals(4, states.distinct().size)
        assertFalse(editableSettingKey("a; rm -rf /"))
        assertFalse(editableSettingKey("\n"))
        assertTrue(editableSettingKey("test_key-1.a:b"))
    }
}
