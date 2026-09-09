// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.freezer

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.pm.InstallSourceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import com.valhalla.thor.data.gateway.AndroidReinstallStateReader
import com.valhalla.thor.data.gateway.ReinstallFinalState
import com.valhalla.thor.data.gateway.ReinstallPostconditionVerifier
import com.valhalla.thor.data.gateway.ReinstallStateReader
import com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator
import com.valhalla.thor.data.gateway.root.DefaultRootLaneStatusSource
import com.valhalla.thor.data.repository.installerPackageNameOf
import com.valhalla.thor.data.source.local.thorUserId
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplicationPackageManager
import com.valhalla.thor.data.repository.RoomPrivilegeSweepStore
import com.valhalla.thor.data.service.*
import com.valhalla.thor.data.source.local.room.AppDatabase
import com.valhalla.thor.domain.model.*
import com.valhalla.thor.domain.repository.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoomPrivilegeSweepDrainRuntimeTest {
    private lateinit var db: AppDatabase
    private lateinit var store: RoomPrivilegeSweepStore
    private val owners = PrivilegeSweepOwnerRegistry()
    private val privilege = MutableStateFlow(PrivilegeState(root = true, active = PrivilegeMode.ROOT, isReady = true))
    private val clock = PrivilegeSweepClock { 10_000L }
    private val gate = PrivilegeSweepProcessGate()
    private val executed = mutableListOf<String>()
    private val wake = RecordingWakeLock()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        store = RoomPrivilegeSweepStore(db.privilegeSweepDao())
    }
    @After fun close() { db.close() }

    @Test fun `load failure settles unstarted owned request to explicit retry block`() = runBlocking {
        val request = create()
        val faulty = object : PrivilegeSweepStore by store {
            override suspend fun load(requestId: UUID): StoredPrivilegeSweep? {
                val row = store.load(requestId)
                if (row?.requestState == PrivilegeSweepRequestState.RUNNING) error("load unavailable")
                return row
            }
        }
        drain(runtime(faulty))
        val row = requireNotNull(store.load(request))
        assertEquals(PrivilegeSweepRequestState.BLOCKED, row.requestState)
        assertEquals(PrivilegeSweepBlockReason.START_BLOCKED, row.blockReason)
        assertEquals(listOf(PrivilegeSweepTargetState.PENDING, PrivilegeSweepTargetState.PENDING), row.targetSnapshots.map { it.state })
        assertTrue(executed.isEmpty())
    }

    @Test fun `wake acquisition and notification failures settle admitted target without replay`() = runBlocking {
        for (boundary in listOf("wake", "notification")) {
            val request = create(boundary)
            wake.failAcquire = boundary == "wake"
            drain(runtime(), onClaimed = { if (boundary == "notification") error("promotion failed") })
            val row = requireNotNull(store.load(request))
            assertEquals(PrivilegeSweepRequestState.BLOCKED, row.requestState)
            assertEquals(listOf(PrivilegeSweepTargetState.UNKNOWN, PrivilegeSweepTargetState.PENDING), row.targetSnapshots.map { it.state })
            assertNull(row.targetSnapshots[0].claimToken)
            assertTrue(executed.isEmpty())
            assertFalse(wake.isHeld)
        }
    }

    @Test fun `cancellation after first or later target commit settles actual active ordinal`() = runBlocking {
        for (ordinal in 0..1) {
            executed.clear()
            val request = create("cancel$ordinal")
            val faulty = object : PrivilegeSweepStore by store {
                override suspend fun claimNextPendingTarget(requestId: UUID, requestClaimToken: String, targetClaimToken: String, nowMs: Long, leaseUntilMs: Long): ClaimedPrivilegeSweepTarget? {
                    val target = store.claimNextPendingTarget(requestId, requestClaimToken, targetClaimToken, nowMs, leaseUntilMs)
                    if (target?.ordinal == ordinal) {
                        store.requestCancellation(requestId, nowMs)
                        assertTrue(owners.cancelActive(requestId))
                        currentCoroutineContext().ensureActive()
                    }
                    return target
                }
            }
            drain(runtime(faulty))
            val row = requireNotNull(store.load(request))
            assertEquals(ordinal, executed.size)
            assertTrue(row.targetSnapshots.none { it.state == PrivilegeSweepTargetState.RUNNING || it.claimToken != null })
            assertNotNull(row.terminalState)
            assertEquals(PrivilegeSweepTargetState.CANCELLED, row.targetSnapshots[ordinal].state)
            if (ordinal == 1) assertEquals(PrivilegeSweepTargetState.SUCCEEDED, row.targetSnapshots[0].state)
        }
    }

    @Test fun `privilege loss between targets preserves completed result and pending work`() = runBlocking {
        val request = create()
        drain(runtime(execute = { _, pkg ->
            executed += pkg
            privilege.value = PrivilegeState(isReady = true)
            itemResult(SweepAttemptOutcome.SUCCEEDED)
        }))
        val row = requireNotNull(store.load(request))
        assertEquals(PrivilegeSweepBlockReason.PRIVILEGE_AUTHORIZATION_REQUIRED, row.blockReason)
        assertEquals(listOf(PrivilegeSweepTargetState.SUCCEEDED, PrivilegeSweepTargetState.PENDING), row.targetSnapshots.map { it.state })
        assertEquals(1, executed.size)
        assertTrue(store.resumeBlockedRequest(request, requireNotNull(row.blockReason), 10_001))
        privilege.value = PrivilegeState(root = true, active = PrivilegeMode.ROOT, isReady = true)
        drain(runtime())
        assertEquals(StoredSweepTerminal.SUCCEEDED, store.load(request)?.terminalState)
        assertEquals(2, executed.size)
    }

    @Test fun `ordinary failures continue and finalization failure retains every committed result`() = runBlocking {
        val request = create()
        val faulty = object : PrivilegeSweepStore by store {
            override suspend fun finishClaimedRequestIfDrained(requestId: UUID, claimToken: String, nowMs: Long): Boolean = error("finalization unavailable")
        }
        drain(runtime(faulty, execute = { _, pkg ->
            executed += pkg
            itemResult(SweepAttemptOutcome.FAILED)
        }))
        val row = requireNotNull(store.load(request))
        assertEquals(2, executed.size)
        assertEquals(StoredSweepTerminal.FAILED, row.terminalState)
        assertEquals(2, row.failed)
        assertEquals(2, wake.renewals)
        assertFalse(wake.isHeld)
    }

    @Test fun `each target persists only its own root fallback provenance`() = runBlocking {
        val request = create("target-provenance")
        val executions = ArrayDeque(
            listOf(
                itemResult(SweepAttemptOutcome.SUCCEEDED, rootLaneDegraded = true),
                itemResult(SweepAttemptOutcome.SUCCEEDED, rootLaneDegraded = false),
            )
        )

        drain(runtime(execute = { _, _ -> executions.removeFirst() }))

        val completed = requireNotNull(store.load(request))
        assertEquals(listOf(true, false), completed.targetSnapshots.map { it.rootLaneDegraded })
        val fresh = DefaultRootLaneStatusSource()
        val cancellation = PrivilegeSweepCancellationCoordinator({ PrivilegeSweepCancellationDecision.NotFound }, { false }, { ServiceStartResult.AlreadyRunning }, {})
        val controller = DefaultPrivilegeSweepController(store, clock, gate, PrivilegeQueueWakeSignal { ServiceStartResult.AlreadyRunning }, SweepQueueCanceller(cancellation), fresh)
        assertTrue(requireNotNull(controller.observe(request).first()).rootLaneDegraded)
    }

    @Test fun `cutover preserves inherited service-owned legacy request for exact recovery`() = runBlocking {
        val request = create("owned-legacy", legacy = true)
        assertNotNull(
            store.claimOldestRunnableRequest(
                sessionToken = "inherited-session",
                claimToken = "inherited-claim",
                nowMs = 1_000L,
                leaseUntilMs = 2_000L,
            )
        )
        val cutover = PrivilegeSweepWorkManagerCutover(
            LegacyPrivilegeSweepExecutionFence(),
            SweepQueueWorkManager {},
            store,
            clock,
            gate,
        )

        cutover.awaitCompleted()

        val preserved = requireNotNull(store.load(request))
        assertEquals(PrivilegeSweepRequestState.RUNNING, preserved.requestState)
        assertTrue(preserved.targetSnapshots.all { it.state == PrivilegeSweepTargetState.PENDING })
        assertTrue(
            store.recoverRequestClaims(
                sessionToken = "replacement-session",
                nowMs = 10_000L,
                localOwnerIsLive = { _, _ -> false },
            ).isEmpty()
        )
        assertEquals(PrivilegeSweepRequestState.QUEUED, store.load(request)?.requestState)
        assertEquals(
            request,
            store.claimOldestRunnableRequest(
                sessionToken = "replacement-session",
                claimToken = "replacement-claim",
                nowMs = 10_001L,
                leaseUntilMs = 20_000L,
            )?.requestId,
        )
    }

    @Test fun `startup prune preserves converted and unconverted legacy work`() = runBlocking {
        val id = create("legacy-prune", legacy = true)
        for (converted in listOf(false, true)) {
            if (converted) assertTrue(store.markLegacyTargetsUnknown(id, listOf(0, 1), newPrivilegeServiceExecutionId(), 10_000))
            val before = store.load(id)
            val startup = PrivilegeSweepReconciler(
                store,
                clock,
                PrivilegeSweepProcessGate(),
            )
            startup.pruneRetained()
            assertEquals(before, store.load(id))
            assertNull(store.load(id)?.retainUntilEpochMs)
        }
    }

    @Test fun `throwing reinstall reader blocks interrupted target without replay through real adapter`() = runBlocking {
        assertUnavailableReinstallRetained(ReinstallStateReader { _, _ -> error("inspection unavailable") })
    }

    @Test
    @Config(shadows = [UnavailableInstallerSource::class])
    fun `installer source lookup failure remains unknown without changing best effort consumers`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context.packageManager).installPackage(android.content.pm.PackageInfo().apply {
            packageName = REINSTALL_PACKAGE
            applicationInfo = android.content.pm.ApplicationInfo().apply {
                packageName = REINSTALL_PACKAGE
                flags = android.content.pm.ApplicationInfo.FLAG_INSTALLED
            }
        })
        assertNull(context.packageManager.installerPackageNameOf(REINSTALL_PACKAGE))
        assertUnavailableReinstallRetained(AndroidReinstallStateReader(context))
    }

    @Test fun `real reinstall adapter preserves observed positive and negative recovery`() = runBlocking {
        for (satisfied in listOf(true, false)) {
            executed.clear()
            val id = interruptedReinstall()
            val adapter = DefaultPrivilegeSweepReinstallPostconditionVerifier(
                ReinstallPostconditionVerifier(ReinstallStateReader { _, _ ->
                    ReinstallFinalState(true, if (satisfied) "com.android.vending" else null)
                })
            )
            assertEquals(
                if (satisfied) ReinstallPostcondition.SATISFIED else ReinstallPostcondition.NOT_SATISFIED,
                adapter.verify(REINSTALL_PACKAGE, thorUserId, newPrivilegeServiceExecutionId(), id),
            )
            drain(runtime(verifier = adapter))
            assertEquals(StoredSweepTerminal.SUCCEEDED, store.load(id)?.terminalState)
            assertEquals(if (satisfied) emptyList<String>() else listOf(REINSTALL_PACKAGE), executed)
        }
    }

    private suspend fun assertUnavailableReinstallRetained(reader: ReinstallStateReader) {
        val id = interruptedReinstall()
        val adapter = DefaultPrivilegeSweepReinstallPostconditionVerifier(ReinstallPostconditionVerifier(reader))
        val observed = adapter.verify(REINSTALL_PACKAGE, thorUserId, newPrivilegeServiceExecutionId(), id)
        drain(runtime(verifier = adapter))
        val row = requireNotNull(store.load(id))
        assertEquals("unavailable inspection must not execute another reinstall", emptyList<String>(), executed)
        assertEquals(ReinstallPostcondition.UNKNOWN, observed)
        assertEquals(PrivilegeSweepRequestState.BLOCKED, row.requestState)
        assertEquals(PrivilegeSweepTargetState.UNKNOWN, row.targetSnapshots.single().state)
        assertNull(store.claimOldestRunnableRequest("probe-session", "probe-claim", 20_000L, 30_000L))
    }

    private suspend fun interruptedReinstall(): UUID {
        val id = UUID.randomUUID()
        store.createOrFindEquivalent(NewPrivilegeSweepSnapshot(id, newPrivilegeServiceExecutionId(), PrivilegeSweepOperation.REINSTALL, null, thorUserId, PrivilegeSweepSource.MAIN, 1, listOf(REINSTALL_PACKAGE)))
        assertEquals(id, store.claimOldestRunnableRequest("dead-session", "dead-request", 1_000L, 2_000L)?.requestId)
        assertNotNull(store.claimNextPendingTarget(id, "dead-request", "dead-target", 1_001L, 2_000L))
        return id
    }

    @Implements(className = "android.app.ApplicationPackageManager")
    class UnavailableInstallerSource : ShadowApplicationPackageManager() {
        @RequiresApi(Build.VERSION_CODES.R)
        @Implementation(minSdk = Build.VERSION_CODES.R)
        public override fun getInstallSourceInfo(packageName: String): InstallSourceInfo {
            if (packageName == REINSTALL_PACKAGE) error("installer source unavailable")
            return super.getInstallSourceInfo(packageName) as InstallSourceInfo
        }
    }

    private companion object {
        const val REINSTALL_PACKAGE = "com.example.reinstall.recovery"
    }

    private suspend fun create(suffix: String = "default", legacy: Boolean = false): UUID {
        val id = UUID.randomUUID()
        store.createOrFindEquivalent(NewPrivilegeSweepSnapshot(id, if (legacy) UUID.randomUUID() else newPrivilegeServiceExecutionId(), PrivilegeSweepOperation.CLEAR_CACHE, null, 0, PrivilegeSweepSource.MAIN, 1, listOf("com.$suffix.a", "com.$suffix.b")))
        return id
    }

    private fun itemResult(
        outcome: SweepAttemptOutcome,
        rootLaneDegraded: Boolean = false,
    ) = PrivilegeSweepItemExecutionResult(outcome, rootLaneDegraded)

    private fun runtime(
        port: PrivilegeSweepStore = store,
        verifier: PrivilegeSweepReinstallPostconditionVerifier = PrivilegeSweepReinstallPostconditionVerifier { _, _, _, _ -> ReinstallPostcondition.UNKNOWN },
        execute: suspend (StoredPrivilegeSweep, String) -> PrivilegeSweepItemExecutionResult = { _, pkg ->
            executed += pkg
            itemResult(SweepAttemptOutcome.SUCCEEDED)
        },
    ): RoomPrivilegeSweepDrainRuntime {
        val cutover = PrivilegeSweepWorkManagerCutover(LegacyPrivilegeSweepExecutionFence(), SweepQueueWorkManager {}, port, clock, gate)
        return RoomPrivilegeSweepDrainRuntime(ApplicationProvider.getApplicationContext(), cutover, PrivilegeSweepReconciler(port, clock, gate, packageOperationCoordinator = DefaultPackageOperationCoordinator()), verifier, port,
            object : PrivilegeStateProvider { override val state = privilege }, PrivilegeSweepItemExecutor(execute), clock, Dispatchers.IO,
            wakeLockFactory = { ForegroundTaskWakeLock(ForegroundTaskOwner.PRIVILEGE_SWEEP, ForegroundWakeLockFactory { _, _ -> wake }) })
    }

    private suspend fun drain(runtime: PrivilegeSweepDrainRuntime, onClaimed: (String) -> Unit = {}) {
        val finished = CompletableDeferred<Unit>()
        val checked = object : PrivilegeSweepDrainRuntime by runtime {
            override suspend fun settleClaim(claim: ClaimedPrivilegeSweepRequest): Boolean {
                assertTrue(owners.isLive(claim.requestId, claim.claimToken))
                return runtime.settleClaim(claim).also { assertTrue(it) }
            }
        }
        val coordinator = PrivilegeSweepDrainCoordinator(Dispatchers.IO, owners, checked)
        try {
            coordinator.wake(onClaimed = { _, pkg -> onClaimed(pkg) }, onAborted = { finished.completeExceptionally(AssertionError("unexpected abort")) }) { finished.complete(Unit) }
            withTimeout(10_000) { finished.await() }
        } finally { coordinator.shutdown() }
    }

    private class RecordingWakeLock : ForegroundWakeLock {
        override var isHeld = false
        var failAcquire = false
        var renewals = 0
        private var acquisitions = 0
        override fun setReferenceCounted(value: Boolean) = Unit
        override fun acquire(timeoutMillis: Long) {
            if (failAcquire) error("wake acquisition failed")
            assertEquals(ForegroundTaskWakeLock.LEASE_MILLIS, timeoutMillis)
            acquisitions++
            if (acquisitions % 2 == 0) renewals++
            isHeld = true
        }
        override fun release() { isHeld = false }
    }
}
