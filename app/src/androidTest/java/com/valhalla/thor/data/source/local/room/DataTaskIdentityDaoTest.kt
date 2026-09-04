// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskInterruption
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.domain.model.StoredDataDestination
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import com.valhalla.thor.domain.model.StoredRestoreSource
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataTaskIdentityDaoTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AppDatabase
    private lateinit var dao: DataTaskDao

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun activeTaskIdentitySurvivesProcessLocalStateLoss() = runBlocking {
        dao.insertTask(newExportTask())
        database.close()
        openDatabase()

        assertEquals(
            UUID.fromString(TASK_ID),
            dao.observeActiveTaskId(
                kind = DataTaskKind.APP_EXPORT,
                targetKey = "package:com.example.app",
            ).first(),
        )
    }

    @Test
    fun privateRestoreSourceCommitRequiresBothLiveClaimTokens() = runBlocking {
        dao.insertTask(newRestoreTask())
        dao.claimOldestRunnableTask("session", "task-claim", 2_000L, 3_000L)
        dao.claimNextPendingItem(TASK_ID, "task-claim", "item-claim", 2_100L, 3_100L)

        assertFalse(
            dao.commitPrivateRestoreSource(
                TASK_ID,
                "stale-task-claim",
                0,
                "item-claim",
                PRIVATE_SOURCE,
                2_200L,
            )
        )
        assertFalse(
            dao.commitPrivateRestoreSource(
                TASK_ID,
                "task-claim",
                0,
                "stale-item-claim",
                PRIVATE_SOURCE,
                2_200L,
            )
        )
        assertTrue(
            dao.commitPrivateRestoreSource(
                TASK_ID,
                "task-claim",
                0,
                "item-claim",
                PRIVATE_SOURCE,
                2_200L,
            )
        )

        val detail = dao.loadTask(TASK_ID)?.detail as StoredDataTaskDetail.ArchiveRestore
        assertEquals(StoredRestoreSource.PrivateCopy(PRIVATE_SOURCE), detail.source)
    }

    @Test
    fun timeoutInterruptionRequiresCurrentUnexpiredTaskClaim() = runBlocking {
        dao.insertTask(newExportTask())
        dao.claimOldestRunnableTask("session", "task-claim", 2_000L, 3_000L)

        assertFalse(
            dao.markClaimInterrupted(
                TASK_ID,
                "stale-claim",
                DataTaskInterruption.SERVICE_TIMEOUT,
                DataTaskResultCode("SERVICE_TIMEOUT"),
                2_100L,
            )
        )
        assertFalse(
            dao.markClaimInterrupted(
                TASK_ID,
                "task-claim",
                DataTaskInterruption.SERVICE_TIMEOUT,
                DataTaskResultCode("SERVICE_TIMEOUT"),
                3_000L,
            )
        )
        assertTrue(
            dao.markClaimInterrupted(
                TASK_ID,
                "task-claim",
                DataTaskInterruption.SERVICE_TIMEOUT,
                DataTaskResultCode("SERVICE_TIMEOUT"),
                2_200L,
            )
        )

        val task = requireNotNull(dao.loadTask(TASK_ID))
        assertEquals(DataTaskInterruption.SERVICE_TIMEOUT, task.interruption)
        assertEquals(DataTaskResultCode("SERVICE_TIMEOUT"), task.resultCode)
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME).build()
        dao = database.dataTaskDao()
    }

    private fun newExportTask() = NewDataTaskRow(
        taskId = TASK_ID,
        payloadSchemaVersion = 1,
        kind = DataTaskKind.APP_EXPORT,
        targetKey = "package:com.example.app",
        initialState = DataTaskState.QUEUED,
        detail = StoredDataTaskDetail.AppExport(
            requestedFormat = BundleFormat.APK,
            destination = StoredDataDestination.Downloads,
            namingLabel = "Example",
            publicationPolicy = DataTaskPublicationPolicy.PUBLIC_DOCUMENT,
            deterministicStagingIdentity = "stage-$TASK_ID",
        ),
        items = listOf(
            NewDataTaskItem(
                ordinal = 0,
                packageName = "com.example.app",
                displayLabel = "Example",
                deterministicStagingIdentity = "item-$TASK_ID-0",
            )
        ),
        createdAtEpochMs = 1_000L,
    )

    private fun newRestoreTask() = NewDataTaskRow(
        taskId = TASK_ID,
        payloadSchemaVersion = 1,
        kind = DataTaskKind.ARCHIVE_RESTORE,
        targetKey = "package:com.example.app",
        initialState = DataTaskState.STAGING_SOURCE,
        detail = StoredDataTaskDetail.ArchiveRestore(
            expectedPackageName = "com.example.app",
            dataClassIds = listOf("apk"),
            restoreObb = false,
            source = StoredRestoreSource.AwaitingTransientGrant,
            mutationBreadcrumb = null,
            deterministicStagingIdentity = "stage-$TASK_ID",
        ),
        items = listOf(
            NewDataTaskItem(
                ordinal = 0,
                packageName = "com.example.app",
                displayLabel = "Example",
                deterministicStagingIdentity = "item-$TASK_ID-0",
            )
        ),
        createdAtEpochMs = 1_000L,
    )

    private companion object {
        const val DATABASE_NAME = "data-task-identity-test"
        const val TASK_ID = "00000000-0000-0000-0000-000000000191"
        const val PRIVATE_SOURCE = "data_tasks/$TASK_ID/restore-source.thor"
    }
}
