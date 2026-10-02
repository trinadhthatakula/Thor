// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import android.content.Context
import android.util.AtomicFile
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.data.util.readKernelBootId
import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.rootservice.RootDataClearProtocol
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single

@Serializable
internal enum class RootDataClearPhase { PREPARED, BINDER }

/** Recovery metadata only; no app data, output, shell commands or failure text is persisted. */
@Serializable
internal data class RootDataClearRecord(
    val id: String,
    val packageName: String,
    val userId: Int,
    val bootId: String?,
    @Required val phase: RootDataClearPhase = RootDataClearPhase.PREPARED,
)

internal class RootDataClearAlreadyPending(val record: RootDataClearRecord) :
    IOException("A previous clear-data operation is still unresolved")

internal class RootDataClearJournalUnavailable(cause: Throwable? = null) :
    IOException("Clear-data recovery information could not be read or saved", cause)

@Serializable
private data class RootDataClearJournal(
    val version: Int,
    val records: List<RootDataClearRecord>,
)

/**
 * Persists an exact package/user barrier before Binder dispatch. Returning from a
 * coroutine, losing a connection, or replacing a daemon cannot retire it. A confirmed terminal
 * outcome (or proven pre-dispatch refusal) may call [finish]; a different known kernel boot proves
 * that the old producer stopped but never proves that its wipe succeeded.
 *
 * The journal is app-private and excluded from backup. A separate initialization marker makes a
 * missing initialized journal an error instead of an empty result. Concurrent instances in this
 * process share a path lock and re-read the AtomicFile on every operation, including after restart.
 */
@Single
internal class RootDataClearBarrier(context: Context) {
    private val directory by lazy { File(context.noBackupFilesDir, DIRECTORY) }
    private val stateFile by lazy { AtomicFile(File(directory, STATE_FILE)) }
    private val markerFile by lazy { AtomicFile(File(context.noBackupFilesDir, MARKER_FILE)) }
    private val mutex by lazy { pathLocks.getOrPut(directory.canonicalPath) { Mutex() } }
    private val globalAdmission by lazy { globalPathLocks.getOrPut(directory.canonicalPath) { Mutex() } }
    private val json = Json { encodeDefaults = true }

    internal var bootIdProvider: () -> String? = ::readKernelBootId

    suspend fun pending(packageName: String, userId: Int = thorUserId): RootDataClearRecord? = locked {
        validateResource(packageName, userId)
        currentRecords().singleOrNull { it.packageName == packageName && it.userId == userId }
    }

    suspend fun anyPending(): Boolean = locked { currentRecords().isNotEmpty() }

    /** Hold admission until a global or unknown-target mutation finishes, excluding every begin. */
    suspend fun <T> withGlobalLease(block: suspend () -> T): PackageLeaseResult<T> =
        withContext(Dispatchers.IO) { globalAdmission }.withLock {
            val blocked = try {
                anyPending()
            } catch (_: RootDataClearJournalUnavailable) {
                true
            }
            if (blocked) return@withLock PackageLeaseResult.Busy(PackageOperationOwner.CLEAR_DATA)
            PackageLeaseResult.Acquired(block())
        }

    /** The returned identity is durable before this method returns permission to dispatch. */
    suspend fun begin(packageName: String, userId: Int): RootDataClearRecord =
        withContext(Dispatchers.IO) { globalAdmission }.withLock {
        locked {
            validateResource(packageName, userId)
            val records = currentRecords()
            records.singleOrNull { it.packageName == packageName && it.userId == userId }
                ?.let { throw RootDataClearAlreadyPending(it) }
            if (records.size >= MAX_RECORDS) throw RootDataClearJournalUnavailable()
            val record = RootDataClearRecord(
                id = UUID.randomUUID().toString(),
                packageName = packageName,
                userId = userId,
                bootId = currentBootId(),
            )
            writeRecords(records + record)
            record
        }
    }

    /** Persist the phase before invoking Binder, keeping the same operation identity. */
    suspend fun markBinder(record: RootDataClearRecord): RootDataClearRecord = locked {
        validateRecord(record)
        val records = currentRecords()
        if (record.phase != RootDataClearPhase.PREPARED || records.none { it == record }) {
            throw RootDataClearJournalUnavailable()
        }
        val updated = record.copy(phase = RootDataClearPhase.BINDER)
        writeRecords(records.map { if (it == record) updated else it })
        updated
    }

    /** A stale identity or an earlier phase can never clear a replacement's barrier. */
    suspend fun finish(record: RootDataClearRecord): Boolean = locked {
        validateRecord(record)
        val records = currentRecords()
        if (records.none { it == record }) return@locked false
        writeRecords(records.filterNot { it == record })
        true
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (pending: RootDataClearAlreadyPending) {
                throw pending
            } catch (unavailable: RootDataClearJournalUnavailable) {
                throw unavailable
            } catch (failure: Exception) {
                throw RootDataClearJournalUnavailable(failure)
            }
        }
    }

    private fun currentRecords(): List<RootDataClearRecord> {
        val records = loadRecords()
        val bootId = currentBootId()
        val retained = records.filter { it.bootId == null || bootId == null || it.bootId == bootId }
        if (retained.size != records.size) writeRecords(retained)
        return retained
    }

    private fun currentBootId(): String? = try {
        canonicalUuid(bootIdProvider())
    } catch (_: Exception) {
        null
    }

    private fun loadRecords(): List<RootDataClearRecord> {
        val stateExists = hasAtomicFile(stateFile)
        val markerExists = hasAtomicFile(markerFile)
        if (!directory.exists()) {
            if (markerExists) throw RootDataClearJournalUnavailable()
            if (!directory.mkdirs()) throw RootDataClearJournalUnavailable()
        } else if (!directory.isDirectory) {
            throw RootDataClearJournalUnavailable()
        }
        if (!markerExists) {
            // No work can be admitted before initialization completes. Resume only an absent
            // or validated empty journal, before boot filtering could retire any old records.
            if (!stateExists) {
                writeRecords(emptyList())
            } else if (readJournalRecords().isNotEmpty()) {
                throw RootDataClearJournalUnavailable()
            }
            atomicWrite(markerFile, MARKER.toByteArray(Charsets.UTF_8))
        } else if (!stateExists) {
            throw RootDataClearJournalUnavailable()
        }
        val marker = readBounded(markerFile, MARKER.length)
        if (!marker.contentEquals(MARKER.toByteArray(Charsets.UTF_8))) {
            throw RootDataClearJournalUnavailable()
        }
        return readJournalRecords()
    }

    private fun readJournalRecords(): List<RootDataClearRecord> {
        val journal = json.decodeFromString<RootDataClearJournal>(
            readBounded(stateFile, MAX_JOURNAL_BYTES).toString(Charsets.UTF_8),
        )
        if (journal.version != 1 || journal.records.size > MAX_RECORDS) {
            throw RootDataClearJournalUnavailable()
        }
        journal.records.forEach(::validateRecord)
        if (journal.records.map { it.id }.toSet().size != journal.records.size ||
            journal.records.map { it.packageName to it.userId }.toSet().size != journal.records.size
        ) throw RootDataClearJournalUnavailable()
        return journal.records
    }

    private fun writeRecords(records: List<RootDataClearRecord>) {
        records.forEach(::validateRecord)
        val bytes = json.encodeToString(RootDataClearJournal(version = 1, records = records)).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_JOURNAL_BYTES) throw RootDataClearJournalUnavailable()
        atomicWrite(stateFile, bytes)
    }

    private fun readBounded(file: AtomicFile, limit: Int): ByteArray = file.openRead().use { stream ->
        val bytes = ByteArrayOutputStream(minOf(limit, 8_192))
        val buffer = ByteArray(8_192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read > limit - bytes.size()) throw RootDataClearJournalUnavailable()
            bytes.write(buffer, 0, read)
        }
        bytes.toByteArray()
    }

    private fun atomicWrite(file: AtomicFile, bytes: ByteArray) {
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            // AtomicFile logs a failed sync on some Android versions. An explicit sync must throw
            // before dispatch is permitted instead of treating that log as durable persistence.
            stream.fd.sync()
            file.finishWrite(stream)
            // Some AtomicFile versions also log rename failures. A successful method return must
            // therefore be followed by reading the committed generation before authorizing work.
            if (!readBounded(file, bytes.size).contentEquals(bytes)) {
                throw RootDataClearJournalUnavailable()
            }
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    private fun validateRecord(record: RootDataClearRecord) {
        validateResource(record.packageName, record.userId)
        if (canonicalUuid(record.id) == null ||
            (record.bootId != null && canonicalUuid(record.bootId) == null)
        ) throw RootDataClearJournalUnavailable()
    }

    private fun validateResource(packageName: String, userId: Int) {
        if (!RootDataClearProtocol.isValidPackageName(packageName) || userId < 0) {
            throw RootDataClearJournalUnavailable()
        }
    }

    private fun canonicalUuid(value: String?): String? = try {
        value?.takeIf { UUID.fromString(it).toString() == it }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun hasAtomicFile(file: AtomicFile): Boolean =
        file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()

    private companion object {
        const val DIRECTORY = "root_data_clear_recovery"
        const val STATE_FILE = "pending.json"
        const val MARKER_FILE = "root_data_clear_recovery_initialized"
        const val MARKER = "thor-root-data-clear-v1\n"
        const val MAX_RECORDS = 256
        const val MAX_JOURNAL_BYTES = 262_144
        val pathLocks = ConcurrentHashMap<String, Mutex>()
        val globalPathLocks = ConcurrentHashMap<String, Mutex>()
    }
}
