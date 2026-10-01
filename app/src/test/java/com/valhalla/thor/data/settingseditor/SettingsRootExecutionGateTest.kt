// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor

import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import com.valhalla.thor.domain.model.SettingsEditorView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SettingsRootExecutionGateTest {
    private class Persistence : SettingsRootExecutions {
        var records = emptyList<SettingsRootExecutionRecord>()
        var failSave = false
        override fun load() = records
        override fun save(records: List<SettingsRootExecutionRecord>) {
            check(!failSave) { "disk_full" }
            this.records = records
        }
    }

    private val resource = SettingsRootResource(SettingsEditorView.SECURE, 10, "test_key")
    private fun gate(store: Persistence, boot: () -> String? = { BOOT_A }) =
        SettingsRootExecutionGate(store, boot, Dispatchers.Unconfined)

    @Test fun `pending submission survives reopening and blocks only the same setting`() = runTest {
        val store = Persistence()
        val observer = gate(store).observer(resource)
        gate(store).ensureWriteAllowed(resource)
        observer.beforeSubmit()
        assertEquals(resource, store.records.single().resource)
        assertNull(store.records.single().kind)
        val reopened = gate(store)
        assertTrue(runCatching { reopened.ensureWriteAllowed(resource) }.exceptionOrNull() is SettingsExecutionUncertain)
        reopened.ensureWriteAllowed(resource.copy(key = "independent_key"))
        reopened.ensureWriteAllowed(resource.copy(userId = 0))
        reopened.ensureWriteAllowed(resource.copy(view = SettingsEditorView.SYSTEM))
    }

    @Test fun `every unconfirmed termination or drain combination remains durable`() = runTest {
        for ((termination, drained) in listOf(false to false, false to true, true to false, true to true)) {
            val store = Persistence()
            val observer = gate(store).observer(resource)
            observer.beforeSubmit()
            val outcome = outcome().copy(
                kind = RootJobOutcomeKind.TERMINATION_UNCONFIRMED,
                terminationConfirmed = termination,
                outputDrained = drained,
                shellReusable = false,
                failure = "sensitive failure detail",
            )
            observer.onOutcome(outcome)
            val record = store.records.single()
            assertEquals(outcome.kind.name, record.kind)
            assertEquals(true, record.started)
            assertEquals(termination, record.terminationConfirmed)
            assertEquals(drained, record.outputDrained)
            assertEquals(false, record.shellReusable)
            assertTrue(record.hasFailure)
            assertTrue(runCatching { gate(store).ensureWriteAllowed(resource) }.exceptionOrNull() is SettingsExecutionUncertain)
        }
    }

    @Test fun `confirmed cleanup releases resource even when transport cannot be reused`() = runTest {
        for (kind in RootJobOutcomeKind.entries.filterNot { it == RootJobOutcomeKind.TERMINATION_UNCONFIRMED }) {
            val store = Persistence()
            val observer = gate(store).observer(resource)
            observer.beforeSubmit()
            observer.onOutcome(outcome().copy(kind = kind, shellReusable = false))
            assertTrue(store.records.isEmpty())
            gate(store).ensureWriteAllowed(resource)
        }
    }

    @Test fun `cancellation before dispatch clears its pending record after acknowledgement`() = runTest {
        val store = Persistence()
        val observer = gate(store).observer(resource)
        observer.beforeSubmit()
        observer.onOutcome(outcome().copy(kind = RootJobOutcomeKind.CANCELLED, started = false, exitCode = null))
        assertTrue(store.records.isEmpty())
    }

    @Test fun `old or refused observers cannot release another execution`() = runTest {
        val store = Persistence()
        val first = gate(store).observer(resource)
        val second = gate(store).observer(resource)
        first.beforeSubmit()
        assertTrue(runCatching { second.beforeSubmit() }.exceptionOrNull() is SettingsExecutionUncertain)
        second.onOutcome(outcome().copy(started = false))
        assertEquals(1, store.records.size)
        first.onOutcome(outcome())
        second.beforeSubmit()
        val secondId = store.records.single().id
        first.onOutcome(outcome())
        assertEquals(secondId, store.records.single().id)
    }

    @Test fun `recording failure leaves pending barrier and failed initial recording refuses dispatch`() = runTest {
        val store = Persistence()
        val observer = gate(store).observer(resource)
        store.failSave = true
        assertTrue(runCatching { observer.beforeSubmit() }.isFailure)
        assertTrue(store.records.isEmpty())
        store.failSave = false
        observer.beforeSubmit()
        store.failSave = true
        assertTrue(runCatching { observer.onOutcome(outcome()) }.isFailure)
        assertNull(store.records.single().kind)
        assertTrue(runCatching { gate(store).ensureWriteAllowed(resource) }.exceptionOrNull() is SettingsExecutionUncertain)
    }

    @Test fun `only a different known kernel boot clears unconfirmed execution`() = runTest {
        val store = Persistence()
        gate(store).observer(resource).beforeSubmit()
        for (boot in listOf(null, BOOT_A)) {
            assertTrue(runCatching { gate(store) { boot }.ensureWriteAllowed(resource) }.exceptionOrNull() is SettingsExecutionUncertain)
        }
        gate(store) { BOOT_B }.ensureWriteAllowed(resource)
        assertTrue(store.records.isEmpty())
        gate(store) { null }.observer(resource).beforeSubmit()
        assertTrue(runCatching { gate(store) { BOOT_C }.ensureWriteAllowed(resource) }.exceptionOrNull() is SettingsExecutionUncertain)
    }

    @Test fun `malformed recorded or current boot identity never retires a barrier`() = runTest {
        val invalidIdentities = listOf("", "boot-a", "0-0-0-0-1", BOOT_A.uppercase(), " $BOOT_A")
        for (invalid in invalidIdentities) {
            val malformedRecord = SettingsRootExecutionRecord("pending", resource, invalid)
            val persisted = Persistence().apply { records = listOf(malformedRecord) }
            assertTrue(runCatching { gate(persisted).ensureWriteAllowed(resource) }.exceptionOrNull() is SettingsExecutionUncertain)
            assertEquals(listOf(malformedRecord), persisted.records)

            val current = Persistence()
            gate(current).observer(resource).beforeSubmit()
            val before = current.records
            assertTrue(runCatching { gate(current) { invalid }.ensureWriteAllowed(resource) }.exceptionOrNull() is SettingsExecutionUncertain)
            assertEquals(before, current.records)

            val captured = Persistence()
            gate(captured) { invalid }.observer(resource).beforeSubmit()
            assertNull(captured.records.single().bootId)
            assertTrue(runCatching { gate(captured).ensureWriteAllowed(resource) }.exceptionOrNull() is SettingsExecutionUncertain)
        }
    }

    private fun outcome() = RootJobOutcome(
        kind = RootJobOutcomeKind.EXITED,
        exitCode = 0,
        stdout = listOf("private setting value"),
        stderr = listOf("private error output"),
        started = true,
        terminationConfirmed = true,
        outputDrained = true,
        shellReusable = true,
        failure = null,
    )

    private companion object {
        const val BOOT_A = "ae8f781d-0b12-40c1-8303-17ff7f9121e7"
        const val BOOT_B = "24173a01-57ad-4738-9838-670edac53b52"
        const val BOOT_C = "6e63b2ba-aa12-4e3b-a645-6621bf388a98"
    }
}
