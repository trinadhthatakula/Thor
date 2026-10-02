// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor

import android.content.Context
import android.os.Bundle
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.data.repository.localState
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import com.valhalla.thor.domain.model.SettingValue
import com.valhalla.thor.domain.model.SettingsEditOutcome
import com.valhalla.thor.domain.model.SettingsEditRecord
import com.valhalla.thor.domain.model.SettingsEditorView
import com.valhalla.thor.domain.repository.PreferenceRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Explicit opt-in. Uses production reads and disposable keys; history/termination states are seeded
 * fixtures, not actual process death or a claim that an unacknowledged producer has been stopped.
 */
@RunWith(AndroidJUnit4::class)
class SettingsEditorReconciliationIntegrationTest {
    @Test
    fun uncertainHistoryObservationsPersistWithoutChangingSettingsOrUndoEligibility() = runBlocking {
        withFixture { fixture ->
            val scenarios = listOf(
                SettingsEditorView.SYSTEM to SettingsEditOutcome.PENDING,
                SettingsEditorView.SECURE to SettingsEditOutcome.UNKNOWN,
                SettingsEditorView.GLOBAL to SettingsEditOutcome.UNCONFIRMED,
            )
            for ((view, outcome) in scenarios) {
                for (present in listOf(true, false)) {
                    val current = if (present) SettingValue(true, "fixture") else SettingValue.ABSENT
                    val key = fixture.createKey(view, current)
                    val original = fixture.seed(view, key, outcome, desired = SettingValue(true, "requested"))
                    val started = System.currentTimeMillis()
                    val observed = fixture.repository.reconcile(original.id).getOrThrow()
                    val observation = requireNotNull(observed.observation)
                    assertEquals(current, observation.value)
                    assertEquals(fixture.mode, observation.provider)
                    assertTrue("Observation time is captured by this read", observation.timestamp >= started)
                    assertEquals("Only observation metadata changes", original, observed.copy(observation = null))
                    assertEquals(current, fixture.value(view, key))
                    assertTrue("Readback must not make uncertain edits undoable", fixture.repository.undo(original.id).isFailure)
                    val reopened = FileSettingsEditHistory(File(fixture.context.noBackupFilesDir, "settings_editor_history.json"))
                    assertEquals(observed, reopened.load().single { it.id == original.id })
                }
            }
            metric("persisted_read_only", fixture.mode, "records=6")
        }
    }

    @Test
    fun matchingReadbackRemainsUnverifiedAndASeparateStaleRestorationConflicts() = runBlocking {
        withFixture { fixture ->
            val view = SettingsEditorView.SYSTEM
            val requested = SettingValue(true, "requested")
            val key = fixture.createKey(view, requested)
            val original = fixture.seed(view, key, SettingsEditOutcome.UNKNOWN, desired = requested)
            val observed = fixture.repository.reconcile(original.id).getOrThrow()
            assertEquals(requested, requireNotNull(observed.observation).value)
            assertEquals(SettingsEditOutcome.UNKNOWN, observed.outcome)
            assertTrue(fixture.repository.undo(original.id).isFailure)

            val newer = SettingValue(true, "newer")
            assertEquals(SettingsEditOutcome.VERIFIED, fixture.repository.change(view, key, requested, newer).getOrThrow().outcome)
            val historyBeforeConflict = fixture.store.history.load()
            val staleRestore = fixture.repository.change(view, key, requireNotNull(observed.observation).value, original.before)
            assertTrue("A separate edit must use a fresh conflict check", staleRestore.isFailure)
            assertEquals("conflict", staleRestore.exceptionOrNull()?.message)
            assertEquals(newer, fixture.value(view, key))
            assertTrue("Preflight conflicts dispatch no edit or journal row", historyBeforeConflict == fixture.store.history.load())
            metric("stale_restore_refused", fixture.mode, "conflicts=1")
        }
    }

    @Test
    fun readbackPreservesTheUnresolvedWriteBarrierAndConsentRemainsRequired() = runBlocking {
        withFixture { fixture ->
            val view = SettingsEditorView.SECURE
            val current = SettingValue(true, "fixture")
            val key = fixture.createKey(view, current)
            val original = fixture.seed(view, key, SettingsEditOutcome.UNCONFIRMED, desired = current)
            val resource = SettingsRootResource(view, view.userId(thorUserId), key)
            val observer = fixture.store.rootExecutions.observer(resource)
            // This observer belongs only to a synthetic receipt: no producer is ever submitted.
            // Completing it during cleanup removes its exact UUID, never another execution record.
            fixture.beforeCleanup += {
                observer.onOutcome(RootJobOutcome(
                    kind = RootJobOutcomeKind.FAILED,
                    exitCode = null,
                    stdout = emptyList(),
                    stderr = emptyList(),
                    started = false,
                    terminationConfirmed = true,
                    outputDrained = true,
                    shellReusable = true,
                    failure = null,
                ))
            }
            observer.beforeSubmit()
            val executions = FileSettingsRootExecutions(File(fixture.context.noBackupFilesDir, "settings_editor_root_executions.json"))
            val pending = executions.load()
            assertNotNull(pending.singleOrNull { it.resource == resource })

            val observed = fixture.repository.reconcile(original.id).getOrThrow()
            assertEquals(current, requireNotNull(observed.observation).value)
            assertEquals(SettingsEditOutcome.UNCONFIRMED, observed.outcome)
            assertEquals("Current-state readback cannot clear a write barrier", pending, executions.load())
            val denied = fixture.repository.change(view, key, current, SettingValue.ABSENT)
            assertTrue("The exact key remains protected even after matching readback", denied.exceptionOrNull() is SettingsExecutionUncertain)
            assertEquals(current, fixture.value(view, key))
            assertEquals(pending, executions.load())

            fixture.setConsent(false)
            val historyBeforeDeniedRead = fixture.store.history.load()
            val consentDenied = fixture.repository.reconcile(original.id)
            assertTrue(consentDenied.isFailure)
            assertEquals("consent_required", consentDenied.exceptionOrNull()?.message)
            assertTrue("Consent refusal preserves history", historyBeforeDeniedRead == fixture.store.history.load())
            assertEquals(pending, executions.load())
            metric("unresolved_barrier_retained", fixture.mode, "barriers=1 consent_refusals=1")
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val modeName = InstrumentationRegistry.getArguments().getString("settingsEditorMode")
        assumeTrue("Explicit ROOT or SHIZUKU opt-in required", modeName == "ROOT" || modeName == "SHIZUKU")
        val koin = GlobalContext.get()
        val fixture = Fixture(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            mode = PrivilegeMode.valueOf(requireNotNull(modeName)),
            repository = requireNotNull(koin.getOrNull<SettingsEditorRepository>()),
            store = requireNotNull(koin.getOrNull<SettingsEditorStore>()),
            preferences = requireNotNull(koin.getOrNull<PreferenceRepository>()),
            privilege = requireNotNull(koin.getOrNull<PrivilegeManager>()),
        )
        val initialMode = fixture.preferences.userPreferences.first().preferredPrivilegeMode
        val initialConsent = fixture.store.consent.first()
        val initialHistory = fixture.store.history.load()
        var primary: Throwable? = null
        try {
            fixture.selectMode(fixture.mode)
            assertEquals(fixture.mode, withTimeout(30_000) { fixture.privilege.refreshAndAwait() }.active)
            fixture.setConsent(true)
            block(fixture)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                val failures = mutableListOf<Throwable>()
                suspend fun attempt(action: suspend () -> Unit) {
                    try { action() } catch (failure: Throwable) { failures += failure }
                }
                fixture.beforeCleanup.forEach { cleanup -> attempt(cleanup) }
                attempt {
                    fixture.selectMode(fixture.mode)
                    withTimeout(30_000) { fixture.privilege.refreshAndAwait() }
                    fixture.setConsent(true)
                }
                fixture.keys.forEach { (view, key) ->
                    attempt {
                        val current = fixture.value(view, key)
                        if (current.present) {
                            assertEquals(SettingsEditOutcome.VERIFIED, fixture.repository.change(view, key, current, SettingValue.ABSENT).getOrThrow().outcome)
                        }
                        assertEquals(SettingValue.ABSENT, fixture.value(view, key))
                    }
                }
                attempt { fixture.store.history.save(initialHistory) }
                attempt { fixture.setConsent(initialConsent) }
                attempt {
                    fixture.selectMode(initialMode)
                    withTimeout(30_000) { fixture.privilege.refreshAndAwait() }
                }
                val failure = primary ?: failures.firstOrNull()
                failures.filter { it !== failure }.forEach { failure?.addSuppressed(it) }
                if (primary == null && failure != null) throw failure
            }
        }
    }

    private class Fixture(
        val context: Context,
        val mode: PrivilegeMode,
        val repository: SettingsEditorRepository,
        val store: SettingsEditorStore,
        val preferences: PreferenceRepository,
        val privilege: PrivilegeManager,
    ) {
        val keys = mutableListOf<Pair<SettingsEditorView, String>>()
        val beforeCleanup = mutableListOf<suspend () -> Unit>()

        suspend fun selectMode(selected: PrivilegeMode?) {
            preferences.setPrivilegeMode(selected)
            withTimeout(10_000) { preferences.userPreferences.first { it.preferredPrivilegeMode == selected } }
        }

        suspend fun setConsent(accepted: Boolean) {
            context.localState.edit { it[booleanPreferencesKey("settings_editor_consent_accepted")] = accepted }
            withTimeout(10_000) { store.consent.first { it == accepted } }
        }

        suspend fun value(view: SettingsEditorView, key: String): SettingValue =
            SettingsEditorController.valueOf(repository.read(view).getOrThrow(), key)

        suspend fun createKey(view: SettingsEditorView, current: SettingValue): String {
            val key = "thor_sett_reconcile_" + UUID.randomUUID().toString().replace("-", "")
            assertEquals("Only absent UUID keys are owned by this test", SettingValue.ABSENT, value(view, key))
            keys += view to key
            if (current.present) {
                assertEquals(SettingsEditOutcome.VERIFIED, repository.change(view, key, SettingValue.ABSENT, current).getOrThrow().outcome)
            }
            return key
        }

        fun seed(view: SettingsEditorView, key: String, outcome: SettingsEditOutcome, desired: SettingValue): SettingsEditRecord {
            val record = SettingsEditRecord(
                id = UUID.randomUUID().toString(),
                view = view,
                userId = view.userId(thorUserId),
                key = key,
                before = SettingValue.ABSENT,
                desired = desired,
                // Historic provenance is distinct from the provider performing today's read.
                provider = if (mode == PrivilegeMode.ROOT) PrivilegeMode.SHIZUKU else PrivilegeMode.ROOT,
                timestamp = System.currentTimeMillis() - 1_000,
                outcome = outcome,
                undoOf = UUID.randomUUID().toString(),
            )
            store.history.save(listOf(record) + store.history.load())
            return record
        }
    }

    private fun metric(scenario: String, mode: PrivilegeMode, counts: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nTHOR_SETTINGS_RECONCILE_METRIC $scenario provider=$mode $counts\n")
        })
    }
}
