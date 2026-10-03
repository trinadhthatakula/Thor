// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.system.Os
import android.util.AtomicFile
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.data.repository.localState
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.model.*
import com.valhalla.thor.domain.repository.PreferenceRepository
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Emulator-only, host-orchestrated arm -> app SIGKILL -> observe -> real reboot -> recover.
 * Use settingsLivePhase and one settingsLiveId UUID with settingsEditorMode=ROOT. Keep Settings
 * Editor closed. The host must verify ready.json and the app PID/start identity before killing it.
 *
 * The production controller journals PENDING and the real gateway observer journals the receipt.
 * Only the writer payload is replaced with a bounded test-APK helper. Its post-death heartbeat
 * acknowledges actual writes to one disposable key. No observer result is forged or intercepted.
 * Private originals stay on-device; do not copy fixture.json into host evidence. Loss of the app
 * callback must keep the real receipt on the same boot even after the helper finishes normally.
 */
@RunWith(AndroidJUnit4::class)
class SettingsEditorLiveProducerIntegrationTest {
    @Test
    fun armLiveWriter(): Unit = runBlocking {
        val env = environment("arm")
        check(!env.directory.exists()) { "live_fixture_already_exists" }
        val initial = FixtureState(
            fixtureId = env.id,
            processId = Process.myPid(),
            processStartTicks = processStartTicks(),
            bootId = requireNotNull(readSettingsBootId()) { "live_boot_unavailable" },
            appLastUpdateTime = env.lastUpdateTime(),
            key = key(env.id),
            unrelatedKey = "thor_sett_live_other_" + env.id.replace("-", ""),
            initialHistory = env.store.history.load(),
            initialPreference = env.preferences.userPreferences.first().preferredPrivilegeMode,
            initialConsent = env.context.localState.data.first()[CONSENT_KEY],
        )
        // A later boot retires all valid old-boot receipts. Never run over unrelated uncertainty.
        check(env.rootRecords().isEmpty()) { "live_requires_empty_root_journal" }
        val parent = requireNotNull(env.directory.parentFile)
        if (parent.mkdir()) Os.chmod(parent.absolutePath, 448)
        check(env.directory.mkdirs()) { "live_fixture_unavailable" }
        Os.chmod(env.directory.absolutePath, 448)
        try { writeState(env.directory, initial) }
        catch (failure: Throwable) {
            if (!env.directory.deleteRecursively()) failure.addSuppressed(IOException("live_unstarted_cleanup_failed"))
            throw failure
        }
        var primary: Throwable? = null
        try {
            env.prepareRoot()
            assertEquals(SettingValue.ABSENT, env.value(initial.key))
            assertEquals(SettingValue.ABSENT, env.value(initial.unrelatedKey))
            writeState(env.directory, initial.copy(ownsKeys = true))
            check(env.control.mkdir()) { "live_control_unavailable" }
            Os.chmod(env.control.absolutePath, 448) // 0700; markers created by root are chowned to us.
            val factory = productionSessionFactory(env.repository)
            val controller = SettingsEditorController(
                currentUserId = { thorUserId }, allowed = { env.store.consent.first() },
                openSession = {
                    val delegate = factory()
                    check(delegate.provider == PrivilegeMode.ROOT) { "live_requires_root_session" }
                    object : SettingsEditorSession by delegate {
                        override suspend fun write(view: SettingsEditorView, userId: Int, key: String,
                            expected: SettingValue, desired: SettingValue): SettingsBridgeResult = coroutineScope {
                            check(view == SettingsEditorView.SYSTEM && userId == 0 && key == initial.key)
                            check(expected == SettingValue.ABSENT && desired == initialValue())
                            val pending = env.store.history.load().single { it.key == key && it.view == view && it.userId == userId }
                            check(pending.outcome == SettingsEditOutcome.PENDING) { "live_pending_missing" }
                            // Genuine beforeSubmit/onOutcome callbacks retain their production behavior.
                            val command = async(Dispatchers.IO) {
                                env.gateway.executeShellCommand(env.helperCommand(), PrivilegeExecutionContext(
                                    commandClass = PrivilegeCommandClass("test.settings.live_producer"),
                                    rootExecutionPolicy = RootExecutionPolicy.ISOLATED,
                                    commandTimeout = 190.seconds,
                                    rootExecutionObserver = env.store.rootExecutions.observer(initial.resource()),
                                )).getOrThrow()
                            }
                            val ready = withTimeout(30_000) {
                                while (!File(env.control, "ready.json").exists()) {
                                    check(!command.isCompleted) { "live_helper_ended_before_readiness" }
                                    delay(25)
                                }
                                JSONObject(File(env.control, "ready.json").readText())
                            }
                            validateHelper(ready, initial)
                            check(!command.isCompleted) { "live_helper_already_ended" }
                            val receipt = env.rootRecords().single()
                            check(receipt.resource == initial.resource() && receipt.bootId == initial.bootId && receipt.kind == null) {
                                "live_receipt_not_pending"
                            }
                            check(env.store.history.load().single { it.id == pending.id } == pending) { "live_pending_changed" }
                            val armed = readState(env.directory).copy(phase = LIVE, pending = pending,
                                receipt = receipt, helper = ready.toString())
                            writeState(env.directory, armed)
                            writeReady(env.directory, armed, "ready.json")
                            metric("armed", "fixture=${env.id} producer_uid=0 real_receipt=true")
                            // The host kills only the app. The independently bounded producer keeps writing.
                            command.await()
                            error("live_host_interruption_did_not_happen")
                        }
                    }
                }, history = env.store.history,
            )
            withContext(Dispatchers.IO) {
                controller.change(SettingsEditorView.SYSTEM, initial.key, SettingValue.ABSENT, initialValue())
            }
            error("live_arm_returned")
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            // SIGKILL skips this. Ordinary failures clean up only after a real acknowledgement.
            guardedCleanup(env, primary, allowChangedBoot = false)
        }
    }

    @Test
    fun observeAfterAppDeath() = runBlocking {
        val env = environment("observe")
        val state = readState(env.directory)
        env.validateState(state)
        check(state.phase == LIVE && readSettingsBootId() == state.bootId) { "live_observe_requires_original_boot" }
        check(Process.myPid() != state.processId || processStartTicks() != state.processStartTicks) { "live_requires_new_app_process" }
        val pending = requireNotNull(state.pending)
        val helper = JSONObject(requireNotNull(state.helper))
        var primary: Throwable? = null
        try {
            assertEquals(pending, env.store.history.load().single { it.id == pending.id })
            env.assertReceipt(state)
            // Both samples are read by the NEW app process. A later counter proves continued
            // acknowledged Settings writes, not just survival of a readiness file from before death.
            val first = JSONObject(File(env.control, "heartbeat.json").readText())
            validateHelper(first, state)
            val later = withTimeout(12_000) {
                while (true) {
                    val next = JSONObject(File(env.control, "heartbeat.json").readText())
                    validateHelper(next, state)
                    check(sameIdentity(helper, next)) { "live_writer_identity_changed" }
                    if (next.getLong("writes") > first.getLong("writes") && next.getLong("sequence") > first.getLong("sequence")) return@withTimeout next
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            metric("heartbeat", "fixture=${env.id} first=${first.getLong("writes")} later=${later.getLong("writes")} new_app_pid=${Process.myPid()}")
            env.prepareRoot()
            env.reconcileAndRefuse(state, initialValue())
            // A receipt protects only its exact resource, not an unrelated setting.
            assertEquals(SettingsEditOutcome.VERIFIED, env.repository.change(SettingsEditorView.SYSTEM,
                state.unrelatedKey, SettingValue.ABSENT, SettingValue(true, "independent")).getOrThrow().outcome)
            assertEquals(SettingsEditOutcome.VERIFIED, env.repository.change(SettingsEditorView.SYSTEM,
                state.unrelatedKey, SettingValue(true, "independent"), SettingValue.ABSENT).getOrThrow().outcome)
            env.assertReceipt(state)
            val complete = File(env.control, "complete")
            check(complete.createNewFile()) { "live_completion_already_requested" }
            Os.chmod(complete.absolutePath, 384) // 0600
            val completed = withTimeout(15_000) {
                while (!File(env.control, "completed.json").exists()) delay(25)
                JSONObject(File(env.control, "completed.json").readText())
            }
            check(completed.getString("phase") == "COMPLETED" && sameIdentity(helper, completed)) { "live_completion_identity_mismatch" }
            env.awaitProcessesGone(helper)
            // Completion evidence is test-only; production still never received its acknowledgement.
            env.reconcileAndRefuse(state, completedValue())
            metric("observed", "fixture=${env.id} pending_preserved=true receipt_preserved=true root_refused=true unrelated_key_usable=true producer_finished=true")
            env.restorePreferences(state)
            val awaiting = state.copy(phase = AWAITING_REBOOT)
            writeState(env.directory, awaiting)
            writeReady(env.directory, awaiting, "reboot-ready.json")
            metric("awaiting_reboot", "fixture=${env.id} same_boot_barrier_retained=true")
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            // Never delete this same-boot receipt or its before-image, even on a failed assertion.
            try { env.restorePreferences(state) }
            catch (cleanup: Throwable) { if (primary != null) primary.addSuppressed(cleanup) else throw cleanup }
        }
    }

    @Test
    fun recoverAfterReboot() = runBlocking {
        val env = environment("recover")
        val state = readState(env.directory)
        env.validateState(state)
        check(state.phase == AWAITING_REBOOT) { "live_recovery_not_armed" }
        val currentBoot = requireNotNull(readSettingsBootId()) { "live_boot_unavailable" }
        check(currentBoot != state.bootId) { "live_recovery_requires_actual_reboot" }
        env.assertReceipt(state)
        var primary: Throwable? = null
        try {
            env.prepareRoot()
            env.assertReceipt(state) // Readiness reads must not retire the receipt.
            assertEquals(completedValue(), env.value(state.key))
            val observed = env.repository.reconcile(requireNotNull(state.pending).id).getOrThrow()
            assertEquals(state.pending, observed.copy(observation = null))
            env.assertReceipt(state) // Reconciliation remains read-only even on the later boot.
            // Public write admission observes the new boot and retires the old receipt normally.
            assertEquals(SettingsEditOutcome.VERIFIED, env.repository.change(SettingsEditorView.SYSTEM,
                state.key, completedValue(), SettingValue.ABSENT).getOrThrow().outcome)
            check(env.rootRecords().isEmpty()) { "live_new_boot_did_not_retire_receipt" }
            metric("recovered", "fixture=${env.id} changed_boot=true public_write_verified=true")
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally { guardedCleanup(env, primary, allowChangedBoot = true) }
    }

    /** Failure recovery only; never count this as a passed live-death acceptance sequence. */
    @Test
    fun cleanupAfterFailedRunReboot() = runBlocking {
        val env = environment("cleanup")
        val state = readState(env.directory)
        env.validateState(state)
        check(requireNotNull(readSettingsBootId()) != state.bootId) { "live_cleanup_requires_actual_reboot" }
        guardedCleanup(env, null, allowChangedBoot = true)
    }

    private suspend fun guardedCleanup(env: Environment, primary: Throwable?, allowChangedBoot: Boolean) = withContext(NonCancellable) {
        try { env.cleanup(readState(env.directory), allowChangedBoot) }
        catch (cleanup: Throwable) { if (primary != null) primary.addSuppressed(cleanup) else throw cleanup }
    }

    private fun environment(phase: String): Environment {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit live-writer ROOT phase opt-in required", args.getString("settingsEditorMode") == "ROOT" && args.getString("settingsLivePhase") == phase)
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "live_death_reboot_fixture_is_emulator_only" }
        val id = requireNotNull(args.getString("settingsLiveId"))
        require(UUID.fromString(id).toString() == id)
        check(thorUserId == 0 && Process.myUid() != 0)
        val koin = GlobalContext.get()
        return Environment(id, InstrumentationRegistry.getInstrumentation().targetContext,
            requireNotNull(koin.getOrNull<SettingsEditorRepository>()),
            requireNotNull(koin.getOrNull<SettingsEditorStore>()),
            requireNotNull(koin.getOrNull<PreferenceRepository>()),
            requireNotNull(koin.getOrNull<PrivilegeManager>()),
            requireNotNull(koin.getOrNull<RootLaneStatusSource>()),
            requireNotNull(koin.getOrNull<RootSystemGateway>()))
    }

    @Suppress("UNCHECKED_CAST")
    private fun productionSessionFactory(repository: SettingsEditorRepository): suspend () -> SettingsEditorSession {
        val controller = SettingsEditorRepository::class.java.getDeclaredField("controller").apply { isAccessible = true }.get(repository)
        check(controller is SettingsEditorController)
        return SettingsEditorController::class.java.getDeclaredField("openSession").apply { isAccessible = true }
            .get(controller) as suspend () -> SettingsEditorSession
    }

    private class Environment(val id: String, val context: Context, val repository: SettingsEditorRepository,
        val store: SettingsEditorStore, val preferences: PreferenceRepository, val privilege: PrivilegeManager,
        val statuses: RootLaneStatusSource, val gateway: RootSystemGateway) {
        val directory = File(context.noBackupFilesDir, "settings_live_writer/$id")
        val control = File(directory, "control")
        fun lastUpdateTime(): Long = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        fun rootRecords(): List<SettingsRootExecutionRecord> =
            FileSettingsRootExecutions(File(context.noBackupFilesDir, "settings_editor_root_executions.json")).load()
        suspend fun value(key: String): SettingValue = SettingsEditorController.valueOf(repository.read(SettingsEditorView.SYSTEM).getOrThrow(), key)
        fun assertReceipt(state: FixtureState) { check(rootRecords() == listOf(requireNotNull(state.receipt))) { "live_real_receipt_changed" } }
        fun validateState(state: FixtureState) {
            check(state.fixtureId == id && state.key == key(id) && state.unrelatedKey == "thor_sett_live_other_" + id.replace("-", "")) { "live_fixture_identity_mismatch" }
            check(lastUpdateTime() == state.appLastUpdateTime) { "live_app_was_replaced" }
        }
        fun helperCommand(): String {
            val request = JSONObject().put("key", key(id)).put("userId", 0).put("variant", "ignore_term_child").put("maxSeconds", 180)
            val payload = Base64.encodeToString(request.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val shell = "trap '' TERM; exec /system/bin/app_process /system/bin --nice-name=thor_sett_live_${id.replace("-", "")} " +
                "com.valhalla.thor.data.settingseditor.SettingsEditorLiveWriterProbe ${quote(payload)}"
            val classpath = settingsEditorProbeClasspath()
            return "CLASSPATH=${quote(classpath)} /system/bin/toybox timeout --foreground -s KILL 180 /system/bin/sh -c ${quote(shell)}"
        }
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
                    delay(25) // Only a read-only readiness probe may retry.
                }
            }
            setConsent(true)
        }
        suspend fun reconcileAndRefuse(state: FixtureState, expected: SettingValue) {
            val pending = requireNotNull(state.pending)
            val observed = repository.reconcile(pending.id).getOrThrow()
            assertTrue("Reconciliation preserves PENDING and its before-image", observed.copy(observation = null) == pending)
            assertEquals(expected, requireNotNull(observed.observation).value)
            assertEquals(PrivilegeMode.ROOT, requireNotNull(observed.observation).provider)
            assertTrue(repository.undo(pending.id).isFailure)
            val history = store.history.load()
            val refusal = repository.change(SettingsEditorView.SYSTEM, state.key, expected, SettingValue.ABSENT)
            assertTrue("Conflicting writes must fail specifically at the uncertainty gate", refusal.exceptionOrNull() is SettingsExecutionUncertain)
            assertTrue("Refusal leaves private history unchanged", history == store.history.load())
            assertEquals(expected, value(state.key))
            assertReceipt(state)
        }
        suspend fun awaitProcessesGone(helper: JSONObject) = withTimeout(15_000) {
            val child = helper.getJSONObject("child")
            val identities = listOf(helper, helper.getJSONObject("watchdog"), child, child.getJSONObject("watchdog"))
            for (identity in identities) {
                val pid = identity.getInt("pid")
                check(pid > 1)
                while (true) {
                    val result = gateway.executeShellCommand("if [ -e /proc/$pid/stat ]; then cat /proc/$pid/stat; else printf 'ABSENT\\n'; fi",
                        PrivilegeExecutionContext(commandClass = PrivilegeCommandClass("test.settings.live_identity"))).getOrThrow()
                    if (result.first == 0) {
                        val stat = result.second.orEmpty().trim()
                        if (stat == "ABSENT") break
                        val fields = stat.substringAfterLast(") ").split(Regex("\\s+"))
                        check(fields.size >= 20) { "live_process_identity_unreadable" }
                        if (fields[19].toLong() != identity.getLong("startTicks") || fields[0] in listOf("Z", "X")) break
                    }
                    delay(50)
                }
            }
        }
        suspend fun setConsent(value: Boolean?) {
            context.localState.edit { if (value == null) it.remove(CONSENT_KEY) else it[CONSENT_KEY] = value }
            withTimeout(10_000) { store.consent.first { it == (value == true) } }
        }
        suspend fun restorePreferences(state: FixtureState) {
            var primary: Throwable? = null
            try { setConsent(state.initialConsent) } catch (failure: Throwable) { primary = failure }
            try {
                preferences.setPrivilegeMode(state.initialPreference)
                withTimeout(10_000) { preferences.userPreferences.first { it.preferredPrivilegeMode == state.initialPreference } }
                withTimeout(30_000) {
                    statuses.statuses.first { lanes -> lanes.values.none { it.activeCommandClass != null } }
                    privilege.refreshAndAwait()
                }
            } catch (failure: Throwable) { if (primary != null) primary.addSuppressed(failure) else primary = failure }
            primary?.let { throw it }
        }
        suspend fun cleanup(state: FixtureState, allowChangedBoot: Boolean) {
            validateState(state)
            var primary: Throwable? = null
            try {
                if (state.ownsKeys) {
                    // Only this emulator's real changed boot permits admission to retire a lost receipt.
                    val changedBoot = readSettingsBootId()?.let { it != state.bootId } == true
                    val receipts = rootRecords()
                    val ownedReceipt = receipts.size == 1 && receipts.single().resource == state.resource() &&
                        receipts.single().bootId == state.bootId && (state.receipt == null || receipts == listOf(state.receipt))
                    check(receipts.isEmpty() || (allowChangedBoot && changedBoot && ownedReceipt)) { "live_cleanup_retains_unresolved_receipt" }
                    prepareRoot()
                    for (owned in listOf(state.key, state.unrelatedKey)) {
                        val current = value(owned)
                        val allowed = if (owned == state.key) listOf(SettingValue.ABSENT, initialValue(), completedValue())
                            else listOf(SettingValue.ABSENT, SettingValue(true, "independent"))
                        check(current in allowed) { "live_cleanup_value_conflict" }
                        if (current.present || (owned == state.key && ownedReceipt)) assertEquals(SettingsEditOutcome.VERIFIED,
                            repository.change(SettingsEditorView.SYSTEM, owned, current, SettingValue.ABSENT).getOrThrow().outcome)
                        assertEquals(SettingValue.ABSENT, value(owned))
                    }
                }
                check(rootRecords().isEmpty()) { "live_cleanup_receipt_remains" }
                val unrelated = store.history.load().filterNot { state.ownsKeys && it.key in listOf(state.key, state.unrelatedKey) && it.view == SettingsEditorView.SYSTEM && it.userId == 0 }
                check(unrelated == state.initialHistory.take(unrelated.size)) { "live_concurrent_history_changed" }
                store.history.save(state.initialHistory)
                check(store.history.load() == state.initialHistory) { "live_history_restore_failed" }
            } catch (failure: Throwable) { primary = failure }
            finally { try { restorePreferences(state) } catch (failure: Throwable) { if (primary != null) primary.addSuppressed(failure) else primary = failure } }
            primary?.let { throw it }
            check(directory.deleteRecursively()) { "live_fixture_cleanup_failed" }
            metric("cleanup", "fixture=$id keys_absent=true originals_restored=true")
        }
    }

    @Serializable
    private data class FixtureState(val fixtureId: String, val processId: Int, val processStartTicks: Long,
        val bootId: String, val appLastUpdateTime: Long, val key: String, val unrelatedKey: String,
        val initialHistory: List<SettingsEditRecord>, val initialPreference: PrivilegeMode?,
        val initialConsent: Boolean?, val ownsKeys: Boolean = false, val phase: String = "PREPARING",
        val pending: SettingsEditRecord? = null, val receipt: SettingsRootExecutionRecord? = null, val helper: String? = null) {
        fun resource() = SettingsRootResource(SettingsEditorView.SYSTEM, 0, key)
    }

    private companion object {
        const val LIVE = "LIVE_WRITER_AWAITING_APP_DEATH"
        const val AWAITING_REBOOT = "AWAITING_REBOOT"
        val CONSENT_KEY = booleanPreferencesKey("settings_editor_consent_accepted")
        val json = Json { encodeDefaults = true }
        fun key(id: String) = "thor_sett_live_" + id.replace("-", "")
        fun initialValue() = SettingValue(true, SettingsEditorLiveWriterProbe.INITIAL_VALUE)
        fun completedValue() = SettingValue(true, SettingsEditorLiveWriterProbe.COMPLETED_VALUE)
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        fun processStartTicks() = File("/proc/self/stat").readText().substringAfterLast(") ").trim().split(Regex("\\s+"))[19].toLong()
        fun sameIdentity(a: JSONObject, b: JSONObject) = listOf("pid", "startTicks", "pgid", "uid", "bootId").all { a.get(it) == b.get(it) }
        fun validateHelper(helper: JSONObject, state: FixtureState) {
            check(helper.getString("runId") == state.fixtureId && helper.getString("key") == state.key && helper.getInt("userId") == 0)
            check(helper.getString("phase") == "LIVE" && helper.getString("bootId") == state.bootId && helper.getInt("uid") == 0)
            check(helper.getString("variant") == "ignore_term_child" && helper.getBoolean("termIgnored") && helper.getLong("writes") >= 1)
            val child = helper.getJSONObject("child")
            check(child.getString("role") == "child" && child.getInt("uid") == 0 && child.getBoolean("termIgnored"))
            check(child.getInt("pgid") == helper.getInt("pgid") && helper.getInt("pgid") > 1)
        }
        fun readState(directory: File): FixtureState = AtomicFile(File(directory, "fixture.json")).openRead().bufferedReader().use { json.decodeFromString(it.readText()) }
        fun writeState(directory: File, state: FixtureState) {
            atomicWrite(File(directory, "fixture.json"), json.encodeToString(state))
            check(readState(directory) == state) { "live_fixture_not_durable" }
        }
        fun writeReady(directory: File, state: FixtureState, name: String) {
            val marker = JSONObject().put("fixtureId", state.fixtureId).put("phase", state.phase)
                .put("processId", state.processId).put("processStartTicks", state.processStartTicks).put("bootId", state.bootId)
                .put("recordId", requireNotNull(state.pending).id).put("key", state.key).put("userId", 0).put("table", "system")
            atomicWrite(File(directory, name), marker.toString())
        }
        fun atomicWrite(file: File, value: String) {
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try { stream.write(value.toByteArray(Charsets.UTF_8)); stream.fd.sync(); atomic.finishWrite(stream) }
            catch (failure: Exception) { atomic.failWrite(stream); throw failure }
        }
        fun metric(phase: String, detail: String) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nTHOR_SETTINGS_LIVE_METRIC phase=$phase $detail\n")
            })
        }
    }
}
