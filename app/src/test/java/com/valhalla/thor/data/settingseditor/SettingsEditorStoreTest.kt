// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.repository.localState
import com.valhalla.thor.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class SettingsEditorStoreTest {
    @Test fun `root recovery metadata survives reopening and corrupt state fails closed`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.noBackupFilesDir, "settings_root_test_${java.util.UUID.randomUUID()}.json")
        try {
            val record = SettingsRootExecutionRecord(
                "execution", SettingsRootResource(SettingsEditorView.GLOBAL, 0, "test_key"), "boot-a",
                kind = "TERMINATION_UNCONFIRMED", started = true,
                terminationConfirmed = false, outputDrained = false, shellReusable = false,
                hasFailure = true,
            )
            FileSettingsRootExecutions(file).save(listOf(record))
            assertEquals(listOf(record), FileSettingsRootExecutions(file).load())
            val serialized = file.readText()
            assertFalse(serialized.contains("stdout"))
            assertFalse(serialized.contains("stderr"))
            assertFalse(serialized.contains("desired"))
            assertFalse(serialized.contains("command"))
            file.writeText("broken recovery metadata")
            assertTrue(runCatching { FileSettingsRootExecutions(file).load() }.isFailure)
        } finally { file.delete() }
    }
    @Test fun `consent is stored only in device local preferences`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = booleanPreferencesKey("settings_editor_consent_accepted")
        context.localState.edit { it.remove(key) }
        val store = SettingsEditorStore(context)
        assertFalse(store.consent.first())
        store.acceptConsent().getOrThrow()
        assertTrue(store.consent.first())
        assertTrue(SettingsEditorStore(context).consent.first())
        context.localState.edit { it.remove(key) }
        assertFalse(store.consent.first())
    }
    @Test fun `journal preserves absent empty SQL null and multiline values across reopening`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.noBackupFilesDir, "settings_editor_test_history.json")
        file.delete()
        try {
            val original = SettingsEditRecord("test", SettingsEditorView.SECURE, 10, "test_key", SettingValue(true, null),
                SettingValue(true, "\n='\"null\n"), PrivilegeMode.ROOT, 1, SettingsEditOutcome.PENDING)
            FileSettingsEditHistory(file).save(listOf(original))
            assertEquals(listOf(original), FileSettingsEditHistory(file).load())
            val finished = original.copy(before = SettingValue.ABSENT, desired = SettingValue(true, ""), outcome = SettingsEditOutcome.VERIFIED)
            FileSettingsEditHistory(file).save(listOf(finished))
            assertEquals(listOf(finished), FileSettingsEditHistory(file).load())
            file.writeText("broken journal")
            assertTrue(runCatching { FileSettingsEditHistory(file).load() }.isFailure)
        } finally { file.delete() }
    }
    @Test fun `legacy uncertain journal loads without an observation and accepts a later observation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.noBackupFilesDir, "settings_legacy_history_${java.util.UUID.randomUUID()}.json")
        try {
            file.writeText("""[{"id":"legacy","view":"SECURE","userId":10,"key":"test_key","before":{"present":false},"desired":{"present":true,"value":"requested"},"provider":"ROOT","timestamp":100,"outcome":"UNKNOWN"}]""")

            val original = FileSettingsEditHistory(file).load().single()

            assertEquals(SettingsEditOutcome.UNKNOWN, original.outcome)
            assertEquals(SettingValue.ABSENT, original.before)
            assertEquals(SettingValue(true, "requested"), original.desired)
            assertNull(original.observation)
            val observed = original.copy(observation = SettingsEditObservation(SettingValue(true, null), PrivilegeMode.SHIZUKU, 200L))
            FileSettingsEditHistory(file).save(listOf(observed))
            assertEquals(listOf(observed), FileSettingsEditHistory(file).load())
            assertEquals(original, FileSettingsEditHistory(file).load().single().copy(observation = null))
        } finally { file.delete() }
    }
    @Test fun `durable observations retain exact value kinds without changing uncertain outcomes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.noBackupFilesDir, "settings_observed_history_${java.util.UUID.randomUUID()}.json")
        val values = listOf(SettingValue.ABSENT, SettingValue(true, null), SettingValue(true, ""),
            SettingValue(true, "null"), SettingValue(true, "\n='\"null\n"))
        try {
            val records = values.mapIndexed { index, value ->
                SettingsEditRecord("observed_$index", SettingsEditorView.SECURE, 10, "test_key_$index",
                    SettingValue(true, "before"), SettingValue(true, "requested"), PrivilegeMode.ROOT, 100L,
                    SettingsEditOutcome.UNCONFIRMED,
                    observation = SettingsEditObservation(value, PrivilegeMode.SHIZUKU, 200L + index))
            }

            FileSettingsEditHistory(file).save(records)

            val reopened = FileSettingsEditHistory(file).load()
            assertEquals(records, reopened)
            assertEquals(values, reopened.map { requireNotNull(it.observation).value })
            assertTrue(reopened.all { it.outcome == SettingsEditOutcome.UNCONFIRMED })
        } finally { file.delete() }
    }
    @Test fun `lost final journal save survives controller reopening and reconciliation never replays the write`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.noBackupFilesDir, "settings_pending_history_${java.util.UUID.randomUUID()}.json")
        var current = SettingValue.ABSENT
        var writes = 0
        val desired = SettingValue(true, "requested")
        val session = object : SettingsEditorSession {
            override val provider = PrivilegeMode.ROOT
            override suspend fun read(view: SettingsEditorView, userId: Int): List<SettingEntry> =
                if (current.present) listOf(SettingEntry("test_key", current.value)) else emptyList()
            override suspend fun write(view: SettingsEditorView, userId: Int, key: String, expected: SettingValue, desired: SettingValue): SettingsBridgeResult {
                writes++
                current = desired
                return SettingsBridgeResult(read(view, userId))
            }
        }
        val finalSaveFailure = IOException("final journal unavailable")
        try {
            val durable = FileSettingsEditHistory(file)
            val failingFinalSave = object : SettingsEditHistory {
                override fun load() = durable.load()
                override fun save(records: List<SettingsEditRecord>) {
                    if (records.first().outcome != SettingsEditOutcome.PENDING) throw finalSaveFailure
                    durable.save(records)
                }
            }
            val interrupted = SettingsEditorController({ 10 }, { true }, { session }, failingFinalSave, { 100L })

            val failure = runCatching {
                interrupted.change(SettingsEditorView.SECURE, "test_key", SettingValue.ABSENT, desired)
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            // Coroutine stack recovery may wrap the original exception across NonCancellable.
            assertTrue(generateSequence(failure) { it.cause }.take(16).any { it === finalSaveFailure })
            val pending = FileSettingsEditHistory(file).load().single()
            assertEquals(SettingsEditOutcome.PENDING, pending.outcome)
            assertEquals(SettingValue.ABSENT, pending.before)
            assertEquals(desired, current)
            assertEquals(1, writes)

            val reopened = SettingsEditorController({ 10 }, { true }, { session }, FileSettingsEditHistory(file), { 200L })
            val observed = reopened.reconcile(pending.id)

            assertEquals(pending.copy(observation = SettingsEditObservation(desired, PrivilegeMode.ROOT, 200L)), observed)
            assertEquals(listOf(observed), FileSettingsEditHistory(file).load())
            assertTrue(runCatching { reopened.undo(pending.id) }.isFailure)
            assertEquals(1, writes)
            assertEquals(desired, current)
        } finally { file.delete() }
    }
}
