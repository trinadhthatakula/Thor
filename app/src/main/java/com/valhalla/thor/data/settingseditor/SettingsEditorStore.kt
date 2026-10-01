// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor

import android.content.Context
import android.util.AtomicFile
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.valhalla.thor.data.repository.localState
import com.valhalla.thor.domain.model.SettingsEditRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single
import java.io.File
import java.io.IOException
import java.util.UUID

@Single
class SettingsEditorStore(context: Context) {
    private val local = context.localState
    private val consentKey = booleanPreferencesKey("settings_editor_consent_accepted")
    val consent = local.data.map { it[consentKey] == true }.catch { failure ->
        if (failure is IOException) emit(false) else throw failure
    }
    suspend fun acceptConsent(): Result<Unit> = try {
        local.edit { it[consentKey] = true }
        Result.success(Unit)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.failure(IOException("consent_write_failed")) }

    // Excluded from both cloud backup and device transfer. Values never leave this device.
    internal val history: SettingsEditHistory = FileSettingsEditHistory(File(context.noBackupFilesDir, "settings_editor_history.json"))
    internal val rootExecutions = SettingsRootExecutionGate(
        FileSettingsRootExecutions(File(context.noBackupFilesDir, "settings_editor_root_executions.json")),
        bootId = ::readSettingsBootId,
    )
}

internal class FileSettingsRootExecutions(file: File) : SettingsRootExecutions {
    private val atomic = AtomicFile(file)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override fun load(): List<SettingsRootExecutionRecord> {
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return emptyList()
        return json.decodeFromString(atomic.openRead().bufferedReader().use { it.readText() })
    }
    override fun save(records: List<SettingsRootExecutionRecord>) {
        val encoded = json.encodeToString(records).toByteArray(Charsets.UTF_8)
        val stream = atomic.startWrite()
        try { stream.write(encoded); atomic.finishWrite(stream) }
        catch (failure: Exception) { atomic.failWrite(stream); throw failure }
    }
}
internal class FileSettingsEditHistory(file: File) : SettingsEditHistory {
    private val atomic = AtomicFile(file)
    private val json = Json { ignoreUnknownKeys = true }
    override fun load(): List<SettingsEditRecord> {
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return emptyList()
        return json.decodeFromString(atomic.openRead().bufferedReader().use { it.readText() })
    }
    override fun save(records: List<SettingsEditRecord>) {
        val encoded = json.encodeToString(records).toByteArray(Charsets.UTF_8)
        val stream = atomic.startWrite()
        try { stream.write(encoded); atomic.finishWrite(stream) }
        catch (failure: Exception) { atomic.failWrite(stream); throw failure }
    }
}

/** Kernel identity is read-only; Settings.Global.BOOT_COUNT can itself be edited by root. */
internal fun readSettingsBootId(): String? = try {
    File("/proc/sys/kernel/random/boot_id").readText().trim().let { value ->
        value.takeIf { UUID.fromString(it).toString() == it }
    }
} catch (_: Exception) { null }
