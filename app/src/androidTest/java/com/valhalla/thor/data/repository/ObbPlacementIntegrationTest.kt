// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.superuser.utils.escapeForShell
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.domain.model.ObbPlacement
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.RootExecutionObserver
import com.valhalla.thor.domain.model.RootExecutionPolicy
import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import com.valhalla.thor.domain.model.RootLaneMode
import com.valhalla.thor.domain.model.RootLaneStatusSource
import com.valhalla.thor.domain.repository.SystemRepository
import java.io.File
import java.io.FileDescriptor
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Uses only fresh UUID leaves owned by this test under the installed instrumentation package. */
@RunWith(AndroidJUnit4::class)
class ObbPlacementIntegrationTest {
    @Test
    fun bothPlacementPathsUseOwnedArchiveAndPreserveBytesAndUnrelatedFiles() = runBlocking<Unit> {
        val fixture = readyFixture()
        val files = File(fixture.context.cacheDir, "obb-placement-test-${UUID.randomUUID()}")
            .apply { assertTrue(mkdir()) }
        val firstBytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        val secondBytes = ByteArray(128 * 1024) { (it % 127).toByte() }
        val sentinel = destination(fixture, "sentinel-${UUID.randomUUID()}.obb")
        val targets = mutableListOf<String>()
        val publicationFiles = ConcurrentLinkedQueue<File>()
        val submissions = AtomicInteger()
        val outcomes = ConcurrentLinkedQueue<RootJobOutcome>()
        val commands = ConcurrentLinkedQueue<ObservedCommand>()
        val current = AtomicReference<ObservedCommand>()
        try {
            assertTargetsAbsent(fixture, listOf(sentinel))
            targets += sentinel
            rootCommand(fixture, requireNotNull(obbMkdirCommand(externalRoot(), fixture.packageName)))
            rootCommand(fixture, "printf preserved > ${sentinel.escapeForShell()}")
            for (streaming in listOf(false, true)) {
                val firstLeaf = "main-${UUID.randomUUID()}.obb"
                val secondLeaf = "patch-${UUID.randomUUID()}.obb"
                val firstTarget = destination(fixture, firstLeaf)
                val secondTarget = destination(fixture, secondLeaf)
                assertTargetsAbsent(fixture, listOf(firstTarget, secondTarget))
                targets += listOf(firstTarget, secondTarget)
                val archive = archive(files, fixture.packageName, firstLeaf to firstBytes, secondLeaf to secondBytes)
                val workId = UUID.randomUUID()
                val sweepId = UUID.randomUUID()
                val sourceFiles = mutableListOf<File>()
                val peaks = mutableListOf<Long>()
                val expected = PrivilegeExecutionContext(
                    lane = PrivilegeExecutionLane.SWEEP,
                    packageName = fixture.packageName,
                    workRequestId = workId,
                    sweepRequestId = sweepId,
                    commandTimeout = 17.seconds,
                    rootExecutionObserver = object : RootExecutionObserver {
                        override suspend fun beforeSubmit() {
                            submissions.incrementAndGet()
                            val active = current.get()
                            assertOwnedArchive(fixture, active.execution.commandClass)
                            assertTrue("Ownership is persisted before submission", receipt(fixture).isFile)
                        }

                        override suspend fun onOutcome(outcome: RootJobOutcome) {
                            outcomes += outcome
                            assertComplete(outcome)
                            current.get().source?.let {
                                assertTrue("The source remains until the observer acknowledges completion", it.isFile)
                            }
                            assertTrue(receipt(fixture).isFile)
                        }
                    },
                )
                val repository = object : SystemRepository by fixture.repository {
                    override suspend fun executeShellCommand(
                        command: String,
                        execution: PrivilegeExecutionContext,
                    ): Result<Pair<Int, String?>> {
                        assertRouted(execution, expected)
                        val source = if (execution.commandClass == COPY) copySource(command) else null
                        if (source != null) {
                            assertStagedSource(fixture, source)
                            val publication = publicationFile(command)
                            assertPublicationPath(publication, File(destination(fixture, source.name)))
                            publicationFiles += publication
                            if (streaming) assertTrue(sourceFiles.all { !it.exists() })
                            sourceFiles += source
                            peaks += requireNotNull(source.parentFile).listFiles().orEmpty()
                                .filter { it.isFile }.sumOf { it.length() }
                        }
                        val observed = ObservedCommand(execution, source)
                        current.set(observed)
                        commands += observed
                        return fixture.gateway.executeShellCommand(command, execution)
                    }
                }
                val installer = ObbInstaller(fixture.context, repository, Dispatchers.IO)
                val progress = mutableListOf<Triple<String, Int, Int>>()
                val started = SystemClock.elapsedRealtimeNanos()
                val result = withTimeout(30_000) {
                    if (streaming) installer.placeStreaming(archive, fixture.packageName,
                        onFile = { leaf, index, total -> progress += Triple(leaf, index, total) }, execution = expected)
                    else installer.place(archive, fixture.packageName, execution = expected)
                }
                val elapsed = SystemClock.elapsedRealtimeNanos() - started
                assertEquals(ObbPlacement.Placed(2), result)
                assertArrayEquals(firstBytes, readTarget(fixture, files, firstTarget))
                assertArrayEquals(secondBytes, readTarget(fixture, files, secondTarget))
                assertEquals("preserved", rootCommand(fixture, "cat ${sentinel.escapeForShell()}").second)
                assertEquals(2, sourceFiles.size)
                assertTrue(sourceFiles.all { !it.exists() && it.parentFile?.exists() == false })
                assertFalse(receipt(fixture).exists())
                assertTargetsAbsent(fixture, publicationFiles.map { requireNotNull(it.parentFile).absolutePath })
                if (streaming) {
                    assertEquals(listOf(firstBytes.size.toLong(), secondBytes.size.toLong()), peaks)
                    assertEquals(listOf(Triple(firstLeaf, 1, 2), Triple(secondLeaf, 2, 2)), progress)
                }
                report("regular", mapOf("streaming" to streaming, "placement_ns" to elapsed,
                    "source_bytes" to firstBytes.size + secondBytes.size, "peak_staged_bytes" to peaks.max()))
            }
            assertEquals(listOf(MKDIR, COPY, COPY, MKDIR, COPY, COPY), commands.map { it.execution.commandClass })
            assertEquals(6, submissions.get())
            assertEquals(6, outcomes.size)
        } finally {
            withContext(NonCancellable) {
                if (outcomes.size == submissions.get() && outcomes.all { it.cleanupConfirmed }) {
                    removePublicationTemps(fixture, publicationFiles)
                    removeTargets(fixture, targets)
                    assertTrue(files.deleteRecursively())
                } else report("retained", mapOf("fixture" to files.absolutePath, "receipt" to receipt(fixture).absolutePath))
            }
        }
    }

    @Test
    fun partialCopyCancellationWaitsForAcknowledgementAndKeepsInteractiveResponsive() = runBlocking<Unit> {
        val fixture = readyFixture()
        val files = File(fixture.context.cacheDir, "obb-placement-fifo-${UUID.randomUUID()}")
            .apply { assertTrue(mkdir()) }
        val leaf = "main-${UUID.randomUUID()}.obb"
        val unstartedLeaf = "patch-${UUID.randomUUID()}.obb"
        val target = destination(fixture, leaf)
        val unstartedTarget = destination(fixture, unstartedLeaf)
        val targets = mutableListOf<String>()
        val fifo = File(files, "source.pipe")
        val nextMarker = File(files, "next")
        val prefix = ByteArray(1024) { (it % 127).toByte() }
        val archive = archive(files, fixture.packageName, leaf to ByteArray(4096), unstartedLeaf to ByteArray(2048))
        val source = AtomicReference<File>()
        val publication = AtomicReference<File>()
        val current = AtomicReference<ObservedCommand>()
        val submissions = AtomicInteger()
        val outcomes = ConcurrentLinkedQueue<RootJobOutcome>()
        val copies = AtomicInteger()
        val outcomeEntered = CompletableDeferred<RootJobOutcome>()
        val finishRecording = CompletableDeferred<Unit>()
        val cancellation = CompletableDeferred<CancellationException>()
        val progress = mutableListOf<String>()
        var descriptor: FileDescriptor? = null
        var pending: Job? = null
        try {
            assertTargetsAbsent(fixture, listOf(target, unstartedTarget))
            targets += listOf(target, unstartedTarget)
            rootCommand(fixture, requireNotNull(obbMkdirCommand(externalRoot(), fixture.packageName)))
            rootCommand(fixture, "printf 'previous expansion' > ${target.escapeForShell()}")
            // FUSE shared storage does not support FIFOs. App creation here preserves SELinux
            // categories; only the generated command's source operand is replaced below.
            Os.mkfifo(fifo.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
            descriptor = Os.open(fifo.absolutePath, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
            assertEquals(prefix.size, Os.write(descriptor, prefix, 0, prefix.size))
            val shellPid = rootCommand(fixture, "printf '%s' \"\$\$\"", archiveContext(fixture)).second
            assertNotNull(shellPid?.toLongOrNull())
            val expected = PrivilegeExecutionContext(
                packageName = fixture.packageName,
                workRequestId = UUID.randomUUID(),
                commandTimeout = 30.seconds,
                rootExecutionObserver = object : RootExecutionObserver {
                    override suspend fun beforeSubmit() {
                        submissions.incrementAndGet()
                        assertOwnedArchive(fixture, current.get().execution.commandClass)
                        assertTrue(receipt(fixture).isFile)
                    }

                    override suspend fun onOutcome(outcome: RootJobOutcome) {
                        outcomes += outcome
                        if (current.get().execution.commandClass == COPY) {
                            outcomeEntered.complete(outcome)
                            withTimeout(20_000) { finishRecording.await() }
                        } else assertComplete(outcome)
                    }
                },
            )
            val repository = object : SystemRepository by fixture.repository {
                override suspend fun executeShellCommand(
                    command: String,
                    execution: PrivilegeExecutionContext,
                ): Result<Pair<Int, String?>> {
                    assertRouted(execution, expected)
                    if (execution.commandClass != COPY) {
                        current.set(ObservedCommand(execution, null))
                        return fixture.gateway.executeShellCommand(command, execution)
                    }
                    assertEquals("Cancelled placement is not replayed", 1, copies.incrementAndGet())
                    val original = copySource(command)
                    assertStagedSource(fixture, original)
                    assertEquals(leaf, original.name)
                    source.set(original)
                    current.set(ObservedCommand(execution, original))
                    val match = requireNotNull(COPY_OPERANDS.find(command))
                    val staged = publicationFile(command)
                    assertPublicationPath(staged, File(target))
                    publication.set(staged)
                    val sourceOperand = requireNotNull(match.groups[1])
                    val substituted = command.replaceRange(sourceOperand.range, fifo.absolutePath)
                    return fixture.gateway.executeShellCommand(substituted, execution)
                }
            }
            val installer = ObbInstaller(fixture.context, repository, Dispatchers.IO)
            val running = launch(Dispatchers.IO) {
                try {
                    installer.placeStreaming(archive, fixture.packageName,
                        onFile = { name, _, _ -> progress += name }, execution = expected)
                } catch (cancelled: CancellationException) {
                    cancellation.complete(cancelled)
                    throw cancelled
                }
            }
            pending = running
            withTimeout(15_000) {
                while (true) {
                    assertFalse("cp must write the prefix before completing", running.isCompleted)
                    val path = publication.get()?.absolutePath
                    val size = if (path == null) null else rootCommand(fixture,
                        "if [ -f ${path.escapeForShell()} ]; then stat -c %s ${path.escapeForShell()}; else printf 0; fi")
                        .second?.trim()?.toLongOrNull()
                    if (size == prefix.size.toLong()) break
                    delay(10)
                }
            }
            assertArrayEquals(prefix, readTarget(fixture, files, requireNotNull(publication.get()).absolutePath))
            assertEquals("previous expansion", readTarget(fixture, files, target).decodeToString())
            assertOwnedArchive(fixture, COPY)
            val interactiveStarted = SystemClock.elapsedRealtimeNanos()
            assertEquals("independent", rootCommand(fixture, "printf independent").second)
            val interactiveElapsed = SystemClock.elapsedRealtimeNanos() - interactiveStarted
            assertFalse(running.isCompleted)
            val requested = CancellationException("Cancel acknowledged partial OBB placement")
            val cancelledAt = SystemClock.elapsedRealtimeNanos()
            running.cancel(requested)
            val outcome = withTimeout(15_000) { outcomeEntered.await() }
            val acknowledgedAfter = SystemClock.elapsedRealtimeNanos() - cancelledAt
            report("fifo_outcome", mapOf("kind" to outcome.kind, "exit_code" to (outcome.exitCode ?: "null"),
                "cleanup_confirmed" to outcome.cleanupConfirmed, "shell_reusable" to outcome.shellReusable,
                "staged_path" to requireNotNull(publication.get()).absolutePath,
                "stderr" to outcome.stderr.joinToString(" | ") { it.replace("\n", "\\n").replace("\r", "\\r") }))
            assertEquals(RootJobOutcomeKind.CANCELLED, outcome.kind)
            assertTrue(outcome.started)
            assertTrue(outcome.cleanupConfirmed)
            assertTrue(outcome.shellReusable)
            assertTrue(outcome.stdout.isEmpty())
            assertFalse("The caller waits for outcome observation", running.isCompleted)
            assertOwnedArchive(fixture, COPY)
            val staged = requireNotNull(source.get())
            assertTrue("Cancellation cannot remove the producer's source before acknowledgement", staged.isFile)
            assertTrue(receipt(fixture).isFile)
            assertEquals("Cancellation must preserve the previously published expansion", "previous expansion",
                readTarget(fixture, files, target).decodeToString())
            assertTargetsAbsent(fixture, listOf(requireNotNull(publication.get().parentFile).absolutePath))
            val receiptBytes = receipt(fixture).length()
            finishRecording.complete(Unit)
            withTimeout(15_000) { running.join() }
            val received = withTimeout(5_000) { cancellation.await() }
            assertTrue(generateSequence<Throwable>(received) { it.cause }.any { it === requested })
            assertFalse(staged.exists())
            assertFalse(requireNotNull(staged.parentFile).exists())
            assertFalse(receipt(fixture).exists())
            assertEquals(listOf(leaf), progress)
            assertEquals(1, copies.get())
            assertEquals(2, submissions.get())
            assertEquals(2, outcomes.size)
            assertTargetsAbsent(fixture, listOf(unstartedTarget))
            assertEquals("previous expansion", readTarget(fixture, files, target).decodeToString())
            assertNull(fixture.statuses.statuses.value.getValue(PrivilegeExecutionLane.ARCHIVE).activeCommandClass)
            val next = rootCommand(fixture, "printf 'next\\n' >> ${nextMarker.absolutePath.escapeForShell()}; " +
                "printf 'next\\n'; printf '%s' \"\$\$\"", archiveContext(fixture))
            assertEquals(listOf("next", shellPid), next.second.orEmpty().lines())
            assertEquals(listOf("next"), nextMarker.readLines())
            report("fifo", mapOf("interactive_while_copy_ns" to interactiveElapsed,
                "cancel_to_ack_ns" to acknowledgedAfter, "staged_partial_bytes" to prefix.size,
                "previous_target_preserved" to true,
                "retained_source_bytes_before_ack" to 4096, "receipt_bytes" to receiptBytes,
                "cancellation_stderr_lines" to outcome.stderr.size))
        } finally {
            withContext(NonCancellable) {
                finishRecording.complete(Unit)
                pending?.cancel()
                try {
                    withTimeout(20_000) { pending?.join() }
                } finally {
                    descriptor?.let { Os.close(it) }
                    if (outcomes.size == submissions.get() && outcomes.all { it.cleanupConfirmed }) {
                        removePublicationTemps(fixture, listOfNotNull(publication.get()))
                        removeTargets(fixture, targets)
                        assertTrue(files.deleteRecursively())
                    } else report("retained", mapOf("fixture" to files.absolutePath, "receipt" to receipt(fixture).absolutePath))
                }
            }
        }
    }

    @Test
    fun publicationPreservesExistingFilesOnCopyAndSizeFailuresAndRefusesDirectories() = runBlocking<Unit> {
        val fixture = readyFixture()
        val files = File(fixture.context.cacheDir, "obb-placement-command-${UUID.randomUUID()}")
            .apply { assertTrue(mkdir()) }
        val source = File(files, "source.obb").apply { writeBytes(ByteArray(2048) { (it % 127).toByte() }) }
        val target = destination(fixture, "main-${UUID.randomUUID()}.obb")
        val directoryTarget = destination(fixture, "directory-${UUID.randomUUID()}.obb")
        val targets = mutableListOf<String>()
        val directories = mutableListOf<String>()
        val publications = ConcurrentLinkedQueue<File>()
        val submissions = AtomicInteger()
        val outcomes = ConcurrentLinkedQueue<RootJobOutcome>()
        try {
            assertTargetsAbsent(fixture, listOf(target, directoryTarget))
            targets += target
            rootCommand(fixture, requireNotNull(obbMkdirCommand(externalRoot(), fixture.packageName)))
            rootCommand(fixture, "printf original > ${target.escapeForShell()}")
            for ((input, expectedSize) in listOf(File(files, "missing") to source.length(), source to source.length() + 1)) {
                val command = requireNotNull(obbPlaceCommand(externalRoot(), fixture.packageName,
                    File(target).name, input.absolutePath, expectedSize))
                val result = runPublicationCommand(fixture, command, File(target), publications, submissions, outcomes)
                assertTrue("Copy failure or short-copy verification must fail", result.first != 0)
                assertEquals("original", readTarget(fixture, files, target).decodeToString())
            }
            val replace = requireNotNull(obbPlaceCommand(externalRoot(), fixture.packageName,
                File(target).name, source.absolutePath, source.length()))
            assertEquals(0, runPublicationCommand(fixture, replace, File(target), publications, submissions, outcomes).first)
            assertArrayEquals(source.readBytes(), readTarget(fixture, files, target))

            rootCommand(fixture, "mkdir ${directoryTarget.escapeForShell()}")
            directories += directoryTarget
            val protectedTarget = "$directoryTarget/keep"
            val nestedTarget = "$directoryTarget/${File(directoryTarget).name}"
            targets += listOf(protectedTarget, nestedTarget)
            rootCommand(fixture, "printf protected > ${protectedTarget.escapeForShell()}")
            val refuse = requireNotNull(obbPlaceCommand(externalRoot(), fixture.packageName,
                File(directoryTarget).name, source.absolutePath, source.length()))
            val refused = runPublicationCommand(fixture, refuse, File(directoryTarget), publications, submissions, outcomes)
            assertTrue("A real directory must not become a nested file destination", refused.first != 0)
            assertEquals("protected", readTarget(fixture, files, protectedTarget).decodeToString())
            assertTargetsAbsent(fixture, listOf(nestedTarget))
            assertEquals(4, submissions.get())
            assertEquals(4, outcomes.size)
            report("publication", mapOf("ordinary_failures_preserved_target" to 2,
                "existing_file_replaced" to true, "directory_refused" to true))
        } finally {
            withContext(NonCancellable) {
                if (outcomes.size == submissions.get() && outcomes.all { it.cleanupConfirmed }) {
                    removePublicationTemps(fixture, publications)
                    removeTargets(fixture, targets)
                    directories.forEach { rootCommand(fixture, "rmdir ${it.escapeForShell()}") }
                    assertTrue(files.deleteRecursively())
                } else report("retained", mapOf("fixture" to files.absolutePath))
            }
        }
    }

    @Test
    fun publicationReplacesLeafSymlinksWithoutFollowingThemAndRefusesSymlinkedPackageDirectory() = runBlocking<Unit> {
        val fixture = readyFixture()
        val files = File(fixture.context.cacheDir, "obb-placement-symlink-${UUID.randomUUID()}")
            .apply { assertTrue(mkdir()) }
        val external = File(files, "storage")
        val packageDirectory = File(requireNotNull(obbDestinationDir(external.absolutePath, fixture.packageName)))
            .apply { assertTrue(mkdirs()) }
        val source = File(files, "source.obb").apply { writeText("replacement") }
        val protectedFile = File(files, "protected-file").apply { writeText("file preserved") }
        val protectedDirectory = File(files, "protected-directory").apply { assertTrue(mkdir()) }
        val protectedChild = File(protectedDirectory, "keep").apply { writeText("directory preserved") }
        val targets = mutableListOf<String>()
        val links = mutableListOf<File>()
        val publications = ConcurrentLinkedQueue<File>()
        val submissions = AtomicInteger()
        val outcomes = ConcurrentLinkedQueue<RootJobOutcome>()
        try {
            // Private app storage supports symlinks even when the device's emulated storage does not.
            for (referent in listOf(protectedFile, protectedDirectory)) {
                val target = File(packageDirectory, "main-${UUID.randomUUID()}.obb")
                Os.symlink(referent.absolutePath, target.absolutePath)
                links += target
                targets += target.absolutePath
                val command = requireNotNull(obbPlaceCommand(external.absolutePath, fixture.packageName,
                    target.name, source.absolutePath, source.length()))
                assertEquals(0, runPublicationCommand(fixture, command, target, publications, submissions, outcomes).first)
                rootCommand(fixture, "[ ! -L ${target.absolutePath.escapeForShell()} ] && [ -f ${target.absolutePath.escapeForShell()} ]")
                assertArrayEquals(source.readBytes(), readTarget(fixture, files, target.absolutePath))
                assertEquals("file preserved", protectedFile.readText())
                assertEquals("directory preserved", protectedChild.readText())
            }
            val redirectedExternal = File(files, "redirected-storage")
            val linkedPackage = File(requireNotNull(obbDestinationDir(redirectedExternal.absolutePath, fixture.packageName)))
            assertTrue(requireNotNull(linkedPackage.parentFile).mkdirs())
            Os.symlink(protectedDirectory.absolutePath, linkedPackage.absolutePath)
            links += linkedPackage
            val refusedTarget = File(linkedPackage, "main-${UUID.randomUUID()}.obb")
            targets += refusedTarget.absolutePath
            val command = requireNotNull(obbPlaceCommand(redirectedExternal.absolutePath, fixture.packageName,
                refusedTarget.name, source.absolutePath, source.length()))
            assertTrue(runPublicationCommand(fixture, command, refusedTarget, publications, submissions, outcomes).first != 0)
            assertTargetsAbsent(fixture, listOf(refusedTarget.absolutePath))
            assertEquals("directory preserved", protectedChild.readText())
            assertEquals(3, submissions.get())
            assertEquals(3, outcomes.size)
            report("symlink", mapOf("leaf_links_replaced" to 2, "referents_preserved" to true,
                "package_directory_link_refused" to true))
        } finally {
            withContext(NonCancellable) {
                if (outcomes.size == submissions.get() && outcomes.all { it.cleanupConfirmed }) {
                    removePublicationTemps(fixture, publications)
                    removeTargets(fixture, targets)
                    links.filter { Files.isSymbolicLink(it.toPath()) }.forEach { assertTrue(it.delete()) }
                    assertTrue(files.deleteRecursively())
                } else report("retained", mapOf("fixture" to files.absolutePath))
            }
        }
    }

    private suspend fun readyFixture(): Fixture {
        assumeTrue("Explicit root-device opt-in required", InstrumentationRegistry.getArguments().getString("odinRoot") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageName = instrumentation.context.packageName
        assertTrue(packageName.startsWith("com.valhalla.thor.") && packageName.endsWith(".test"))
        assertFalse(packageName == context.packageName)
        @Suppress("DEPRECATION")
        assertEquals(packageName, context.packageManager.getPackageInfo(packageName, 0).packageName)
        val koin = GlobalContext.get()
        val gateway = requireNotNull(koin.getOrNull<RootSystemGateway>())
        val statuses = requireNotNull(koin.getOrNull<RootLaneStatusSource>())
        val manager = requireNotNull(koin.getOrNull<PrivilegeManager>())
        withTimeout(30_000) {
            statuses.statuses.first { lanes -> lanes.values.none { it.activeCommandClass != null } }
            assertTrue(manager.refreshAndAwait().rootAvailability.canAdmitRoot)
            statuses.statuses.first { lanes -> lanes.values.none { it.activeCommandClass != null } }
        }
        return Fixture(context, packageName, gateway, statuses, requireNotNull(koin.getOrNull<SystemRepository>()))
    }

    internal fun assertRouted(actual: PrivilegeExecutionContext, expected: PrivilegeExecutionContext) {
        assertEquals(PrivilegeExecutionLane.ARCHIVE, actual.lane)
        assertEquals(RootExecutionPolicy.ISOLATED, actual.rootExecutionPolicy)
        assertTrue(actual.commandClass == MKDIR || actual.commandClass == COPY)
        assertEquals(expected.packageName, actual.packageName)
        assertEquals(expected.workRequestId, actual.workRequestId)
        assertEquals(expected.sweepRequestId, actual.sweepRequestId)
        assertEquals(expected.commandTimeout, actual.commandTimeout)
        assertSame(expected.provenance, actual.provenance)
    }

    internal fun assertOwnedArchive(fixture: Fixture, commandClass: PrivilegeCommandClass) {
        val status = fixture.statuses.statuses.value.getValue(PrivilegeExecutionLane.ARCHIVE)
        assertEquals("Acceptance requires the owned ARCHIVE session", RootLaneMode.ISOLATED, status.mode)
        assertEquals(commandClass, status.activeCommandClass)
    }

    internal fun assertComplete(outcome: RootJobOutcome) {
        assertEquals(RootJobOutcomeKind.EXITED, outcome.kind)
        assertEquals(0, outcome.exitCode)
        assertTrue(outcome.started)
        assertTrue(outcome.cleanupConfirmed)
        assertTrue(outcome.shellReusable)
        assertTrue(outcome.stdout.isEmpty())
        assertTrue(outcome.stderr.isEmpty())
        assertNull(outcome.failure)
    }

    internal fun receipt(fixture: Fixture) = File(fixture.context.noBackupFilesDir,
        "obb_placement/${fixture.packageName}/receipt.json")

    internal fun copySource(command: String): File = File(requireNotNull(COPY_OPERANDS.find(command)).groupValues[1])

    internal fun publicationFile(command: String): File = File(requireNotNull(COPY_OPERANDS.find(command)).groupValues[2])

    internal fun assertPublicationPath(staged: File, target: File) {
        assertEquals(target.name, staged.name)
        val directory = requireNotNull(staged.parentFile)
        assertEquals(target.parentFile, directory.parentFile)
        assertTrue(directory.name.startsWith(".thor-obb-"))
        assertEquals(directory.name.removePrefix(".thor-obb-"),
            UUID.fromString(directory.name.removePrefix(".thor-obb-")).toString())
    }

    private suspend fun runPublicationCommand(
        fixture: Fixture,
        command: String,
        target: File,
        publications: ConcurrentLinkedQueue<File>,
        submissions: AtomicInteger,
        outcomes: ConcurrentLinkedQueue<RootJobOutcome>,
    ): Pair<Int, String?> {
        val staged = publicationFile(command)
        assertPublicationPath(staged, target)
        assertTargetsAbsent(fixture, listOf(requireNotNull(staged.parentFile).absolutePath))
        publications += staged
        val terminal = AtomicReference<RootJobOutcome>()
        val execution = archiveContext(fixture).copy(commandClass = COPY,
            rootExecutionPolicy = RootExecutionPolicy.ISOLATED,
            rootExecutionObserver = object : RootExecutionObserver {
                override suspend fun beforeSubmit() {
                    submissions.incrementAndGet()
                    assertOwnedArchive(fixture, COPY)
                }

                override suspend fun onOutcome(outcome: RootJobOutcome) {
                    outcomes += outcome
                    terminal.set(outcome)
                }
            })
        val result = withTimeout(20_000) { fixture.gateway.executeShellCommand(command, execution).getOrThrow() }
        val outcome = requireNotNull(terminal.get())
        assertEquals(RootJobOutcomeKind.EXITED, outcome.kind)
        assertEquals(result.first, outcome.exitCode)
        assertTrue(outcome.started)
        assertTrue(outcome.cleanupConfirmed)
        assertTrue(outcome.shellReusable)
        assertTargetsAbsent(fixture, listOf(requireNotNull(staged.parentFile).absolutePath))
        return result
    }

    internal fun assertStagedSource(fixture: Fixture, source: File) {
        val root = File(requireNotNull(fixture.context.getExternalFilesDir(null)), "obb_placement")
        assertTrue(source.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
        assertTrue(source.isFile)
        assertNotNull(UUID.fromString(requireNotNull(source.parentFile).name))
    }

    private fun archive(files: File, packageName: String, vararg entries: Pair<String, ByteArray>): File =
        File(files, "bundle-${UUID.randomUUID()}.xapk").apply {
            ZipOutputStream(outputStream()).use { output ->
                entries.forEach { (leaf, bytes) ->
                    output.putNextEntry(ZipEntry("Android/obb/$packageName/$leaf"))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
        }

    private fun externalRoot() = requireNotNull(Environment.getExternalStorageDirectory()).absolutePath

    internal fun destination(fixture: Fixture, leaf: String) =
        "${requireNotNull(obbDestinationDir(externalRoot(), fixture.packageName))}/$leaf"

    private fun archiveContext(fixture: Fixture) = PrivilegeExecutionContext(
        lane = PrivilegeExecutionLane.ARCHIVE,
        commandClass = PrivilegeCommandClass("test.obb-placement"),
        packageName = fixture.packageName,
    )

    private suspend fun rootCommand(
        fixture: Fixture,
        command: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Pair<Int, String?> = withTimeout(15_000) {
        fixture.gateway.executeShellCommand(command, execution).getOrThrow().also {
            assertEquals("Fixture command: $command\nOutput: ${it.second.orEmpty()}", 0, it.first)
        }
    }

    private suspend fun assertTargetsAbsent(fixture: Fixture, targets: List<String>) {
        targets.forEach { target ->
            rootCommand(fixture, "[ ! -e ${target.escapeForShell()} ] && [ ! -L ${target.escapeForShell()} ]")
        }
    }

    private suspend fun removeTargets(fixture: Fixture, targets: List<String>) {
        // Never remove the package directory: an unrelated OBB may already live beside these leaves.
        targets.forEach { rootCommand(fixture, "rm -f ${it.escapeForShell()}") }
        assertTargetsAbsent(fixture, targets)
    }

    private suspend fun removePublicationTemps(fixture: Fixture, publications: Collection<File>) {
        // Only known, acknowledged command operands are eligible; never recurse through Android/obb.
        publications.forEach { staged ->
            val directory = requireNotNull(staged.parentFile).absolutePath
            rootCommand(fixture, "rm -f ${staged.absolutePath.escapeForShell()} && " +
                "if [ -d ${directory.escapeForShell()} ]; then rmdir ${directory.escapeForShell()}; fi")
        }
    }

    private suspend fun readTarget(fixture: Fixture, files: File, target: String): ByteArray {
        val readback = File(files, "readback-${UUID.randomUUID()}").apply { writeBytes(byteArrayOf()) }
        rootCommand(fixture, "cat ${target.escapeForShell()} > ${readback.absolutePath.escapeForShell()}")
        return readback.readBytes().also { assertTrue(readback.delete()) }
    }

    private fun report(name: String, values: Map<String, Any>) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nTHOR_OBB_PLACEMENT_METRIC $name ${values.entries.joinToString(" ") { "${it.key}=${it.value}" }}\n")
        })
    }

    internal data class Fixture(
        val context: Context,
        val packageName: String,
        val gateway: RootSystemGateway,
        val statuses: RootLaneStatusSource,
        val repository: SystemRepository,
    )

    internal data class ObservedCommand(val execution: PrivilegeExecutionContext, val source: File?)

    internal companion object {
        val MKDIR = PrivilegeCommandClass("obb.mkdir")
        val COPY = PrivilegeCommandClass("obb.copy")
        val COPY_OPERANDS = Regex("cp -f '([^']+)' '([^']+)'")
    }
}
