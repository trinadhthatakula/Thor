// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor

import com.valhalla.thor.domain.model.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class SettingsBridgeResult(val entries: List<SettingEntry>, val conflict: Boolean = false)
internal interface SettingsEditorSession {
    val provider: PrivilegeMode
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
        val entries = session.read(view, userId)
        check(valueOf(entries, key) == expected) { "conflict" }
        check(allowed()) { "consent_required" }
        // Persist the original state before dispatch; a failed journal write prevents mutation.
        val record = SettingsEditRecord(UUID.randomUUID().toString(), view, userId, key, expected, desired, session.provider, now(), undoOf = undoOf)
        val records = history.load().take(99)
        history.save(listOf(record) + records)
        // Once dispatch starts, finish readback + journal even if the screen is dismissed. Never replay.
        return withContext(NonCancellable) {
            val outcome = try {
                val result = session.write(view, userId, key, expected, desired)
                when {
                    result.conflict -> SettingsEditOutcome.CONFLICT
                    valueOf(result.entries, key) == desired -> SettingsEditOutcome.VERIFIED
                    else -> SettingsEditOutcome.REJECTED
                }
            } catch (_: Exception) { SettingsEditOutcome.UNKNOWN }
            val finished = record.copy(outcome = outcome)
            history.save(listOf(finished) + records)
            finished
        }
    }
    companion object {
        internal fun valueOf(entries: List<SettingEntry>, key: String): SettingValue =
            entries.singleOrNull { it.key == key }?.let { SettingValue(true, it.value) } ?: SettingValue.ABSENT
    }
}
