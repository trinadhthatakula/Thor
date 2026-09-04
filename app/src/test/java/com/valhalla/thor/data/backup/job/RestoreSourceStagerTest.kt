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

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreSourceStagerTest {

    @Test
    fun `transient raw URI is copied before only the private path is committed`() = runTest {
        val events = mutableListOf<String>()
        val committed = mutableListOf<String>()
        val stager = RestoreSourceStager(
            takeSource = {
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
            privateSourceUri = {
                assertEquals(PRIVATE_PATH, it)
                PRIVATE_URI
            },
            discardPrivateSource = { error("committed source must be retained") },
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
            takeSource = { null },
            copyToPrivate = { _, _, _ ->
                copied = true
                RestoreSourceCopyResult.Completed(PRIVATE_PATH)
            },
            commitPrivateSource = { _, _, _ -> error("must not commit") },
            privateSourceUri = { PRIVATE_URI },
            discardPrivateSource = {},
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
    fun `lost ownership discards the private copy`() = runTest {
        val discarded = mutableListOf<String>()
        val stager = RestoreSourceStager(
            takeSource = { RAW_URI },
            copyToPrivate = { _, _, _ -> RestoreSourceCopyResult.Completed(PRIVATE_PATH) },
            commitPrivateSource = { _, _, _ -> false },
            privateSourceUri = { PRIVATE_URI },
            discardPrivateSource = { discarded += it },
            nowMs = { NOW_MS },
        )

        val result = stager.resolve(
            claim(),
            StoredRestoreSource.AwaitingTransientGrant,
            appliedCheckpoints()
        )

        assertEquals(RestoreSourceResolution.OwnershipLost, result)
        assertEquals(listOf(PRIVATE_PATH), discarded)
    }

    @Test
    fun `staging aborts and cleans exact partial when byte checkpoint loses ownership`() = runTest {
        val checkpointedBytes = mutableListOf<Long>()
        val cleaned = mutableListOf<String>()
        val stager = RestoreSourceStager(
            takeSource = { RAW_URI },
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
            privateSourceUri = { error("must not expose an uncommitted source") },
            discardPrivateSource = { error("copy dependency already owns partial cleanup") },
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
    fun `staging deadline cancels copy and cleans only its exact partial`() = runTest {
        val copyStarted = CompletableDeferred<Unit>()
        val cleaned = mutableListOf<String>()
        val stager = RestoreSourceStager(
            takeSource = { RAW_URI },
            copyToPrivate = { _, _, _ ->
                copyStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cleaned += "$PRIVATE_PATH.part"
                }
            },
            commitPrivateSource = { _, _, _ -> error("timed out copy must not commit") },
            privateSourceUri = { error("timed out copy must not be exposed") },
            discardPrivateSource = { error("copy dependency owns partial cleanup") },
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
        assertEquals(listOf("$PRIVATE_PATH.part"), cleaned)
    }

    @Test
    fun `terminal cleanup discards only operation-owned private sources`() {
        val discarded = mutableListOf<String>()
        val stager = RestoreSourceStager(
            takeSource = { null },
            copyToPrivate = { _, _, _ -> RestoreSourceCopyResult.SourceUnavailable },
            commitPrivateSource = { _, _, _ -> false },
            privateSourceUri = { null },
            persistedSourceUri = { null },
            discardPrivateSource = { discarded += it },
            nowMs = { NOW_MS },
        )

        stager.discard(StoredRestoreSource.AwaitingTransientGrant)
        stager.discard(StoredRestoreSource.PersistedGrant("grant_identity"))
        stager.discard(StoredRestoreSource.PrivateCopy(PRIVATE_PATH))

        assertEquals(listOf(PRIVATE_PATH), discarded)
    }

    @Test
    fun `private copy is reopened from its relative token without a raw URI`() = runTest {
        val stager = RestoreSourceStager(
            takeSource = { error("must not consume a transient source") },
            copyToPrivate = { _, _, _ -> error("must not copy") },
            commitPrivateSource = { _, _, _ -> error("must not commit") },
            privateSourceUri = { path -> "file:///private/$path" },
            discardPrivateSource = {},
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

    private fun appliedCheckpoints() = DataTaskCheckpointSink { DataTaskSinkWrite.APPLIED }

    private fun claim() = DataSyncClaim(
        taskId = TASK_ID,
        claimToken = CLAIM_TOKEN,
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
        const val RAW_URI = "content://documents/raw-restore-source"
        const val PRIVATE_PATH =
            "data_tasks/00000000-0000-0000-0000-0000000000a1/restore-source.thor"
        const val PRIVATE_URI = "file:///private/restore-source.thor"
        const val NOW_MS = 7_000L
    }
}
