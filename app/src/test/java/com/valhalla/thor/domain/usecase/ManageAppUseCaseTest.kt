// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.gateway.ComponentEnabledState
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.model.PackageOperationBusy
import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.PrivilegeExecutionTimeouts
import com.valhalla.thor.domain.repository.PackageOperationCoordinator
import com.valhalla.thor.domain.repository.SystemRepository
import java.lang.reflect.InvocationTargetException
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PACKAGE_NAME = "com.example.app"

class ManageAppUseCaseTest {

    @Test
    fun `coordinator is a required non-null constructor dependency`() {
        val constructor = ManageAppUseCase::class.java.getDeclaredConstructor(
            SystemRepository::class.java,
            PackageOperationCoordinator::class.java,
        )
        val repository = RecordingManageSystemRepository { null }

        val failure = runCatching {
            constructor.newInstance(repository, null)
        }.exceptionOrNull()

        assertTrue(
            "a null coordinator must be rejected by the constructor",
            failure is InvocationTargetException && failure.cause is NullPointerException,
        )
        assertFalse(
            "a default coordinator would make Koin resolution optional",
            ManageAppUseCase::class.java.declaredConstructors.any { candidate ->
                candidate.parameterTypes.lastOrNull()?.name ==
                        "kotlin.jvm.internal.DefaultConstructorMarker"
            },
        )
    }

    @Test
    fun `forceUnfreeze holds one UNFREEZE lease across unsuspend and enable`() = runTest {
        val coordinator = RecordingPackageOperationCoordinator()
        val repository = RecordingManageSystemRepository { coordinator.currentOwner }
        val useCase = ManageAppUseCase(repository, coordinator)

        val result = useCase.forceUnfreeze(PACKAGE_NAME)

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                LeaseCall(
                    PACKAGE_NAME,
                    PackageOperationOwner.UNFREEZE,
                    PrivilegeExecutionTimeouts.INTERACTIVE_ADMISSION,
                )
            ),
            coordinator.calls,
        )
        assertEquals(
            listOf(
                "setAppSuspended($PACKAGE_NAME, false)",
                "setAppDisabled($PACKAGE_NAME, false)",
            ),
            repository.calls,
        )
    }

    @Test
    fun `ordinary package mutations each acquire one owner-specific lease`() = runTest {
        val coordinator = RecordingPackageOperationCoordinator()
        val repository = RecordingManageSystemRepository { coordinator.currentOwner }
        val useCase = ManageAppUseCase(repository, coordinator)

        useCase.forceStop(PACKAGE_NAME).getOrThrow()
        useCase.clearCache(PACKAGE_NAME).getOrThrow()
        useCase.clearAppData(PACKAGE_NAME).getOrThrow()
        useCase.setAppDisabled(PACKAGE_NAME, true).getOrThrow()
        useCase.setAppDisabled(PACKAGE_NAME, false).getOrThrow()
        useCase.setAppSuspended(PACKAGE_NAME, true).getOrThrow()
        useCase.setAppSuspended(PACKAGE_NAME, false).getOrThrow()
        useCase.restoreApp(PACKAGE_NAME, enabled = false, isSuspended = true).getOrThrow()
        useCase.forceUnfreeze(PACKAGE_NAME).getOrThrow()
        useCase.uninstallApp(PACKAGE_NAME).getOrThrow()
        useCase.reinstallAppWithGoogle(PACKAGE_NAME).getOrThrow()

        assertEquals(
            listOf(
                PackageOperationOwner.FORCE_STOP,
                PackageOperationOwner.CLEAR_CACHE,
                PackageOperationOwner.CLEAR_DATA,
                PackageOperationOwner.FREEZE,
                PackageOperationOwner.UNFREEZE,
                PackageOperationOwner.FREEZE,
                PackageOperationOwner.UNFREEZE,
                PackageOperationOwner.UNFREEZE,
                PackageOperationOwner.UNFREEZE,
                PackageOperationOwner.UNINSTALL,
                PackageOperationOwner.REINSTALL,
            ),
            coordinator.calls.map(LeaseCall::owner),
        )
        assertTrue(
            coordinator.calls.all {
                it.admissionTimeout == PrivilegeExecutionTimeouts.INTERACTIVE_ADMISSION
            }
        )
    }

    @Test
    fun `execution lane selects its stable admission timeout`() = runTest {
        val coordinator = RecordingPackageOperationCoordinator()
        val repository = RecordingManageSystemRepository { coordinator.currentOwner }
        val useCase = ManageAppUseCase(repository, coordinator)

        useCase.forceStop(
            PACKAGE_NAME,
            PrivilegeExecutionContext(lane = PrivilegeExecutionLane.SWEEP),
        ).getOrThrow()
        useCase.clearAppData(
            PACKAGE_NAME,
            PrivilegeExecutionContext(lane = PrivilegeExecutionLane.ARCHIVE),
        ).getOrThrow()

        assertEquals(
            listOf(
                PrivilegeExecutionTimeouts.SWEEP_ADMISSION,
                PrivilegeExecutionTimeouts.ARCHIVE_ADMISSION,
            ),
            coordinator.calls.map(LeaseCall::admissionTimeout),
        )
    }

    @Test
    fun `busy lease becomes PackageOperationBusy without touching repository`() = runTest {
        val coordinator = RecordingPackageOperationCoordinator(
            busyOwner = PackageOperationOwner.ARCHIVE_RESTORE
        )
        val repository = RecordingManageSystemRepository { coordinator.currentOwner }
        val useCase = ManageAppUseCase(repository, coordinator)

        val failure = useCase.forceStop(PACKAGE_NAME).exceptionOrNull()

        assertTrue("expected PackageOperationBusy, got $failure", failure is PackageOperationBusy)
        assertSame(
            PackageOperationOwner.ARCHIVE_RESTORE,
            (failure as PackageOperationBusy).owner,
        )
        assertEquals(emptyList<String>(), repository.calls)
    }
}

private data class LeaseCall(
    val packageName: String,
    val owner: PackageOperationOwner,
    val admissionTimeout: Duration,
)

private class RecordingPackageOperationCoordinator(
    private val busyOwner: PackageOperationOwner? = null,
) : PackageOperationCoordinator {
    val calls = mutableListOf<LeaseCall>()
    var currentOwner: PackageOperationOwner? = null
        private set

    override suspend fun <T> withPackageLease(
        packageName: String,
        owner: PackageOperationOwner,
        admissionTimeout: Duration,
        block: suspend () -> T,
    ): PackageLeaseResult<T> {
        check(currentOwner == null) { "ManageAppUseCase attempted a nested public lease" }
        calls += LeaseCall(packageName, owner, admissionTimeout)
        busyOwner?.let { return PackageLeaseResult.Busy(it) }
        currentOwner = owner
        return try {
            PackageLeaseResult.Acquired(block())
        } finally {
            currentOwner = null
        }
    }
}

private class RecordingManageSystemRepository(
    private val currentOwner: () -> PackageOperationOwner?,
) : SystemRepository {
    val calls = mutableListOf<String>()

    private fun record(call: String): Result<Unit> {
        check(currentOwner() != null) { "$call ran outside a package lease" }
        calls += call
        return Result.success(Unit)
    }

    override suspend fun forceStopApp(packageName: String): Result<Unit> =
        record("forceStopApp($packageName)")

    override suspend fun clearCache(packageName: String): Result<Long?> {
        record("clearCache($packageName)").getOrThrow()
        return Result.success(0L)
    }

    override suspend fun clearAppData(packageName: String): Result<Unit> =
        record("clearAppData($packageName)")

    override suspend fun setAppDisabled(
        packageName: String,
        isDisabled: Boolean,
    ): Result<Unit> = record("setAppDisabled($packageName, $isDisabled)")

    override suspend fun setAppSuspended(
        packageName: String,
        isSuspended: Boolean,
    ): Result<Unit> = record("setAppSuspended($packageName, $isSuspended)")

    override suspend fun uninstallApp(packageName: String): Result<Unit> =
        record("uninstallApp($packageName)")

    override suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit> =
        record("reinstallAppWithGoogle($packageName)")

    override suspend fun isRootAvailable(): Boolean = error("off the manage-app path")
    override suspend fun isShizukuAvailable(): Boolean = error("off the manage-app path")
    override suspend fun isDhizukuAvailable(): Boolean = error("off the manage-app path")
    override suspend fun clearAllCaches(): Result<Long?> = error("off the manage-app path")
    override suspend fun setAppRestricted(
        packageName: String,
        isRestricted: Boolean,
    ): Result<Unit> = error("off the manage-app path")

    override suspend fun rebootDevice(reason: String): Result<Unit> =
        error("off the manage-app path")

    override suspend fun copyFileWithRoot(
        sourcePath: String,
        destinationPath: String,
    ): Result<Unit> = error("off the manage-app path")

    override suspend fun getAppPaths(packageName: String): Result<List<String>> =
        error("off the manage-app path")

    override suspend fun grantPermission(
        packageName: String,
        permissionName: String,
    ): Result<Unit> = error("off the manage-app path")

    override suspend fun revokePermission(
        packageName: String,
        permissionName: String,
    ): Result<Unit> = error("off the manage-app path")

    override suspend fun setComponentEnabled(
        packageName: String,
        className: String,
        state: ComponentEnabledState,
    ): Result<Unit> = error("off the manage-app path")

    override suspend fun forceLaunchActivity(
        packageName: String,
        className: String,
    ): Result<Unit> = error("off the manage-app path")

    override suspend fun stopService(
        packageName: String,
        className: String,
    ): Result<Unit> = error("off the manage-app path")

    override suspend fun executeShellCommand(command: String): Result<Pair<Int, String?>> =
        error("off the manage-app path")

    override suspend fun probeObb(packageName: String): ObbProbe = error("off the manage-app path")
}
