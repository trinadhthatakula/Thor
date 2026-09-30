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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class SettingsEditorStoreTest {
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
}
