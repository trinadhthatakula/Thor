// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.DetailedAppInfo
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.util.UiText
import com.valhalla.thor.util.UiTextException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PKG = "com.some.system.app"

/**
 * Everything that reached the privilege gateway, in order.
 *
 * The load-bearing assertion in most of these tests is that [calls] is **empty**: a gate that
 * returned a refusal *after* the freeze had already reached the gateway would satisfy any assertion
 * made on the returned `Result` alone, and the app would already be frozen — disabled, or, where
 * disabling is unavailable, removed for this user with its data.
 */
private class RecordingSystemRepository : SystemRepository {
    val calls = mutableListOf<String>()

    override suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit> {
        calls += "setAppDisabled($packageName, $isDisabled)"
        return Result.success(Unit)
    }

    override suspend fun setAppSuspended(packageName: String, isSuspended: Boolean): Result<Unit> {
        calls += "setAppSuspended($packageName, $isSuspended)"
        return Result.success(Unit)
    }

    // Nothing else belongs on a freeze path. These throw rather than record so a stray call is a
    // loud failure instead of a line in `calls` that no assertion happens to look at.
    override suspend fun isRootAvailable(): Boolean = error("off the freeze path")
    override suspend fun isShizukuAvailable(): Boolean = error("off the freeze path")
    override suspend fun isDhizukuAvailable(): Boolean = error("off the freeze path")
    override suspend fun forceStopApp(packageName: String): Result<Unit> =
        error("off the freeze path")

    override suspend fun clearCache(packageName: String): Result<Long?> =
        error("off the freeze path")

    override suspend fun clearAllCaches(): Result<Long?> = error("off the freeze path")

    override suspend fun clearAppData(packageName: String): Result<Unit> =
        error("off the freeze path")

    override suspend fun setAppRestricted(
        packageName: String,
        isRestricted: Boolean,
    ): Result<Unit> = error("off the freeze path")

    override suspend fun uninstallApp(packageName: String): Result<Unit> =
        error("off the freeze path")

    override suspend fun rebootDevice(reason: String): Result<Unit> = error("off the freeze path")

    override suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit> =
        error("off the freeze path")

    override suspend fun copyFileWithRoot(
        sourcePath: String,
        destinationPath: String,
    ): Result<Unit> = error("off the freeze path")

    override suspend fun getAppPaths(packageName: String): Result<List<String>> =
        error("off the freeze path")

    override suspend fun grantPermission(
        packageName: String,
        permissionName: String,
    ): Result<Unit> = error("off the freeze path")

    override suspend fun revokePermission(
        packageName: String,
        permissionName: String,
    ): Result<Unit> = error("off the freeze path")

    override suspend fun executeShellCommand(command: String): Result<Pair<Int, String?>> =
        error("off the freeze path")

    override suspend fun probeObb(packageName: String): ObbProbe = error("off the freeze path")
}

/**
 * The tier source the gate reads.
 *
 * [details] is a lambda, not a value, so a test can hand back `null` (the package vanished
 * between the tap and the lookup) or throw — the two shapes of "the tier is unknown", which is
 * the whole fail-closed question.
 */
private class FakeAppRepository(private val details: (String) -> AppInfo?) : AppRepository {
    val lookups = mutableListOf<String>()

    override suspend fun getAppDetails(packageName: String): AppInfo? {
        lookups += packageName
        return details(packageName)
    }

    override fun getAllApps(): Flow<List<AppInfo>> = flowOf(emptyList())
    override suspend fun getDetailedAppInfo(packageName: String): DetailedAppInfo? =
        error("the gate must not need the heavy detail read")

    override suspend fun getApkDetails(apkPath: String): AppInfo? = error("off the freeze path")
    override suspend fun updateInstallSizes(sizes: Map<String, Long>) {
        error("off the freeze path")
    }
}

/**
 * The `FreezeTier.BLOCKED` gate, now that it lives in the domain layer instead of in
 * `AppRiskDialog` declining to render a confirm button.
 *
 * All three single-app freeze entry points — `AppListViewModel.freezeApp`,
 * `FreezerViewModel.freezeSingleApp` and `AppInfoDetailsViewModel.toggleFreezerState` — call
 * `FreezeAppUseCase` and nothing else for the freeze direction, so pinning it here pins all
 * three. The view models add only the message rendering.
 *
 * [ManageAppUseCase] is deliberately **real**, wrapped around a recording [SystemRepository]:
 * the gate has to sit *above* that primitive (the batch paths call it directly and would
 * double-report if it refused), so faking `ManageAppUseCase` would fake away half of what is
 * under test. See `the freeze primitive stays ungated so a batch skip is reported once`.
 *
 * `freezeTierOf` opens with `!isSystem -> NORMAL`, so every non-NORMAL fixture below is a system
 * app by construction. Tier derivation itself is `FreezePolicyTest`'s job, not this class's.
 */
class FreezeAppUseCaseTest {

    private val system = RecordingSystemRepository()
    private val manage = ManageAppUseCase(system)
    private var details: (String) -> AppInfo? = { error("this test set no tier") }
    private val repository = FakeAppRepository { details(it) }
    private val gate = FreezeAppUseCase(repository, manage)

    private val unsafeSystemApp =
        AppInfo(packageName = PKG, isSystem = true, bloatRecommendation = "Unsafe")
    private val expertSystemApp =
        AppInfo(packageName = PKG, isSystem = true, bloatRecommendation = "Expert")
    private val safeSystemApp =
        AppInfo(packageName = PKG, isSystem = true, bloatRecommendation = "Recommended")

    // --- BLOCKED is refused ------------------------------------------------------------------

    @Test
    fun `a blocked app is refused and nothing reaches the gateway`() = runTest {
        details = { unsafeSystemApp }

        val result = gate(PKG)

        assertTrue(result.isFailure)
        assertEquals(emptyList<String>(), system.calls)
    }

    @Test
    fun `the refusal carries the skipped message, not a bare error`() = runTest {
        // UiTextException's `message` is null. That is why each of the three view models special
        // cases it: routing it through R.string.error_format instead renders a bare "Error: ".
        details = { unsafeSystemApp }

        val refusal = gate(PKG).exceptionOrNull()

        assertTrue("expected a UiTextException, got $refusal", refusal is UiTextException)
        assertNull(refusal?.message)
        assertEquals(
            UiText.StringResource(R.string.error_unsafe_skipped),
            (refusal as UiTextException).uiText
        )
    }

    @Test
    fun `a system app is refused while the UAD list is unreadable`() = runTest {
        // The recommendation string cannot be trusted when the load that produced it failed, so
        // reading `bloatRecommendation` alone instead of the whole tier would let this through.
        details = {
            AppInfo(
                packageName = PKG,
                isSystem = true,
                bloatRecommendation = "Recommended",
                isUadLoadFailed = true
            )
        }

        assertTrue(gate(PKG).isFailure)
        assertEquals(emptyList<String>(), system.calls)
    }

    @Test
    fun `suspend mode is not a way around the block`() = runTest {
        // Suspending a blocked app is still freezing it. If the mode were consulted before the
        // tier, flipping the Freezer to SUSPEND would be a one-tap bypass of the whole gate.
        details = { unsafeSystemApp }

        assertTrue(gate(PKG, FreezerMode.SUSPEND).isFailure)
        assertEquals(emptyList<String>(), system.calls)
    }

    // --- Fail closed on an unknown tier ------------------------------------------------------
    //
    // The PR #287 defect: `getAppDetails` returns null on *any* failure, and the call sites read
    // `app != null && app.freezeTier != BLOCKED`, so an unresolvable package read as a safe one
    // and froze immediately. An unknown tier is not a safe tier.

    @Test
    fun `an unresolvable package is refused`() = runTest {
        details = { null }

        assertTrue(gate(PKG).isFailure)
        assertEquals(emptyList<String>(), system.calls)
    }

    @Test
    fun `an unresolvable package is refused with the same message as a blocked one`() = runTest {
        // One message for both readings on purpose — error_unsafe_skipped says "System app is
        // UNSAFE / safety check failed", which covers "we know it is unsafe" and "we could not
        // find out". Two messages would mean two spellings of the rule, and eventually two rules.
        details = { null }

        val refusal = gate(PKG).exceptionOrNull()

        assertEquals(
            UiText.StringResource(R.string.error_unsafe_skipped),
            (refusal as? UiTextException)?.uiText
        )
    }

    @Test
    fun `a resolver failure never reaches the gateway`() = runTest {
        // getAppDetails is documented to return null on failure, but that is one implementation's
        // promise, not the interface's. Whatever the gate does with a throw, it must not freeze.
        details = { throw IllegalStateException("PackageManager died mid-lookup") }

        runCatching { gate(PKG) }

        assertEquals(emptyList<String>(), system.calls)
    }

    @Test
    fun `a resolver failure comes back as a refusal, not as a thrown exception`() = runTest {
        // Every caller is a bare `viewModelScope.launch { … }` with no try/catch, so an exception
        // escaping invoke() is an app crash where the user expected the "Skipped" toast. The two
        // Results here are different questions: did invoke() escape, and did it refuse.
        details = { throw IllegalStateException("PackageManager died mid-lookup") }

        val call = runCatching { gate(PKG) }

        assertTrue(
            "the gate let a resolver failure escape as ${call.exceptionOrNull()} instead of " +
                    "refusing; FreezeAppUseCase reads getAppDetails outside any try/catch, so " +
                    "the tier lookup needs to be wrapped and a throw treated as unresolvable",
            call.isSuccess
        )
        assertTrue(call.getOrThrow().isFailure)
    }

    // --- Everything else still freezes -------------------------------------------------------

    @Test
    fun `an expert app is warned about elsewhere, not blocked here`() = runTest {
        // EXPERT is a loud warning the user can accept, not a stop. A gate that caught it too
        // would silently remove a whole tier of apps from Thor with no error anyone would file.
        details = { expertSystemApp }

        assertTrue(gate(PKG).isSuccess)
        assertEquals(listOf("setAppDisabled($PKG, true)"), system.calls)
    }

    @Test
    fun `a normal system app freezes`() = runTest {
        details = { safeSystemApp }

        assertTrue(gate(PKG).isSuccess)
        assertEquals(listOf("setAppDisabled($PKG, true)"), system.calls)
    }

    @Test
    fun `a user app is not blocked by a stale unsafe recommendation`() = runTest {
        // isSystem = false, so the tier is NORMAL whatever the UAD row says. A gate written
        // against `bloatRecommendation` instead of `freezeTier` passes every test above and
        // fails this one — freezing a user app is reversible with `pm enable`, so there is
        // nothing here to protect the user from.
        details = { AppInfo(packageName = PKG, bloatRecommendation = "Unsafe") }

        assertTrue(gate(PKG).isSuccess)
        assertEquals(listOf("setAppDisabled($PKG, true)"), system.calls)
    }

    @Test
    fun `suspend mode suspends instead of disabling`() = runTest {
        details = { safeSystemApp }

        assertTrue(gate(PKG, FreezerMode.SUSPEND).isSuccess)
        assertEquals(listOf("setAppSuspended($PKG, true)"), system.calls)
    }

    @Test
    fun `the tier is read from the repository, once, for the package asked about`() = runTest {
        // Not from an AppInfo the caller passed in: the point of the class is that a surface
        // holding nothing but a package name — a shortcut, an extension trigger, an automation
        // intent — is covered too. One lookup is also the documented cost of the gate.
        details = { safeSystemApp }

        gate(PKG)

        assertEquals(listOf(PKG), repository.lookups)
    }

    // --- Unfreeze is never gated -------------------------------------------------------------

    @Test
    fun `unfreezing a blocked app is never gated`() = runTest {
        // FreezeAppUseCase has no unfreeze direction at all — the view models call this primitive
        // straight for `freeze = false`. Unfreezing is the way *out* of a bad state: an app can
        // be frozen from before it was ever classified, or from a Thor without this gate, and a
        // block that caught unfreeze too would trap the very app it claims to protect.
        //
        // Which tier this package is in is not part of the fixture on purpose: the empty
        // `lookups` below is the assertion. Nothing resolves a tier here, so no tier can gate it.
        assertTrue(manage.setAppDisabled(PKG, false).isSuccess)
        assertEquals(listOf("setAppDisabled($PKG, false)"), system.calls)
        assertEquals(emptyList<String>(), repository.lookups)
    }

    @Test
    fun `force-unfreezing a blocked app clears both freeze dimensions`() = runTest {
        // What "Unfreeze all" runs per package. Same asymmetry, and it must not consult a tier
        // either: an app that is both disabled and suspended has to come back from both.
        assertTrue(manage.forceUnfreeze(PKG).isSuccess)
        assertEquals(
            listOf("setAppSuspended($PKG, false)", "setAppDisabled($PKG, false)"),
            system.calls
        )
        assertEquals(emptyList<String>(), repository.lookups)
    }

    // --- The batch paths must not report twice -----------------------------------------------

    @Test
    fun `the freeze primitive stays ungated so a batch skip is reported once`() = runTest {
        // It freezes whatever it is handed and never resolves a tier (`lookups` stays empty).
        // Deliberate, and the reason the gate is a separate use case rather than a check inside
        // ManageAppUseCase. BulkFreezeRunner, MainViewModel.performCountedFreeze and
        // AppListViewModel.performMultiAction classify their whole target list against one
        // shared snapshot (freezableCandidates / freezeTier), count the blocked ones as skipped,
        // and then call this primitive per survivor. If it refused as well, a blocked app would
        // be counted once by the filter and once again by the loop's failure branch — and every
        // survivor would pay a redundant per-package getAppDetails.
        //
        // If this test ever goes red because a gate moved down here, the batch counting in those
        // three paths has to be revisited in the same change.
        assertTrue(manage.setAppDisabled(PKG, true).isSuccess)
        assertEquals(listOf("setAppDisabled($PKG, true)"), system.calls)
        assertEquals(emptyList<String>(), repository.lookups)
    }
}
