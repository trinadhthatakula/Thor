// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.freezer

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.data.repository.RoomPrivilegeSweepStore
import com.valhalla.thor.data.source.local.room.AppDatabase
import com.valhalla.thor.domain.model.*
import com.valhalla.thor.domain.repository.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Framework service, real Room, and a controlled executor; no root command or physical-device work. */
@RunWith(AndroidJUnit4::class)
class PrivilegeSweepServiceIntegrationTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var store: RoomPrivilegeSweepStore
    private val owners = PrivilegeSweepOwnerRegistry()
    private val entered = ConcurrentHashMap<UUID, CompletableDeferred<Unit>>()
    private val release = ConcurrentHashMap<UUID, CompletableDeferred<Unit>>()
    private val cleanup = ConcurrentHashMap<UUID, CompletableDeferred<Unit>>()
    private val cleanupEntered = ConcurrentHashMap<UUID, CompletableDeferred<Unit>>()
    private val clock = PrivilegeSweepClock { System.currentTimeMillis() }
    private var nextCreatedAtMillis = System.currentTimeMillis()

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNull("isolated test must not boot production Koin", GlobalContext.getOrNull())
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}").close()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        store = RoomPrivilegeSweepStore(db.privilegeSweepDao())
        val gate = PrivilegeSweepProcessGate()
        val wm = object : PrivilegeSweepWorkManager {
            override suspend fun enqueue(work: androidx.work.OneTimeWorkRequest) = error("unused")
            override fun observeState(workId: UUID) = flowOf<SweepWorkState?>(null)
            override suspend fun currentState(workId: UUID): SweepWorkState? = null
        }
        val reconciler = PrivilegeSweepReconciler(store, wm, clock, gate)
        val verifier = PrivilegeSweepReinstallPostconditionVerifier { _, _, _, _ -> ReinstallPostcondition.UNKNOWN }
        val runtime = RoomPrivilegeSweepDrainRuntime(context,
            PrivilegeSweepWorkManagerCutover(LegacyPrivilegeSweepExecutionFence(), SweepQueueWorkManager {}, store, clock, gate),
            reconciler, verifier, store,
            object : PrivilegeStateProvider { override val state = MutableStateFlow(PrivilegeState(root = true, active = PrivilegeMode.ROOT, isReady = true)) },
            PrivilegeSweepItemExecutor { snapshot, _ ->
                entered.getValue(snapshot.requestId).complete(Unit)
                try {
                    release.getValue(snapshot.requestId).await()
                    PrivilegeSweepItemExecutionResult(SweepAttemptOutcome.SUCCEEDED, false)
                }
                finally {
                    cleanupEntered.getValue(snapshot.requestId).complete(Unit)
                    withContext(NonCancellable) { cleanup.getValue(snapshot.requestId).await() }
                }
            }, clock, Dispatchers.IO)
        val wake = PrivilegeQueueWakeSignal { id -> context.startForegroundService(PrivilegeSweepService.intent(context, id)); com.valhalla.thor.data.service.ServiceStartResult.Requested }
        val cancellation = PrivilegeSweepCancellationCoordinator(RoomPrivilegeSweepCancellationActions(store, owners, wake, reconciler, verifier, clock))
        startKoin { modules(module {
            single<PrivilegeSweepStore> { store }
            single<CoroutineDispatcher>(named("io")) { Dispatchers.IO }
            single<CoroutineDispatcher>(named("main")) { Dispatchers.Main }
            single { cancellation }
            single { gate }
            factory { PrivilegeSweepDrainCoordinator(Dispatchers.IO, owners, runtime) }
        }) }
    }

    @After fun close() = runBlocking {
        release.values.forEach { it.complete(Unit) }
        cleanup.values.forEach { it.complete(Unit) }
        context.stopService(Intent(context, PrivilegeSweepService::class.java))
        eventually { !PrivilegeSweepService.isRunning && owners.ownedRequestForTest() == null }
        stopKoin()
        db.close()
    }

    @Test fun repeatWakeRetainsActiveNotificationAndCancelTargetsOnlyDisplayedRequest() = runBlocking {
        val a = create()
        val b = create()
        start(a)
        awaitEntered(a, "A")
        val original = notification()
        val cancelA = original.actions.single().actionIntent
        assertTrue(cancelA.isImmutable)
        start(b)
        assertNotNull("repeat wake changed A cancellation identity", withTimeoutOrNull(10_000) {
            while (notification().actions.single().actionIntent != cancelA) delay(20)
            true
        })
        assertEquals(original.extras.getCharSequence(Notification.EXTRA_TEXT).toString(), notification().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        cancelA.send()
        assertNotNull("cancel A did not admit B: A=${store.load(a)} B=${store.load(b)} owner=${owners.ownedRequestForTest()}",
            withTimeoutOrNull(10_000) { entered.getValue(b).await(); true })
        assertEquals(StoredSweepTerminal.CANCELLED, store.load(a)?.terminalState)
        assertEquals(PrivilegeSweepRequestState.RUNNING, store.load(b)?.requestState)
        val cancelB = notification().actions.single().actionIntent
        assertNotEquals(cancelA, cancelB)
        cancelA.send()
        delay(100)
        assertEquals(PrivilegeSweepRequestState.RUNNING, store.load(b)?.requestState)
        release.getValue(b).complete(Unit)
        eventually { store.load(b)?.terminalState == StoredSweepTerminal.SUCCEEDED }
    }

    @Test fun requestlessStickyWakeDrainsQueueAndPreservesActiveIdentity() = runBlocking {
        val a = create()
        startWithoutRequest()
        awaitEntered(a, "A")
        val original = notification()
        val cancelA = original.actions.single().actionIntent

        startWithoutRequest()
        assertNotNull("requestless repeat wake replaced A cancellation identity", withTimeoutOrNull(10_000) {
            while (notification().actions.single().actionIntent != cancelA) delay(20)
            true
        })
        assertEquals(
            original.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
            notification().extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )

        release.getValue(a).complete(Unit)
        eventually { !PrivilegeSweepService.isRunning }
        assertEquals(StoredSweepTerminal.SUCCEEDED, store.load(a)?.terminalState)
    }

    @Test fun frameworkDestructionCancelsChildButRetainsOwnershipThroughNonCancellableCleanup() = runBlocking {
        val a = create(holdCleanup = true)
        val b = create()
        start(a)
        awaitEntered(a, "A")
        context.stopService(Intent(context, PrivilegeSweepService::class.java))
        assertNotNull("A cleanup did not start", withTimeoutOrNull(10_000) { cleanupEntered.getValue(a).await(); true })
        assertEquals(a, owners.ownedRequestForTest())
        assertFalse(entered.getValue(b).isCompleted)
        assertEquals(PrivilegeSweepRequestState.QUEUED, store.load(b)?.requestState)
        cleanup.getValue(a).complete(Unit)
        eventually { owners.ownedRequestForTest() == null }
        val row = requireNotNull(store.load(a))
        assertEquals(PrivilegeSweepRequestState.BLOCKED, row.requestState)
        assertEquals(PrivilegeSweepTargetState.UNKNOWN, row.targetSnapshots.single().state)
        assertFalse(entered.getValue(b).isCompleted)
        start(b)
        awaitEntered(b, "B")
        release.getValue(b).complete(Unit)
        eventually { store.load(b)?.terminalState == StoredSweepTerminal.SUCCEEDED }
    }

    @Test fun frameworkConstructsAndImmediatelyPromotesBeforeFirstExecution() = runBlocking {
        val a = create()
        start(a)
        awaitEntered(a, "A")
        assertTrue(PrivilegeSweepService.isRunning)
        val posted = notification()
        assertEquals("thor.jobs.privileged", posted.channelId)
        assertEquals(Notification.FLAG_FOREGROUND_SERVICE, posted.flags and Notification.FLAG_FOREGROUND_SERVICE)
        release.getValue(a).complete(Unit)
        eventually { !PrivilegeSweepService.isRunning }
        assertEquals(StoredSweepTerminal.SUCCEEDED, store.load(a)?.terminalState)
    }

    private suspend fun create(holdCleanup: Boolean = false): UUID {
        val id = UUID.randomUUID()
        entered[id] = CompletableDeferred()
        release[id] = CompletableDeferred()
        cleanupEntered[id] = CompletableDeferred()
        cleanup[id] = CompletableDeferred<Unit>().also { if (!holdCleanup) it.complete(Unit) }
        store.createOrFindEquivalent(NewPrivilegeSweepSnapshot(id, newPrivilegeServiceExecutionId(), PrivilegeSweepOperation.CLEAR_CACHE, null, 0, PrivilegeSweepSource.MAIN, nextCreatedAtMillis++, listOf("com.test.${id.toString().replace('-', '_')}")))
        return id
    }
    private fun start(id: UUID) { context.startForegroundService(PrivilegeSweepService.intent(context, id)) }
    private fun startWithoutRequest() {
        context.startForegroundService(Intent(context, PrivilegeSweepService::class.java))
    }
    private suspend fun notification(): Notification {
        var posted: Notification? = null
        withTimeoutOrNull(10_000) {
            while (posted == null) {
                posted = context.getSystemService(NotificationManager::class.java).activeNotifications
                    .firstOrNull { it.id == PrivilegeSweepServiceNotification.NOTIFICATION_ID }
                    ?.notification
                if (posted == null) delay(20)
            }
        }
        return requireNotNull(posted) { "privilege foreground notification was not posted" }
    }
    private suspend fun awaitEntered(id: UUID, label: String) {
        val admitted = withTimeoutOrNull(10_000) { entered.getValue(id).await(); true }
        assertNotNull(
            "$label did not enter: row=${store.load(id)} owner=${owners.ownedRequestForTest()} " +
                "entered=${entered.filterValues { it.isCompleted }.keys}",
            admitted,
        )
    }
    private suspend fun eventually(test: suspend () -> Boolean) {
        withTimeout(10_000) { while (!test()) delay(20) }
    }
}
