// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.appops

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.domain.model.AppOpEntry
import com.valhalla.thor.domain.model.AppOpMode
import com.valhalla.thor.domain.model.AppOpScope
import com.valhalla.thor.domain.model.AppOpsSnapshot
import com.valhalla.thor.domain.repository.AppOpsRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Opt-in live test of the production catalog, repository and selected Root/Shizuku gateway.
 * Pass appOpsTestPackage=com.valhalla.thor.acceptance.fixture with that disposable fixture
 * installed and Thor already authorized. Only READ_CLIPBOARD is changed; both scopes are
 * restored in finally. The test intentionally uses Thor's ordinary application/Koin runtime.
 */
@RunWith(AndroidJUnit4::class)
class AppOpsIntegrationTest {
    private lateinit var context: Context
    private lateinit var repository: AppOpsRepository
    private var targetUid: Int = -1

    @Before
    @Suppress("DEPRECATION")
    fun requireExplicitDisposableTarget() {
        val requested = InstrumentationRegistry.getArguments().getString("appOpsTestPackage")
        assumeTrue("Explicit App Ops fixture opt-in required", requested == TARGET)
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val info = context.packageManager.getApplicationInfo(TARGET, 0)
        assertTrue("Fixture must be debuggable", info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertFalse("Fixture must not be a system app", info.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        assertFalse("Fixture must not be an updated system app", info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
        assertTrue("Fixture must be installed", info.flags and ApplicationInfo.FLAG_INSTALLED != 0)
        assertEquals("Fixture must belong to Thor's Android user", Process.myUid() / UID_RANGE, info.uid / UID_RANGE)
        assertFalse("Fixture must not share Thor's UID", info.uid == Process.myUid())
        assertEquals("Fixture must have a unique UID", setOf(TARGET), context.packageManager.getPackagesForUid(info.uid).orEmpty().toSet())
        targetUid = info.uid
        // The instrumentation compiler's graph does not include ThorApplication's loaded modules.
        // Koin's optional lookup is checked at runtime, then required here so missing production
        // wiring fails the integration test instead of substituting a fake repository.
        repository = requireNotNull(GlobalContext.get().getOrNull<AppOpsRepository>()) {
            "ThorApplication must load the production AppOpsRepository binding"
        }
    }

    @Test
    fun deviceCatalogAndScopedModesRoundTrip() = runBlocking<Unit> {
        val initial = repository.getAppOps(TARGET).getOrThrow()
        assertEquals(targetUid, initial.uid)
        assertEquals(Process.myUid() / UID_RANGE, initial.userId)
        assertEquals(listOf(TARGET), initial.sharedUidPackages)
        assertTrue("The authorized gateway should allow edits", initial.canEdit)
        assertTrue("Device catalog must contain operations", initial.entries.isNotEmpty())
        assertEquals("Aliases must not produce duplicate controls", initial.entries.size, initial.entries.map { it.definition.code }.distinct().size)
        assertPermissionRelevance(TARGET, initial)

        // The disposable data fixture has no requested permissions. Reading Thor itself supplies
        // a positive check that manifest-linked operations appear in Relevant without editing it.
        val thor = repository.getAppOps(context.packageName).getOrThrow()
        assertPermissionRelevance(context.packageName, thor)
        assertTrue("Thor should have permission-linked operations", thor.entries.any { it.permissionRequested })

        val original = initial.entries.single { it.definition.debugName == "READ_CLIPBOARD" }
        val definition = original.definition
        assertTrue("READ_CLIPBOARD must support a platform reset", definition.allowsReset)
        assertEquals("This fixture checks literal DEFAULT against Android's clipboard baseline", AppOpMode.ALLOW, definition.platformDefault)
        val code = definition.code
        var originalFailure: Throwable? = null
        try {
            // Isolate package-mode assertions even when an earlier fixture run left a UID mode.
            repository.resetAppOpMode(TARGET, code, AppOpScope.UID).getOrThrow()
            assertFalse(readClipboard().hasUidOverride)

            repository.setAppOpMode(TARGET, code, AppOpScope.PACKAGE, AppOpMode.IGNORE).getOrThrow()
            readClipboard().also { entry ->
                assertEquals(AppOpMode.IGNORE, entry.packageMode)
                assertEquals(AppOpMode.IGNORE, entry.displayedMode)
                assertTrue(entry.observed)
                assertTrue(entry.isRelevant)
                assertTrue(entry.isChanged)
            }

            repository.setAppOpMode(TARGET, code, AppOpScope.PACKAGE, AppOpMode.DEFAULT).getOrThrow()
            readClipboard().also { entry ->
                assertEquals("Literal default is mode 3", AppOpMode.DEFAULT, entry.packageMode)
                assertEquals(AppOpMode.DEFAULT, entry.displayedMode)
                assertEquals("The platform baseline must remain distinct", AppOpMode.ALLOW, entry.definition.platformDefault)
            }

            repository.resetAppOpMode(TARGET, code, AppOpScope.PACKAGE).getOrThrow()
            readClipboard().also { entry ->
                assertEquals(AppOpMode.ALLOW, entry.packageMode ?: entry.definition.platformDefault)
                assertEquals(AppOpMode.ALLOW, entry.displayedMode)
                assertFalse(entry.isChanged)
            }

            repository.setAppOpMode(TARGET, code, AppOpScope.UID, AppOpMode.IGNORE).getOrThrow()
            readClipboard().also { entry ->
                assertEquals(AppOpMode.ALLOW, entry.packageMode ?: entry.definition.platformDefault)
                assertEquals(AppOpMode.IGNORE, entry.uidMode)
                assertTrue(entry.hasUidOverride)
                assertEquals("UID override must mask the package baseline", AppOpMode.IGNORE, entry.displayedMode)
            }

            // Package readback must still succeed while the effective mode is masked by the UID.
            repository.setAppOpMode(TARGET, code, AppOpScope.PACKAGE, AppOpMode.DEFAULT).getOrThrow()
            readClipboard().also { entry ->
                assertEquals(AppOpMode.DEFAULT, entry.packageMode)
                assertEquals(AppOpMode.IGNORE, entry.uidMode)
                assertEquals(AppOpMode.IGNORE, entry.displayedMode)
            }
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                // Attempt both restorations even if one transport call fails. Setting the
                // baseline may retain a history entry, but restores the original policy.
                val packageRestore = runCatching {
                    repository.setAppOpMode(TARGET, code, AppOpScope.PACKAGE,
                        original.packageMode ?: definition.platformDefault).getOrThrow()
                }
                val uidRestore = runCatching {
                    repository.setAppOpMode(TARGET, code, AppOpScope.UID,
                        original.uidMode ?: definition.platformDefault).getOrThrow()
                }
                val verifyRestore = runCatching {
                    val restored = readClipboard()
                    assertEquals(original.packageMode ?: definition.platformDefault, restored.packageMode ?: definition.platformDefault)
                    assertEquals(original.uidMode ?: definition.platformDefault, restored.uidMode ?: definition.platformDefault)
                }
                val cleanupFailures = listOf(packageRestore, uidRestore, verifyRestore).mapNotNull { it.exceptionOrNull() }
                if (cleanupFailures.isNotEmpty()) {
                    val failure = AssertionError("Could not restore the fixture's READ_CLIPBOARD modes")
                    cleanupFailures.forEach(failure::addSuppressed)
                    val primaryFailure = originalFailure
                    if (primaryFailure != null) primaryFailure.addSuppressed(failure) else throw failure
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun assertPermissionRelevance(packageName: String, snapshot: AppOpsSnapshot) {
        val requested = context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        snapshot.entries.forEach { entry ->
            val expected = entry.definition.relatedPermissions.any(requested::contains)
            assertEquals("Manifest relevance for ${entry.definition.debugName}", expected, entry.permissionRequested)
            if (expected) assertTrue("Requested operation must be relevant", entry.isRelevant)
        }
    }

    private suspend fun readClipboard(): AppOpEntry = repository.getAppOps(TARGET).getOrThrow()
        .entries.single { it.definition.debugName == "READ_CLIPBOARD" }

    private companion object {
        const val TARGET = "com.valhalla.thor.acceptance.fixture"
        const val UID_RANGE = 100_000
    }
}
