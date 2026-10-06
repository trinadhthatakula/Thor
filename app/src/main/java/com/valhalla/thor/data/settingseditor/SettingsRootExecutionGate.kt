// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor

import com.valhalla.thor.domain.model.RootExecutionObserver
import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.SettingsEditorView
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** A provider switch or matching readback cannot prove a previous root writer has stopped. */
internal class SettingsExecutionUncertain(cause: Throwable? = null) :
    Exception("settings_execution_unconfirmed", cause)

@Serializable
internal data class SettingsRootResource(val view: SettingsEditorView, val userId: Int, val key: String)

/** Only recovery metadata is persisted; no values, command payloads, output, or failure text. */
@Serializable
internal data class SettingsRootExecutionRecord(
    val id: String,
    val resource: SettingsRootResource,
    val bootId: String?,
    val kind: String? = null,
    val started: Boolean? = null,
    val exitCode: Int? = null,
    val terminationConfirmed: Boolean? = null,
    val outputDrained: Boolean? = null,
    val shellReusable: Boolean? = null,
    val hasFailure: Boolean = false,
)

internal interface SettingsRootExecutions {
    fun load(): List<SettingsRootExecutionRecord>
    fun save(records: List<SettingsRootExecutionRecord>)
}

/**
 * Protects this Thor installation's writes to an exact setting, including Shizuku writes.
 * The pending record is durable before isolated submission and survives process death. Only a
 * confirmed acknowledgement or an observed later Android boot can retire that record. Odin 1.1.0
 * exposes no process identity that would support finer automatic recovery after lost completion.
 */
internal class SettingsRootExecutionGate(
    private val persistence: SettingsRootExecutions,
    private val bootId: () -> String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    suspend fun ensureWriteAllowed(resource: SettingsRootResource) = withContext(io) {
        mutex.withLock { checkAvailable(currentRecords(), resource) }
    }

    fun observer(resource: SettingsRootResource): RootExecutionObserver {
        val id = UUID.randomUUID().toString()
        return object : RootExecutionObserver {
            override suspend fun beforeSubmit() = withContext(io) {
                mutex.withLock {
                    val records = currentRecords()
                    checkAvailable(records, resource)
                    persistence.save(records + SettingsRootExecutionRecord(id, resource, canonicalBootId(bootId())))
                }
            }

            override suspend fun onOutcome(outcome: RootJobOutcome) = withContext(io) {
                mutex.withLock {
                    val records = persistence.load()
                    // An unsuccessful beforeSubmit must never clear somebody else's barrier.
                    val pending = records.singleOrNull { it.id == id } ?: return@withLock
                    val replacement = if (outcome.cleanupConfirmed) null else pending.copy(
                        kind = outcome.kind.name,
                        started = outcome.started,
                        exitCode = outcome.exitCode,
                        terminationConfirmed = outcome.terminationConfirmed,
                        outputDrained = outcome.outputDrained,
                        shellReusable = outcome.shellReusable,
                        hasFailure = outcome.failure != null,
                    )
                    persistence.save(records.filterNot { it.id == id } + listOfNotNull(replacement))
                }
            }
        }
    }

    internal fun checkAvailable(records: List<SettingsRootExecutionRecord>, resource: SettingsRootResource) {
        if (records.any { it.resource == resource }) throw SettingsExecutionUncertain()
    }

    internal fun currentRecords(): List<SettingsRootExecutionRecord> {
        val records = persistence.load()
        val currentBoot = canonicalBootId(bootId())
        val retained = records.filter { record ->
            val recordedBoot = canonicalBootId(record.bootId)
            // Missing, invalid, or unchanged identities provide no proof that the writer stopped.
            currentBoot == null || recordedBoot == null || currentBoot == recordedBoot
        }
        if (retained.size != records.size) persistence.save(retained)
        return retained
    }

    internal fun canonicalBootId(value: String?): String? = try {
        value?.takeIf { UUID.fromString(it).toString() == it }
    } catch (_: IllegalArgumentException) { null }
}
