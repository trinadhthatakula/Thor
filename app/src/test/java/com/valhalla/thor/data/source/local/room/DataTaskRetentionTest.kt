// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.backup.job.DataTaskStore
import com.valhalla.thor.domain.model.AppShareRequest
import com.valhalla.thor.domain.model.AppShareTarget
import com.valhalla.thor.domain.model.DataTaskItemResult
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskOutputState
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.domain.model.NewDataTaskOutput
import com.valhalla.thor.domain.model.SharePrepareFormat
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DataTaskRetentionTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: DataTaskDao
    private lateinit var store: DataTaskStore
    private var nextTask = 1L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.dataTaskDao()
        store = DataTaskStore(dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `share insertion preserves first package order and separates task selection identity`() = runTest {
        val first = newTaskId()
        val second = newTaskId()
        val request = AppShareRequest(
            targets = listOf(
                AppShareTarget("com.example.second", "Second"),
                AppShareTarget("com.example.first", "First"),
                AppShareTarget("com.example.second", "Duplicate"),
            ),
            format = SharePrepareFormat.APK,
        )

        assertEquals(DataTaskState.QUEUED, store.insertShare(first, request, 100L))
        assertEquals(DataTaskState.QUEUED, store.insertShare(second, request, 100L))

        val task = snapshot(first)
        assertEquals(DataTaskKind.SHARE_PREPARE, task.kind)
        assertEquals("share:$first", task.targetKey)
        assertEquals("share:$second", snapshot(second).targetKey)
        assertEquals(listOf("com.example.second", "com.example.first"), task.items.map { it.packageName })
        assertEquals(listOf("Second", "First"), task.items.map { it.displayLabel })
        assertEquals(listOf(0, 1), task.items.map { it.ordinal })
        assertEquals(listOf("item-$first-0", "item-$first-1"), task.items.map { it.deterministicStagingIdentity })
        assertEquals(
            StoredDataTaskDetail.SharePrepare(
                requestedFormat = SharePrepareFormat.APK,
                publicationPolicy = DataTaskPublicationPolicy.PRIVATE_SHARE_WITH_24_HOUR_EXPIRY,
                deterministicStagingIdentity = "stage-$first",
            ),
            task.detail,
        )
    }

    @Test
    fun `retention projection includes owning task and complete ordered output set`() = runTest {
        val taskId = readyShare(listOf(1_000L, 2_000L))

        val candidates = store.expiredReadyShareTasks(1_000L)

        assertEquals(listOf(taskId), candidates.map { it.taskId })
        assertEquals(listOf(0, 1), candidates.single().outputs.map { it.itemOrdinal })
        assertEquals(listOf(1_000L, 2_000L), candidates.single().outputs.map { it.expiresAtEpochMs })
        assertEquals(snapshot(taskId), store.readyShareRetentionSnapshot(taskId, 1_000L))
        assertNull(store.readyShareRetentionSnapshot(taskId, 999L))
    }

    @Test
    fun `retention query skips nonshare live malformed and public owners without mutations`() = runTest {
        val mutations = listOf(
            "UPDATE data_tasks SET kind = 'APP_EXPORT' WHERE task_id = ?",
            "UPDATE data_tasks SET state = 'RUNNING', claim_token = 'live-owner' WHERE task_id = ?",
            "UPDATE data_tasks SET state = 'CANCELLED' WHERE task_id = ?",
            "UPDATE data_tasks SET payload_schema_version = 999 WHERE task_id = ?",
            "UPDATE export_task_details SET publication_policy = 'PUBLIC_DOCUMENT' WHERE task_id = ?",
            "UPDATE export_task_details SET requested_format = 'UNKNOWN' WHERE task_id = ?",
            "DELETE FROM export_task_details WHERE task_id = ?",
            "UPDATE data_task_items SET state = 'RUNNING', claim_token = 'live-item' WHERE task_id = ?",
        )
        val ignored = mutations.map { readyShare(listOf(1_000L)) }
        val valid = readyShare(listOf(1_000L))
        mutations.zip(ignored).forEach { (mutation, taskId) ->
            execute(mutation, taskId.toString())
        }
        val before = storedRows()

        assertEquals(listOf(valid), store.expiredReadyShareTasks(1_000L).map { it.taskId })
        ignored.forEach { assertNull(store.readyShareRetentionSnapshot(it, 1_000L)) }
        assertEquals(before, storedRows())
    }

    @Test
    fun `mixed expiry preserves later row and keeps expired owner eligible for next cleanup`() = runTest {
        val taskId = readyShare(listOf(1_000L, 2_000L))
        val first = store.expiredReadyShareTasks(1_000L).single()
        val laterOutput = first.outputs[1]

        assertTrue(store.markReadyTaskExpiredAfterCleanup(first, listOf(first.outputs[0].outputId), 1_000L))

        val partiallyCleaned = snapshot(taskId)
        assertEquals(DataTaskState.EXPIRED, partiallyCleaned.state)
        assertEquals(DataTaskOutputState.EXPIRED, partiallyCleaned.outputs[0].state)
        assertNull(partiallyCleaned.outputs[0].privateRelativePath)
        assertEquals(laterOutput, partiallyCleaned.outputs[1])
        assertEquals(1_000L, partiallyCleaned.terminalAtEpochMs)
        assertEquals(86_401_000L, partiallyCleaned.retainUntilEpochMs)
        assertTrue(store.expiredReadyShareTasks(1_999L).isEmpty())
        assertNull(store.readyShareRetentionSnapshot(taskId, 1_999L))

        val remaining = store.expiredReadyShareTasks(2_000L).single()
        assertEquals(DataTaskState.EXPIRED, remaining.state)
        assertTrue(store.markReadyTaskExpiredAfterCleanup(remaining, listOf(laterOutput.outputId), 2_000L))

        val fullyCleaned = snapshot(taskId)
        assertTrue(fullyCleaned.outputs.all { it.state == DataTaskOutputState.EXPIRED })
        assertTrue(fullyCleaned.outputs.all { it.privateRelativePath == null })
        assertEquals(partiallyCleaned.terminalAtEpochMs, fullyCleaned.terminalAtEpochMs)
        assertEquals(partiallyCleaned.retainUntilEpochMs, fullyCleaned.retainUntilEpochMs)
        assertTrue(store.expiredReadyShareTasks(2_000L).isEmpty())
    }

    @Test
    fun `ready partial owner expires after matching cleanup`() = runTest {
        val taskId = readyShare(listOf(1_000L))
        execute("UPDATE data_tasks SET state = 'READY_PARTIAL' WHERE task_id = ?", taskId.toString())
        val candidate = store.expiredReadyShareTasks(1_000L).single()

        assertTrue(store.markReadyTaskExpiredAfterCleanup(candidate, candidate.outputs.map { it.outputId }, 1_000L))

        assertEquals(DataTaskState.EXPIRED, snapshot(taskId).state)
        assertEquals(DataTaskOutputState.EXPIRED, snapshot(taskId).outputs.single().state)
    }

    @Test
    fun `stale output UUID set leaves replacement row and parent byte for byte unchanged`() = runTest {
        val taskId = readyShare(listOf(1_000L, 1_000L))
        val stale = snapshot(taskId)
        execute(
            "UPDATE data_task_outputs SET output_id = ? WHERE output_id = ?",
            UUID(5L, 5L).toString(),
            stale.outputs[1].outputId.toString(),
        )
        val before = storedRows()

        assertFalse(store.markReadyTaskExpiredAfterCleanup(stale, stale.outputs.map { it.outputId }, 1_000L))

        assertEquals(before, storedRows())
        assertEquals(DataTaskState.READY, snapshot(taskId).state)
        assertTrue(snapshot(taskId).outputs.all { it.privateRelativePath != null })
    }

    @Test
    fun `same UUID replacement metadata rejects stale cleanup without altering any row`() = runTest {
        val mutations = listOf(
            "UPDATE data_task_outputs SET expires_at_epoch_ms = 999 WHERE task_id = ?",
            "UPDATE data_task_outputs SET private_relative_path = private_relative_path || '.new' WHERE task_id = ?",
            "UPDATE data_task_outputs SET byte_size = byte_size + 1 WHERE task_id = ?",
            "UPDATE data_task_items SET attempt_count = attempt_count + 1 WHERE task_id = ?",
            "UPDATE data_tasks SET attempt_count = attempt_count + 1 WHERE task_id = ?",
        )
        mutations.forEach { mutation ->
            val taskId = readyShare(listOf(1_000L))
            val stale = snapshot(taskId)
            execute(mutation, taskId.toString())
            val before = storedRows()

            assertFalse(store.markReadyTaskExpiredAfterCleanup(stale, stale.outputs.map { it.outputId }, 1_000L))

            assertEquals(mutation, before, storedRows())
        }
    }

    @Test
    fun `owner replaced with running task cannot be mutated by stale cleanup`() = runTest {
        val taskId = readyShare(listOf(1_000L))
        val stale = snapshot(taskId)
        execute(
            "UPDATE data_tasks SET state = 'RUNNING', claim_token = 'new-live-owner' WHERE task_id = ?",
            taskId.toString(),
        )
        val before = storedRows()

        assertNull(store.readyShareRetentionSnapshot(taskId, 1_000L))
        assertFalse(store.markReadyTaskExpiredAfterCleanup(stale, stale.outputs.map { it.outputId }, 1_000L))

        assertEquals(before, storedRows())
    }

    @Test
    fun `cleanup requires exact due set and cannot clear future output`() = runTest {
        val taskId = readyShare(listOf(1_000L, 1_000L, 2_000L))
        val candidate = snapshot(taskId)
        val before = storedRows()

        assertFalse(store.markReadyTaskExpiredAfterCleanup(candidate, listOf(candidate.outputs[0].outputId), 1_000L))
        assertEquals(before, storedRows())
        assertFalse(store.markReadyTaskExpiredAfterCleanup(candidate, candidate.outputs.map { it.outputId }, 1_000L))
        assertEquals(before, storedRows())
    }

    @Test
    fun `additional output invalidates stale complete snapshot before any mutation`() = runTest {
        val taskId = readyShare(listOf(1_000L))
        val candidate = snapshot(taskId)
        execute(
            """
            INSERT INTO data_task_outputs(
                output_id, task_id, item_ordinal, private_relative_path, display_name,
                mime_type, byte_size, state, expires_at_epoch_ms
            ) SELECT ?, task_id, item_ordinal, private_relative_path, display_name,
                mime_type, byte_size, state, expires_at_epoch_ms
                FROM data_task_outputs WHERE task_id = ?
            """.trimIndent(),
            UUID(6L, 6L).toString(),
            taskId.toString(),
        )
        val before = storedRows()

        assertFalse(store.markReadyTaskExpiredAfterCleanup(candidate, candidate.outputs.map { it.outputId }, 1_000L))

        assertEquals(before, storedRows())
    }

    private suspend fun readyShare(expiries: List<Long>): UUID {
        val taskId = newTaskId()
        dao.insertTask(
            NewDataTaskRow(
                taskId = taskId.toString(),
                payloadSchemaVersion = 1,
                kind = DataTaskKind.SHARE_PREPARE,
                targetKey = "share:$taskId",
                initialState = DataTaskState.QUEUED,
                detail = StoredDataTaskDetail.SharePrepare(
                    requestedFormat = SharePrepareFormat.APK,
                    publicationPolicy = DataTaskPublicationPolicy.PRIVATE_SHARE_WITH_24_HOUR_EXPIRY,
                    deterministicStagingIdentity = "stage-$taskId",
                ),
                items = expiries.indices.map { ordinal ->
                    NewDataTaskItem(
                        ordinal = ordinal,
                        packageName = "com.example.app$ordinal",
                        displayLabel = "App $ordinal",
                        deterministicStagingIdentity = "item-$taskId-$ordinal",
                    )
                },
                createdAtEpochMs = 100L,
            ),
        )
        expiries.forEachIndexed { ordinal, expiry ->
            // Item settlement releases the task claim; mimic the real runtime's next claim.
            val taskClaim = "task-$taskId-$ordinal"
            assertNotNull(dao.claimOldestRunnableTask("session", taskClaim, 110L, 10_000L))
            val itemClaim = "item-$taskId-$ordinal"
            assertNotNull(dao.claimNextPendingItem(taskId.toString(), taskClaim, itemClaim, 120L, 10_000L))
            assertTrue(
                dao.settleClaimedTask(
                    taskId = taskId.toString(),
                    taskClaimToken = taskClaim,
                    itemOrdinal = ordinal,
                    itemClaimToken = itemClaim,
                    outcome = DataTaskRunOutcome.ItemCompleted(
                        DataTaskItemResult(
                            terminalState = DataTaskItemTerminalState.SUCCEEDED,
                            resultCode = DataTaskResultCode("SHARE_PREPARE_COMPLETED"),
                            warnings = emptyList(),
                            outputs = listOf(
                                NewDataTaskOutput(
                                    outputId = UUID.nameUUIDFromBytes("$taskId:$ordinal".toByteArray()),
                                    privateRelativePath = "share_ready/item-$taskId-$ordinal/com.example.app$ordinal/$ordinal.apk",
                                    displayName = "$ordinal.apk",
                                    mimeType = "application/vnd.android.package-archive",
                                    byteSize = 42L,
                                    state = DataTaskOutputState.READY,
                                    expiresAtEpochMs = expiry,
                                ),
                            ),
                            finishedAtEpochMs = 130L,
                        ),
                    ),
                    nowMs = 130L,
                ),
            )
        }
        return taskId
    }

    private fun newTaskId(): UUID = UUID(0L, nextTask++)

    private suspend fun snapshot(taskId: UUID): DataTaskSnapshot =
        requireNotNull(store.loadTask(taskId))

    private fun execute(sql: String, vararg values: Any?) {
        database.openHelper.writableDatabase.execSQL(sql, values)
    }

    private fun storedRows(): Map<String, List<List<String?>>> =
        listOf("data_tasks", "export_task_details", "data_task_items", "data_task_outputs")
            .associateWith { table ->
                database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1, 2").use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(List(cursor.columnCount) { index ->
                                if (cursor.isNull(index)) null else cursor.getString(index)
                            })
                        }
                    }
                }
            }
}
