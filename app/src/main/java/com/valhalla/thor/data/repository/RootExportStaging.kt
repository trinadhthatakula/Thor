// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.util.AtomicFile
import com.valhalla.thor.data.util.readKernelBootId
import com.valhalla.thor.domain.model.IsolatedRootExecutionException
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.RootExecutionObserver
import com.valhalla.thor.domain.model.RootExecutionPolicy
import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Recovery metadata only: source paths, command text, output and raw failures are never stored. */
@Serializable
internal data class RootExportStagingRecord(
    val id: String,
    val packageName: String?,
    val workRequestId: String?,
    val sweepRequestId: String?,
    val bootId: String?,
    val version: Int = 1,
    val kind: String? = null,
    val started: Boolean? = null,
    val exitCode: Int? = null,
    val terminationConfirmed: Boolean? = null,
    val outputDrained: Boolean? = null,
    val shellReusable: Boolean? = null,
    val hasFailure: Boolean = false,
) {
    fun withOutcome(outcome: RootJobOutcome) = copy(
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

    fun isValidFor(directoryName: String): Boolean {
        if (version != 1 || canonicalExportStagingUuid(id) != directoryName ||
            (bootId != null && canonicalExportStagingUuid(bootId) == null) ||
            (workRequestId != null && canonicalExportStagingUuid(workRequestId) == null) ||
            (sweepRequestId != null && canonicalExportStagingUuid(sweepRequestId) == null)) return false
        return if (kind == null) {
            started == null && exitCode == null && terminationConfirmed == null &&
                outputDrained == null && shellReusable == null && !hasFailure
        } else {
            RootJobOutcomeKind.entries.any { it.name == kind } && started != null &&
                terminationConfirmed != null && outputDrained != null && shellReusable != null
        }
    }
}

/**
 * Root never writes into a bundle tree that callers or launch sweeps can delete. Each invocation
 * owns a new private directory, durably records its submission, then atomically promotes a completed
 * payload onto the ordinary cache volume. There is deliberately no copy fallback for that move.
 *
 * A missing acknowledgement leaves the payload and receipt here, even across process restart.
 * Reclamation requires acknowledged cleanup or a different known kernel boot. Unknown/corrupt
 * receipts are retained; neither file length nor a replacement shell proves that a writer stopped.
 * Calls perform filesystem work and belong on the export's IO dispatcher.
 */
internal class RootExportStaging(
    private val root: File,
    private val bootId: () -> String? = ::readKernelBootId,
) {
    suspend fun copy(
        source: String,
        destination: File,
        execution: PrivilegeExecutionContext,
        copy: suspend (String, String, PrivilegeExecutionContext) -> Result<Unit>,
    ) {
        currentCoroutineContext().ensureActive()
        val directory = allocate()
        val payload = File(directory, PAYLOAD_NAME)
        var submissionPermitted = false
        var recordedOutcome: RootJobOutcome? = null
        var primary: Throwable? = null
        try {
            // The app owns the existing leaf; root cp truncates it without changing its ownership.
            if (!payload.createNewFile()) throw IOException("Root export payload already exists")
            val pending = RootExportStagingRecord(
                id = directory.name,
                packageName = execution.packageName,
                workRequestId = execution.workRequestId?.toString(),
                sweepRequestId = execution.sweepRequestId?.toString(),
                bootId = currentBootId(),
            )
            val observer = object : RootExecutionObserver {
                override suspend fun beforeSubmit() {
                    check(!submissionPermitted) { "Root export copy already submitted" }
                    writeReceipt(directory, pending)
                    execution.rootExecutionObserver?.beforeSubmit()
                    // A hook that throws never gives the adapter permission to submit.
                    submissionPermitted = true
                }

                override suspend fun onOutcome(outcome: RootJobOutcome) {
                    writeReceipt(directory, pending.withOutcome(outcome))
                    recordedOutcome = outcome
                    execution.rootExecutionObserver?.onOutcome(outcome)
                }
            }
            val isolated = execution.copy(
                rootExecutionPolicy = RootExecutionPolicy.ISOLATED,
                rootExecutionObserver = observer,
            ).also { it.provenance = execution.provenance }
            copy(source, payload.absolutePath, isolated).getOrThrow()
            val outcome = recordedOutcome
                ?: throw IOException("Root export copy has no recorded completion")
            if (outcome.kind != RootJobOutcomeKind.EXITED || outcome.exitCode != 0 ||
                !outcome.started || !outcome.cleanupConfirmed) {
                throw IsolatedRootExecutionException(outcome)
            }
            currentCoroutineContext().ensureActive()
            Files.move(payload.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            synchronized(activeLock) {
                try {
                    if (!submissionPermitted || recordedOutcome?.cleanupConfirmed == true) {
                        if (!deleteOwnedTree(directory)) {
                            throw IOException("Root export staging cleanup failed")
                        }
                    }
                } catch (cleanup: Exception) {
                    // In particular, cancellation and its attached Odin outcome remain primary.
                    val failure = primary
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                } finally {
                    activeDirectories.remove(directory.absolutePath)
                }
            }
        }
    }

    /** Returns the number of safely reclaimed workspaces; active work is never swept. */
    fun sweep(): Int = synchronized(activeLock) {
        if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) return@synchronized 0
        val currentBoot = currentBootId()
        val directories = root.canonicalFile.listFiles() ?: return@synchronized 0
        directories.count { directory ->
            if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) ||
                canonicalExportStagingUuid(directory.name) == null || directory.absolutePath in activeDirectories) {
                return@count false
            }
            val record = try { readReceipt(directory) } catch (_: Exception) { return@count false }
            if (!record.isValidFor(directory.name)) return@count false
            val laterBoot = currentBoot != null && record.bootId != null && currentBoot != record.bootId
            if (!record.cleanupConfirmed && !laterBoot) return@count false
            try { deleteOwnedTree(directory) } catch (_: Exception) { false }
        }
    }

    private fun allocate(): File = synchronized(activeLock) {
        if ((!root.mkdirs() && !root.isDirectory) || Files.isSymbolicLink(root.toPath())) {
            throw IOException("Root export staging is unavailable")
        }
        val directory = File(root.canonicalFile, UUID.randomUUID().toString())
        if (!directory.mkdir()) throw IOException("Root export workspace could not be created")
        activeDirectories.add(directory.absolutePath)
        directory
    }

    private fun currentBootId(): String? = try { canonicalExportStagingUuid(bootId()) } catch (_: Exception) { null }

    internal fun writeReceipt(directory: File, record: RootExportStagingRecord) {
        val atomic = atomicReceipt(directory)
        val bytes = json.encodeToString(record).toByteArray(Charsets.UTF_8)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
        if (readReceipt(directory) != record) throw IOException("Root export receipt was not committed")
    }

    private fun readReceipt(directory: File): RootExportStagingRecord =
        atomicReceipt(directory).openRead().bufferedReader().use { json.decodeFromString(it.readText()) }

    private fun atomicReceipt(directory: File): AtomicFile {
        for (suffix in listOf("", ".bak", ".new")) {
            val path = File(directory, RECEIPT_NAME + suffix).toPath()
            if (Files.exists(path, NOFOLLOW_LINKS) && !Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                throw IOException("Root export receipt is not a regular file")
            }
        }
        return AtomicFile(File(directory, RECEIPT_NAME))
    }

    private fun deleteOwnedTree(file: File): Boolean {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return true
        if (Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)) {
            val children = file.listFiles() ?: return false
            if (!children.all(::deleteOwnedTree)) return false
        }
        return file.delete()
    }

    companion object {
        const val PAYLOAD_NAME = "payload"
        const val RECEIPT_NAME = "receipt.json"
        internal val json = Json { encodeDefaults = true }
        internal val activeLock = Any()
        internal val activeDirectories = mutableSetOf<String>()
    }
}

internal fun canonicalExportStagingUuid(value: String?): String? = try {
    value?.takeIf { UUID.fromString(it).toString() == it }
} catch (_: IllegalArgumentException) { null }
