// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor

import com.valhalla.thor.domain.model.*
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsEditorReconciliationTest {
    private class Fixture(initial: SettingsEditRecord = record()) {
        var records = listOf(initial)
        var current = initial.desired
        var userId = 10
        var consent = true
        var provider = PrivilegeMode.SHIZUKU
        var clock = 200L
        var openFailure: Exception? = null
        var readFailure: Exception? = null
        var saveFailure: Exception? = null
        var response: List<SettingEntry>? = null
        var afterRead: suspend () -> Unit = {}
        var writeAdmission: suspend (SettingsRootResource) -> Unit = {}
        var opens = 0
        var saves = 0
        var writes = 0
        val reads = mutableListOf<Pair<SettingsEditorView, Int>>()
        val writeAdmissions = mutableListOf<SettingsRootResource>()
        val readStarted = CompletableDeferred<Unit>()
        val history = object : SettingsEditHistory {
            override fun load() = records
            override fun save(records: List<SettingsEditRecord>) {
                saves++
                saveFailure?.let { throw it }
                this@Fixture.records = records
            }
        }
        val session = object : SettingsEditorSession {
            override val provider get() = this@Fixture.provider
            override suspend fun read(view: SettingsEditorView, userId: Int): List<SettingEntry> {
                reads += view to userId
                readStarted.complete(Unit)
                readFailure?.let { throw it }
                val entries = response ?: if (current.present) listOf(SettingEntry(KEY, current.value)) else emptyList()
                afterRead()
                return entries
            }
            override suspend fun ensureWriteAllowed(view: SettingsEditorView, userId: Int, key: String) {
                val resource = SettingsRootResource(view, userId, key)
                writeAdmissions += resource
                writeAdmission(resource)
            }
            override suspend fun write(view: SettingsEditorView, userId: Int, key: String, expected: SettingValue, desired: SettingValue): SettingsBridgeResult {
                writes++
                current = desired
                return SettingsBridgeResult(if (current.present) listOf(SettingEntry(key, current.value)) else emptyList())
            }
        }
        val controller = SettingsEditorController({ userId }, { consent }, {
            opens++
            openFailure?.let { throw it }
            session
        }, history, { clock })
    }

    @Test fun `current observations preserve exact values and never verify uncertain writes`() = runTest {
        val values = listOf(SettingValue.ABSENT, SettingValue(true, null), SettingValue(true, ""),
            SettingValue(true, "null"), SettingValue(true, "different=\n'\" value"))
        for (outcome in listOf(SettingsEditOutcome.PENDING, SettingsEditOutcome.UNKNOWN, SettingsEditOutcome.UNCONFIRMED)) {
            for (value in values + record().desired) {
                val original = record().copy(outcome = outcome)
                val f = Fixture(original).apply { current = value }

                val observed = f.controller.reconcile(original.id)

                assertEquals(original.copy(observation = SettingsEditObservation(value, PrivilegeMode.SHIZUKU, f.clock)), observed)
                assertEquals(listOf(observed), f.records)
                assertEquals(original, observed.copy(observation = null))
                assertEquals(1, f.opens)
                assertEquals(0, f.writes)
                assertTrue(f.writeAdmissions.isEmpty())
                assertTrue(runCatching { f.controller.undo(original.id) }.isFailure)
                assertEquals(0, f.writes)
            }
        }
    }

    @Test fun `reconciliation reads the recorded table and Android user while preserving history order`() = runTest {
        for ((view, userId) in listOf(SettingsEditorView.SECURE to 10, SettingsEditorView.SYSTEM to 10, SettingsEditorView.GLOBAL to 0)) {
            val original = record().copy(view = view, userId = userId)
            val f = Fixture(original)
            val newer = original.copy(id = "newer", timestamp = 150L)
            val older = original.copy(id = "older", timestamp = 50L)
            f.records = listOf(newer, original, older)

            val observed = f.controller.reconcile(original.id)

            assertEquals(listOf(view to userId), f.reads)
            assertEquals(listOf(newer, observed, older), f.records)
            assertEquals(0, f.writes)
        }
    }

    @Test fun `wrong user or malformed history identity refuses before provider access`() = runTest {
        val invalid = listOf(
            record().copy(userId = 0),
            record().copy(view = SettingsEditorView.GLOBAL, userId = 10),
            record().copy(view = SettingsEditorView.ANDROID_PROPERTIES),
            record().copy(key = "bad;key"),
        )
        for (original in invalid) {
            val f = Fixture(original)
            assertTrue(runCatching { f.controller.reconcile(original.id) }.isFailure)
            assertEquals(listOf(original), f.records)
            assertEquals(0, f.opens)
            assertEquals(0, f.saves)
            assertEquals(0, f.writes)
        }
    }

    @Test fun `missing or ambiguous history and completed outcomes cannot be reconciled`() = runTest {
        for (outcome in listOf(SettingsEditOutcome.VERIFIED, SettingsEditOutcome.REJECTED, SettingsEditOutcome.CONFLICT)) {
            val original = record().copy(outcome = outcome)
            val f = Fixture(original)
            assertTrue(runCatching { f.controller.reconcile(original.id) }.isFailure)
            assertEquals(listOf(original), f.records)
            assertEquals(0, f.opens)
            assertEquals(0, f.saves)
        }
        val f = Fixture()
        assertTrue(runCatching { f.controller.reconcile("not_in_history") }.isFailure)
        f.records = listOf(record(), record())
        assertTrue(runCatching { f.controller.reconcile(record().id) }.isFailure)
        assertEquals(0, f.opens)
        assertEquals(0, f.saves)
    }

    @Test fun `unavailable or disallowed providers never yield an observation or replay`() = runTest {
        val unavailable = Fixture().apply { openFailure = IOException("provider unavailable") }
        assertSame(unavailable.openFailure, runCatching { unavailable.controller.reconcile(record().id) }.exceptionOrNull())
        assertTrue(unavailable.reads.isEmpty())
        assertEquals(0, unavailable.saves)
        assertEquals(0, unavailable.writes)
        for (provider in listOf(PrivilegeMode.NONE, PrivilegeMode.DHIZUKU)) {
            val f = Fixture().apply { this.provider = provider }
            assertTrue(runCatching { f.controller.reconcile(record().id) }.isFailure)
            assertTrue(f.reads.isEmpty())
            assertEquals(listOf(record()), f.records)
            assertEquals(0, f.saves)
            assertEquals(0, f.writes)
        }
    }

    @Test fun `consent is needed before access and a revoked consent cannot publish the read`() = runTest {
        val denied = Fixture().apply { consent = false }
        assertTrue(runCatching { denied.controller.reconcile(record().id) }.isFailure)
        assertEquals(0, denied.opens)
        assertEquals(0, denied.saves)
        val revoked = Fixture().apply { afterRead = { consent = false } }
        assertTrue(runCatching { revoked.controller.reconcile(record().id) }.isFailure)
        assertEquals(1, revoked.reads.size)
        assertEquals(listOf(record()), revoked.records)
        assertEquals(0, revoked.saves)
        assertEquals(0, revoked.writes)
    }

    @Test fun `failed read or journal keeps the previous durable observation unchanged`() = runTest {
        val original = record().copy(observation = SettingsEditObservation(SettingValue.ABSENT, PrivilegeMode.ROOT, 150L))
        for (readFails in listOf(true, false)) {
            val failure = IOException(if (readFails) "read failed" else "journal full")
            val f = Fixture(original).apply {
                if (readFails) readFailure = failure else saveFailure = failure
            }

            assertSame(failure, runCatching { f.controller.reconcile(original.id) }.exceptionOrNull())

            assertEquals(listOf(original), f.records)
            assertEquals(0, f.writes)
            assertTrue(f.writeAdmissions.isEmpty())
        }
    }

    @Test fun `ambiguous target readback does not become an absent observation`() = runTest {
        val f = Fixture().apply { response = listOf(SettingEntry(KEY, "one"), SettingEntry(KEY, "two")) }
        assertTrue(runCatching { f.controller.reconcile(record().id) }.isFailure)
        assertEquals(listOf(record()), f.records)
        assertEquals(0, f.saves)
        assertEquals(0, f.writes)
    }

    @Test fun `cancelling a pending read preserves the journal without mutation`() = runTest {
        val blocked = CompletableDeferred<Unit>()
        val f = Fixture().apply { afterRead = { blocked.await() } }
        val job = launch { f.controller.reconcile(record().id) }
        f.readStarted.await()

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(listOf(record()), f.records)
        assertEquals(0, f.saves)
        assertEquals(0, f.writes)
    }

    @Test fun `cancellation discovered after a read cannot publish an observation`() = runTest {
        val f = Fixture().apply { afterRead = { currentCoroutineContext().cancel() } }

        val job = launch { f.controller.reconcile(record().id) }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(listOf(record()), f.records)
        assertEquals(0, f.saves)
        assertEquals(0, f.writes)
    }

    @Test fun `a later explicit read replaces only the observation including its provider and time`() = runTest {
        val original = record()
        val f = Fixture(original)
        f.controller.reconcile(original.id)
        f.current = SettingValue.ABSENT
        f.provider = PrivilegeMode.ROOT
        f.clock = 300L

        val latest = f.controller.reconcile(original.id)

        assertEquals(original.copy(observation = SettingsEditObservation(SettingValue.ABSENT, PrivilegeMode.ROOT, 300L)), latest)
        assertEquals(listOf(latest), f.records)
        assertEquals(2, f.opens)
        assertEquals(0, f.writes)
    }

    @Test fun `a reviewed restoration still checks fresh state and keeps the uncertain source record`() = runTest {
        val original = record()
        val f = Fixture(original)
        val observed = f.controller.reconcile(original.id)
        f.current = SettingValue(true, "external change")

        assertTrue(runCatching {
            f.controller.change(original.view, original.key, requireNotNull(observed.observation).value, original.before)
        }.isFailure)
        assertEquals(0, f.writes)
        assertEquals(listOf(observed), f.records)

        val restored = f.controller.change(original.view, original.key, f.current, original.before)
        assertEquals(SettingsEditOutcome.VERIFIED, restored.outcome)
        assertEquals(listOf(restored, observed), f.records)
        assertEquals(1, f.writes)
        assertEquals(original.before, f.current)
    }

    @Test fun `matching readback leaves an unresolved root producer durable and writes blocked on either provider`() = runTest {
        for (provider in listOf(PrivilegeMode.ROOT, PrivilegeMode.SHIZUKU)) {
            val resource = SettingsRootResource(SettingsEditorView.SECURE, 10, KEY)
            val persistence = object : SettingsRootExecutions {
                var records = emptyList<SettingsRootExecutionRecord>()
                override fun load() = records
                override fun save(records: List<SettingsRootExecutionRecord>) { this.records = records }
            }
            val gate = SettingsRootExecutionGate(persistence, { BOOT_ID }, Dispatchers.Unconfined)
            gate.observer(resource).beforeSubmit()
            val barrier = persistence.records
            val original = record().copy(outcome = SettingsEditOutcome.UNCONFIRMED)
            val f = Fixture(original).apply {
                this.provider = provider
                writeAdmission = { gate.ensureWriteAllowed(it) }
            }

            val observed = f.controller.reconcile(original.id)

            assertEquals(original.desired, requireNotNull(observed.observation).value)
            assertEquals(SettingsEditOutcome.UNCONFIRMED, observed.outcome)
            assertEquals(barrier, persistence.records)
            assertTrue(f.writeAdmissions.isEmpty())
            val changed = runCatching { f.controller.change(original.view, original.key, original.desired, original.before) }
            assertTrue(changed.exceptionOrNull() is SettingsExecutionUncertain)
            assertEquals(barrier, persistence.records)
            assertEquals(listOf(observed), f.records)
            assertEquals(1, f.reads.size)
            assertEquals(0, f.writes)
        }
    }

    @Test fun `overlapping reconciliation and edit preserve both journal updates`() = runTest {
        val original = record()
        val release = CompletableDeferred<Unit>()
        val f = Fixture(original).apply { afterRead = { release.await() } }
        val observation = launch { f.controller.reconcile(original.id) }
        f.readStarted.await()
        val mutation = launch { f.controller.change(original.view, original.key, original.desired, original.before) }
        runCurrent()
        assertEquals(0, f.writes)
        assertEquals(listOf(original), f.records)

        release.complete(Unit)
        observation.join()
        mutation.join()

        assertEquals(1, f.writes)
        assertEquals(2, f.records.size)
        assertEquals(SettingsEditOutcome.VERIFIED, f.records.first().outcome)
        assertEquals(original.copy(observation = SettingsEditObservation(original.desired, PrivilegeMode.SHIZUKU, f.clock)), f.records.last())
    }

    private companion object {
        const val KEY = "test_key"
        const val BOOT_ID = "ae8f781d-0b12-40c1-8303-17ff7f9121e7"
        fun record() = SettingsEditRecord("uncertain", SettingsEditorView.SECURE, 10, KEY,
            SettingValue(true, "before"), SettingValue(true, "requested"), PrivilegeMode.ROOT, 100L,
            SettingsEditOutcome.UNKNOWN)
    }
}
