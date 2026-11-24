// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.launcher

import com.valhalla.thor.R
import com.valhalla.thor.data.freezer.PrivilegeSweepSurfaceLauncher
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkRequest
import com.valhalla.thor.domain.model.BulkScope
import com.valhalla.thor.domain.model.FreezeProfile
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchRejection
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchResult
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepPhase
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.PrivilegeSweepSpec
import com.valhalla.thor.domain.model.PrivilegeSweepStatus
import com.valhalla.thor.domain.repository.PrivilegeSweepController
import com.valhalla.thor.presentation.FakeFreezeProfileRepository
import com.valhalla.thor.presentation.FakeFreezerRepository
import com.valhalla.thor.presentation.FakePrivilegeStateProvider
import com.valhalla.thor.presentation.FakePrivilegeSweepController
import com.valhalla.thor.presentation.privilegeSweepResolver
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
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
    fun `unready privilege ends the wait after ten seconds without claiming denial`() = runTest {
        val privilege = FakePrivilegeStateProvider()
        val failure = async { shortcutPrivilegeFailureRes(privilege) }
        runCurrent()

        advanceTimeBy(9_999L)
        runCurrent()
        assertFalse(failure.isCompleted)
        advanceTimeBy(1L)
        runCurrent()

        assertEquals(R.string.freezer_launch_failed, failure.await())
        privilege.emit(PrivilegeState(root = true, active = PrivilegeMode.ROOT, isReady = true))
        runCurrent()
        assertEquals(R.string.freezer_launch_failed, failure.await())
    }

    @Test
    fun `privilege becoming ready inside the deadline permits launch`() = runTest {
        val privilege = FakePrivilegeStateProvider()
        val failure = async { shortcutPrivilegeFailureRes(privilege) }
        runCurrent()
        advanceTimeBy(9_999L)
        runCurrent()
        assertFalse(failure.isCompleted)

        privilege.emit(PrivilegeState(shizuku = true, active = PrivilegeMode.SHIZUKU, isReady = true))
        runCurrent()

        assertNull(failure.await())
    }

    @Test
    fun `confirmed absence asks for privilege without waiting for the deadline`() = runTest {
        val privilege = FakePrivilegeStateProvider(PrivilegeState(isReady = true))

        assertEquals(R.string.tile_grant_privilege_toast, shortcutPrivilegeFailureRes(privilege))
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `destroyed shortcut cancels readiness without returning a failure message`() = runTest {
        val privilege = FakePrivilegeStateProvider()
        val failure = async { shortcutPrivilegeFailureRes(privilege) }
        runCurrent()
        failure.cancel()
        runCurrent()

        var cancelled = false
        try {
            failure.await()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(0L, testScheduler.currentTime)
    }

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

        }
        val launcher = PrivilegeSweepSurfaceLauncher(
            resolver = privilegeSweepResolver(
                freezerRepository = FakeFreezerRepository(setOf("a"))
            ),
            controller = controller,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val enqueue = launcher.launchShortcut(BulkRequest(BulkOp.FREEZE))
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
    fun `pinned profile resolves current membership even while another profile is running`() = runTest {
        val profiles = FakeFreezeProfileRepository(listOf(FreezeProfile(8, "Games", listOf("old.game"))))
        val controller = FakePrivilegeSweepController()
        controller.emit(PrivilegeSweepStatus(
            requestId = UUID(0, 1), workId = UUID(0, 2), operation = PrivilegeSweepOperation.FREEZE,
            source = PrivilegeSweepSource.PROFILE, phase = PrivilegeSweepPhase.RUNNING,
            total = 1, succeeded = 0, failed = 0, busy = 0, unresolved = 1,
            rootLaneDegraded = false, profileIds = setOf(7L),
        ))
        val launcher = PrivilegeSweepSurfaceLauncher(
            privilegeSweepResolver(FakeFreezerRepository(setOf("watchlist.app")), profiles),
            controller, StandardTestDispatcher(testScheduler),
        )
        val request = BulkRequest(BulkOp.FREEZE, BulkScope.Profile(8))

        profiles.update(8, "Office", listOf("current.game"))
        launcher.launchShortcut(request).await()
        val spec = controller.launched.single()
        assertEquals(listOf("current.game"), spec.packageNames)
        assertEquals(setOf("PROFILE", "PROFILE:8"), spec.sourceAssociations)
        assertEquals(PrivilegeSweepOperation.FREEZE, spec.operation)

        profiles.delete(8)
        launcher.launchShortcut(request).await()
        assertTrue(controller.launched.last().packageNames.isEmpty())
        assertEquals(R.string.profile_no_apps_to_freeze, shortcutBulkMessageRes(
            PrivilegeSweepLaunchResult.Rejected(PrivilegeSweepLaunchRejection.NoTargets), request.scope,
        ))
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
