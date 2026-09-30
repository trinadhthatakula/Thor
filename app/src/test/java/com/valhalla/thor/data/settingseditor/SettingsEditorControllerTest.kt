// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor

import com.valhalla.thor.domain.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SettingsEditorControllerTest {
    private class Fixture {
        var consent = true
        var current = SettingValue.ABSENT
        var refuse = false
        var transportFailure = false
        var historyFailure = false
        var writes = 0
        var records = emptyList<SettingsEditRecord>()
        val history = object : SettingsEditHistory {
            override fun load() = records
            override fun save(records: List<SettingsEditRecord>) { if (historyFailure) error("disk_full"); this@Fixture.records = records }
        }
        val session = object : SettingsEditorSession {
            override val provider = PrivilegeMode.SHIZUKU
            override suspend fun read(view: SettingsEditorView, userId: Int) = if (current.present) listOf(SettingEntry("test_key", current.value)) else emptyList()
            override suspend fun write(view: SettingsEditorView, userId: Int, key: String, expected: SettingValue, desired: SettingValue): SettingsBridgeResult {
                assertEquals(SettingsEditOutcome.PENDING, records.first().outcome)
                assertEquals(expected, records.first().before)
                writes++
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
