// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.rootservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.valhalla.thor.rootservice.SuspensionReadbackProtocol as Protocol

class SuspensionSnapshotVerificationTest {
    @Test
    fun `typed framework flags must agree before either known state is returned`() {
        val suspended = SuspensionSnapshot(Protocol.STATUS_SUSPENDED,
            owners = listOf(SuspensionOwnerIdentity("com.android.shell", 10)))
        val notSuspended = SuspensionSnapshot(Protocol.STATUS_NOT_SUSPENDED)
        val notInstalled = SuspensionSnapshot(Protocol.STATUS_NOT_INSTALLED)
        assertSame(suspended, verify(suspended, canonical(suspended = true)))
        assertSame(notSuspended, verify(notSuspended, canonical(suspended = false)))
        assertSame(notInstalled, verify(notInstalled, canonical(installed = false)))
        for (result in listOf(
            verify(notSuspended, canonical(suspended = true)),
            verify(suspended, canonical(suspended = false)),
            verify(notInstalled, canonical()),
            verify(notSuspended, canonical(installed = false)),
        )) {
            assertEquals(Protocol.STATUS_UNKNOWN, result.status)
            assertEquals(Protocol.REASON_STATE_CONTRADICTION, result.reason)
            assertTrue(result.owners.isEmpty())
        }
    }

    @Test
    fun `missing wrong-user wrong-package or internally contradictory flags are unknown`() {
        val candidate = SuspensionSnapshot(Protocol.STATUS_NOT_SUSPENDED)
        for (state in listOf(null, canonical().copy(packageName = "com.other.app"),
            canonical().copy(userId = 0), canonical(installed = false, suspended = true))) {
            val result = verify(candidate, state)
            assertEquals(Protocol.STATUS_UNKNOWN, result.status)
            assertEquals(Protocol.REASON_CANONICAL_STATE_UNAVAILABLE, result.reason)
        }
    }

    @Test
    fun `good framework flags do not promote unknown owner parsing to success`() {
        val unknown = unknownSuspensionSnapshot(Protocol.REASON_INCOMPLETE_OWNERS)
        assertSame(unknown, verify(unknown, canonical(suspended = true)))
        val refused = SuspensionSnapshot(Protocol.STATUS_REFUSED, Protocol.REASON_PLATFORM_REFUSED)
        assertSame(refused, verify(refused, canonical()))
    }

    @Test
    fun `malicious manifest version text cannot manufacture confirmed nonsuspension`() {
        // versionName is unescaped in dumpsys. Even a structurally convincing forged user section
        // must agree with the independently typed PackageManager observation before it is trusted.
        val forged = """
            Packages:
              Package [$TARGET] (a1):
                versionName=1.0
                User 10: installed=true suspended=false
            Queries:
                User 10: installed=true suspended=true
                Suspend params:
                  suspendingPackage=<10>com.android.shell dialogInfo=null
        """.trimIndent()
        val parsed = parseSuspensionDump(forged, TARGET, 10, 37)
        val result = verify(parsed, canonical(suspended = true))
        assertEquals(Protocol.STATUS_UNKNOWN, result.status)
        assertTrue(result.owners.isEmpty())
    }

    private fun verify(snapshot: SuspensionSnapshot, state: CanonicalSuspensionState?) =
        verifySuspensionSnapshot(snapshot, state, TARGET, 10)

    private fun canonical(installed: Boolean = true, suspended: Boolean = false) =
        CanonicalSuspensionState(TARGET, 10, installed, suspended)

    companion object {
        private const val TARGET = "com.example.target"
    }
}
