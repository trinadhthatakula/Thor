// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.launcher

import com.valhalla.thor.R
import com.valhalla.thor.data.freezer.PrivilegeSweepSurfaceLauncher
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkRequest
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchRejection
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchResult
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.PrivilegeSweepSpec
import com.valhalla.thor.domain.model.PrivilegeSweepStatus
import com.valhalla.thor.domain.repository.PrivilegeSweepController
import com.valhalla.thor.presentation.FakeFreezerRepository
import com.valhalla.thor.presentation.privilegeSweepResolver
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FreezerLaunchActivityTest {
    @Test
    fun `shortcut reports accepted enqueue without waiting for completion`() {
        val accepted = PrivilegeSweepLaunchResult.Accepted(
            requestId = UUID(0L, 1L),
            workId = UUID(1L, 1L),
            coalesced = false,
        )

        assertEquals(R.string.sweep_queued, shortcutBulkMessageRes(accepted))
    }

    @Test
    fun `report window expires while process owned enqueue continues`() = runTest {
        val release = CompletableDeferred<Unit>()
        val requestId = UUID(0L, 2L)
        val controller = object : PrivilegeSweepController {
            override val activeRequests: Flow<List<PrivilegeSweepStatus>> = flowOf(emptyList())

            override suspend fun launch(spec: PrivilegeSweepSpec): PrivilegeSweepLaunchResult =
                withContext(NonCancellable) {
                    release.await()
                    PrivilegeSweepLaunchResult.Accepted(
                        requestId = requestId,
                        workId = UUID(1L, 2L),
                        coalesced = false,
                    )
                }

            override fun observe(requestId: UUID): Flow<PrivilegeSweepStatus?> = flowOf(null)
            override fun observeLatest(source: PrivilegeSweepSource): Flow<PrivilegeSweepStatus?> =
                flowOf(null)

            override suspend fun cancelQueue() = Unit
        }
        val launcher = PrivilegeSweepSurfaceLauncher(
            resolver = privilegeSweepResolver(
                freezerRepository = FakeFreezerRepository(setOf("a"))
            ),
            controller = controller,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val enqueue = launcher.launch(BulkRequest(BulkOp.FREEZE), PrivilegeSweepSource.LAUNCHER)
        val reported = async { withTimeoutOrNull(2_000L) { enqueue.await() } }

        advanceTimeBy(2_001L)
        runCurrent()

        assertTrue(reported.isCompleted)
        assertNull(reported.await())
        assertFalse(enqueue.isCompleted)

        release.complete(Unit)
        runCurrent()
        assertEquals(requestId, (enqueue.await() as PrivilegeSweepLaunchResult.Accepted).requestId)
    }

    @Test
    fun `shortcut reports notification gate rejection visibly`() {
        val rejected = PrivilegeSweepLaunchResult.Rejected(
            PrivilegeSweepLaunchRejection.NotificationsRequired
        )

        assertEquals(
            R.string.notification_access_needed_subtitle,
            shortcutBulkMessageRes(rejected),
        )
    }
}
