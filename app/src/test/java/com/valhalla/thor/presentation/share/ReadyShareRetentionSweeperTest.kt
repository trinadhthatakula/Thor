// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.share

import com.valhalla.thor.data.source.local.room.DataTaskItemSnapshot
import com.valhalla.thor.data.source.local.room.DataTaskOutputSnapshot
import com.valhalla.thor.data.source.local.room.DataTaskSnapshot
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskInterruption
import com.valhalla.thor.domain.model.DataTaskItemState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskOutputState
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.domain.model.SharePrepareFormat
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ReadyShareRetentionSweeperTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun notYetDueFilesAndRowsSurvive() = runTest {
        val expected = snapshot(deadlines = listOf(NOW_MS + 1))
        val dependencies = FakeDependencies(temporary.newFolder(), StandardTestDispatcher(testScheduler), expected)
        val file = dependencies.writeOutput(expected)

        ReadyShareRetentionSweeper(dependencies).sweep()

        assertTrue(file.isFile)
        assertEquals(expected, dependencies.current(expected))
        assertTrue(dependencies.casCalls.isEmpty())
    }

    @Test
    fun deadlineEqualityDeletesOnlyExactOwnedLeafBeforeCas() = runTest {
        val expected = snapshot()
        val dependencies = FakeDependencies(temporary.newFolder(), StandardTestDispatcher(testScheduler), expected)
        val owned = dependencies.writeOutput(expected)
        val sibling = File(owned.parentFile, "untracked.apk").apply { writeText("leave me") }
        val staging = File(dependencies.cacheDirectory, "stage-${expected.taskId}/partial.apk").apply {
            parentFile!!.mkdirs()
            writeText("live staging")
        }
        val foreign = snapshot()
        val foreignFile = dependencies.writeOutput(foreign)
        dependencies.beforeCas = { snapshot, ids ->
            assertEquals(expected, snapshot)
            assertEquals(expected.outputs.map { it.outputId }, ids)
            assertFalse("deletion must precede durable expiry", owned.exists())
        }

        ReadyShareRetentionSweeper(dependencies).sweep()

        assertFalse(owned.exists())
        assertTrue("cleanup must not delete directories", owned.parentFile!!.isDirectory)
        assertTrue(sibling.isFile)
        assertTrue(staging.isFile)
        assertTrue(foreignFile.isFile)
        assertEquals(DataTaskState.EXPIRED, dependencies.current(expected).state)
        assertEquals(DataTaskOutputState.EXPIRED, dependencies.current(expected).outputs.single().state)
    }

    @Test
    fun missingSafelyOwnedLeafStillSettlesExpired() = runTest {
        val expected = snapshot()
        val dependencies = FakeDependencies(temporary.newFolder(), StandardTestDispatcher(testScheduler), expected)

        ReadyShareRetentionSweeper(dependencies).sweep()

        assertEquals(DataTaskState.EXPIRED, dependencies.current(expected).state)
        assertEquals(listOf(expected.outputs.map { it.outputId }), dependencies.casCalls.map { it.second })
    }

    @Test
    fun readyPartialParentExpiresAfterItsDueLeafIsDeleted() = runTest {
        val expected = snapshot(state = DataTaskState.READY_PARTIAL)
        val dependencies = FakeDependencies(temporary.newFolder(), StandardTestDispatcher(testScheduler), expected)
        val file = dependencies.writeOutput(expected)

        ReadyShareRetentionSweeper(dependencies).sweep()

        assertFalse(file.exists())
        assertEquals(DataTaskState.EXPIRED, dependencies.current(expected).state)
    }

    @Test
    fun mixedDeadlinesExpireParentButPreserveLaterLeafUntilItsOwnDeadline() = runTest {
        val expected = snapshot(deadlines = listOf(NOW_MS, NOW_MS + 10))
        val dependencies = FakeDependencies(temporary.newFolder(), StandardTestDispatcher(testScheduler), expected)
        val first = dependencies.writeOutput(expected, 0)
        val later = dependencies.writeOutput(expected, 1)
        val sweeper = ReadyShareRetentionSweeper(dependencies)

        sweeper.sweep()

        assertFalse(first.exists())
        assertTrue(later.isFile)
        assertEquals(DataTaskState.EXPIRED, dependencies.current(expected).state)
        assertEquals(listOf(DataTaskOutputState.EXPIRED, DataTaskOutputState.READY),
            dependencies.current(expected).outputs.map { it.state })
        assertEquals(listOf(expected.outputs.first().outputId), dependencies.casCalls.single().second)

        dependencies.now = NOW_MS + 9
        sweeper.sweep()
        assertTrue(later.isFile)
        assertEquals(1, dependencies.casCalls.size)

        dependencies.now = NOW_MS + 10
        ReadyShareRetentionSweeper(dependencies).sweep()
        assertFalse(later.exists())
        assertEquals(listOf(expected.outputs.last().outputId), dependencies.casCalls.last().second)
        assertTrue(dependencies.current(expected).outputs.all { it.state == DataTaskOutputState.EXPIRED })
    }

    @Test
    fun staleFullSnapshotNeverDeletesReplacementAtTheSamePath() = runTest {
        val expected = snapshot()
        val output = expected.outputs.single()
        val mutations = listOf(
            expected.copy(updatedAtEpochMs = expected.updatedAtEpochMs + 1),
            expected.copy(detail = (expected.detail as StoredDataTaskDetail.SharePrepare).copy(
                requestedFormat = SharePrepareFormat.AUTO,
            )),
            expected.copy(items = expected.items.map { it.copy(attemptCount = it.attemptCount + 1) }),
            expected.copy(items = expected.items.map { it.copy(deterministicStagingIdentity = "replacement") }),
            expected.copy(outputs = listOf(output.copy(outputId = UUID.randomUUID()))),
            expected.copy(outputs = listOf(output.copy(byteSize = output.byteSize + 1))),
            expected.copy(outputs = listOf(output.copy(mimeType = BundleFormat.APKS.mime))),
            expected.copy(outputs = listOf(output.copy(expiresAtEpochMs = NOW_MS + 1))),
            expected.copy(outputs = listOf(output.copy(displayName = "replacement.apk"))),
            expected.copy(outputs = listOf(output.copy(privateRelativePath = "other/leaf.apk"))),
            expected.copy(outputs = listOf(output.copy(state = DataTaskOutputState.SHARED))),
            expected.copy(outputs = emptyList()),
        )
        mutations.forEachIndexed { index, replacement ->
            val dependencies = FakeDependencies(temporary.newFolder(), StandardTestDispatcher(testScheduler), replacement)
            dependencies.candidatesOverride = listOf(expected)
            val file = dependencies.writeOutput(expected)

            ReadyShareRetentionSweeper(dependencies).sweep()

            assertTrue("snapshot mutation $index must prevent all deletion", file.isFile)
            assertEquals(replacement, dependencies.current(expected))
            assertTrue("snapshot mutation $index must not reach CAS", dependencies.casCalls.isEmpty())
        }
    }

    @Test
    fun freshSnapshotIsLoadedInsideSharedLockBeforeAnyDeletion() = runTest {
        val expected = snapshot()
        val dependencies = FakeDependencies(temporary.newFolder(), StandardTestDispatcher(testScheduler), expected)
        val file = dependencies.writeOutput(expected)
        val releaseHandoff = CompletableDeferred<Unit>()
        val handoff = launch {
            dependencies.accessLock.withLock { releaseHandoff.await() }
        }
        runCurrent()
        val sweep = launch { ReadyShareRetentionSweeper(dependencies).sweep() }
        runCurrent()

        assertTrue(dependencies.candidatesLoaded.isCompleted)
        assertEquals("fresh-load must wait for the handoff lock", 0, dependencies.freshLoads)
        assertTrue(file.exists())
        val replacement = expected.copy(outputs = expected.outputs.map { it.copy(outputId = UUID.randomUUID()) })
        dependencies.rows[expected.taskId] = replacement
        releaseHandoff.complete(Unit)
        handoff.join()
        sweep.join()

        assertEquals(1, dependencies.freshLoads)
        assertTrue("stale candidate must not delete the replacement file", file.isFile)
        assertTrue(dependencies.casCalls.isEmpty())
    }

    @Test
    fun traversalAbsoluteAndForeignOperationPathsAreNeverDeletedOrExpired() = runTest {
        val expected = snapshot()
        val output = expected.outputs.single()
        val unsafePaths = listOf(
            "share_ready/../outside.apk",
            "/outside.apk",
            "share_ready/item-${UUID.randomUUID()}-0/com.example.item0/ready-0.apk",
            "share_ready/item-${expected.taskId}-0/com.example.item0/../ready-0.apk",
            "stage-${expected.taskId}/ready-0.apk",
            null,
        )
        unsafePaths.forEach { path ->
            val malformed = expected.copy(outputs = listOf(output.copy(privateRelativePath = path)))
            val dependencies = FakeDependencies(temporary.newFolder(), StandardTestDispatcher(testScheduler), malformed)
            val owned = dependencies.writeOutput(expected)
            val outside = File(dependencies.cacheDirectory, "outside.apk").apply { writeText("private unrelated file") }

            ReadyShareRetentionSweeper(dependencies).sweep()

            assertTrue("invalid persisted path must not fall back to derived leaf: $path", owned.isFile)
            assertTrue(outside.isFile)
            assertTrue(dependencies.casCalls.isEmpty())
        }
    }

    @Test
    fun unsupportedOwnersAndForeignItemRowsCannotAuthorizeDeletion() = runTest {
        val expected = snapshot()
        val detail = expected.detail as StoredDataTaskDetail.SharePrepare
        val invalid = listOf(
            expected.copy(kind = DataTaskKind.APP_EXPORT),
            expected.copy(payloadSchemaVersion = 2),
            expected.copy(state = DataTaskState.RUNNING),
            expected.copy(detail = detail.copy(publicationPolicy = DataTaskPublicationPolicy.PUBLIC_DOCUMENT)),
            expected.copy(detail = detail.copy(deterministicStagingIdentity = "stage-${UUID.randomUUID()}")),
            expected.copy(items = emptyList()),
            expected.copy(items = expected.items + expected.items),
            expected.copy(items = expected.items.map { it.copy(state = DataTaskItemState.FAILED) }),
            expected.copy(items = expected.items.map { it.copy(packageName = "..") }),
            expected.copy(items = expected.items.map { it.copy(deterministicStagingIdentity = "item-${UUID.randomUUID()}-0") }),
            expected.copy(outputs = expected.outputs.map { it.copy(itemOrdinal = 9) }),
            expected.copy(outputs = expected.outputs.map { it.copy(displayName = "../ready-0.apk") }),
            expected.copy(outputs = expected.outputs + expected.outputs),
        )
        invalid.forEachIndexed { index, malformed ->
            val dependencies = FakeDependencies(temporary.newFolder(), StandardTestDispatcher(testScheduler), malformed)
            val file = dependencies.writeOutput(expected)

            ReadyShareRetentionSweeper(dependencies).sweep()

            assertTrue("invalid owner or item $index must not authorize deletion", file.isFile)
            assertTrue(dependencies.casCalls.isEmpty())
        }
    }

    @Test
    fun nonReadyAndUndatedOutputsAreNotCleanupCandidates() = runTest {
        val expected = snapshot()
        val invalid = DataTaskOutputState.entries.filter { it != DataTaskOutputState.READY }.map { state ->
            expected.copy(outputs = expected.outputs.map { it.copy(state = state) })
        } + expected.copy(outputs = expected.outputs.map { it.copy(expiresAtEpochMs = null) })
        invalid.forEach { task ->
            val dependencies = FakeDependencies(temporary.newFolder(), StandardTestDispatcher(testScheduler), task)
            val file = dependencies.writeOutput(expected)

            ReadyShareRetentionSweeper(dependencies).sweep()

            assertTrue(file.isFile)
            assertTrue(dependencies.casCalls.isEmpty())
        }
    }

    @Test
    fun symlinkAtAnyPrivatePathComponentIsRejectedWithoutDeletingTarget() = runTest {
        val expected = snapshot()
        val components = expected.outputs.single().privateRelativePath!!.split('/')
        components.indices.forEach { index ->
            val dependencies = FakeDependencies(temporary.newFolder(), StandardTestDispatcher(testScheduler), expected)
            val outside = temporary.newFolder()
            val target = File(outside, "target")
            val targetLeaf = if (index == components.lastIndex) target else {
                target.mkdirs()
                File(target, components.drop(index + 1).joinToString("/"))
            }
            targetLeaf.parentFile!!.mkdirs()
            targetLeaf.writeText("foreign target")
            val link = File(dependencies.cacheDirectory, components.take(index + 1).joinToString("/"))
            link.parentFile!!.mkdirs()
            Files.createSymbolicLink(link.toPath(), target.toPath())

            ReadyShareRetentionSweeper(dependencies).sweep()

            assertTrue("symlink target component $index must survive", targetLeaf.isFile)
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertTrue(dependencies.casCalls.isEmpty())
        }
    }

    @Test
    fun directoryAtExpectedLeafIsNotDeletedRecursivelyOrMarkedClean() = runTest {
        val expected = snapshot()
        val dependencies = FakeDependencies(temporary.newFolder(), StandardTestDispatcher(testScheduler), expected)
        val directory = dependencies.outputFile(expected).apply { mkdirs() }
        val child = File(directory, "unrelated").apply { writeText("keep") }

        ReadyShareRetentionSweeper(dependencies).sweep()

        assertTrue(directory.isDirectory)
        assertTrue(child.isFile)
        assertTrue(dependencies.casCalls.isEmpty())
    }

    @Test
    fun failedDeletionKeepsSnapshotRecoverableWhileOtherTasksStillExpire() = runTest {
        val failing = snapshot()
        val successful = snapshot()
        val dependencies = FakeDependencies(temporary.newFolder(), StandardTestDispatcher(testScheduler), failing, successful)
        val failedFile = dependencies.writeOutput(failing)
        val goodFile = dependencies.writeOutput(successful)
        dependencies.delete = { file -> if (file.canonicalFile == failedFile.canonicalFile) false else file.delete() }

        ReadyShareRetentionSweeper(dependencies).sweep()

        assertTrue(failedFile.isFile)
        assertEquals(failing, dependencies.current(failing))
        assertFalse(goodFile.exists())
        assertEquals(DataTaskState.EXPIRED, dependencies.current(successful).state)
        dependencies.delete = File::delete
        ReadyShareRetentionSweeper(dependencies).sweep()
        assertFalse(failedFile.exists())
        assertEquals(DataTaskState.EXPIRED, dependencies.current(failing).state)
    }

    @Test
    fun partialDeletionFailureRetriesMissingLeafAndRemainingLeafOnNextLaunch() = runTest {
        val expected = snapshot(deadlines = listOf(NOW_MS, NOW_MS))
        val dependencies = FakeDependencies(temporary.newFolder(), StandardTestDispatcher(testScheduler), expected)
        val first = dependencies.writeOutput(expected, 0)
        val second = dependencies.writeOutput(expected, 1)
        dependencies.delete = { file ->
            if (file == second) throw IOException("injected filesystem failure")
            file.delete()
        }

        ReadyShareRetentionSweeper(dependencies).sweep()

        assertFalse(first.exists())
        assertTrue(second.isFile)
        assertEquals(expected, dependencies.current(expected))
        assertTrue("partial cleanup must not CAS a subset", dependencies.casCalls.isEmpty())
        dependencies.delete = File::delete
        ReadyShareRetentionSweeper(dependencies).sweep()
        assertFalse(second.exists())
        assertEquals(DataTaskState.EXPIRED, dependencies.current(expected).state)
        assertEquals(expected.outputs.map { it.outputId }, dependencies.casCalls.single().second)
    }

    @Test
    fun cancellationAfterDeletionBeforeCasPropagatesAndNextLaunchSettlesMissingFile() = runTest {
        val expected = snapshot()
        val dependencies = FakeDependencies(temporary.newFolder(), StandardTestDispatcher(testScheduler), expected)
        val file = dependencies.writeOutput(expected)
        dependencies.beforeCas = { _, _ -> throw CancellationException("process stopped") }
        var cancelled = false

        try {
            ReadyShareRetentionSweeper(dependencies).sweep()
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue("cancellation must not be swallowed", cancelled)
        assertFalse(file.exists())
        assertEquals(expected, dependencies.current(expected))
        dependencies.beforeCas = { _, _ -> }
        ReadyShareRetentionSweeper(dependencies).sweep()
        assertEquals(DataTaskState.EXPIRED, dependencies.current(expected).state)
    }

    @Test
    fun casFailureAfterDeletionDoesNotFabricateExpiryAndNextLaunchRetries() = runTest {
        val expected = snapshot()
        val dependencies = FakeDependencies(temporary.newFolder(), StandardTestDispatcher(testScheduler), expected)
        val file = dependencies.writeOutput(expected)
        dependencies.beforeCas = { _, _ -> throw IOException("database unavailable") }

        ReadyShareRetentionSweeper(dependencies).sweep()

        assertFalse(file.exists())
        assertEquals(expected, dependencies.current(expected))
        dependencies.beforeCas = { _, _ -> }
        ReadyShareRetentionSweeper(dependencies).sweep()
        assertEquals(DataTaskState.EXPIRED, dependencies.current(expected).state)
    }

    @Test
    fun staleCasCannotExpireReplacementMetadataAfterDeletion() = runTest {
        val expected = snapshot()
        val replacement = expected.copy(outputs = expected.outputs.map { it.copy(outputId = UUID.randomUUID()) })
        val dependencies = FakeDependencies(temporary.newFolder(), StandardTestDispatcher(testScheduler), expected)
        dependencies.writeOutput(expected)
        dependencies.beforeCas = { _, _ -> dependencies.rows[expected.taskId] = replacement }

        ReadyShareRetentionSweeper(dependencies).sweep()

        assertEquals(replacement, dependencies.current(expected))
        assertEquals(expected, dependencies.casCalls.single().first)
    }

    @Test
    fun databaseAndFilesystemWorkUseInjectedIoDispatcher() = runTest {
        val expected = snapshot()
        val io = StandardTestDispatcher(testScheduler, name = "retention-io")
        val dependencies = FakeDependencies(temporary.newFolder(), io, expected)
        dependencies.writeOutput(expected)

        ReadyShareRetentionSweeper(dependencies).sweep()
        advanceUntilIdle()

        assertEquals(3, dependencies.dispatchers.size)
        assertTrue(dependencies.dispatchers.all { it === io })
        assertEquals(DataTaskState.EXPIRED, dependencies.current(expected).state)
    }

    private fun snapshot(
        deadlines: List<Long?> = listOf(NOW_MS),
        state: DataTaskState = DataTaskState.READY,
    ): DataTaskSnapshot {
        val taskId = UUID.randomUUID()
        return DataTaskSnapshot(
            taskId = taskId,
            queueSequence = 1,
            payloadSchemaVersion = 1,
            kind = DataTaskKind.SHARE_PREPARE,
            state = state,
            targetKey = "share:$taskId",
            detail = StoredDataTaskDetail.SharePrepare(
                requestedFormat = SharePrepareFormat.APK,
                publicationPolicy = DataTaskPublicationPolicy.PRIVATE_SHARE_WITH_24_HOUR_EXPIRY,
                deterministicStagingIdentity = "stage-$taskId",
            ),
            stage = null,
            completed = deadlines.size.toLong(),
            total = deadlines.size.toLong(),
            attemptCount = 1,
            interruption = DataTaskInterruption.NONE,
            resultCode = null,
            cancelRequestedAtEpochMs = null,
            createdAtEpochMs = 1,
            claimedAtEpochMs = 2,
            startedAtEpochMs = 2,
            updatedAtEpochMs = 3,
            terminalAtEpochMs = 3,
            retainUntilEpochMs = NOW_MS + 100,
            acknowledgedAtEpochMs = null,
            items = deadlines.indices.map { ordinal ->
                DataTaskItemSnapshot(
                    ordinal = ordinal,
                    packageName = "com.example.item$ordinal",
                    displayLabel = "Item $ordinal",
                    state = DataTaskItemState.SUCCEEDED,
                    attemptCount = 1,
                    resultCode = null,
                    deterministicStagingIdentity = "item-$taskId-$ordinal",
                    startedAtEpochMs = 2,
                    finishedAtEpochMs = 3,
                )
            },
            outputs = deadlines.mapIndexed { ordinal, deadline ->
                DataTaskOutputSnapshot(
                    outputId = UUID.randomUUID(),
                    itemOrdinal = ordinal,
                    privateRelativePath = "share_ready/item-$taskId-$ordinal/com.example.item$ordinal/ready-$ordinal.apk",
                    displayName = "ready-$ordinal.apk",
                    mimeType = BundleFormat.APK.mime,
                    byteSize = 5,
                    state = DataTaskOutputState.READY,
                    expiresAtEpochMs = deadline,
                )
            },
        )
    }

    private class FakeDependencies(
        override val cacheDirectory: File,
        override val ioDispatcher: CoroutineDispatcher,
        vararg snapshots: DataTaskSnapshot,
    ) : ReadyShareRetentionDependencies {
        override val accessLock = ReadyShareAccessLock()
        val rows = snapshots.associateByTo(linkedMapOf()) { it.taskId }
        var now = NOW_MS
        var candidatesOverride: List<DataTaskSnapshot>? = null
        var freshLoads = 0
        val candidatesLoaded = CompletableDeferred<Unit>()
        val casCalls = mutableListOf<Pair<DataTaskSnapshot, List<UUID>>>()
        val dispatchers = mutableListOf<ContinuationInterceptor?>()
        var delete: (File) -> Boolean = File::delete
        var beforeCas: suspend (DataTaskSnapshot, List<UUID>) -> Unit = { _, _ -> }

        override fun nowMs(): Long = now
        override fun deleteFile(file: File): Boolean = delete(file)

        override suspend fun expiredReadyShareTasks(nowMs: Long): List<DataTaskSnapshot> {
            dispatchers += currentCoroutineContext()[ContinuationInterceptor]
            candidatesLoaded.complete(Unit)
            return candidatesOverride ?: rows.values.toList()
        }

        override suspend fun readyShareRetentionSnapshot(taskId: UUID, nowMs: Long): DataTaskSnapshot? {
            dispatchers += currentCoroutineContext()[ContinuationInterceptor]
            freshLoads++
            return rows[taskId]
        }

        override suspend fun markReadyTaskExpiredAfterCleanup(
            expected: DataTaskSnapshot,
            outputIds: List<UUID>,
            nowMs: Long,
        ): Boolean {
            dispatchers += currentCoroutineContext()[ContinuationInterceptor]
            casCalls += expected to outputIds
            beforeCas(expected, outputIds)
            if (rows[expected.taskId] != expected) return false
            val due = expected.outputs.filter {
                it.state == DataTaskOutputState.READY && it.expiresAtEpochMs?.let { time -> time <= nowMs } == true
            }.map { it.outputId }
            if (outputIds != due || due.isEmpty()) return false
            rows[expected.taskId] = expected.copy(
                state = DataTaskState.EXPIRED,
                outputs = expected.outputs.map { output ->
                    if (output.outputId in outputIds) output.copy(state = DataTaskOutputState.EXPIRED) else output
                },
                updatedAtEpochMs = nowMs,
            )
            return true
        }

        fun current(snapshot: DataTaskSnapshot): DataTaskSnapshot = requireNotNull(rows[snapshot.taskId])

        fun outputFile(snapshot: DataTaskSnapshot, ordinal: Int = 0): File =
            File(cacheDirectory, requireNotNull(snapshot.outputs[ordinal].privateRelativePath))

        fun writeOutput(snapshot: DataTaskSnapshot, ordinal: Int = 0): File = outputFile(snapshot, ordinal).apply {
            parentFile!!.mkdirs()
            writeText("ready")
        }
    }

    private companion object {
        const val NOW_MS = 100_000L
    }
}
