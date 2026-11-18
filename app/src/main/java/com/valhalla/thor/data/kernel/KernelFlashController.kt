// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.kernel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.util.AtomicFile
import com.valhalla.superuser.Shell
import com.valhalla.thor.util.getDisplayName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.valhalla.thor.data.util.readKernelBootId
import com.valhalla.thor.domain.repository.RootAdmissionController
import com.valhalla.thor.domain.repository.RootRefreshController
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/** Application lifetime owner. Navigation, rotation and service recreation never submit a second job. */
@Single
class KernelFlashController(
    private val context: Context,
    private val rootAdmission: RootAdmissionController,
    private val rootRefresh: RootRefreshController,
    @Named("io") private val io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(KernelFlashState())
    val state = mutableState.asStateFlow()
    private val home = File(context.noBackupFilesDir, "kernel-flasher")
    private val journal = AtomicFile(File(home, "last-flash.json"))
    private val logFile = File(home, "last-flash.log")
    private var work: File? = null
    private var requestId: String? = null
    private val logLock = Any()
    private var logBytes = 0L

    private val initialized = scope.async {
        mutex.withLock {
            try {
                check(home.isDirectory || home.mkdirs()) { "Cannot create kernel staging directory." }
                // Older Android AtomicFile implementations temporarily keep only .bak during a
                // rewrite. readFully restores it; ignoring it could unlock a still-running flash.
                if (journal.baseFile.exists() || File(home, "last-flash.json.bak").exists()) {
                    val record = Json.decodeFromString<KernelFlashRecord>(journal.readFully().decodeToString())
                    val recovered = recoverKernelFlash(record, currentBootId())
                    mutableState.value = KernelFlashState(
                        phase = recovered, filename = record.filename, sha256 = record.sha256,
                        dispatched = recovered != KernelFlashPhase.IDLE,
                        slotSelection = record.slotSelection, targetSlot = record.targetSlot, passes = recoverKernelPasses(record.passes),
                        log = if (logFile.exists() && logFile.length() <= MAX_LOG_BYTES) {
                            logFile.readLines().takeLast(MAX_LOG_LINES)
                        } else emptyList(),
                    )
                } else mutableState.value = KernelFlashState(phase = KernelFlashPhase.IDLE)
            } catch (failure: Exception) {
                // Corrupt recovery metadata must not silently unlock a possibly active flasher.
                mutableState.value = KernelFlashState(
                    phase = KernelFlashPhase.UNKNOWN, dispatched = true, error = failure.message,
                )
            }
        }
    }

    private suspend fun currentBootId(): String? = readKernelBootId() ?: runCatching {
        rootAdmission.awaitInitialObservation()
        rootAdmission.withRootAdmission { KernelFlashRunner().use { it.bootId() } }
    }.getOrNull()

    /** Some Android builds expose boot_id only to root. Retry recovery when root is refreshed. */
    fun refreshRecovery() {
        scope.launch {
            initialized.await()
            mutex.withLock {
                if (!state.value.busy) {
                    runCatching {
                        rootAdmission.withRootAdmission { KernelFlashRunner().use { it.slotInfo() } }
                    }.onSuccess { info -> mutableState.update { it.copy(slotInfo = info) } }
                }
                if (!state.value.dispatched || state.value.busy) return@withLock
                runCatching {
                    val record = Json.decodeFromString<KernelFlashRecord>(journal.readFully().decodeToString())
                    if (recoverKernelFlash(record, currentBootId()) == KernelFlashPhase.IDLE) {
                        mutableState.update { it.copy(phase = KernelFlashPhase.IDLE, dispatched = false, error = null) }
                    }
                }
            }
        }
    }

    fun select(uri: Uri) {
        scope.launch { prepareSelection(uri) }
    }

    /** Import only an already classified private copy. This never confirms or starts a flash. */
    suspend fun selectShared(file: File, filename: String?, expectedSha256: String): Boolean =
        withContext(io) { prepareSelection(Uri.fromFile(file), filename, expectedSha256) }

    private suspend fun prepareSelection(
        uri: Uri, displayName: String? = null, expectedSha256: String? = null,
    ): Boolean {
        initialized.await()
        if (state.value.dispatched && !state.value.busy) mutex.withLock {
            runCatching {
                val record = Json.decodeFromString<KernelFlashRecord>(journal.readFully().decodeToString())
                if (recoverKernelFlash(record, currentBootId()) == KernelFlashPhase.IDLE) {
                    mutableState.update { it.copy(phase = KernelFlashPhase.IDLE, dispatched = false, error = null) }
                }
            }
        }
        // Do not queue an external import behind a running installer's long-held mutex.
        if (!state.value.canSelect) {
            return expectedSha256 != null && state.value.dispatched && state.value.sha256 == expectedSha256
        }
        return mutex.withLock {
            if (!state.value.canSelect) {
                return@withLock expectedSha256 != null && state.value.dispatched && state.value.sha256 == expectedSha256
            }
            mutableState.value = KernelFlashState(phase = KernelFlashPhase.PREPARING, slotInfo = state.value.slotInfo)
            try {
                val hasRoot = rootRefresh.refreshAndAwait().canAdmitRoot
                check(hasRoot) { "Root access is required." }
                work?.let(::deleteStaging)
                val directory = File(home, UUID.randomUUID().toString())
                check(directory.mkdir()) { "Cannot create package staging directory." }
                work = directory
                val archive = File(directory, "package.zip")
                val filename = (displayName ?: uri.getDisplayName(context))
                    ?.filter { !it.isISOControl() }?.take(200) ?: "AnyKernel3.zip"
                context.contentResolver.openInputStream(uri)?.use { input ->
                    archive.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > AnyKernel3Archive.MAX_ARCHIVE_BYTES) throw IOException("ZIP exceeds 512 MiB.")
                            output.write(buffer, 0, read)
                        }
                    }
                } ?: throw IOException("Cannot open the selected ZIP.")
                val inspection = AnyKernel3Archive.inspect(archive)
                check(expectedSha256 == null || inspection.sha256 == expectedSha256) { "The shared ZIP changed. Share it again." }
                val slots = if (hasRoot) {
                    withKernelRootAdmission(rootRefresh, rootAdmission) { KernelFlashRunner().use { it.slotInfo() } }
                } else KernelSlotInfo()
                check(archive.setReadOnly()) { "Cannot protect the staged ZIP." }
                currentCoroutineContext().ensureActive()
                mutableState.value = KernelFlashState(
                    phase = KernelFlashPhase.READY, filename = filename,
                    sha256 = inspection.sha256, bytes = inspection.compressedBytes,
                    slotInfo = slots, supportsSlotSelection = inspection.supportsSlotSelection,
                    slotSelection = if (inspection.supportsSlotSelection && slots.current != null) {
                        KernelSlotSelection.CURRENT
                    } else KernelSlotSelection.PACKAGE_DEFAULT,
                )
            } catch (failure: Exception) {
                work?.let(::deleteStaging)
                work = null
                mutableState.update { it.copy(phase = KernelFlashPhase.FAILED, error = failure.message) }
                if (failure is CancellationException) throw failure
            }
            true
        }
    }

    /** Called only after the per-package acknowledgement; no URI or script is accepted by the service. */
    fun selectSlot(slot: KernelSlotSelection) {
        scope.launch {
            initialized.await()
            mutex.withLock {
                if (state.value.phase != KernelFlashPhase.READY) return@withLock
                runCatching { slot.target(state.value.slotInfo, state.value.supportsSlotSelection) }
                    .onSuccess { mutableState.update { state -> state.copy(slotSelection = slot) } }
            }
        }
    }

    fun confirm(expectedSha256: String, slot: KernelSlotSelection, currentSlot: String?) {
        scope.launch {
            initialized.await()
            mutex.withLock {
                if (state.value.phase != KernelFlashPhase.READY || state.value.sha256 != expectedSha256) return@withLock
                if (state.value.slotSelection != slot || state.value.slotInfo.current != currentSlot) return@withLock
                val token = UUID.randomUUID().toString()
                requestId = token
                mutableState.update { it.copy(phase = KernelFlashPhase.STARTING, error = null, log = emptyList()) }
                try {
                    ContextCompat.startForegroundService(context, Intent(context, KernelFlashService::class.java)
                        .putExtra(KernelFlashService.REQUEST_ID, token))
                    scope.launch {
                        delay(15_000)
                        startFailed(token, "The foreground service did not start. Keep Thor open and try again.")
                    }
                } catch (failure: Exception) {
                    requestId = null
                    mutableState.update { it.copy(phase = KernelFlashPhase.READY, error = failure.message) }
                }
            }
        }
    }

    fun startFromService(token: String?, finished: () -> Unit) {
        scope.launch {
            initialized.await()
            mutex.withLock {
                if (token == null || token != requestId || state.value.phase != KernelFlashPhase.STARTING) {
                    // A duplicate service start must not stop the service that owns the active run.
                    if (state.value.phase != KernelFlashPhase.FLASHING) finished()
                    return@withLock
                }
                requestId = null
                withContext(NonCancellable) {
                    runFlash()
                    finished()
                }
            }
        }
    }

    fun startFailed(token: String?, reason: String) {
        scope.launch {
            mutex.withLock {
                if (token == requestId && state.value.phase == KernelFlashPhase.STARTING) {
                    requestId = null
                    mutableState.update { it.copy(phase = KernelFlashPhase.READY, error = reason) }
                }
            }
        }
    }

    // The root installer writes directly, without StorageManager.allocateBytes. Count only space
    // already free; allocatable cached bytes are not reserved for those later shell writes.
    @SuppressLint("UsableSpace")
    private suspend fun runFlash() {
        data class PreparedPass(val pass: KernelFlashPass, val directory: File, val runner: KernelFlashRunner)
        val prepared = mutableListOf<PreparedPass>()
        var submitted = false
        var passSubmitted = false
        var terminalConfirmed = true
        var outcomePersisted = true
        var receipt: KernelFlashRecord? = null
        val directory = work
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Thor:KernelFlash")
        try {
            wakeLock.acquire(60 * 60 * 1000L)
            withKernelRootAdmission(rootRefresh, rootAdmission) {
                checkNotNull(directory) { "Select the ZIP again." }
                val archive = File(directory, "package.zip")
                val confirmed = state.value
                val plan = confirmed.slotSelection.plan(confirmed.slotInfo, confirmed.supportsSlotSelection)
                synchronized(logLock) {
                    logFile.writeText("")
                    logBytes = 0
                }
                appendLog("Package: ${confirmed.filename}\nSHA-256: ${confirmed.sha256}")
                appendLog("Requested slots: ${plan.joinToString { it.target ?: "package default" }}")
                val verified = AnyKernel3Archive.inspect(archive)
                check(verified.sha256 == confirmed.sha256) { "The staged ZIP changed. Select it again." }
                check(directory.usableSpace >= verified.expandedBytes + 512L * 1024 * 1024 + archive.length() * plan.size) {
                    "Not enough free storage for independent package copies, extraction and 512 MiB of boot image working space."
                }
                // Prepare every copy and shell before writing either slot. AK3 packages may delete
                // their ZIP or working tree, so each run owns a full, independent package copy.
                var preparedBootId: String? = null
                plan.forEach { pass ->
                    val passDirectory = File(directory, UUID.randomUUID().toString())
                    check(passDirectory.mkdir()) { "Cannot create the installer working directory." }
                    val activeRunner = KernelFlashRunner()
                    prepared += PreparedPass(pass, passDirectory, activeRunner)
                    val copy = File(passDirectory, "package.zip")
                    archive.copyTo(copy)
                    check(AnyKernel3Archive.inspect(copy).sha256 == confirmed.sha256) { "Cannot verify the installer package copy." }
                    check(copy.setReadOnly()) { "Cannot protect the installer package copy." }
                    AnyKernel3Archive.extractInstaller(copy, File(passDirectory, "update-binary"))
                    activeRunner.prepare(passDirectory)
                    val slots = activeRunner.slotInfo()
                    check(pass.selection.target(slots, verified.supportsSlotSelection) == pass.target &&
                        (pass.selection == KernelSlotSelection.PACKAGE_DEFAULT || slots.current == confirmed.slotInfo.current)) {
                        "The device slot changed. Select the ZIP again."
                    }
                    val bootId = checkNotNull(activeRunner.bootId()) { "Cannot read the kernel boot identity safely." }
                    check(preparedBootId == null || preparedBootId == bootId) { "The device boot changed. Select the ZIP again." }
                    preparedBootId = bootId
                }
                executeKernelPasses(plan) { index, pass ->
                    passSubmitted = false
                    terminalConfirmed = true
                    outcomePersisted = true
                    val ready = prepared[index]
                    val activeRunner = ready.runner
                    val passDirectory = ready.directory
                    val passArchive = File(passDirectory, "package.zip")
                    val inspected = AnyKernel3Archive.inspect(passArchive)
                    check(inspected.sha256 == confirmed.sha256) { "The staged ZIP changed. No further pass will run." }
                    check(directory.usableSpace >= inspected.expandedBytes + 512L * 1024 * 1024) {
                        "Not enough free storage for extraction and boot image working space. Free at least 512 MiB beyond the expanded ZIP size."
                    }
                    val slots = activeRunner.slotInfo()
                    val target = pass.selection.target(slots, inspected.supportsSlotSelection)
                    if (pass.selection != KernelSlotSelection.PACKAGE_DEFAULT) {
                        check(slots.current == confirmed.slotInfo.current && target == pass.target) {
                            "The device slot changed. No further pass will run."
                        }
                    }
                    val bootId = checkNotNull(activeRunner.bootId()) { "Cannot read the kernel boot identity safely." }
                    check(preparedBootId == bootId) { "The device boot changed. No further pass will run." }
                    val previous = receipt ?: KernelFlashRecord(
                        KernelFlashPhase.FLASHING, bootId, confirmed.filename, confirmed.sha256,
                        slotSelection = confirmed.slotSelection,
                        targetSlot = confirmed.slotSelection.target(slots, verified.supportsSlotSelection),
                        plannedSlots = plan.map { it.target },
                    )
                    val intent = previous.copy(
                        phase = KernelFlashPhase.FLASHING, exitCode = null,
                        passes = previous.passes + KernelFlashPassRecord(target, KernelFlashPhase.FLASHING),
                    )
                    // Persist the intent for EACH pass before its potentially destructive dispatch.
                    persist(intent)
                    receipt = intent
                    submitted = true
                    passSubmitted = true
                    terminalConfirmed = false
                    outcomePersisted = false
                    mutableState.update { it.copy(
                        phase = KernelFlashPhase.FLASHING, dispatched = true,
                        targetSlot = receipt?.targetSlot, passes = checkNotNull(receipt).passes,
                    ) }
                    appendLog("Pass ${index + 1}/${plan.size}: ${target ?: "package default"} (${pass.selection.name})")
                    val code = activeRunner.flash(passDirectory, passArchive, pass.selection, ::appendLog)
                    terminalConfirmed = code != Shell.Result.JOB_NOT_EXECUTED
                    val passPhase = when {
                        !terminalConfirmed -> KernelFlashPhase.UNKNOWN
                        code == 0 -> KernelFlashPhase.SUCCESS
                        else -> KernelFlashPhase.FAILED
                    }
                    val overallPhase = if (code == 0 && index < plan.lastIndex) KernelFlashPhase.FLASHING else passPhase
                    receipt = checkNotNull(receipt).let { record -> record.copy(
                        phase = overallPhase, exitCode = code,
                        passes = record.passes.dropLast(1) + KernelFlashPassRecord(target, passPhase, code),
                    ) }
                    persist(checkNotNull(receipt))
                    outcomePersisted = true
                    appendLog("Pass ${index + 1}/${plan.size} installer exit code: $code")
                    if (code != 0 && index < plan.lastIndex) appendLog("Remaining slot passes were not started.")
                    mutableState.update { it.copy(phase = overallPhase, passes = checkNotNull(receipt).passes) }
                    if (terminalConfirmed) {
                        runCatching { activeRunner.clean(passDirectory) }
                            .onFailure { appendLog("Installer finished; working directory cleanup failed: ${it.message}") }
                        runCatching { activeRunner.close() }
                    }
                    code
                }
            }
        } catch (failure: Exception) {
            appendLog(failure.message ?: failure.javaClass.simpleName)
            val phase = if (submitted && (!terminalConfirmed || !outcomePersisted)) KernelFlashPhase.UNKNOWN else KernelFlashPhase.FAILED
            receipt = receipt?.let { it.copy(phase = phase, passes = recoverKernelPasses(it.passes)) }
            if (submitted) receipt?.let { runCatching { persist(it) } }
            mutableState.update { it.copy(
                phase = phase, dispatched = submitted, error = failure.message,
                passes = receipt?.passes.orEmpty(),
            ) }
        } finally {
            // Never clean files or close a shell while a submitted installer may still be running.
            if (!passSubmitted || terminalConfirmed) {
                prepared.forEach { pass ->
                    runCatching { pass.runner.clean(pass.directory) }
                    runCatching { pass.runner.close() }
                }
                directory?.let(::deleteStaging)
                work = null
            }
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun persist(record: KernelFlashRecord) {
        val output = journal.startWrite()
        val bytes = Json.encodeToString(record).encodeToByteArray()
        try {
            output.write(bytes)
            output.fd.sync()
            journal.finishWrite(output)
            check(journal.readFully().contentEquals(bytes)) { "Cannot persist the kernel flash receipt." }
        } catch (failure: Exception) {
            journal.failWrite(output)
            throw failure
        }
    }

    private fun deleteStaging(directory: File) {
        // File.deleteRecursively follows directory links. Installer-created links must not cause
        // app-side cleanup to traverse outside the generated work directory.
        runCatching {
            Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }

    private fun appendLog(line: String) = synchronized(logLock) {
        // Keep draining both pipes even when an installer floods output or storage becomes full.
        val display = line.take(2048).replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), "")
        mutableState.update { it.copy(log = (it.log + display).takeLast(MAX_LOG_LINES)) }
        val bytes = (display + "\n").encodeToByteArray()
        if (logBytes + bytes.size <= MAX_LOG_BYTES - 128) {
            runCatching { logFile.appendBytes(bytes) }
            logBytes += bytes.size
        } else if (logBytes < MAX_LOG_BYTES) {
            runCatching { logFile.appendText("\n[Saved log truncated at 1 MiB; live output continues.]\n") }
            logBytes = MAX_LOG_BYTES
        }
    }

    fun saveLog(uri: Uri) {
        scope.launch {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    synchronized(logLock) {
                        if (logFile.exists()) logFile.inputStream().use { it.copyTo(output) }
                    }
                } ?: throw IOException("Cannot write the log.")
            } catch (failure: Exception) {
                mutableState.update { it.copy(error = failure.message) }
            }
        }
    }

    fun reboot() {
        scope.launch {
            mutex.withLock {
                if (state.value.phase != KernelFlashPhase.SUCCESS) return@withLock
                try {
                    rootAdmission.withRootAdmission {
                        Shell.Builder.create().setFlags(0).setTimeout(10).build().use { shell ->
                            check(shell.isRoot) { "Root access is required to reboot." }
                            val code = shell.newJob().add("/system/bin/reboot").exec().code
                            check(code == 0 || code == Shell.Result.JOB_NOT_EXECUTED) {
                                "Reboot failed. Use the device power menu."
                            }
                        }
                    }
                } catch (failure: Exception) {
                    mutableState.update { it.copy(error = failure.message) }
                }
            }
        }
    }

    private companion object {
        const val MAX_LOG_BYTES = 1024 * 1024L
        const val MAX_LOG_LINES = 500
    }
}
