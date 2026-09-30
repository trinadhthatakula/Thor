// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.data.repository.localState
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.valhalla.thor.domain.model.*
import com.valhalla.thor.domain.repository.PreferenceRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Opt-in only. Writes unique disposable keys and restores state, consent, preference and journal. */
@RunWith(AndroidJUnit4::class)
class SettingsEditorIntegrationTest {
    @Test fun disposableKeysRoundTripThroughTheSelectedProductionGateway() = runBlocking {
        val modeName = InstrumentationRegistry.getArguments().getString("settingsEditorMode")
        assumeTrue("Explicit opt-in required", modeName == "ROOT" || modeName == "SHIZUKU")
        val mode = PrivilegeMode.valueOf(modeName!!)
        val koin = GlobalContext.get()
        val repository = requireNotNull(koin.getOrNull<SettingsEditorRepository>())
        val store = requireNotNull(koin.getOrNull<SettingsEditorStore>())
        val prefs = requireNotNull(koin.getOrNull<PreferenceRepository>())
        val privilege = requireNotNull(koin.getOrNull<PrivilegeManager>())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val initialMode = prefs.userPreferences.first().preferredPrivilegeMode
        val initialConsent = store.consent.first()
        val initialHistory = store.history.load()
        val touched = mutableListOf<Pair<SettingsEditorView, String>>()
        try {
            prefs.setPrivilegeMode(mode)
            withTimeout(30_000) { prefs.userPreferences.first { it.preferredPrivilegeMode == mode } }
            val state = withTimeout(30_000) { privilege.refreshAndAwait() }
            assertEquals(mode, state.active)
            store.acceptConsent().getOrThrow()
            withTimeout(10_000) { store.consent.first { it } }
            for (view in listOf(SettingsEditorView.SYSTEM, SettingsEditorView.SECURE, SettingsEditorView.GLOBAL)) {
                val key = "thor_sett_edit_test_" + java.util.UUID.randomUUID().toString().replace("-", "")
                assertEquals(SettingValue.ABSENT, value(repository, view, key))
                touched += view to key
                val exact = SettingValue(true, "literal=null=a=b\nquotes:'\" dollars:$ backslash:\\ unicode:漢字\n")
                val created = repository.change(view, key, SettingValue.ABSENT, exact).getOrThrow()
                assertEquals(SettingsEditOutcome.VERIFIED, created.outcome)
                assertEquals(mode, created.provider)
                assertEquals(exact, value(repository, view, key))
                val updated = repository.change(view, key, exact, SettingValue(true, "")).getOrThrow()
                assertEquals(SettingsEditOutcome.VERIFIED, updated.outcome)
                assertEquals(SettingValue(true, ""), value(repository, view, key))
                assertEquals(SettingsEditOutcome.VERIFIED, repository.undo(updated.id).getOrThrow().outcome)
                assertEquals(exact, value(repository, view, key))
                assertTrue(repository.change(view, key, SettingValue.ABSENT, SettingValue(true, "stale")).isFailure)
                val literalNull = repository.change(view, key, exact, SettingValue(true, "null")).getOrThrow()
                assertEquals(SettingsEditOutcome.REJECTED, literalNull.outcome)
                assertEquals(SettingValue(true, null), value(repository, view, key))
                val deleted = repository.change(view, key, SettingValue(true, null), SettingValue.ABSENT).getOrThrow()
                assertEquals(SettingsEditOutcome.VERIFIED, deleted.outcome)
                assertEquals(SettingValue.ABSENT, value(repository, view, key))
                assertEquals(SettingsEditOutcome.VERIFIED, repository.undo(deleted.id).getOrThrow().outcome)
                assertEquals(SettingValue(true, null), value(repository, view, key))
            }
            for (view in listOf(SettingsEditorView.ANDROID_PROPERTIES, SettingsEditorView.JAVA_PROPERTIES, SettingsEditorView.ENVIRONMENT)) {
                assertTrue("Diagnostic view $view must load", repository.read(view).getOrThrow().isNotEmpty())
            }
            prefs.setPrivilegeMode(PrivilegeMode.DHIZUKU)
            withTimeout(10_000) { prefs.userPreferences.first { it.preferredPrivilegeMode == PrivilegeMode.DHIZUKU } }
            assertTrue("Selected Dhizuku must not fall back", repository.read(SettingsEditorView.SYSTEM).isFailure)
        } finally {
            withContext(NonCancellable) {
                prefs.setPrivilegeMode(mode)
                withTimeout(10_000) { prefs.userPreferences.first { it.preferredPrivilegeMode == mode } }
                withTimeout(30_000) { privilege.refreshAndAwait() }
                val cleanup = touched.map { (view, key) -> runCatching {
                    val current = value(repository, view, key)
                    if (current.present) assertEquals(SettingsEditOutcome.VERIFIED, repository.change(view, key, current, SettingValue.ABSENT).getOrThrow().outcome)
                    assertEquals(SettingValue.ABSENT, value(repository, view, key))
                } }
                context.localState.edit { it[booleanPreferencesKey("settings_editor_consent_accepted")] = initialConsent }
                store.history.save(initialHistory)
                prefs.setPrivilegeMode(initialMode)
                val failure = cleanup.firstNotNullOfOrNull { it.exceptionOrNull() }
                if (failure != null) throw failure
            }
        }
    }
    private suspend fun value(repository: SettingsEditorRepository, view: SettingsEditorView, key: String): SettingValue =
        SettingsEditorController.valueOf(repository.read(view).getOrThrow(), key)
}
