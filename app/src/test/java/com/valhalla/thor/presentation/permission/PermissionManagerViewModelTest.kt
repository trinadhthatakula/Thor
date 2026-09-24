// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.permission

import com.valhalla.thor.domain.model.AppOpDefinition
import com.valhalla.thor.domain.model.AppOpEntry
import com.valhalla.thor.domain.model.AppOpMode
import com.valhalla.thor.domain.model.AppOpScope
import com.valhalla.thor.domain.model.AppOpsSnapshot
import com.valhalla.thor.domain.model.AppPermission
import com.valhalla.thor.domain.model.PermissionIndex
import com.valhalla.thor.domain.repository.AppOpsRepository
import com.valhalla.thor.domain.repository.PermissionRepository
import com.valhalla.thor.domain.usecase.GetAppPermissionsUseCase
import com.valhalla.thor.domain.usecase.TogglePermissionUseCase
import com.valhalla.thor.presentation.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionManagerViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun `App Ops mode stays at read-back value while write is pending`() = runTest {
        val appOps = FakeAppOpsRepository(snapshot(AppOpMode.ALLOW))
        val vm = viewModel(appOps)
        vm.loadPermissions(PACKAGE, "Example")
        vm.loadAppOps()
        advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        appOps.writeGate = gate
        vm.setAppOpMode(OP_CODE, AppOpScope.PACKAGE, AppOpMode.IGNORE)
        runCurrent()

        assertEquals(OP_CODE, vm.uiState.value.savingAppOpCode)
        assertEquals(AppOpMode.ALLOW, vm.uiState.value.appOpsSnapshot!!.entries.single().displayedMode)

        appOps.current = snapshot(AppOpMode.IGNORE)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(Triple(OP_CODE, AppOpScope.PACKAGE, AppOpMode.IGNORE)), appOps.writes)
        assertNull(vm.uiState.value.savingAppOpCode)
        assertEquals(AppOpMode.IGNORE, vm.uiState.value.appOpsSnapshot!!.entries.single().displayedMode)
    }

    @Test
    fun `reset invokes the separate reset operation and then re-reads`() = runTest {
        val appOps = FakeAppOpsRepository(snapshot(AppOpMode.IGNORE))
        val vm = viewModel(appOps)
        vm.loadPermissions(PACKAGE, "Example")
        vm.loadAppOps()
        advanceUntilIdle()

        appOps.current = snapshot(null)
        vm.resetAppOpMode(OP_CODE, AppOpScope.PACKAGE)
        advanceUntilIdle()

        assertEquals(listOf(OP_CODE to AppOpScope.PACKAGE), appOps.resets)
        assertTrue(appOps.writes.isEmpty())
        assertEquals(AppOpMode.ALLOW, vm.uiState.value.appOpsSnapshot!!.entries.single().displayedMode)
    }

    @Test
    fun `failed write hides the last mode because the command may have applied`() = runTest {
        val appOps = FakeAppOpsRepository(snapshot(AppOpMode.ALLOW))
        val vm = viewModel(appOps)
        vm.loadPermissions(PACKAGE, "Example")
        vm.loadAppOps()
        advanceUntilIdle()

        appOps.writeFailure = IllegalStateException("refused")
        vm.setAppOpMode(OP_CODE, AppOpScope.PACKAGE, AppOpMode.DENY)
        advanceUntilIdle()

        assertNull(vm.uiState.value.savingAppOpCode)
        assertEquals(1, appOps.reads)
        assertNull(vm.uiState.value.appOpsSnapshot)
        assertTrue(vm.uiState.value.appOpsStatusUncertain)
    }

    @Test
    fun `failed read-back marks previously shown modes stale`() = runTest {
        val appOps = FakeAppOpsRepository(snapshot(AppOpMode.ALLOW))
        val vm = viewModel(appOps)
        vm.loadPermissions(PACKAGE, "Example")
        vm.loadAppOps()
        advanceUntilIdle()

        appOps.readFailure = IllegalStateException("read failed")
        vm.setAppOpMode(OP_CODE, AppOpScope.PACKAGE, AppOpMode.IGNORE)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.appOpsLoadFailed)
        assertTrue(vm.uiState.value.appOpsStatusUncertain)
        assertNull(vm.uiState.value.appOpsSnapshot)
    }

    @Test
    fun `failed refresh leaves prior status visible but blocks changes until retry`() = runTest {
        val appOps = FakeAppOpsRepository(snapshot(AppOpMode.ALLOW))
        val vm = viewModel(appOps)
        vm.loadPermissions(PACKAGE, "Example")
        vm.loadAppOps()
        advanceUntilIdle()

        appOps.readFailure = IllegalStateException("read failed")
        vm.loadAppOps(force = true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.appOpsLoadFailed)
        assertEquals(AppOpMode.ALLOW, vm.uiState.value.appOpsSnapshot!!.entries.single().displayedMode)

        vm.setAppOpMode(OP_CODE, AppOpScope.PACKAGE, AppOpMode.IGNORE)
        advanceUntilIdle()
        assertTrue(appOps.writes.isEmpty())

        appOps.readFailure = null
        appOps.current = snapshot(AppOpMode.IGNORE)
        vm.loadAppOps(force = true)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.appOpsLoadFailed)
        assertEquals(AppOpMode.IGNORE, vm.uiState.value.appOpsSnapshot!!.entries.single().displayedMode)
    }

    @Test
    fun `in-flight refresh cannot race a write or replace its read-back`() = runTest {
        val appOps = FakeAppOpsRepository(snapshot(AppOpMode.ALLOW))
        val vm = viewModel(appOps)
        vm.loadPermissions(PACKAGE, "Example")
        vm.loadAppOps()
        advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        appOps.nextReadGate = gate
        vm.loadAppOps(force = true)
        runCurrent()
        vm.setAppOpMode(OP_CODE, AppOpScope.PACKAGE, AppOpMode.IGNORE)
        runCurrent()
        assertTrue(appOps.writes.isEmpty())

        appOps.current = snapshot(AppOpMode.IGNORE)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(AppOpMode.ALLOW, vm.uiState.value.appOpsSnapshot!!.entries.single().displayedMode)

        vm.setAppOpMode(OP_CODE, AppOpScope.PACKAGE, AppOpMode.IGNORE)
        advanceUntilIdle()
        assertEquals(AppOpMode.IGNORE, vm.uiState.value.appOpsSnapshot!!.entries.single().displayedMode)
        assertEquals(3, appOps.reads)
    }

    @Test
    fun `initial read failure requires retry before any write`() = runTest {
        val appOps = FakeAppOpsRepository(snapshot(AppOpMode.ALLOW))
        appOps.readFailure = IllegalStateException("read failed")
        val vm = viewModel(appOps)
        vm.loadPermissions(PACKAGE, "Example")
        vm.loadAppOps()
        advanceUntilIdle()

        assertNull(vm.uiState.value.appOpsSnapshot)
        assertTrue(vm.uiState.value.appOpsLoadFailed)
        vm.setAppOpMode(OP_CODE, AppOpScope.PACKAGE, AppOpMode.IGNORE)
        advanceUntilIdle()
        assertTrue(appOps.writes.isEmpty())
    }

    @Test
    fun `normal and signature permissions cannot reach grant revoke path`() = runTest {
        val permissions = FakePermissionRepository(
            listOf(
                permission("android.permission.INTERNET", isRuntime = false),
                permission("android.permission.CAMERA", isRuntime = true),
            ),
        )
        val vm = viewModel(FakeAppOpsRepository(snapshot(null)), permissions)
        vm.loadPermissions(PACKAGE, "Example")
        advanceUntilIdle()

        vm.togglePermission("android.permission.INTERNET", grant = false)
        vm.togglePermission("android.permission.CAMERA", grant = true)
        advanceUntilIdle()

        assertEquals(listOf("android.permission.CAMERA"), permissions.granted)
        assertTrue(permissions.revoked.isEmpty())
    }

    @Test
    fun `runtime permission change refreshes derived App Ops before returning to the tab`() = runTest {
        val appOps = FakeAppOpsRepository(snapshot(AppOpMode.IGNORE))
        val permissions = FakePermissionRepository(
            listOf(permission("android.permission.CAMERA", isRuntime = true)),
        )
        val vm = viewModel(appOps, permissions)
        vm.loadPermissions(PACKAGE, "Example")
        vm.loadAppOps()
        advanceUntilIdle()

        val refreshedRead = CompletableDeferred<Unit>()
        appOps.nextReadGate = refreshedRead
        appOps.current = snapshot(AppOpMode.ALLOW)
        vm.togglePermission("android.permission.CAMERA", grant = true)
        runCurrent()

        assertTrue(vm.uiState.value.permissions.single().isGranted)
        assertNull(vm.uiState.value.appOpsSnapshot)
        assertTrue(vm.uiState.value.isAppOpsLoading)
        assertFalse(vm.uiState.value.appOpsLoadFailed)
        assertFalse(vm.uiState.value.appOpsStatusUncertain)

        // The tab's ordinary load call joins the new read instead of reusing the old mode.
        vm.loadAppOps()
        refreshedRead.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, appOps.reads)
        assertFalse(vm.uiState.value.isAppOpsLoading)
        assertEquals(AppOpMode.ALLOW, vm.uiState.value.appOpsSnapshot!!.entries.single().displayedMode)
    }

    @Test
    fun `refresh started before a runtime permission change cannot restore its stale mode`() = runTest {
        val appOps = FakeAppOpsRepository(snapshot(AppOpMode.IGNORE))
        val permissions = FakePermissionRepository(
            listOf(permission("android.permission.CAMERA", isRuntime = true)),
        )
        val vm = viewModel(appOps, permissions)
        vm.loadPermissions(PACKAGE, "Example")
        vm.loadAppOps()
        advanceUntilIdle()

        val olderRead = CompletableDeferred<Unit>()
        appOps.nextReadGate = olderRead
        vm.loadAppOps(force = true)
        runCurrent()

        appOps.current = snapshot(AppOpMode.ALLOW)
        vm.togglePermission("android.permission.CAMERA", grant = true)
        advanceUntilIdle()
        assertEquals(AppOpMode.ALLOW, vm.uiState.value.appOpsSnapshot!!.entries.single().displayedMode)

        olderRead.complete(Unit)
        advanceUntilIdle()

        assertEquals(3, appOps.reads)
        assertEquals(AppOpMode.ALLOW, vm.uiState.value.appOpsSnapshot!!.entries.single().displayedMode)
        assertFalse(vm.uiState.value.isAppOpsLoading)
    }

    @Test
    fun `runtime permission change during write readback waits for a fresh snapshot`() = runTest {
        val appOps = FakeAppOpsRepository(snapshot(AppOpMode.IGNORE))
        val permissions = FakePermissionRepository(
            listOf(permission("android.permission.CAMERA", isRuntime = true)),
        )
        val vm = viewModel(appOps, permissions)
        vm.loadPermissions(PACKAGE, "Example")
        vm.loadAppOps()
        advanceUntilIdle()

        val writeReadBack = CompletableDeferred<Unit>()
        appOps.nextReadGate = writeReadBack
        appOps.current = snapshot(AppOpMode.DENY)
        vm.setAppOpMode(OP_CODE, AppOpScope.PACKAGE, AppOpMode.DENY)
        runCurrent()

        appOps.current = snapshot(AppOpMode.ALLOW)
        vm.togglePermission("android.permission.CAMERA", grant = true)
        runCurrent()
        assertNull(vm.uiState.value.appOpsSnapshot)
        assertEquals(OP_CODE, vm.uiState.value.savingAppOpCode)
        assertEquals(2, appOps.reads)

        val freshRead = CompletableDeferred<Unit>()
        appOps.nextReadGate = freshRead
        writeReadBack.complete(Unit)
        runCurrent()

        assertNull(vm.uiState.value.savingAppOpCode)
        assertNull(vm.uiState.value.appOpsSnapshot)
        assertTrue(vm.uiState.value.isAppOpsLoading)
        assertTrue(vm.uiState.value.appOpsStatusUncertain)

        freshRead.complete(Unit)
        advanceUntilIdle()

        assertEquals(3, appOps.reads)
        assertEquals(AppOpMode.ALLOW, vm.uiState.value.appOpsSnapshot!!.entries.single().displayedMode)
        assertFalse(vm.uiState.value.appOpsStatusUncertain)
        assertFalse(vm.uiState.value.appOpsLoadFailed)
    }

    @Test
    fun `failed fresh read after a superseded write readback keeps modes uncertain`() = runTest {
        val appOps = FakeAppOpsRepository(snapshot(AppOpMode.IGNORE))
        val permissions = FakePermissionRepository(
            listOf(permission("android.permission.CAMERA", isRuntime = true)),
        )
        val vm = viewModel(appOps, permissions)
        vm.loadPermissions(PACKAGE, "Example")
        vm.loadAppOps()
        advanceUntilIdle()

        val writeReadBack = CompletableDeferred<Unit>()
        appOps.nextReadGate = writeReadBack
        vm.setAppOpMode(OP_CODE, AppOpScope.PACKAGE, AppOpMode.DENY)
        runCurrent()

        vm.togglePermission("android.permission.CAMERA", grant = true)
        runCurrent()
        appOps.readFailure = IllegalStateException("read failed")
        writeReadBack.complete(Unit)
        advanceUntilIdle()

        assertEquals(3, appOps.reads)
        assertNull(vm.uiState.value.savingAppOpCode)
        assertNull(vm.uiState.value.appOpsSnapshot)
        assertFalse(vm.uiState.value.isAppOpsLoading)
        assertTrue(vm.uiState.value.appOpsStatusUncertain)
        assertTrue(vm.uiState.value.appOpsLoadFailed)
    }

    @Test
    fun `read-only App Ops cannot initiate writes`() = runTest {
        val appOps = FakeAppOpsRepository(snapshot(AppOpMode.ALLOW, canEdit = false))
        val vm = viewModel(appOps)
        vm.loadPermissions(PACKAGE, "Example")
        vm.loadAppOps()
        advanceUntilIdle()

        vm.setAppOpMode(OP_CODE, AppOpScope.UID, AppOpMode.DENY)
        vm.resetAppOpMode(OP_CODE, AppOpScope.PACKAGE)
        advanceUntilIdle()

        assertTrue(appOps.writes.isEmpty())
        assertTrue(appOps.resets.isEmpty())
        assertFalse(vm.uiState.value.isAppOpsLoading)
    }

    @Test
    fun `permission controlled App Ops cannot initiate writes or resets`() = runTest {
        val initial = snapshot(AppOpMode.IGNORE)
        val entry = initial.entries.single()
        val appOps = FakeAppOpsRepository(initial.copy(entries = listOf(
            entry.copy(definition = entry.definition.copy(isRuntimePermissionControlled = true)),
        )))
        val vm = viewModel(appOps)
        vm.loadPermissions(PACKAGE, "Example")
        vm.loadAppOps()
        advanceUntilIdle()

        vm.setAppOpMode(OP_CODE, AppOpScope.UID, AppOpMode.ALLOW)
        vm.resetAppOpMode(OP_CODE, AppOpScope.PACKAGE)
        advanceUntilIdle()

        assertTrue(appOps.writes.isEmpty())
        assertTrue(appOps.resets.isEmpty())
        assertNull(vm.uiState.value.savingAppOpCode)
    }

    private fun viewModel(
        appOps: FakeAppOpsRepository,
        permissions: FakePermissionRepository = FakePermissionRepository(),
    ) = PermissionManagerViewModel(
        getAppPermissionsUseCase = GetAppPermissionsUseCase(permissions),
        togglePermissionUseCase = TogglePermissionUseCase(permissions),
        permissionRepository = permissions,
        appOpsRepository = appOps,
    )

    private fun snapshot(mode: AppOpMode?, canEdit: Boolean = true) = AppOpsSnapshot(
        userId = 0,
        uid = 10123,
        entries = listOf(
            AppOpEntry(
                definition = AppOpDefinition(
                    code = OP_CODE,
                    debugName = "OP_CAMERA",
                    publicName = "android:camera",
                    aliases = emptyList(),
                    relatedPermissions = listOf("android.permission.CAMERA"),
                    platformDefault = AppOpMode.ALLOW,
                    allowsReset = true,
                ),
                packageMode = mode,
                uidMode = null,
                observed = true,
                permissionRequested = true,
            ),
        ),
        canEdit = canEdit,
    )

    private fun permission(name: String, isRuntime: Boolean) = AppPermission(
        name = name,
        label = name.substringAfterLast('.'),
        description = "",
        group = null,
        isGranted = false,
        isRuntime = isRuntime,
        protectionLevel = 0,
    )

    private class FakeAppOpsRepository(var current: AppOpsSnapshot) : AppOpsRepository {
        var reads = 0
        var readFailure: Throwable? = null
        var nextReadGate: CompletableDeferred<Unit>? = null
        var writeFailure: Throwable? = null
        var writeGate: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<Triple<Int, AppOpScope, AppOpMode>>()
        val resets = mutableListOf<Pair<Int, AppOpScope>>()

        override suspend fun getAppOps(packageName: String): Result<AppOpsSnapshot> {
            reads++
            val captured = current
            val gate = nextReadGate
            nextReadGate = null
            gate?.await()
            return readFailure?.let { Result.failure(it) } ?: Result.success(captured)
        }

        override suspend fun setAppOpMode(
            packageName: String,
            code: Int,
            scope: AppOpScope,
            mode: AppOpMode,
        ): Result<Unit> {
            writes += Triple(code, scope, mode)
            writeGate?.await()
            return writeFailure?.let { Result.failure(it) } ?: Result.success(Unit)
        }

        override suspend fun resetAppOpMode(packageName: String, code: Int, scope: AppOpScope): Result<Unit> {
            resets += code to scope
            return Result.success(Unit)
        }
    }

    private class FakePermissionRepository(
        private val permissions: List<AppPermission> = emptyList(),
    ) : PermissionRepository {
        val granted = mutableListOf<String>()
        val revoked = mutableListOf<String>()

        override suspend fun getAppPermissions(packageName: String) = Result.success(permissions)
        override suspend fun buildPermissionIndex() = Result.success(PermissionIndex())
        override suspend fun grantPermission(packageName: String, permissionName: String): Result<Unit> {
            granted += permissionName
            return Result.success(Unit)
        }
        override suspend fun revokePermission(packageName: String, permissionName: String): Result<Unit> {
            revoked += permissionName
            return Result.success(Unit)
        }
        override suspend fun isPrivilegeActive() = true
    }

    private companion object {
        const val PACKAGE = "com.example.app"
        const val OP_CODE = 26
    }
}
