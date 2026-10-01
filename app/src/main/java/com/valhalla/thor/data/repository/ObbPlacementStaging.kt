// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.util.AtomicFile
import com.valhalla.thor.data.util.readKernelBootId
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.RootExecutionObserver
import com.valhalla.thor.domain.model.RootExecutionPolicy
import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Private recovery metadata. Paths, commands, output and raw failure details are never persisted. */
@Serializable
internal data class ObbPlacementRecord(
    val id: String,
    val packageName: String,
    val workRequestId: String?,
    val sweepRequestId: String?,
    val bootId: String?,
    val version: Int = 1,
    val submitted: Boolean = false,
    val kind: String? = null,
    val started: Boolean? = null,
    val exitCode: Int? = null,
    val terminationConfirmed: Boolean? = null,
    val outputDrained: Boolean? = null,
    val shellReusable: Boolean? = null,
    val hasFailure: Boolean = false,
) {
    fun withOutcome(outcome: RootJobOutcome) = copy(
        submitted = true,
        kind = outcome.kind.name,
        started = outcome.started,
        exitCode = outcome.exitCode,
        terminationConfirmed = outcome.terminationConfirmed,
        outputDrained = outcome.outputDrained,
        shellReusable = outcome.shellReusable,
        hasFailure = outcome.failure != null,
    )

    val cleanupConfirmed: Boolean
        get() = kind != null && kind != RootJobOutcomeKind.TERMINATION_UNCONFIRMED.name &&
            terminationConfirmed == true && outputDrained == true

    fun isValidFor(packageDirectory: String): Boolean {
        if (version != 1 || packageName != packageDirectory || !isUsablePackageName(packageName) ||
            canonicalObbPlacementUuid(id) == null ||
            (bootId != null && canonicalObbPlacementUuid(bootId) == null) ||
            (workRequestId != null && canonicalObbPlacementUuid(workRequestId) == null) ||
            (sweepRequestId != null && canonicalObbPlacementUuid(sweepRequestId) == null)) return false
        if (kind == null) {
            return started == null && exitCode == null && terminationConfirmed == null &&
                outputDrained == null && shellReusable == null && !hasFailure
        }
        return submitted && RootJobOutcomeKind.entries.any { it.name == kind } && started != null &&
            terminationConfirmed != null && outputDrained != null && shellReusable != null
    }
}

/**
 * Owns an OBB placement's extracted sources independently of cache and archive cleanup.
 *
 * A pending root receipt blocks later placements of the same package, including after process
 * restart. Only acknowledged termination and output drain, or a different known kernel boot,
 * allow reclamation. This component never removes or repairs the final Android/obb destination.
 * The source root belongs under externalFilesDir so the existing Shizuku path can still read it;
 * the receipt root belongs under noBackupFilesDir. Call on the placement's IO dispatcher.
 */
internal class ObbPlacementStaging(
    private val receiptRoot: File,
    private val stagingRoot: File,
    private val bootId: () -> String? = ::readKernelBootId,
) {
    /** Checks admission before an installer can replace a package that has an unresolved OBB copy. */
    fun checkAvailable(packageName: String) = synchronized(lock) {
        if (!isUsablePackageName(packageName)) throw IOException("Invalid game data package")
        val packageDirectory = File(rootLocation(receiptRoot), packageName)
        if (packageDirectory.absolutePath in activePackages) {
            throw IOException("Game data placement is already running")
        }
        if (!Files.exists(packageDirectory.toPath(), NOFOLLOW_LINKS)) return@synchronized
        val previous = validatedReceipt(packageDirectory)
        if (!canRecover(previous)) throw IOException("A previous game data copy has not confirmed completion")
        removeOwnedFiles(File(rootLocation(stagingRoot), previous.id), packageDirectory)
    }

    /** A read-only, fail-closed guard for automatic rollback; it does not establish completion. */
    fun hasUnresolvedPlacement(packageName: String): Boolean = synchronized(lock) {
        try {
            if (!isUsablePackageName(packageName)) return@synchronized true
            val packageDirectory = File(rootLocation(receiptRoot), packageName)
            if (packageDirectory.absolutePath in activePackages) return@synchronized true
            if (!Files.exists(packageDirectory.toPath(), NOFOLLOW_LINKS)) return@synchronized false
            !canRecover(validatedReceipt(packageDirectory))
        } catch (_: Exception) {
            true
        }
    }

    fun open(packageName: String, execution: PrivilegeExecutionContext): Session = synchronized(lock) {
        checkAvailable(packageName)
        val receipts = ensureRoot(receiptRoot)
        val sources = ensureRoot(stagingRoot)
        val packageDirectory = File(receipts, packageName)
        val key = packageDirectory.absolutePath
        val currentBoot = currentBootId()

        val record = ObbPlacementRecord(
            id = UUID.randomUUID().toString(),
            packageName = packageName,
            workRequestId = execution.workRequestId?.toString(),
            sweepRequestId = execution.sweepRequestId?.toString(),
            bootId = currentBoot,
        )
        val directory = File(sources, record.id)
        if (!packageDirectory.mkdir()) throw IOException("Game data recovery record could not be created")
        var sourceCreated = false
        try {
            if (!directory.mkdir()) throw IOException("Game data staging could not be created")
            sourceCreated = true
            // A restart before beforeSubmit is safe: no command could have received this source.
            writeReceipt(packageDirectory, record)
        } catch (failure: Exception) {
            try {
                if (sourceCreated) {
                    removeOwnedFiles(directory, packageDirectory)
                } else if (!deleteObbOwnedTree(packageDirectory)) {
                    throw IOException("Game data staging cleanup failed")
                }
            } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
        activePackages.add(key)
        Session(this, packageDirectory, directory, record)
    }

    internal fun writeReceipt(directory: File, record: ObbPlacementRecord) {
        val atomic = atomicReceipt(directory)
        val stream = atomic.startWrite()
        try {
            stream.write(json.encodeToString(record).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
        if (readReceipt(directory) != record) throw IOException("Game data recovery record was not committed")
    }

    private fun readReceipt(directory: File): ObbPlacementRecord = try {
        atomicReceipt(directory).openRead().bufferedReader().use { json.decodeFromString(it.readText()) }
    } catch (failure: Exception) {
        throw IOException("Game data recovery record could not be read", failure)
    }

    private fun atomicReceipt(directory: File): AtomicFile {
        for (suffix in listOf("", ".bak", ".new")) {
            val path = File(directory, RECEIPT_NAME + suffix).toPath()
            if (Files.exists(path, NOFOLLOW_LINKS) && !Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                throw IOException("Game data recovery record is not a regular file")
            }
        }
        return AtomicFile(File(directory, RECEIPT_NAME))
    }

    private fun ensureRoot(root: File): File {
        val location = rootLocation(root)
        if (!location.mkdirs() && !location.isDirectory) {
            throw IOException("Game data staging is unavailable")
        }
        return location
    }

    private fun rootLocation(root: File): File {
        if (Files.exists(root.toPath(), NOFOLLOW_LINKS) && !Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) {
            throw IOException("Game data staging is unavailable")
        }
        return root.canonicalFile
    }

    private fun validatedReceipt(directory: File): ObbPlacementRecord {
        if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) {
            throw IOException("Game data recovery record is unavailable")
        }
        return readReceipt(directory).also {
            if (!it.isValidFor(directory.name)) throw IOException("Game data recovery record is invalid")
        }
    }

    private fun canRecover(record: ObbPlacementRecord): Boolean {
        val currentBoot = currentBootId()
        val laterBoot = currentBoot != null && record.bootId != null && currentBoot != record.bootId
        return !record.submitted || record.cleanupConfirmed || laterBoot
    }

    private fun currentBootId(): String? =
        try { canonicalObbPlacementUuid(bootId()) } catch (_: Exception) { null }

    internal fun removeOwnedFiles(source: File, receipt: File) {
        // Retain the receipt if source cleanup fails so a later call can retry safely.
        if (!deleteObbOwnedTree(source) || !deleteObbOwnedTree(receipt)) {
            throw IOException("Game data staging cleanup failed")
        }
    }

    class Session internal constructor(
        private val owner: ObbPlacementStaging,
        private val packageDirectory: File,
        val directory: File,
        private val record: ObbPlacementRecord,
    ) : AutoCloseable {
        private var closed = false
        private var command: CommandState? = null

        /** False until a potentially submitted root copy has a durably recorded acknowledgement. */
        val canCleanup: Boolean
            get() = synchronized(lock) { canCleanupLocked() }

        fun observe(execution: PrivilegeExecutionContext): PrivilegeExecutionContext = synchronized(lock) {
            checkOpen()
            if (!canCleanupLocked()) throw IOException("The previous game data copy has not confirmed completion")
            val state = CommandState()
            command = state
            execution.copy(
                rootExecutionPolicy = RootExecutionPolicy.ISOLATED,
                rootExecutionObserver = CommandObserver(this, state, execution.rootExecutionObserver),
            ).also { it.provenance = execution.provenance }
        }

        internal suspend fun beforeSubmit(state: CommandState, observer: RootExecutionObserver?) {
            synchronized(lock) {
                checkCurrent(state)
                if (state.preparing) throw IOException("Game data copy already submitted")
                state.preparing = true
                owner.writeReceipt(packageDirectory, record.copy(submitted = true))
            }
            observer?.beforeSubmit()
            synchronized(lock) {
                checkCurrent(state)
                // If the observer rejects submission, the adapter never permits dispatch.
                state.submissionPermitted = true
            }
        }

        internal suspend fun onOutcome(
            state: CommandState,
            outcome: RootJobOutcome,
            observer: RootExecutionObserver?,
        ) {
            synchronized(lock) {
                checkCurrent(state)
                if (state.recordingAttempted) throw IOException("Game data copy completion already recorded")
                state.recordingAttempted = true
                owner.writeReceipt(packageDirectory, record.withOutcome(outcome))
                state.recordedOutcome = outcome
            }
            observer?.onOutcome(outcome)
        }

        override fun close() = synchronized(lock) {
            if (closed) return@synchronized
            closed = true
            try {
                if (canCleanupLocked()) owner.removeOwnedFiles(directory, packageDirectory)
            } finally {
                activePackages.remove(packageDirectory.absolutePath)
            }
        }

        private fun canCleanupLocked(): Boolean =
            command?.let { !it.submissionPermitted || it.recordedOutcome?.cleanupConfirmed == true } ?: true

        private fun checkOpen() {
            if (closed) throw IOException("Game data placement is closed")
        }

        private fun checkCurrent(state: CommandState) {
            checkOpen()
            if (command !== state) throw IOException("Game data copy observer is stale")
        }
    }

    internal class CommandState {
        var preparing = false
        var submissionPermitted = false
        var recordingAttempted = false
        var recordedOutcome: RootJobOutcome? = null
    }

    private class CommandObserver(
        private val session: Session,
        private val state: CommandState,
        private val observer: RootExecutionObserver?,
    ) : RootExecutionObserver {
        override suspend fun beforeSubmit() = session.beforeSubmit(state, observer)
        override suspend fun onOutcome(outcome: RootJobOutcome) = session.onOutcome(state, outcome, observer)
    }

    companion object {
        const val RECEIPT_NAME = "receipt.json"
        internal val lock = Any()
        internal val activePackages = mutableSetOf<String>()
        internal val json = Json { encodeDefaults = true }
    }
}

internal fun deleteObbOwnedTree(file: File): Boolean {
    if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return true
    if (Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)) {
        val children = file.listFiles() ?: return false
        if (!children.all(::deleteObbOwnedTree)) return false
    }
    return file.delete()
}

internal fun canonicalObbPlacementUuid(value: String?): String? = try {
    value?.let { UUID.fromString(it).toString().takeIf { canonical -> canonical == it } }
} catch (_: IllegalArgumentException) {
    null
}
