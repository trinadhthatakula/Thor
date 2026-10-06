// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class SettingsEditorView(val table: String?) {
    SYSTEM("system"), SECURE("secure"), GLOBAL("global"), ANDROID_PROPERTIES(null), JAVA_PROPERTIES(null), ENVIRONMENT(null);
    val writable: Boolean get() = table != null
    fun userId(currentUserId: Int): Int = if (this == GLOBAL) 0 else currentUserId
}

/** Absent, present SQL null, empty and literal "null" are deliberately distinct. */
@Serializable
data class SettingValue(val present: Boolean, val value: String? = null) {
    init { require(present || value == null) }
    companion object { val ABSENT = SettingValue(false) }
}
@Serializable
data class SettingEntry(val key: String, val value: String?)
@Serializable
enum class SettingsEditOutcome {
    PENDING, VERIFIED, REJECTED, CONFLICT, UNKNOWN, UNCONFIRMED;

    val canReconcile: Boolean
        get() = this == PENDING || this == UNKNOWN || this == UNCONFIRMED
}

/** A later read, not a new verdict about the original write or its termination. */
@Serializable
data class SettingsEditObservation(
    val value: SettingValue,
    val provider: PrivilegeMode,
    val timestamp: Long,
)

@Serializable
data class SettingsEditRecord(
    val id: String,
    val view: SettingsEditorView,
    val userId: Int,
    val key: String,
    val before: SettingValue,
    val desired: SettingValue,
    val provider: PrivilegeMode,
    val timestamp: Long,
    val outcome: SettingsEditOutcome = SettingsEditOutcome.PENDING,
    val undoOf: String? = null,
    val observation: SettingsEditObservation? = null,
)

fun settingsEditorMode(state: PrivilegeState, preferred: PrivilegeMode?): PrivilegeMode? =
    if (!state.isReady || preferred == PrivilegeMode.DHIZUKU || state.active == PrivilegeMode.DHIZUKU) null
    else state.active.takeIf { it == PrivilegeMode.ROOT || it == PrivilegeMode.SHIZUKU }

fun editableSettingKey(key: String): Boolean = key.length in 1..256 && Regex("[A-Za-z0-9_.:-]+").matches(key)
