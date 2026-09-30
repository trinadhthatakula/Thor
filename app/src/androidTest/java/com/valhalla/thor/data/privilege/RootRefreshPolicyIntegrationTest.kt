// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.privilege

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.superuser.utils.escapeForShell
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.RootAdmissionUnavailable
import com.valhalla.thor.domain.model.RootConfirmation
import com.valhalla.thor.domain.model.RootRefreshStatus
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Host-controlled policy test using cache/root-refresh-policy/. After ready, the host denies root
 * and writes denied; after denial-verified, it restores root and writes granted. The host waits for
 * its policy cache to settle before each acknowledgement and owns restoring policy on failure.
 */
@RunWith(AndroidJUnit4::class)
class RootRefreshPolicyIntegrationTest {
    @Test
    fun refreshAndAwaitFollowsHostRevocationAndRegrant() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("App-authorized root required", arguments.getString("odinRoot") == "true")
        assumeTrue("Explicit host policy coordination required", arguments.getString("odinPolicyToggle") == "true")

        val koin = GlobalContext.get()
        val manager = requireNotNull(koin.getOrNull<PrivilegeManager>())
        val gateway = requireNotNull(koin.getOrNull<RootSystemGateway>())
        val markers = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "root-refresh-policy")
        val ready = File(markers, "ready")
        val denied = File(markers, "denied")
        val denialVerified = File(markers, "denial-verified")
        val granted = File(markers, "granted")
        val forbidden = File(markers, "forbidden")

        try {
            assertTrue("Stale policy markers must be removed", !markers.exists() || markers.deleteRecursively())
            assertTrue(markers.mkdir())
            val initial = withTimeout(30_000) { manager.refreshAndAwait() }
            assertEquals(RootConfirmation.ROOT, initial.rootAvailability.confirmation)
            assertEquals(RootRefreshStatus.IDLE, initial.rootAvailability.refreshStatus)
            assertTrue(initial.root)
            ready.writeText("ready")

            awaitHostMarker(denied)
            val afterDenial = withTimeout(30_000) { manager.refreshAndAwait() }
            assertEquals(RootConfirmation.NON_ROOT, afterDenial.rootAvailability.confirmation)
            assertEquals(RootRefreshStatus.IDLE, afterDenial.rootAvailability.refreshStatus)
            assertTrue(afterDenial.rootAvailability.confirmedRevision > initial.rootAvailability.confirmedRevision)
            assertFalse(afterDenial.root)
            val refused = withTimeout(15_000) {
                gateway.executeShellCommand(
                    "printf unexpected > ${forbidden.absolutePath.escapeForShell()}",
                    PrivilegeExecutionContext(commandClass = PrivilegeCommandClass("test.root-policy.denied")),
                )
            }
            assertTrue("Fresh root work must be refused after denial", refused.exceptionOrNull() is RootAdmissionUnavailable)
            assertFalse("Denied work must not dispatch", forbidden.exists())
            denialVerified.writeText("denial-verified")

            awaitHostMarker(granted)
            val afterGrant = withTimeout(30_000) { manager.refreshAndAwait() }
            assertEquals(RootConfirmation.ROOT, afterGrant.rootAvailability.confirmation)
            assertEquals(RootRefreshStatus.IDLE, afterGrant.rootAvailability.refreshStatus)
            assertTrue(afterGrant.rootAvailability.confirmedRevision > afterDenial.rootAvailability.confirmedRevision)
            assertTrue(afterGrant.root)
            val identity = withTimeout(15_000) {
                gateway.executeShellCommand(
                    "id -u",
                    PrivilegeExecutionContext(commandClass = PrivilegeCommandClass("test.root-policy.granted")),
                ).getOrThrow()
            }
            assertEquals(0, identity.first)
            assertEquals("0", identity.second?.trim())
        } finally {
            // Synchronous cleanup also runs when a host wait or refresh is cancelled.
            markers.deleteRecursively()
        }
    }

    private suspend fun awaitHostMarker(marker: File) {
        withTimeout(90_000) {
            while (!marker.exists()) delay(50)
        }
    }
}
