// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import com.valhalla.thor.domain.model.FreezeState
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.PrivilegeExecutionTimeouts
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.repository.PackageOperationCoordinator
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import com.valhalla.thor.domain.repository.SweepAttemptOutcome
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.presentation.FakeSystemRepository
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeSweepItemExecutorTest {

    @Test
    fun `freeze dispatches configured disable action with complete sweep context`() = runTest {
        val trace = mutableListOf<String>()
        val repository = FakeSystemRepository(trace)
        val executor = executor(repository, FakeStateReader(FreezeState.ACTIVE, trace))
        val snapshot = stored(
            operation = PrivilegeSweepOperation.FREEZE,
            freezerMode = FreezerMode.FREEZE,
        )

        val outcome = executor.execute(snapshot, PACKAGE)

        assertEquals(SweepAttemptOutcome.SUCCEEDED, outcome)
        assertEquals(listOf("state:$PACKAGE", "setAppDisabled:$PACKAGE:true"), trace)
        assertSweepExecution(
            snapshot,
            PrivilegeCommandClass("sweep.freeze"),
            repository.executions.single().second,
        )
    }

    @Test
    fun `freeze dispatches configured suspend action`() = runTest {
        val repository = FakeSystemRepository()
        val snapshot = stored(
            operation = PrivilegeSweepOperation.FREEZE,
            freezerMode = FreezerMode.SUSPEND,
        )

        val outcome = executor(repository).execute(snapshot, PACKAGE)

        assertEquals(SweepAttemptOutcome.SUCCEEDED, outcome)
        assertEquals(listOf("setAppSuspended:$PACKAGE:true"), repository.calls)
        assertSweepExecution(
            snapshot,
            PrivilegeCommandClass("sweep.freeze"),
            repository.executions.single().second,
        )
    }

    @Test
    fun `unfreeze uses one composite force unfreeze operation`() = runTest {
        val repository = FakeSystemRepository()
        val snapshot = stored(operation = PrivilegeSweepOperation.UNFREEZE)

        val outcome = executor(repository, FakeStateReader(FreezeState.FROZEN))
            .execute(snapshot, PACKAGE)

        assertEquals(SweepAttemptOutcome.SUCCEEDED, outcome)
        assertEquals(
            listOf(
                "setAppSuspended:$PACKAGE:false",
                "setAppDisabled:$PACKAGE:false",
            ),
            repository.calls,
        )
        assertEquals(2, repository.executions.size)
        repository.executions.forEach { (_, execution) ->
            assertSweepExecution(snapshot, PrivilegeCommandClass("sweep.unfreeze"), execution)
        }
    }

    @Test
    fun `cache clear and reinstall dispatch their admitted actions`() = runTest {
        val cases = listOf(
            PrivilegeSweepOperation.CLEAR_CACHE to "clearCache:$PACKAGE",
            PrivilegeSweepOperation.REINSTALL to "reinstallAppWithGoogle:$PACKAGE",
        )

        cases.forEach { (operation, expectedCall) ->
            val repository = FakeSystemRepository()
            val snapshot = stored(operation = operation)

            val outcome = executor(repository).execute(snapshot, PACKAGE)

            assertEquals(operation.name, SweepAttemptOutcome.SUCCEEDED, outcome)
            assertEquals(operation.name, listOf(expectedCall), repository.calls)
            assertSweepExecution(
                snapshot,
                PrivilegeCommandClass(
                    when (operation) {
                        PrivilegeSweepOperation.CLEAR_CACHE -> "sweep.clear_cache"
                        PrivilegeSweepOperation.REINSTALL -> "sweep.reinstall"
                        else -> error("unexpected operation")
                    }
                ),
                repository.executions.single().second,
            )
        }
    }

    @Test
    fun `already converged freeze and unfreeze succeed without mutation`() = runTest {
        val cases = listOf(
            stored(
                operation = PrivilegeSweepOperation.FREEZE,
                freezerMode = FreezerMode.FREEZE,
            ) to FreezeState.FROZEN,
            stored(operation = PrivilegeSweepOperation.UNFREEZE) to FreezeState.ACTIVE,
        )

        cases.forEach { (snapshot, state) ->
            val repository = FakeSystemRepository()

            val outcome = executor(repository, FakeStateReader(state)).execute(snapshot, PACKAGE)

            assertEquals(SweepAttemptOutcome.SUCCEEDED, outcome)
            assertTrue(repository.calls.isEmpty())
        }
    }

    @Test
    fun `package lease encloses state revalidation and mutation`() = runTest {
        val trace = mutableListOf<String>()
        val repository = FakeSystemRepository(trace)
        val coordinator = TestPackageOperationCoordinator(trace = trace)
        val snapshot = stored(operation = PrivilegeSweepOperation.CLEAR_CACHE)

        val outcome = executor(
            repository = repository,
            stateReader = FakeStateReader(FreezeState.ACTIVE, trace),
            coordinator = coordinator,
        ).execute(snapshot, PACKAGE)

        assertEquals(SweepAttemptOutcome.SUCCEEDED, outcome)
        assertEquals(
            listOf(
                "lease:start:${PackageOperationOwner.CLEAR_CACHE}",
                "state:$PACKAGE",
                "clearCache:$PACKAGE",
                "lease:end:${PackageOperationOwner.CLEAR_CACHE}",
            ),
            trace,
        )
        assertEquals(1, coordinator.leaseCalls)
    }

    @Test
    fun `lease contention wins over converged or absent state`() = runTest {
        val cases = listOf(
            stored(
                operation = PrivilegeSweepOperation.FREEZE,
                freezerMode = FreezerMode.FREEZE,
            ) to FreezeState.FROZEN,
            stored(operation = PrivilegeSweepOperation.CLEAR_CACHE) to FreezeState.ABSENT,
        )

        cases.forEach { (snapshot, state) ->
            val trace = mutableListOf<String>()
            val repository = FakeSystemRepository(trace)
            val coordinator = TestPackageOperationCoordinator(
                busyOwner = PackageOperationOwner.ARCHIVE_BACKUP,
                trace = trace,
            )

            val outcome = executor(
                repository = repository,
                stateReader = FakeStateReader(state, trace),
                coordinator = coordinator,
            ).execute(snapshot, PACKAGE)

            assertEquals(snapshot.operation.name, SweepAttemptOutcome.BUSY, outcome)
            assertEquals(snapshot.operation.name, 1, coordinator.leaseCalls)
            assertTrue(snapshot.operation.name, trace.isEmpty())
            assertTrue(snapshot.operation.name, repository.calls.isEmpty())
        }
    }

    @Test
    fun `absent package fails state-dependent operations without mutation`() = runTest {
        val snapshots = listOf(
            stored(
                operation = PrivilegeSweepOperation.FREEZE,
                freezerMode = FreezerMode.FREEZE,
            ),
            stored(operation = PrivilegeSweepOperation.UNFREEZE),
            stored(operation = PrivilegeSweepOperation.CLEAR_CACHE),
        )

        snapshots.forEach { snapshot ->
            val repository = FakeSystemRepository()

            val outcome = executor(repository, FakeStateReader(FreezeState.ABSENT))
                .execute(snapshot, PACKAGE)

            assertEquals(snapshot.operation.name, SweepAttemptOutcome.FAILED, outcome)
            assertTrue(snapshot.operation.name, repository.calls.isEmpty())
        }
    }

    @Test
    fun `reinstall still dispatches after an absent state read`() = runTest {
        val trace = mutableListOf<String>()
        val repository = FakeSystemRepository(trace)

        val outcome = executor(repository, FakeStateReader(FreezeState.ABSENT, trace))
            .execute(stored(operation = PrivilegeSweepOperation.REINSTALL), PACKAGE)

        assertEquals(SweepAttemptOutcome.SUCCEEDED, outcome)
        assertEquals(listOf("state:$PACKAGE", "reinstallAppWithGoogle:$PACKAGE"), trace)
    }

    @Test
    fun `package lease contention becomes busy`() = runTest {
        val repository = FakeSystemRepository()
        val coordinator = TestPackageOperationCoordinator(PackageOperationOwner.ARCHIVE_BACKUP)

        val outcome = executor(
            repository = repository,
            coordinator = coordinator,
        ).execute(stored(operation = PrivilegeSweepOperation.CLEAR_CACHE), PACKAGE)

        assertEquals(SweepAttemptOutcome.BUSY, outcome)
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun `ordinary failed result and thrown exception become failed attempts`() = runTest {
        val failedResultRepository = FakeSystemRepository().apply {
            failWith("clearCache:$PACKAGE", IllegalStateException("failed result"))
        }
        val thrownRepository = FakeSystemRepository().apply {
            onCall = { throw IllegalArgumentException("thrown") }
        }
        val snapshot = stored(operation = PrivilegeSweepOperation.CLEAR_CACHE)

        assertEquals(
            SweepAttemptOutcome.FAILED,
            executor(failedResultRepository).execute(snapshot, PACKAGE),
        )
        assertEquals(
            SweepAttemptOutcome.FAILED,
            executor(thrownRepository).execute(snapshot, PACKAGE),
        )
    }

    @Test
    fun `direct and result wrapped cancellation are rethrown unchanged`() = runTest {
        val wrapped = CancellationException("wrapped")
        val wrappedRepository = FakeSystemRepository().apply {
            failWith("clearCache:$PACKAGE", wrapped)
        }
        val direct = CancellationException("direct")
        val directRepository = FakeSystemRepository().apply {
            onCall = { throw direct }
        }
        val snapshot = stored(operation = PrivilegeSweepOperation.CLEAR_CACHE)

        val wrappedThrown = runCatching {
            executor(wrappedRepository).execute(snapshot, PACKAGE)
        }.exceptionOrNull()
        val directThrown = runCatching {
            executor(directRepository).execute(snapshot, PACKAGE)
        }.exceptionOrNull()

        assertSame(wrapped, wrappedThrown)
        assertSame(direct, directThrown)
    }

    private fun executor(
        repository: FakeSystemRepository,
        stateReader: PrivilegeSweepPackageStateReader = FakeStateReader(FreezeState.ACTIVE),
        coordinator: PackageOperationCoordinator = TestPackageOperationCoordinator(),
    ) = DefaultPrivilegeSweepItemExecutor(
        manageApp = ManageAppUseCase(repository, coordinator),
        stateReader = stateReader,
    )

    private fun assertSweepExecution(
        snapshot: StoredPrivilegeSweep,
        commandClass: PrivilegeCommandClass,
        execution: com.valhalla.thor.domain.model.PrivilegeExecutionContext,
    ) {
        assertEquals(PrivilegeExecutionLane.SWEEP, execution.lane)
        assertEquals(commandClass, execution.commandClass)
        assertEquals(PACKAGE, execution.packageName)
        assertEquals(snapshot.workId, execution.workRequestId)
        assertEquals(snapshot.requestId, execution.sweepRequestId)
        assertEquals(
            PrivilegeExecutionTimeouts.SWEEP_COMMAND,
            checkNotNull(execution.commandTimeout),
        )
    }

    private fun stored(
        operation: PrivilegeSweepOperation,
        freezerMode: FreezerMode? = null,
    ) = StoredPrivilegeSweep(
        requestId = UUID.randomUUID(),
        workId = UUID.randomUUID(),
        operation = operation,
        freezerMode = freezerMode,
        userId = 0,
        source = PrivilegeSweepSource.MAIN,
        createdAtEpochMs = 1L,
        targets = listOf(PACKAGE),
        terminalState = null,
        succeeded = 0,
        failed = 0,
        busy = 0,
        unresolved = 0,
        terminalAtEpochMs = null,
        retainUntilEpochMs = null,
    )

    private class FakeStateReader(
        private val state: FreezeState,
        private val trace: MutableList<String>? = null,
    ) : PrivilegeSweepPackageStateReader {
        override fun stateOf(packageName: String): FreezeState {
            trace?.add("state:$packageName")
            return state
        }
    }

    private class TestPackageOperationCoordinator(
        private val busyOwner: PackageOperationOwner? = null,
        private val trace: MutableList<String>? = null,
    ) : PackageOperationCoordinator {
        var leaseCalls = 0
            private set

        override suspend fun <T> withPackageLease(
            packageName: String,
            owner: PackageOperationOwner,
            admissionTimeout: Duration,
            block: suspend () -> T,
        ): PackageLeaseResult<T> {
            leaseCalls++
            busyOwner?.let { return PackageLeaseResult.Busy(it) }
            trace?.add("lease:start:$owner")
            return try {
                PackageLeaseResult.Acquired(block())
            } finally {
                trace?.add("lease:end:$owner")
            }
        }
    }

    private companion object {
        const val PACKAGE = "com.example.app"
    }
}
