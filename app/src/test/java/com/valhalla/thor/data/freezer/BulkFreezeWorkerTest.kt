// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one worker of a bulk run does to one package when the privilege layer refuses, or when the
 * batch is cancelled under it.
 *
 * This is **not** the concurrency suite `docs/follow-ups/bulk-freeze-runner-concurrency-tests.md`
 * asks for; that one still cannot be written. `BulkFreezeRunner` takes four collaborators a JVM
 * unit test cannot produce, and Kotlin classes are final by default so none of them can be
 * subclassed by a fake:
 *
 * - `PrivilegeManager` — final, and its `init` registers Shizuku binder/permission listeners;
 * - `AppFreezeStateReader` — final, over the abstract `android.content.pm.PackageManager`;
 * - `UadHelper` and `BulkResultNotifier` — final, over `android.content.Context`.
 *
 * :app has no mocking library on purpose (every existing test is a hand-written fake), so the
 * scope, the coalescing, the deadline race and the cancellation handoff stay unreachable until
 * main source grows a seam. The follow-up stays open, and its acceptance criteria are unmet.
 *
 * What *is* reachable is the body of a worker: `run()` resolves one bulk action per batch and each
 * worker then makes exactly one [ManageAppUseCase] call. The happy-path sequences of those calls
 * are pinned by `FreezeAppUseCaseTest`, whose recording repository always succeeds. The two
 * properties the runner's B-3 comment leans on are not covered there and are covered here: a
 * refused package comes back as a `Result.failure`, and a cancelled one throws. Both belong to
 * this use case rather than to the coroutine machinery around it — which is what makes them
 * testable while the machinery is not.
 */
class BulkFreezeWorkerTest {

    @Test
    fun `an unfreeze that cannot unsuspend leaves the package alone and reports the cause`() =
        runTest {
            // Order and short-circuit together decide what a half-failed unfreeze leaves behind.
            // Stopping here leaves the package exactly as it was. Enabling anyway would leave it
            // enabled-but-suspended: still FROZEN to isFrozen(), so the user would see no change
            // from a call that did in fact mutate the package.
            val cause = IllegalStateException("binder died")
            val repository = RecordingSystemRepository { call ->
                if (call.startsWith("setAppSuspended")) Result.failure(cause)
                else Result.success(Unit)
            }

            val result = ManageAppUseCase(repository).forceUnfreeze(PKG)

            assertTrue(result.isFailure)
            // The unsuspend failure itself, not a fresh one wrapped around it: which leg failed
            // and what the gateway said are the only diagnostic a caller gets, and the runner's
            // counters deliberately record neither.
            assertSame(cause, result.exceptionOrNull())
            assertEquals(listOf("setAppSuspended($PKG, false)"), repository.calls)
        }

    @Test
    fun `an unfreeze that cannot enable is a failure, not a partial success`() = runTest {
        // Reaching the second leg must not be mistaken for having restored the app: the package
        // is still disabled, so the runner has to count it in `failed` and the watchlist entry
        // has to stay an unfreeze candidate.
        val cause = IllegalStateException("shizuku not authorised")
        val repository = RecordingSystemRepository { call ->
            if (call.startsWith("setAppDisabled")) Result.failure(cause) else Result.success(Unit)
        }

        val result = ManageAppUseCase(repository).forceUnfreeze(PKG)

        assertTrue(result.isFailure)
        assertSame(cause, result.exceptionOrNull())
        assertEquals(
            listOf("setAppSuspended($PKG, false)", "setAppDisabled($PKG, false)"),
            repository.calls
        )
    }

    @Test
    fun `cancellation propagates instead of being reported as a failed package`() = runTest {
        // BulkFreezeRunner's B-3 split — `if (result.isSuccess) succeeded++ else failed++` — is
        // correct only while a privilege-layer *refusal* is a value and a *cancellation* is a
        // throw. Wrap this use case's body in runCatching (the obvious "make it safer" edit) and
        // a batch killed by the 30s deadline, or replaced by a conflicting op, would count every
        // in-flight package as failed — a claim it has no evidence for, and the same mistake
        // BulkResult.unresolved exists to avoid.
        val repository = RecordingSystemRepository { throw CancellationException("batch replaced") }
        var propagated: CancellationException? = null

        try {
            ManageAppUseCase(repository).forceUnfreeze(PKG)
        } catch (e: CancellationException) {
            propagated = e
        }

        assertEquals("batch replaced", propagated?.message)
        // ...and the enable leg never ran, so a cancelled worker cannot leave a package half
        // restored either.
        assertEquals(listOf("setAppSuspended($PKG, false)"), repository.calls)
    }

    private companion object {
        const val PKG = "com.example.watchlisted"
    }
}

/**
 * Records the calls a worker makes, in order, and lets a test decide what each one answers —
 * including throwing, which is how the cancellation contract above is exercised. The always-succeed
 * default keeps the recorded strings in the same shape as `FreezeAppUseCaseTest`'s fake.
 *
 * Hand-written rather than mocked because :app has no mocking library; see the class KDoc.
 */
private class RecordingSystemRepository(
    private val respond: (String) -> Result<Unit> = { Result.success(Unit) },
) : SystemRepository {

    val calls = mutableListOf<String>()

    override suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit> =
        record("setAppDisabled($packageName, $isDisabled)")

    override suspend fun setAppSuspended(packageName: String, isSuspended: Boolean): Result<Unit> =
        record("setAppSuspended($packageName, $isSuspended)")

    private fun record(call: String): Result<Unit> {
        calls += call
        return respond(call)
    }

    // Nothing below is reachable from a bulk freeze worker. These throw rather than returning a
    // benign default so that a worker which starts calling one of them fails loudly here, instead
    // of recording nothing and still passing every assertion above.
    override suspend fun isRootAvailable(): Boolean = unreachable("isRootAvailable")

    override suspend fun isShizukuAvailable(): Boolean = unreachable("isShizukuAvailable")

    override suspend fun isDhizukuAvailable(): Boolean = unreachable("isDhizukuAvailable")

    override suspend fun forceStopApp(packageName: String): Result<Unit> =
        unreachable("forceStopApp")

    override suspend fun clearCache(packageName: String): Result<Long?> = unreachable("clearCache")

    override suspend fun clearAllCaches(): Result<Long?> = unreachable("clearAllCaches")

    override suspend fun clearAppData(packageName: String): Result<Unit> =
        unreachable("clearAppData")

    override suspend fun setAppRestricted(
        packageName: String,
        isRestricted: Boolean,
    ): Result<Unit> = unreachable("setAppRestricted")

    override suspend fun uninstallApp(packageName: String): Result<Unit> =
        unreachable("uninstallApp")

    override suspend fun rebootDevice(reason: String): Result<Unit> = unreachable("rebootDevice")

    override suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit> =
        unreachable("reinstallAppWithGoogle")

    override suspend fun copyFileWithRoot(
        sourcePath: String,
        destinationPath: String,
    ): Result<Unit> = unreachable("copyFileWithRoot")

    override suspend fun getAppPaths(packageName: String): Result<List<String>> =
        unreachable("getAppPaths")

    override suspend fun grantPermission(
        packageName: String,
        permissionName: String,
    ): Result<Unit> = unreachable("grantPermission")

    override suspend fun revokePermission(
        packageName: String,
        permissionName: String,
    ): Result<Unit> = unreachable("revokePermission")

    override suspend fun executeShellCommand(command: String): Result<Pair<Int, String?>> =
        unreachable("executeShellCommand")

    override suspend fun probeObb(packageName: String): ObbProbe = unreachable("probeObb")

    private fun unreachable(name: String): Nothing =
        throw UnsupportedOperationException("$name is not reachable from a bulk freeze worker")
}
