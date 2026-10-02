// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor

import android.content.Context
import android.os.Bundle
import android.os.Process
import android.util.AtomicFile
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.data.repository.localState
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.RootAdmissionUnavailable
import com.valhalla.thor.domain.model.RootLaneStatusSource
import com.valhalla.thor.domain.model.SettingValue
import com.valhalla.thor.domain.model.SettingsEditOutcome
import com.valhalla.thor.domain.model.SettingsEditRecord
import com.valhalla.thor.domain.model.SettingsEditorView
import com.valhalla.thor.domain.model.ShellLaneBusy
import com.valhalla.thor.domain.repository.PreferenceRepository
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Host-orchestrated, explicit ROOT/user-0 opt-in. Run one method at a time with the same UUID:
 * settingsProcessDeathPhase=arm, wait for the value-free ready.json, interrupt Thor, then recover.
 * Keep Settings Editor closed and do not run concurrent history operations during these phases.
 *
 * The real production session completes its write/readback and Odin cleanup before the test blocks
 * the controller's final journal save. This proves actual app death with lost final persistence;
 * it does not simulate killing a live privileged producer or claim its termination from readback.
 * Private originals survive interruption in fixture.json and must never be copied to host logs.
 */
@RunWith(AndroidJUnit4::class)
class SettingsEditorProcessDeathIntegrationTest {
    @Test
    fun armAcknowledgedWriteBeforeFinalJournal(): Unit = runBlocking {
        val environment = environment("arm")
        val directory = environment.directory
        check(!directory.exists()) { "process_death_fixture_already_exists" }
        val initial = FixtureState(
            fixtureId = environment.id,
            processId = Process.myPid(),
            processStartTicks = processStartTicks(),
            bootId = requireNotNull(readSettingsBootId()) { "kernel_boot_identity_unavailable" },
            appLastUpdateTime = environment.lastUpdateTime(),
            key = "thor_sett_reconcile_" + environment.id.replace("-", ""),
            initialHistory = environment.store.history.load(),
            initialRootExecutions = environment.rootRecords(),
            initialPreference = environment.preferences.userPreferences.first().preferredPrivilegeMode,
            initialConsent = environment.context.localState.data.first()[CONSENT_KEY],
        )
        // Commit and reopen all originals before changing provider, consent, or any setting.
        check(directory.mkdirs()) { "process_death_fixture_unavailable" }
        try {
            writeState(directory, initial)
        } catch (failure: Throwable) {
            // No provider, consent, history or setting mutation has happened yet.
            if (!directory.deleteRecursively()) failure.addSuppressed(IOException("process_death_unstarted_fixture_cleanup_failed"))
            throw failure
        }
        var primary: Throwable? = null
        try {
            environment.prepareRoot()
            assertEquals(SettingValue.ABSENT, environment.value(initial.key))
            writeState(directory, initial.copy(ownsKey = true))
            val factory = productionSessionFactory(environment.repository)
            val interceptedHistory = object : SettingsEditHistory {
                override fun load(): List<SettingsEditRecord> = environment.store.history.load()

                override fun save(records: List<SettingsEditRecord>) {
                    val record = records.singleOrNull {
                        it.view == SettingsEditorView.SYSTEM && it.userId == 0 && it.key == initial.key
                    } ?: throw IOException("process_death_test_record_missing")
                    if (record.outcome == SettingsEditOutcome.PENDING) {
                        environment.store.history.save(records)
                        val persisted = environment.store.history.load().single { it.id == record.id }
                        check(persisted == record) { "process_death_pending_not_durable" }
                        writeState(directory, readState(directory).copy(pending = record))
                        return
                    }
                    // This callback is reached only after the actual session write has returned.
                    // Refuse readiness unless that write verified and its real root gate retired.
                    check(record.outcome == SettingsEditOutcome.VERIFIED) { "process_death_write_not_verified" }
                    val state = readState(directory)
                    val pending = requireNotNull(state.pending) { "process_death_pending_missing" }
                    check(record.copy(outcome = SettingsEditOutcome.PENDING) == pending) { "process_death_record_changed" }
                    check(environment.store.history.load().single { it.id == pending.id } == pending) {
                        "process_death_final_save_was_not_intercepted"
                    }
                    check(environment.rootRecords().none { it.resource == state.resource() }) {
                        "process_death_root_cleanup_not_confirmed"
                    }
                    writeState(directory, state.copy(phase = ACKNOWLEDGED))
                    writeReady(directory, ReadyMarker(
                        fixtureId = state.fixtureId,
                        phase = ACKNOWLEDGED,
                        processId = state.processId,
                        processStartTicks = state.processStartTicks,
                        bootId = state.bootId,
                        recordId = pending.id,
                        key = state.key,
                    ))
                    metric("armed", "fixture=${state.fixtureId} original_pid=${state.processId} producer_acknowledged=true")
                    // No latch release exists: the expected exit is real host-driven process death.
                    // If the host does not interrupt, fail and run the normal guarded cleanup below.
                    CountDownLatch(1).await(180, TimeUnit.SECONDS)
                    throw IOException("process_death_host_interruption_guard_expired")
                }
            }
            val controller = SettingsEditorController(
                currentUserId = { thorUserId },
                allowed = { environment.store.consent.first() },
                openSession = {
                    factory().also { check(it.provider == PrivilegeMode.ROOT) { "process_death_requires_root_session" } }
                },
                history = interceptedHistory,
            )
            withContext(Dispatchers.IO) {
                controller.change(SettingsEditorView.SYSTEM, initial.key, SettingValue.ABSENT, initial.desired())
            }
            error("process_death_arm_returned_without_interruption")
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            // SIGKILL/force-stop never executes this. A missed host interruption or ordinary error
            // does; cleanup refuses a real unresolved root receipt and retains the private backup.
            finishCleanup(environment, primary)
        }
    }

    @Test
    fun recoverAfterAcknowledgedWriteProcessDeath() = runBlocking {
        val environment = environment("recover")
        val state = readState(environment.directory)
        var primary: Throwable? = null
        try {
            check(state.fixtureId == environment.id && state.phase == ACKNOWLEDGED) { "process_death_fixture_not_ready" }
            check(readSettingsBootId() == state.bootId) { "process_death_requires_same_kernel_boot" }
            check(Process.myPid() != state.processId || processStartTicks() != state.processStartTicks) {
                "process_death_requires_a_new_app_process"
            }
            check(environment.lastUpdateTime() == state.appLastUpdateTime) { "process_death_app_was_replaced" }
            val pending = requireNotNull(state.pending) { "process_death_pending_missing" }
            assertTrue("Lost final persistence leaves the exact PENDING record", environment.store.history.load().single { it.id == pending.id } == pending)
            assertEquals(SettingsEditOutcome.PENDING, pending.outcome)
            assertTrue("The acknowledged producer has no remaining root barrier", environment.rootRecords().none { it.resource == state.resource() })

            environment.prepareRoot()
            assertEquals(state.desired(), environment.value(state.key))
            val observed = environment.repository.reconcile(pending.id).getOrThrow()
            assertEquals(state.desired(), requireNotNull(observed.observation).value)
            assertEquals(PrivilegeMode.ROOT, requireNotNull(observed.observation).provider)
            assertTrue("Reconciliation preserves the original before-image and uncertain outcome", observed.copy(observation = null) == pending)
            assertTrue("Current-state observation must not enable verified undo", environment.repository.undo(pending.id).isFailure)
            assertEquals(state.desired(), environment.value(state.key))
            val reopened = FileSettingsEditHistory(File(environment.context.noBackupFilesDir, "settings_editor_history.json"))
            assertTrue("The recovered observation is durable", reopened.load().single { it.id == pending.id } == observed)
            assertTrue("Reconciliation does not create or retire root execution records", environment.rootRecords() == state.initialRootExecutions)
            metric("recovered", "fixture=${state.fixtureId} original_pid=${state.processId} current_pid=${Process.myPid()} pending_preserved=true")
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            finishCleanup(environment, primary)
        }
    }

    private suspend fun finishCleanup(environment: Environment, primary: Throwable?) = withContext(NonCancellable) {
        try {
            environment.cleanup(readState(environment.directory))
        } catch (cleanup: Throwable) {
            if (primary != null) {
                if (cleanup !== primary) primary.addSuppressed(cleanup)
            } else {
                throw cleanup
            }
        }
    }

    private fun environment(phase: String): Environment {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit ROOT process-death phase opt-in required",
            arguments.getString("settingsEditorMode") == "ROOT" && arguments.getString("settingsProcessDeathPhase") == phase)
        val id = requireNotNull(arguments.getString("settingsProcessDeathId")) { "process_death_fixture_id_required" }
        require(UUID.fromString(id).toString() == id) { "process_death_fixture_id_invalid" }
        check(thorUserId == 0 && Process.myUid() != 0) { "process_death_requires_application_user_zero" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get()
        return Environment(
            id = id,
            context = context,
            repository = requireNotNull(koin.getOrNull<SettingsEditorRepository>()),
            store = requireNotNull(koin.getOrNull<SettingsEditorStore>()),
            preferences = requireNotNull(koin.getOrNull<PreferenceRepository>()),
            privilege = requireNotNull(koin.getOrNull<PrivilegeManager>()),
            statuses = requireNotNull(koin.getOrNull<RootLaneStatusSource>()),
        )
    }

    /** Reuse the exact current production session factory without adding a production test hook. */
    @Suppress("UNCHECKED_CAST")
    private fun productionSessionFactory(repository: SettingsEditorRepository): suspend () -> SettingsEditorSession {
        try {
            val field = SettingsEditorRepository::class.java.getDeclaredField("controller").apply { isAccessible = true }
            val controller = field.get(repository)
            check(controller is SettingsEditorController) { "process_death_production_controller_unavailable" }
            val factoryField = SettingsEditorController::class.java.getDeclaredField("openSession").apply { isAccessible = true }
            return (factoryField.get(controller) as? (suspend () -> SettingsEditorSession))
                ?: error("process_death_production_session_factory_unavailable")
        } catch (failure: ReflectiveOperationException) {
            throw IllegalStateException("process_death_production_session_reflection_unavailable", failure)
        }
    }

    private class Environment(
        val id: String,
        val context: Context,
        val repository: SettingsEditorRepository,
        val store: SettingsEditorStore,
        val preferences: PreferenceRepository,
        val privilege: PrivilegeManager,
        val statuses: RootLaneStatusSource,
    ) {
        val directory = File(context.noBackupFilesDir, "settings_reconcile_death/$id")

        fun lastUpdateTime(): Long = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

        fun rootRecords(): List<SettingsRootExecutionRecord> =
            FileSettingsRootExecutions(File(context.noBackupFilesDir, "settings_editor_root_executions.json")).load()

        suspend fun value(key: String): SettingValue =
            SettingsEditorController.valueOf(repository.read(SettingsEditorView.SYSTEM).getOrThrow(), key)

        suspend fun prepareRoot() {
            preferences.setPrivilegeMode(PrivilegeMode.ROOT)
            withTimeout(10_000) { preferences.userPreferences.first { it.preferredPrivilegeMode == PrivilegeMode.ROOT } }
            withTimeout(30_000) {
                statuses.statuses.first { lanes -> lanes.values.none { it.activeCommandClass != null } }
                privilege.refreshAndAwait()
                while (true) {
                    privilege.state.first { it.active == PrivilegeMode.ROOT && it.rootAvailability.canAdmitRoot }
                    statuses.statuses.first { lanes -> lanes.values.none { it.activeCommandClass != null } }
                    val probe = repository.read(SettingsEditorView.SYSTEM)
                    if (probe.isSuccess) break
                    val failure = probe.exceptionOrNull()
                    if (failure !is RootAdmissionUnavailable && failure !is ShellLaneBusy) probe.getOrThrow()
                    // Only the read-only readiness probe may repeat; no mutation is ever replayed.
                    delay(25)
                }
            }
            setConsent(true)
        }

        suspend fun setConsent(accepted: Boolean?) {
            context.localState.edit {
                if (accepted == null) it.remove(CONSENT_KEY) else it[CONSENT_KEY] = accepted
            }
            withTimeout(10_000) { store.consent.first { it == (accepted == true) } }
        }

        suspend fun cleanup(state: FixtureState) {
            check(state.fixtureId == id && state.key == "thor_sett_reconcile_" + id.replace("-", "")) {
                "process_death_cleanup_fixture_identity_mismatch"
            }
            val failures = mutableListOf<Throwable>()
            try {
                if (state.ownsKey) {
                    prepareRoot()
                    // Never manufacture an acknowledgement or remove a root execution record.
                    check(rootRecords().none { it.resource == state.resource() }) { "process_death_cleanup_retains_unresolved_producer" }
                    val current = value(state.key)
                    check(current == SettingValue.ABSENT || current == state.desired()) { "process_death_cleanup_value_conflict" }
                    if (current.present) {
                        assertEquals(SettingsEditOutcome.VERIFIED,
                            repository.change(SettingsEditorView.SYSTEM, state.key, current, SettingValue.ABSENT).getOrThrow().outcome)
                    }
                    assertEquals(SettingValue.ABSENT, value(state.key))
                }
                check(rootRecords() == state.initialRootExecutions) { "process_death_unrelated_root_journal_changed" }
                // Refuse to overwrite new unrelated history. Controller retention may have dropped
                // old tail rows; the private backup restores that tail after owned-key deletion.
                val unrelated = store.history.load().filterNot {
                    state.ownsKey && it.key == state.key && it.userId == 0 && it.view == SettingsEditorView.SYSTEM
                }
                check(unrelated == state.initialHistory.take(unrelated.size)) { "process_death_concurrent_history_changed" }
                store.history.save(state.initialHistory)
                check(store.history.load() == state.initialHistory) { "process_death_history_restore_failed" }
            } catch (failure: Throwable) {
                failures += failure
            } finally {
                try { setConsent(state.initialConsent) } catch (failure: Throwable) { failures += failure }
                try {
                    preferences.setPrivilegeMode(state.initialPreference)
                    withTimeout(10_000) { preferences.userPreferences.first { it.preferredPrivilegeMode == state.initialPreference } }
                    withTimeout(30_000) {
                        statuses.statuses.first { lanes -> lanes.values.none { it.activeCommandClass != null } }
                        privilege.refreshAndAwait()
                    }
                } catch (failure: Throwable) { failures += failure }
            }
            if (failures.isNotEmpty()) {
                val first = failures.first()
                failures.drop(1).filter { it !== first }.forEach(first::addSuppressed)
                throw first
            }
            check(directory.deleteRecursively()) { "process_death_owned_fixture_cleanup_failed" }
            metric("cleanup", "fixture=${state.fixtureId} key_absent=true originals_restored=true")
        }
    }

    @Serializable
    private data class FixtureState(
        val fixtureId: String,
        val processId: Int,
        val processStartTicks: Long,
        val bootId: String,
        val appLastUpdateTime: Long,
        val key: String,
        val initialHistory: List<SettingsEditRecord>,
        val initialRootExecutions: List<SettingsRootExecutionRecord>,
        val initialPreference: PrivilegeMode?,
        val initialConsent: Boolean?,
        val ownsKey: Boolean = false,
        val phase: String = "PREPARED",
        val pending: SettingsEditRecord? = null,
    ) {
        fun resource() = SettingsRootResource(SettingsEditorView.SYSTEM, 0, key)
        fun desired() = SettingValue(true, "fixture-$fixtureId")
    }

    @Serializable
    private data class ReadyMarker(
        val fixtureId: String,
        val phase: String,
        val processId: Int,
        val processStartTicks: Long,
        val bootId: String,
        val recordId: String,
        val key: String,
        val userId: Int = 0,
        val table: String = "system",
    )

    private companion object {
        const val ACKNOWLEDGED = "ACKNOWLEDGED_WRITE_AWAITING_FINAL_JOURNAL"
        val CONSENT_KEY = booleanPreferencesKey("settings_editor_consent_accepted")
        val json = Json { encodeDefaults = true }

        fun processStartTicks(): Long = File("/proc/self/stat").readText()
            .substringAfterLast(") ").trim().split(Regex("\\s+"))[19].toLong()

        fun readState(directory: File): FixtureState = AtomicFile(File(directory, "fixture.json"))
            .openRead().bufferedReader().use { json.decodeFromString(it.readText()) }

        fun writeState(directory: File, state: FixtureState) {
            atomicWrite(File(directory, "fixture.json"), json.encodeToString(state))
            check(readState(directory) == state) { "process_death_fixture_not_durable" }
        }

        fun writeReady(directory: File, marker: ReadyMarker) {
            atomicWrite(File(directory, "ready.json"), json.encodeToString(marker))
        }

        fun atomicWrite(file: File, value: String) {
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(value.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
                atomic.finishWrite(stream)
            } catch (failure: Exception) {
                atomic.failWrite(stream)
                throw failure
            }
        }

        fun metric(phase: String, detail: String) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nTHOR_SETTINGS_DEATH_METRIC phase=$phase $detail\n")
            })
        }
    }
}
