// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.data.source.local.room.ClaimedDataTaskItem
import com.valhalla.thor.domain.model.StoredRestoreSource
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreSourceStagerTest {

    @Test
    fun `transient source is consumed only with its exact capability token`() = runTest {
        val holder = RestoreSourceGrantHolder()
        val first = holder.register(TASK_ID, "content://documents/first")
        val second = holder.register(TASK_ID, "content://documents/second")
        val stager = RestoreSourceStager(
            takeSource = holder::take,
            copyToPrivate = { _, rawUri, _ ->
                assertEquals("content://documents/second", rawUri)
                RestoreSourceCopyResult.Completed(PRIVATE_PATH)
            },
            commitPrivateSource = { _, _, _ -> true },
            privateSourceUri = { _, _ -> PRIVATE_URI },
            discardPrivateSource = { _, _ -> },
            discardUncommittedTaskSources = {},
            nowMs = { NOW_MS },
        )

        assertEquals(
            RestoreSourceResolution.Ready(PRIVATE_URI),
            stager.resolve(
                claim(capabilityToken = second),
                StoredRestoreSource.AwaitingTransientGrant,
                appliedCheckpoints(),
            ),
        )
        assertEquals("content://documents/first", holder.take(TASK_ID, first))
        assertEquals(null, holder.take(TASK_ID, second))
    }

    @Test
    fun `transient raw URI is copied before only the private path is committed`() = runTest {
        val events = mutableListOf<String>()
        val committed = mutableListOf<String>()
        val stager = RestoreSourceStager(
            takeSource = { _, token ->
                assertEquals(CAPABILITY_TOKEN, token)
                events += "take"
                RAW_URI
            },
            copyToPrivate = { taskId, rawUri, reportProgress ->
                assertEquals(TASK_ID, taskId)
                assertEquals(RAW_URI, rawUri)
                events += "copy"
                assertTrue(reportProgress(64 * 1024L))
                RestoreSourceCopyResult.Completed(PRIVATE_PATH)
            },
            commitPrivateSource = { claim, privatePath, nowMs ->
                assertEquals(TASK_ID, claim.taskId)
                assertEquals(CLAIM_TOKEN, claim.claimToken)
                assertEquals(NOW_MS, nowMs)
                committed += privatePath
                events += "commit"
                true
            },
            privateSourceUri = { taskId, path ->
                assertEquals(TASK_ID, taskId)
                assertEquals(PRIVATE_PATH, path)
                PRIVATE_URI
            },
            discardPrivateSource = { _, _ -> error("committed source must be retained") },
            discardUncommittedTaskSources = { error("committed source must be retained") },
            nowMs = { NOW_MS },
        )

        val result = stager.resolve(
            claim(),
            StoredRestoreSource.AwaitingTransientGrant,
            appliedCheckpoints()
        )

        assertEquals(RestoreSourceResolution.Ready(PRIVATE_URI), result)
        assertEquals(listOf("take", "copy", "commit"), events)
        assertEquals(listOf(PRIVATE_PATH), committed)
        assertTrue(committed.none { RAW_URI in it })
    }

    @Test
    fun `missing transient grant waits for source without copying`() = runTest {
        var copied = false
        val stager = RestoreSourceStager(
            takeSource = { _, _ -> null },
            copyToPrivate = { _, _, _ ->
                copied = true
                RestoreSourceCopyResult.Completed(PRIVATE_PATH)
            },
            commitPrivateSource = { _, _, _ -> error("must not commit") },
            privateSourceUri = { _, _ -> PRIVATE_URI },
            discardPrivateSource = { _, _ -> },
            discardUncommittedTaskSources = {},
            nowMs = { NOW_MS },
        )

        val result = stager.resolve(
            claim(),
            StoredRestoreSource.AwaitingTransientGrant,
            appliedCheckpoints()
        )

        assertEquals(RestoreSourceResolution.WaitingForSource, result)
        assertEquals(false, copied)
    }

    @Test
    fun `definitive commit rejection discards exact uncommitted task sources`() = runTest {
        val discarded = mutableListOf<String>()
        var reconciled = false
        val stager = RestoreSourceStager(
            takeSource = { _, _ -> RAW_URI },
            copyToPrivate = { _, _, _ -> RestoreSourceCopyResult.Completed(PRIVATE_PATH) },
            commitPrivateSource = { _, _, _ -> false },
            readStoredSource = {
                reconciled = true
                error("definitive rejection must not be reconciled")
            },
            privateSourceUri = { _, _ -> PRIVATE_URI },
            discardPrivateSource = { _, path -> discarded += path },
            discardUncommittedTaskSources = { discarded += PRIVATE_PATH },
            nowMs = { NOW_MS },
        )

        val result = stager.resolve(
            claim(),
            StoredRestoreSource.AwaitingTransientGrant,
            appliedCheckpoints()
        )

        assertEquals(RestoreSourceResolution.OwnershipLost, result)
        assertEquals(listOf(PRIVATE_PATH), discarded)
        assertEquals(false, reconciled)
    }

    @Test
    fun `commit side effect survives lost timeout result after authoritative reconciliation`() =
        runTest {
            var storedSource: StoredRestoreSource? = null
            var discarded = false
            val stager = RestoreSourceStager(
                takeSource = { _, _ -> RAW_URI },
                copyToPrivate = { _, _, _ -> RestoreSourceCopyResult.Completed(PRIVATE_PATH) },
                commitPrivateSource = { _, privatePath, _ ->
                    storedSource = StoredRestoreSource.PrivateCopy(privatePath)
                    awaitCancellation()
                },
                readStoredSource = { storedSource },
                privateSourceUri = { _, _ -> PRIVATE_URI },
                discardPrivateSource = { _, _ -> discarded = true },
                discardUncommittedTaskSources = { discarded = true },
                nowMs = { NOW_MS },
            )

            val result = async {
                stager.resolve(
                    claim(),
                    StoredRestoreSource.AwaitingTransientGrant,
                    appliedCheckpoints(),
                )
            }
            runCurrent()
            advanceTimeBy(2.seconds + 1.milliseconds)
            runCurrent()

            assertEquals(RestoreSourceResolution.Ready(PRIVATE_URI), result.await())
            assertEquals(false, discarded)
        }

    @Test
    fun `unavailable commit reconciliation preserves deterministic task source`() = runTest {
        var discarded = false
        val stager = RestoreSourceStager(
            takeSource = { _, _ -> RAW_URI },
            copyToPrivate = { _, _, _ -> RestoreSourceCopyResult.Completed(PRIVATE_PATH) },
            commitPrivateSource = { _, _, _ -> error("commit result unavailable") },
            readStoredSource = { error("authoritative read unavailable") },
            privateSourceUri = { _, _ -> error("ambiguous source must not be exposed") },
            discardPrivateSource = { _, _ -> discarded = true },
            discardUncommittedTaskSources = { discarded = true },
            nowMs = { NOW_MS },
        )

        assertEquals(
            RestoreSourceResolution.OwnershipLost,
            stager.resolve(
                claim(),
                StoredRestoreSource.AwaitingTransientGrant,
                appliedCheckpoints(),
            ),
        )
        assertEquals(false, discarded)
    }

    @Test
    fun `cancellation after final rename waits for claim fenced commit`() = runTest {
        val commitStarted = CompletableDeferred<Unit>()
        val permitCommit = CompletableDeferred<Unit>()
        var committed = false
        var discarded = false
        val stager = RestoreSourceStager(
            takeSource = { _, _ -> RAW_URI },
            copyToPrivate = { _, _, _ -> RestoreSourceCopyResult.Completed(PRIVATE_PATH) },
            commitPrivateSource = { _, _, _ ->
                commitStarted.complete(Unit)
                permitCommit.await()
                committed = true
                true
            },
            privateSourceUri = { _, _ -> error("cancelled caller must not reopen source") },
            discardPrivateSource = { _, _ -> discarded = true },
            discardUncommittedTaskSources = { discarded = true },
            nowMs = { NOW_MS },
        )

        val resolution = async {
            stager.resolve(
                claim(),
                StoredRestoreSource.AwaitingTransientGrant,
                appliedCheckpoints(),
            )
        }
        runCurrent()
        commitStarted.await()
        resolution.cancel()
        permitCommit.complete(Unit)
        runCurrent()
        resolution.join()

        assertTrue(committed)
        assertEquals(false, discarded)
    }

    @Test
    fun `cancellation after final rename before commit cleans deterministic task sources`() =
        runTest {
            val finalRenamed = CompletableDeferred<Unit>()
            val cleaned = mutableListOf<UUID>()
            val stager = RestoreSourceStager(
                takeSource = { _, _ -> RAW_URI },
                copyToPrivate = { _, _, _ ->
                    finalRenamed.complete(Unit)
                    awaitCancellation()
                },
                commitPrivateSource = { _, _, _ -> error("cancelled copy must not commit") },
                privateSourceUri = { _, _ -> error("cancelled copy must not be exposed") },
                discardPrivateSource = { _, _ -> },
                discardUncommittedTaskSources = { cleaned += it },
                nowMs = { NOW_MS },
            )

            val resolution = async {
                stager.resolve(
                    claim(),
                    StoredRestoreSource.AwaitingTransientGrant,
                    appliedCheckpoints(),
                )
            }
            finalRenamed.await()
            resolution.cancel()
            resolution.join()

            assertEquals(listOf(TASK_ID), cleaned)
        }

    @Test
    fun `missing grant cleans deterministic rename before commit orphan`() = runTest {
        val cleaned = mutableListOf<UUID>()
        val stager = RestoreSourceStager(
            takeSource = { _, _ -> null },
            copyToPrivate = { _, _, _ -> error("must not copy without a grant") },
            commitPrivateSource = { _, _, _ -> error("must not commit without a grant") },
            privateSourceUri = { _, _ -> null },
            discardPrivateSource = { _, _ -> },
            discardUncommittedTaskSources = { cleaned += it },
            nowMs = { NOW_MS },
        )

        assertEquals(
            RestoreSourceResolution.WaitingForSource,
            stager.resolve(
                claim(),
                StoredRestoreSource.AwaitingTransientGrant,
                appliedCheckpoints(),
            ),
        )
        assertEquals(listOf(TASK_ID), cleaned)
    }

    @Test
    fun `staging aborts and cleans exact partial when byte checkpoint loses ownership`() = runTest {
        val checkpointedBytes = mutableListOf<Long>()
        val cleaned = mutableListOf<String>()
        val stager = RestoreSourceStager(
            takeSource = { _, _ -> RAW_URI },
            copyToPrivate = { _, _, reportProgress ->
                assertTrue(reportProgress(64 * 1024L))
                if (!reportProgress(128 * 1024L)) {
                    cleaned += "$PRIVATE_PATH.part"
                    RestoreSourceCopyResult.OwnershipLost
                } else {
                    RestoreSourceCopyResult.Completed(PRIVATE_PATH)
                }
            },
            commitPrivateSource = { _, _, _ -> error("must not commit after ownership loss") },
            privateSourceUri = { _, _ -> error("must not expose an uncommitted source") },
            discardPrivateSource = { _, _ -> error("copy dependency already owns partial cleanup") },
            discardUncommittedTaskSources = {},
            nowMs = { NOW_MS },
        )
        val checkpoints = DataTaskCheckpointSink { checkpoint ->
            checkpointedBytes += checkpoint.completed
            if (checkpoint.completed == 64 * 1024L) {
                DataTaskSinkWrite.APPLIED
            } else {
                DataTaskSinkWrite.OWNERSHIP_LOST
            }
        }

        val result = stager.resolve(
            claim(),
            StoredRestoreSource.AwaitingTransientGrant,
            checkpoints,
        )

        assertEquals(RestoreSourceResolution.OwnershipLost, result)
        assertEquals(listOf(64 * 1024L, 128 * 1024L), checkpointedBytes)
        assertEquals(listOf("$PRIVATE_PATH.part"), cleaned)
    }

    @Test
    fun `staging deadline after final rename cleans deterministic final and partial paths`() =
        runTest {
            val copyStarted = CompletableDeferred<Unit>()
            val cleaned = mutableListOf<String>()
            val stager = RestoreSourceStager(
                takeSource = { _, _ -> RAW_URI },
                copyToPrivate = { _, _, _ ->
                    copyStarted.complete(Unit)
                    awaitCancellation()
                },
                commitPrivateSource = { _, _, _ -> error("timed out copy must not commit") },
                privateSourceUri = { _, _ -> error("timed out copy must not be exposed") },
                discardPrivateSource = { _, _ -> error("uncommitted cleanup owns exact paths") },
                discardUncommittedTaskSources = { cleaned += "task:$it" },
                nowMs = { NOW_MS },
            )

            val result = async {
                stager.resolve(
                    claim(),
                    StoredRestoreSource.AwaitingTransientGrant,
                    appliedCheckpoints(),
                )
            }
            runCurrent()
            copyStarted.await()
            advanceTimeBy(9.minutes + 1.milliseconds)
            runCurrent()

            assertEquals(RestoreSourceResolution.WaitingForSource, result.await())
            assertEquals(listOf("task:$TASK_ID"), cleaned)
        }

    @Test
    fun `terminal cleanup discards only operation-owned private sources`() {
        val discarded = mutableListOf<String>()
        val stager = RestoreSourceStager(
            takeSource = { _, _ -> null },
            copyToPrivate = { _, _, _ -> RestoreSourceCopyResult.SourceUnavailable },
            commitPrivateSource = { _, _, _ -> false },
            privateSourceUri = { _, _ -> null },
            persistedSourceUri = { null },
            discardPrivateSource = { _, path -> discarded += path },
            discardUncommittedTaskSources = {},
            nowMs = { NOW_MS },
        )

        stager.discard(TASK_ID, StoredRestoreSource.AwaitingTransientGrant)
        stager.discard(TASK_ID, StoredRestoreSource.PersistedGrant("grant_identity"))
        stager.discard(TASK_ID, StoredRestoreSource.PrivateCopy(PRIVATE_PATH))

        assertEquals(listOf(PRIVATE_PATH), discarded)
    }

    @Test
    fun `private copy is reopened from its relative token without a raw URI`() = runTest {
        val stager = RestoreSourceStager(
            takeSource = { _, _ -> error("must not consume a transient source") },
            copyToPrivate = { _, _, _ -> error("must not copy") },
            commitPrivateSource = { _, _, _ -> error("must not commit") },
            privateSourceUri = { taskId, path ->
                assertEquals(TASK_ID, taskId)
                "file:///private/$path"
            },
            discardPrivateSource = { _, _ -> },
            discardUncommittedTaskSources = {},
            nowMs = { NOW_MS },
        )

        val result = stager.resolve(
            claim(),
            StoredRestoreSource.PrivateCopy(PRIVATE_PATH),
            appliedCheckpoints(),
        )

        assertEquals(
            RestoreSourceResolution.Ready("file:///private/$PRIVATE_PATH"),
            result,
        )
    }

    @Test
    fun `cross task private copy is rejected before open or deletion`() = runTest {
        var opened = false
        var discarded = false
        val stager = RestoreSourceStager(
            takeSource = { _, _ -> error("must not consume a transient source") },
            copyToPrivate = { _, _, _ -> error("must not copy") },
            commitPrivateSource = { _, _, _ -> error("must not commit") },
            privateSourceUri = { _, _ ->
                opened = true
                PRIVATE_URI
            },
            discardPrivateSource = { _, _ -> discarded = true },
            discardUncommittedTaskSources = {},
            nowMs = { NOW_MS },
        )
        val crossTaskSource = StoredRestoreSource.PrivateCopy(
            "data_tasks/00000000-0000-0000-0000-0000000000a2/restore-source.thor"
        )

        assertEquals(
            RestoreSourceResolution.WaitingForSource,
            stager.resolve(claim(), crossTaskSource, appliedCheckpoints()),
        )
        stager.discard(TASK_ID, crossTaskSource)

        assertEquals(false, opened)
        assertEquals(false, discarded)
    }

    private fun appliedCheckpoints() = DataTaskCheckpointSink { DataTaskSinkWrite.APPLIED }

    private fun claim(capabilityToken: String = CAPABILITY_TOKEN) = DataSyncClaim(
        taskId = TASK_ID,
        claimToken = CLAIM_TOKEN,
        transientSourceToken = capabilityToken,
        item = ClaimedDataTaskItem(
            taskId = TASK_ID,
            ordinal = 0,
            packageName = "com.example.app",
            displayLabel = "Example",
            deterministicStagingIdentity = "restore-source-stage",
            claimToken = ITEM_CLAIM_TOKEN,
            claimLeaseExpiresAtEpochMs = 600_000L,
            attemptCount = 1,
        ),
    )

    private companion object {
        val TASK_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        const val CLAIM_TOKEN = "claim-restore-source"
        const val ITEM_CLAIM_TOKEN = "item-claim-restore-source"
        const val CAPABILITY_TOKEN = "restore-capability"
        const val RAW_URI = "content://documents/raw-restore-source"
        const val PRIVATE_PATH =
            "data_tasks/00000000-0000-0000-0000-0000000000a1/restore-source.thor"
        const val PRIVATE_URI = "file:///private/restore-source.thor"
        const val NOW_MS = 7_000L
    }
}
