// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.StoredRestoreSource
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
            copyToPrivate = { taskId, rawUri ->
                assertEquals(TASK_ID, taskId)
                assertEquals(RAW_URI, rawUri)
                events += "copy"
                PRIVATE_PATH
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

        val result = stager.resolve(claim(), StoredRestoreSource.AwaitingTransientGrant)

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
            copyToPrivate = { _, _ -> copied = true; PRIVATE_PATH },
            commitPrivateSource = { _, _, _ -> error("must not commit") },
            privateSourceUri = { PRIVATE_URI },
            discardPrivateSource = {},
            nowMs = { NOW_MS },
        )

        val result = stager.resolve(claim(), StoredRestoreSource.AwaitingTransientGrant)

        assertEquals(RestoreSourceResolution.WaitingForSource, result)
        assertEquals(false, copied)
    }

    @Test
    fun `lost ownership discards the private copy`() = runTest {
        val discarded = mutableListOf<String>()
        val stager = RestoreSourceStager(
            takeSource = { RAW_URI },
            copyToPrivate = { _, _ -> PRIVATE_PATH },
            commitPrivateSource = { _, _, _ -> false },
            privateSourceUri = { PRIVATE_URI },
            discardPrivateSource = { discarded += it },
            nowMs = { NOW_MS },
        )

        val result = stager.resolve(claim(), StoredRestoreSource.AwaitingTransientGrant)

        assertEquals(RestoreSourceResolution.OwnershipLost, result)
        assertEquals(listOf(PRIVATE_PATH), discarded)
    }

    @Test
    fun `terminal cleanup discards only operation-owned private sources`() {
        val discarded = mutableListOf<String>()
        val stager = RestoreSourceStager(
            takeSource = { null },
            copyToPrivate = { _, _ -> null },
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
            copyToPrivate = { _, _ -> error("must not copy") },
            commitPrivateSource = { _, _, _ -> error("must not commit") },
            privateSourceUri = { path -> "file:///private/$path" },
            discardPrivateSource = {},
            nowMs = { NOW_MS },
        )

        val result = stager.resolve(
            claim(),
            StoredRestoreSource.PrivateCopy(PRIVATE_PATH),
        )

        assertEquals(
            RestoreSourceResolution.Ready("file:///private/$PRIVATE_PATH"),
            result,
        )
    }

    private fun claim() = DataSyncClaim(TASK_ID, CLAIM_TOKEN)

    private companion object {
        val TASK_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        const val CLAIM_TOKEN = "claim-restore-source"
        const val RAW_URI = "content://documents/raw-restore-source"
        const val PRIVATE_PATH =
            "data_tasks/00000000-0000-0000-0000-0000000000a1/restore-source.thor"
        const val PRIVATE_URI = "file:///private/restore-source.thor"
        const val NOW_MS = 7_000L
    }
}
