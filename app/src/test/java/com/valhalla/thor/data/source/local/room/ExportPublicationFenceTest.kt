// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.source.local.room

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.backup.job.DataTaskStore
import com.valhalla.thor.domain.model.AppExportRequest
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskCheckpoint
import com.valhalla.thor.domain.model.DataTaskStage
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ExportPublicationFenceTest {
    @Test fun `Room recovery and repeated claim preserve publication fence and reject stale writer`() = runTest {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val store = DataTaskStore(database.dataTaskDao())
            val id = UUID.fromString("77777777-7777-7777-7777-777777777777")
            val now = System.currentTimeMillis()
            val lease = now + 60_000L
            store.insertExport(id, AppExportRequest("com.example.app", BundleFormat.APK, "Example", "content://export.test/tree/root"), now)
            val original = requireNotNull(store.claimOldestRunnableWork("session-0", "task-0", "item-0", now, lease))
            val publishing = DataTaskCheckpoint(DataTaskStage.PUBLISHING, 0, 1, 0, "Example", false, null, now)
            assertTrue(store.checkpointClaimedTask(id, "task-0", 0, "item-0", publishing, lease))
            repeat(3) { index ->
                val next = index + 1
                assertEquals(1, store.recoverClaims("session-$next", now + next) { _, _ -> false }.size)
                val claimed = requireNotNull(store.claimOldestRunnableWork("session-$next", "task-$next", "item-$next", now + next, lease))
                assertEquals(DataTaskStage.PUBLISHING, claimed.task.lastCheckpoint?.stage)
                assertEquals(original.item.deterministicStagingIdentity, claimed.item.deterministicStagingIdentity)
                assertTrue(!store.checkpointClaimedTask(id, "task-0", 0, "item-0", publishing.copy(stage = DataTaskStage.PREPARING), lease))
            }
        } finally { database.close() }
    }
}
