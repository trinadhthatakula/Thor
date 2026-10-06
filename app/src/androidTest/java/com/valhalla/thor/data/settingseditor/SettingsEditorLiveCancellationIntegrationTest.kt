// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor

import android.content.Context
import android.os.Bundle
import android.os.Process
import android.system.Os
import android.system.OsConstants
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
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.RootAdmissionUnavailable
import com.valhalla.thor.domain.model.RootExecutionObserver
import com.valhalla.thor.domain.model.RootExecutionPolicy
import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.RootJobOutcomeKind
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Explicit ROOT/user-0 opt-in. Cancels a real writer and same-group child through the production
 * gateway, with a genuine Settings receipt. This intentionally calls the gateway directly:
 * SettingsEditorController makes accepted writes NonCancellable. It does not simulate app death.
 * Keep Settings Editor closed; private restoration originals must never be copied to host logs.
 */
@RunWith(AndroidJUnit4::class)
class SettingsEditorLiveCancellationIntegrationTest {
    @Test
    fun hostileWriterCancellationAcknowledgesBeforeCleanupAndReadmission() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit live cancellation opt-in required",
            arguments.getString("settingsLiveCancellation") == "true" &&
                arguments.getString("settingsEditorMode") == "ROOT")
        check(thorUserId == 0 && Process.myUid() in 10000 until 100000) { "live_cancel_requires_application_user_zero" }
        val fixture = Fixture()
        check(fixture.context.packageName == "com.valhalla.thor.debug") { "live_cancel_requires_debug_package" }
        val originalReceipts = fixture.rootRecords()
        check(originalReceipts.isEmpty()) { "live_cancel_existing_receipts" }
        val original = SavedState(
            fixture.id, fixture.key, requireNotNull(readSettingsBootId()),
            fixture.store.history.load(), fixture.preferences.userPreferences.first().preferredPrivilegeMode,
            fixture.context.localState.data.first()[CONSENT_KEY],
        )
        fixture.create(original)

        var pending: Job? = null
        var primary: Throwable? = null
        val releaseRecording = CompletableDeferred<Unit>()
        val acknowledgement = CompletableDeferred<Acknowledgement>()
        val terminal = CompletableDeferred<Throwable?>()
        val beforeSubmitEntered = AtomicBoolean()
        val lastOutcome = AtomicReference<RootJobOutcome?>()
        val outcomeForwarded = AtomicBoolean()
        try {
            fixture.prepareRoot()
            assertTrue("Only an absent UUID key is owned", fixture.value() == SettingValue.ABSENT)
            fixture.save(fixture.load().copy(ownsKey = true))
            val gateObserver = fixture.store.rootExecutions.observer(fixture.resource)
            val observer = object : RootExecutionObserver {
                override suspend fun beforeSubmit() {
                    beforeSubmitEntered.set(true)
                    gateObserver.beforeSubmit()
                    val receipt = fixture.rootRecords().single()
                    check(receipt.resource == fixture.resource && receipt.bootId == original.bootId) {
                        "live_cancel_receipt_identity_mismatch"
                    }
                    fixture.save(fixture.load().copy(receipt = receipt))
                }

                override suspend fun onOutcome(outcome: RootJobOutcome) {
                    lastOutcome.set(outcome)
                    val held = fixture.laneHeld()
                    val receiptBefore = fixture.rootRecords()
                    // Always pass the real Odin outcome to the real gate; never synthesize cleanup.
                    gateObserver.onOutcome(outcome)
                    outcomeForwarded.set(true)
                    fixture.save(fixture.load().copy(acknowledgedCleanup = outcome.cleanupConfirmed))
                    acknowledgement.complete(Acknowledgement(outcome, held, receiptBefore, fixture.rootRecords()))
                    // The execution lease remains owned until outcome observation returns.
                    withTimeout(20_000) { releaseRecording.await() }
                }
            }
            val execution = PrivilegeExecutionContext(
                commandClass = COMMAND_CLASS,
                commandTimeout = 100.seconds,
                rootExecutionPolicy = RootExecutionPolicy.ISOLATED,
                rootExecutionObserver = observer,
            )
            val launched = launch(Dispatchers.IO) {
                try {
                    fixture.gateway.executeShellCommand(fixture.command(), execution).getOrThrow()
                    terminal.complete(null)
                } catch (failure: Throwable) {
                    terminal.complete(failure)
                    if (failure is CancellationException) throw failure
                }
            }
            pending = launched
            val ready = withTimeout(20_000) {
                val file = File(fixture.control, "ready.json")
                while (file.length() == 0L) {
                    check(!launched.isCompleted && lastOutcome.get() == null) { "live_cancel_writer_ended_before_ready" }
                    check(File(fixture.control, "error.json").length() == 0L) { "live_cancel_writer_failed" }
                    delay(25)
                }
                JSON.decodeFromString<LiveMarker>(file.readText())
            }
            fixture.validateReady(ready, original.bootId)
            fixture.save(fixture.load().copy(ready = ready))
            val liveReceipt = requireNotNull(fixture.load().receipt)
            assertEquals(listOf(liveReceipt), fixture.rootRecords())
            assertTrue("The real writer owns the interactive lane", fixture.laneHeld())
            assertFalse(launched.isCompleted)
            fixture.assertWriteRefused()

            // Preference changes reuse existing observations; do not refresh a held root lane.
            val shizukuChecked = fixture.privilege.state.value.shizuku
            if (shizukuChecked) {
                fixture.selectMode(PrivilegeMode.SHIZUKU)
                fixture.assertWriteRefused()
                fixture.selectMode(PrivilegeMode.ROOT)
            }
            assertEquals(listOf(liveReceipt), fixture.rootRecords())
            assertTrue("Refusals leave private history unchanged", original.initialHistory == fixture.store.history.load())

            launched.cancel(CancellationException("Cancel owned hostile Settings writer"))
            val acknowledged = withTimeout(15_000) { acknowledgement.await() }
            assertEquals(RootJobOutcomeKind.CANCELLED, acknowledged.outcome.kind)
            assertTrue(acknowledged.outcome.started)
            assertTrue(acknowledged.outcome.cleanupConfirmed)
            assertTrue("Acknowledgement runs before lane release", acknowledged.laneHeld)
            assertEquals(listOf(liveReceipt), acknowledged.receiptsBefore)
            assertTrue("Only actual acknowledged cleanup retires the receipt", acknowledged.receiptsAfter.isEmpty())
            assertTrue(fixture.laneHeld())
            assertFalse("Cancellation awaits outcome recording", launched.isCompleted)
            val refused = fixture.gateway.executeShellCommand("printf 'after-ack'", PrivilegeExecutionContext())
            assertTrue("Outcome recording still owns admission", refused.exceptionOrNull() is ShellLaneBusy)
            releaseRecording.complete(Unit)
            withTimeout(15_000) { launched.join() }
            assertTrue(withTimeout(5_000) { terminal.await() } is CancellationException)
            assertFalse(fixture.laneHeld())
            assertTrue(fixture.rootRecords().isEmpty())

            fixture.prepareRoot()
            fixture.assertRetired(ready.identities())
            assertTrue("Cancellation preserves the helper's initial value",
                fixture.value() == SettingValue(true, SettingsEditorLiveWriterProbe.INITIAL_VALUE))
            assertEquals(SettingsEditOutcome.VERIFIED,
                fixture.repository.change(SettingsEditorView.SYSTEM, fixture.key,
                    SettingValue(true, SettingsEditorLiveWriterProbe.INITIAL_VALUE),
                    SettingValue(true, AFTER_CANCELLATION)).getOrThrow().outcome)
            assertTrue("A reviewed write is usable after acknowledged cleanup", fixture.value() == SettingValue(true, AFTER_CANCELLATION))
            assertTrue(fixture.rootRecords().isEmpty())
            metric("acknowledged", "fixture=${fixture.id} identities=${ready.identities().size} shizuku_checked=$shizukuChecked")
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                val failures = mutableListOf<Throwable>()
                releaseRecording.complete(Unit)
                pending?.cancel()
                try { withTimeout(20_000) { pending?.join() } } catch (failure: Throwable) { failures += failure }
                val safe = pending?.isCompleted != false &&
                    (!beforeSubmitEntered.get() || (outcomeForwarded.get() && lastOutcome.get()?.cleanupConfirmed == true))
                try { fixture.cleanup(safe) } catch (failure: Throwable) { failures += failure }
                val failure = primary ?: failures.firstOrNull()
                failures.filter { it !== failure }.forEach { failure?.addSuppressed(it) }
                if (primary == null && failure != null) throw failure
            }
        }
    }

    private class Fixture {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        private val koin = GlobalContext.get()
        val repository = requireNotNull(koin.getOrNull<SettingsEditorRepository>())
        val store = requireNotNull(koin.getOrNull<SettingsEditorStore>())
        val preferences = requireNotNull(koin.getOrNull<PreferenceRepository>())
        val privilege = requireNotNull(koin.getOrNull<PrivilegeManager>())
        val gateway = requireNotNull(koin.getOrNull<RootSystemGateway>())
        private val statuses = requireNotNull(koin.getOrNull<RootLaneStatusSource>())
        val id = UUID.randomUUID().toString()
        val key = "thor_sett_live_" + id.replace("-", "")
        val resource = SettingsRootResource(SettingsEditorView.SYSTEM, 0, key)
        val directory = File(context.noBackupFilesDir, "settings_live_writer/$id")
        val control = File(directory, "control")
        private var retirementFailed = false
        private var retirementChecked = false

        fun create(state: SavedState) {
            val parent = requireNotNull(directory.parentFile)
            if (!parent.exists()) {
                check(parent.mkdir()) { "live_cancel_parent_unavailable" }
                Os.chmod(parent.absolutePath, 0x1c0)
            }
            val parentStat = Os.lstat(parent.absolutePath)
            check(OsConstants.S_ISDIR(parentStat.st_mode) && parentStat.st_uid == Process.myUid() &&
                (parentStat.st_mode and 0x12) == 0) { "live_cancel_parent_not_private" }
            check(!directory.exists()) { "live_cancel_fixture_exists" }
            check(directory.mkdir()) { "live_cancel_fixture_unavailable" }
            try {
                Os.chmod(directory.absolutePath, 0x1c0) // 0700
                save(state) // All restoration originals are durable before any app/provider change.
                check(control.mkdir()) { "live_cancel_control_unavailable" }
                Os.chmod(control.absolutePath, 0x1c0)
                for (name in listOf("ready.json", "heartbeat.json", "completed.json", "child-ready.json",
                    "child-completed.json", "producer-starting.json")) {
                    val file = File(control, name)
                    check(file.createNewFile()) { "live_cancel_marker_exists" }
                    Os.chmod(file.absolutePath, 0x180) // 0600
                }
            } catch (failure: Throwable) {
                if (!directory.deleteRecursively()) failure.addSuppressed(IOException("live_cancel_unstarted_cleanup_failed"))
                throw failure
            }
        }

        fun load(): SavedState = AtomicFile(File(directory, "fixture.json")).openRead().bufferedReader().use {
            JSON.decodeFromString(it.readText())
        }

        fun save(state: SavedState) {
            val file = AtomicFile(File(directory, "fixture.json"))
            val output = file.startWrite()
            try {
                output.write(JSON.encodeToString(state).toByteArray(Charsets.UTF_8))
                output.fd.sync()
                file.finishWrite(output)
            } catch (failure: Exception) { file.failWrite(output); throw failure }
            check(load() == state) { "live_cancel_fixture_not_durable" }
        }

        fun rootRecords(): List<SettingsRootExecutionRecord> = FileSettingsRootExecutions(
            File(context.noBackupFilesDir, "settings_editor_root_executions.json"),
        ).load()

        fun laneHeld() = statuses.statuses.value.getValue(PrivilegeExecutionLane.INTERACTIVE).activeCommandClass == COMMAND_CLASS

        suspend fun prepareRoot() {
            preferences.setPrivilegeMode(PrivilegeMode.ROOT)
            withTimeout(10_000) { preferences.userPreferences.first { it.preferredPrivilegeMode == PrivilegeMode.ROOT } }
            withTimeout(30_000) {
                statuses.statuses.first { lanes -> lanes.values.none { it.activeCommandClass != null } }
                assertTrue(privilege.refreshAndAwait().rootAvailability.canAdmitRoot)
                while (true) {
                    privilege.state.first { it.active == PrivilegeMode.ROOT && it.rootAvailability.canAdmitRoot }
                    statuses.statuses.first { lanes -> lanes.values.none { it.activeCommandClass != null } }
                    val probe = repository.read(SettingsEditorView.SYSTEM)
                    val failure = probe.exceptionOrNull()
                    if (failure !is RootAdmissionUnavailable && failure !is ShellLaneBusy) {
                        probe.getOrThrow()
                        break
                    }
                    delay(25) // Only read-only pre-dispatch readiness refusals repeat.
                }
            }
            setConsent(true)
        }

        suspend fun selectMode(mode: PrivilegeMode) {
            preferences.setPrivilegeMode(mode)
            withTimeout(10_000) {
                preferences.userPreferences.first { it.preferredPrivilegeMode == mode }
                privilege.state.first { it.isReady && it.active == mode }
            }
        }

        suspend fun setConsent(value: Boolean?) {
            context.localState.edit { if (value == null) it.remove(CONSENT_KEY) else it[CONSENT_KEY] = value }
            withTimeout(10_000) { store.consent.first { it == (value == true) } }
        }

        suspend fun value() = SettingsEditorController.valueOf(repository.read(SettingsEditorView.SYSTEM).getOrThrow(), key)

        suspend fun assertWriteRefused() {
            val before = store.history.load()
            val refused = withTimeout(5_000) {
                repository.change(SettingsEditorView.SYSTEM, key,
                    SettingValue(true, SettingsEditorLiveWriterProbe.INITIAL_VALUE), SettingValue.ABSENT)
            }
            assertTrue("The Settings resource gate refuses before a lane read or mutation",
                refused.exceptionOrNull() is SettingsExecutionUncertain)
            assertTrue("A gate refusal preserves private history", before == store.history.load())
        }

        fun command(): String {
            val classpath = settingsEditorProbeClasspath()
            val request = JSONObject().put("key", key).put("userId", 0).put("variant", "ignore_term_child")
                .put("maxSeconds", WATCHDOG_SECONDS).put("controlDir", control.absolutePath)
            val payload = Base64.encodeToString(request.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val helper = "trap '' TERM; exec /system/bin/app_process /system/bin --nice-name=thor_sett_live_${id.replace("-", "")} " +
                "com.valhalla.thor.data.settingseditor.SettingsEditorLiveWriterProbe ${quote(payload)}"
            return "CLASSPATH=${quote(classpath)} " + settingsEditorProcessDeadline("/system/bin/sh -c ${quote(helper)}", WATCHDOG_SECONDS)
        }

        fun validateReady(marker: LiveMarker, bootId: String) {
            check(marker.schema == 1 && marker.runId == id && marker.key == key && marker.userId == 0 &&
                marker.role == "producer" && marker.phase == "LIVE" && marker.variant == "ignore_term_child" &&
                marker.termIgnored && marker.writes >= 1 && marker.sequence > 0 && marker.watchdogSeconds == WATCHDOG_SECONDS &&
                marker.ownerUid == Process.myUid()) { "live_cancel_invalid_producer_marker" }
            val child = requireNotNull(marker.child) { "live_cancel_child_missing" }
            check(child.schema == 1 && child.runId == id && child.key == key && child.userId == 0 &&
                child.role == "child" && child.phase == "LIVE" && child.variant == marker.variant && child.termIgnored &&
                child.watchdogSeconds == WATCHDOG_SECONDS && child.ownerUid == Process.myUid()) { "live_cancel_invalid_child_marker" }
            val identities = marker.identities()
            check(identities.size == 4 && identities.map { it.pid }.distinct().size == 4) { "live_cancel_identity_collision" }
            identities.forEach {
                check(it.pid > 1 && it.startTicks > 0 && it.uid == 0 && it.bootId == bootId && it.pgid == marker.pgid && it.pgid > 1) {
                    "live_cancel_process_identity_mismatch"
                }
            }
            check(marker.ppid == marker.watchdog.pid && child.ppid == child.watchdog.pid &&
                child.watchdog.ppid == marker.pid) { "live_cancel_process_tree_mismatch" }
        }

        suspend fun assertRetired(identities: List<ProcessIdentity>) {
            // PID reuse and zombies are not a surviving original process. This is diagnostic
            // evidence after the real acknowledgement, never a replacement for the receipt gate.
            try {
                for (identity in identities) {
                    val path = "/proc/${identity.pid}/stat"
                    val result = withTimeout(10_000) {
                        gateway.executeShellCommand("if [ -e ${quote(path)} ]; then cat ${quote(path)} || printf 'UNREADABLE\\n'; fi").getOrThrow()
                    }
                    check(result.first == 0) { "live_cancel_process_probe_failed" }
                    val text = result.second.orEmpty().trim()
                    if (text.isEmpty()) continue
                    check(text != "UNREADABLE" && text.substringBefore(' ').toIntOrNull() == identity.pid && text.contains(") ")) {
                        "live_cancel_process_probe_unreadable"
                    }
                    val fields = text.substringAfterLast(") ").trim().split(Regex("\\s+"))
                    check(fields.size >= 20) { "live_cancel_process_probe_invalid" }
                    val startTicks = requireNotNull(fields[19].toLongOrNull()) { "live_cancel_process_start_invalid" }
                    assertFalse("Acknowledged producer, child and watchdog identities must be retired",
                        startTicks == identity.startTicks && fields[0] != "Z" && fields[0] != "X")
                }
                retirementChecked = true
            } catch (failure: Throwable) {
                retirementFailed = true
                throw failure
            }
        }

        suspend fun cleanup(acknowledgedOrNeverStarted: Boolean) {
            val state = load()
            check(state.fixtureId == id && state.key == key) { "live_cancel_cleanup_identity_mismatch" }
            val failures = mutableListOf<Throwable>()
            try {
                check(acknowledgedOrNeverStarted && rootRecords().isEmpty()) { "live_cancel_cleanup_retains_unresolved_producer" }
                check(!retirementFailed) { "live_cancel_cleanup_retains_failed_identity_probe" }
                if (state.ownsKey) {
                    prepareRoot()
                    if (!retirementChecked) state.ready?.let { assertRetired(it.identities()) }
                    val current = value()
                    check(current == SettingValue.ABSENT || current.value in setOf(
                        SettingsEditorLiveWriterProbe.INITIAL_VALUE, SettingsEditorLiveWriterProbe.COMPLETED_VALUE, AFTER_CANCELLATION,
                    )) { "live_cancel_cleanup_value_conflict" }
                    if (current.present) {
                        assertEquals(SettingsEditOutcome.VERIFIED,
                            repository.change(SettingsEditorView.SYSTEM, key, current, SettingValue.ABSENT).getOrThrow().outcome)
                    }
                    assertTrue("Cleanup removed only the owned setting", value() == SettingValue.ABSENT)
                }
                check(rootRecords().isEmpty()) { "live_cancel_cleanup_receipts_changed" }
                val unrelated = store.history.load().filterNot { state.ownsKey && it.key == key && it.userId == 0 && it.view == SettingsEditorView.SYSTEM }
                check(unrelated == state.initialHistory.take(unrelated.size)) { "live_cancel_concurrent_history_changed" }
                store.history.save(state.initialHistory)
                check(store.history.load() == state.initialHistory) { "live_cancel_history_restore_failed" }
            } catch (failure: Throwable) { failures += failure }
            finally {
                try { setConsent(state.initialConsent) } catch (failure: Throwable) { failures += failure }
                try {
                    preferences.setPrivilegeMode(state.initialPreference)
                    withTimeout(10_000) { preferences.userPreferences.first { it.preferredPrivilegeMode == state.initialPreference } }
                    if (statuses.statuses.value.values.none { it.activeCommandClass != null }) {
                        withTimeout(30_000) { privilege.refreshAndAwait() }
                    }
                } catch (failure: Throwable) { failures += failure }
            }
            if (failures.isNotEmpty()) {
                failures.drop(1).filter { it !== failures.first() }.forEach(failures.first()::addSuppressed)
                throw failures.first()
            }
            // Delete only this UUID's workspace after the receipt, key and originals are safe.
            check(directory.deleteRecursively()) { "live_cancel_fixture_cleanup_failed" }
            metric("cleanup", "fixture=$id originals_restored=true")
        }
    }

    private data class Acknowledgement(val outcome: RootJobOutcome, val laneHeld: Boolean,
        val receiptsBefore: List<SettingsRootExecutionRecord>, val receiptsAfter: List<SettingsRootExecutionRecord>)

    @Serializable
    private data class SavedState(val fixtureId: String, val key: String, val bootId: String,
        val initialHistory: List<SettingsEditRecord>, val initialPreference: PrivilegeMode?, val initialConsent: Boolean?,
        val ownsKey: Boolean = false, val receipt: SettingsRootExecutionRecord? = null,
        val ready: LiveMarker? = null, val acknowledgedCleanup: Boolean = false)

    @Serializable
    private data class ProcessIdentity(val pid: Int, val ppid: Int, val pgid: Int, val uid: Int, val startTicks: Long, val bootId: String)

    @Serializable
    private data class LiveMarker(val schema: Int, val runId: String, val key: String, val userId: Int,
        val role: String, val variant: String, val phase: String, val pid: Int, val ppid: Int, val pgid: Int,
        val uid: Int, val startTicks: Long, val bootId: String, val sequence: Long, val writes: Long,
        val termIgnored: Boolean, val watchdogSeconds: Int, val ownerUid: Int,
        val watchdog: ProcessIdentity, val child: LiveMarker? = null) {
        fun identities(): List<ProcessIdentity> = listOf(ProcessIdentity(pid, ppid, pgid, uid, startTicks, bootId), watchdog) +
            child?.let { listOf(ProcessIdentity(it.pid, it.ppid, it.pgid, it.uid, it.startTicks, it.bootId), it.watchdog) }.orEmpty()
    }

    private companion object {
        const val WATCHDOG_SECONDS = 90
        const val AFTER_CANCELLATION = "live_writer_after_cancellation"
        val COMMAND_CLASS = PrivilegeCommandClass("test.settings-live-cancellation")
        val CONSENT_KEY = booleanPreferencesKey("settings_editor_consent_accepted")
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        fun metric(phase: String, detail: String) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nTHOR_SETTINGS_LIVE_CANCEL_METRIC phase=$phase $detail\n")
            })
        }
    }
}
