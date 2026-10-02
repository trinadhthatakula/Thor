// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor

import com.valhalla.thor.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

internal data class SettingsBridgeResult(val entries: List<SettingEntry>, val conflict: Boolean = false)
internal interface SettingsEditorSession {
    val provider: PrivilegeMode
    suspend fun ensureWriteAllowed(view: SettingsEditorView, userId: Int, key: String) = Unit
    suspend fun read(view: SettingsEditorView, userId: Int): List<SettingEntry>
    suspend fun properties(): List<SettingEntry> = error("unsupported")
    suspend fun write(view: SettingsEditorView, userId: Int, key: String, expected: SettingValue, desired: SettingValue): SettingsBridgeResult
}
internal interface SettingsEditHistory {
    fun load(): List<SettingsEditRecord>
    fun save(records: List<SettingsEditRecord>)
}
internal class SettingsEditorController(
    private val currentUserId: () -> Int,
    private val allowed: suspend () -> Boolean,
    private val openSession: suspend () -> SettingsEditorSession,
    private val history: SettingsEditHistory,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    suspend fun read(view: SettingsEditorView): List<SettingEntry> = mutex.withLock {
        require(view.writable)
        openSession().read(view, view.userId(currentUserId()))
    }
    suspend fun history(): List<SettingsEditRecord> = mutex.withLock { history.load() }
    suspend fun reconcile(id: String): SettingsEditRecord = mutex.withLock {
        val records = history.load()
        val previous = records.singleOrNull { it.id == id } ?: error("missing_history")
        check(previous.outcome.canReconcile) { "reconcile_unavailable" }
        require(previous.view.writable && editableSettingKey(previous.key)) { "invalid_key" }
        check(previous.userId == previous.view.userId(currentUserId())) { "wrong_user" }
        check(allowed()) { "consent_required" }
        val session = openSession()
        check(session.provider == PrivilegeMode.ROOT || session.provider == PrivilegeMode.SHIZUKU) { "unavailable" }
        // A read may use the currently selected provider; the original write's provider stays intact.
        // Do not enter the write gate: observing a value cannot retire an unresolved producer.
        val entries = session.read(previous.view, previous.userId)
        currentCoroutineContext().ensureActive()
        check(entries.count { it.key == previous.key } <= 1) { "provider_error" }
        val observed = previous.copy(observation = SettingsEditObservation(
            value = valueOf(entries, previous.key),
            provider = session.provider,
            timestamp = now(),
        ))
        check(allowed()) { "consent_required" }
        currentCoroutineContext().ensureActive()
        history.save(records.map { if (it.id == id) observed else it })
        observed
    }
    suspend fun change(view: SettingsEditorView, key: String, expected: SettingValue, desired: SettingValue): SettingsEditRecord =
        mutex.withLock { changeLocked(view, key, expected, desired) }
    suspend fun undo(id: String): SettingsEditRecord = mutex.withLock {
        val records = history.load()
        val previous = records.singleOrNull { it.id == id } ?: error("missing_history")
        check(previous.outcome == SettingsEditOutcome.VERIFIED && records.none { it.undoOf == id && it.outcome == SettingsEditOutcome.VERIFIED }) { "undo_unavailable" }
        check(previous.userId == previous.view.userId(currentUserId())) { "wrong_user" }
        changeLocked(previous.view, previous.key, previous.desired, previous.before, id)
    }
    private suspend fun changeLocked(view: SettingsEditorView, key: String, expected: SettingValue, desired: SettingValue, undoOf: String? = null): SettingsEditRecord {
        require(view.writable && editableSettingKey(key)) { "invalid_key" }
        require(desired.value == null || desired.value.length <= 65536) { "invalid_value" }
        check(allowed()) { "consent_required" }
        val session = openSession()
        val userId = view.userId(currentUserId())
        session.ensureWriteAllowed(view, userId, key)
        val entries = session.read(view, userId)
        check(valueOf(entries, key) == expected) { "conflict" }
        check(allowed()) { "consent_required" }
        // Persist the original state before dispatch; a failed journal write prevents mutation.
        val record = SettingsEditRecord(UUID.randomUUID().toString(), view, userId, key, expected, desired, session.provider, now(), undoOf = undoOf)
        val records = history.load().take(99)
        history.save(listOf(record) + records)
        // Once dispatch starts, finish readback + journal even if the screen is dismissed. Never replay.
        return withContext(NonCancellable) {
            var cancellation: CancellationException? = null
            val outcome = try {
                val result = session.write(view, userId, key, expected, desired)
                when {
                    result.conflict -> SettingsEditOutcome.CONFLICT
                    valueOf(result.entries, key) == desired -> SettingsEditOutcome.VERIFIED
                    else -> SettingsEditOutcome.REJECTED
                }
            } catch (cancelled: CancellationException) {
                cancellation = cancelled
                if (hasUnconfirmedRootOutcome(cancelled)) {
                    SettingsEditOutcome.UNCONFIRMED
                } else {
                    SettingsEditOutcome.UNKNOWN
                }
            } catch (_: SettingsExecutionUncertain) {
                SettingsEditOutcome.UNCONFIRMED
            } catch (_: Exception) { SettingsEditOutcome.UNKNOWN }
            val finished = record.copy(outcome = outcome)
            try {
                history.save(listOf(finished) + records)
            } catch (failure: Exception) {
                val cancelled = cancellation ?: throw failure
                if (failure !== cancelled) cancelled.addSuppressed(failure)
            }
            cancellation?.let { throw it }
            finished
        }
    }
    companion object {
        internal fun hasUnconfirmedRootOutcome(cancellation: CancellationException): Boolean {
            val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var remaining = 64
            fun visit(failure: Throwable): Boolean {
                // Stack-trace recovery may move acknowledgement metadata into a cause. Avoid
                // cycles and conservatively retain uncertainty if a graph exceeds this bound.
                if (remaining-- == 0) return true
                if (!visited.add(failure)) return false
                if (failure is IsolatedRootExecutionException && !failure.outcome.cleanupConfirmed) return true
                if (failure.cause?.let(::visit) == true) return true
                return failure.suppressed.any(::visit)
            }
            return visit(cancellation)
        }
        internal fun valueOf(entries: List<SettingEntry>, key: String): SettingValue =
            entries.singleOrNull { it.key == key }?.let { SettingValue(true, it.value) } ?: SettingValue.ABSENT
    }
}
