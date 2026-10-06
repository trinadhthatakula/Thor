// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import android.app.Application
import android.os.DeadObjectException
import android.os.RemoteException
import com.valhalla.thor.rootservice.IThorRootService
import com.valhalla.thor.rootservice.SuspensionOwner
import com.valhalla.thor.rootservice.SuspensionReadbackProtocol as Protocol
import com.valhalla.thor.rootservice.SuspensionReadbackResult
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class RootSuspensionReadbackClientTest {
    @Test
    fun `complete states retain target and owner users across one read`() {
        val service = RecordingService {
            reply(Protocol.STATUS_SUSPENDED, owners = arrayOf(owner("com.android.shell", 10), owner("android", 0)))
        }

        assertEquals(
            RootSuspensionReadback.Suspended(listOf(
                RootSuspensionOwner("com.android.shell", 10),
                RootSuspensionOwner("android", 0),
            )),
            RootSuspensionReadbackClient(service).read(PACKAGE, USER),
        )
        assertEquals(listOf(PACKAGE to USER), service.reads)
        assertEquals(0, service.legacyReads)
    }

    @Test
    fun `only explicit complete states become not suspended or not installed`() {
        assertEquals(RootSuspensionReadback.NotSuspended, read(reply(Protocol.STATUS_NOT_SUSPENDED)))
        assertEquals(RootSuspensionReadback.NotInstalled, read(reply(Protocol.STATUS_NOT_INSTALLED)))
        assertEquals(
            RootSuspensionReadback.Unknown(RootSuspensionReadFailure.SERVICE_UNKNOWN, Protocol.REASON_MISSING_USER),
            read(reply(Protocol.STATUS_UNKNOWN, reason = Protocol.REASON_MISSING_USER)),
        )
        assertEquals(
            RootSuspensionReadback.Refused(Protocol.REASON_USER_MISMATCH),
            read(reply(Protocol.STATUS_REFUSED, reason = Protocol.REASON_USER_MISMATCH)),
        )
    }

    @Test
    fun `null and future protocol never fall back to legacy dump`() {
        for (response in listOf(null, reply().apply { protocolVersion = Protocol.VERSION + 1 })) {
            val service = RecordingService { response }
            assertEquals(
                RootSuspensionReadback.Unknown(RootSuspensionReadFailure.UNSUPPORTED_PROTOCOL),
                RootSuspensionReadbackClient(service).read(PACKAGE, USER),
            )
            assertEquals(1, service.reads.size)
            assertEquals(0, service.legacyReads)
        }
    }

    @Test
    fun `wrong identity contradictory status and invalid owner arrays remain unknown`() {
        val invalid = listOf(
            reply().apply { packageName = "com.example.other" },
            reply().apply { userId = 0 },
            reply().apply { status = 1000 },
            reply().apply { reason = 1000 },
            reply().apply { reason = Protocol.REASON_READ_FAILED },
            reply(Protocol.STATUS_SUSPENDED),
            reply(Protocol.STATUS_UNKNOWN),
            reply(Protocol.STATUS_REFUSED),
            reply(Protocol.STATUS_NOT_SUSPENDED, owners = arrayOf(owner())),
            reply(Protocol.STATUS_NOT_INSTALLED, owners = arrayOf(owner())),
            reply(Protocol.STATUS_UNKNOWN, Protocol.REASON_READ_FAILED, arrayOf(owner())),
            reply(Protocol.STATUS_REFUSED, Protocol.REASON_USER_MISMATCH, arrayOf(owner())),
            reply(Protocol.STATUS_SUSPENDED, owners = arrayOf(owner(userId = -1))),
            reply(Protocol.STATUS_SUSPENDED, owners = arrayOf(owner("<10>com.android.shell"))),
            reply(Protocol.STATUS_SUSPENDED, owners = arrayOf(owner("com.evil\n--flag"))),
            reply(Protocol.STATUS_SUSPENDED, owners = arrayOf(owner("a".repeat(Protocol.MAX_PACKAGE_NAME_LENGTH + 1)))),
            reply(Protocol.STATUS_SUSPENDED, owners = arrayOf(owner(), owner())),
            reply(Protocol.STATUS_SUSPENDED, owners = Array(Protocol.MAX_OWNERS + 1) { owner("com.owner.$it") }),
            reply().apply { javaClass.getField("owners").set(this, null) },
        )
        for (response in invalid) {
            assertEquals(
                "status=${response.status}, reason=${response.reason}",
                RootSuspensionReadback.Unknown(RootSuspensionReadFailure.MALFORMED_REPLY),
                read(response),
            )
        }
    }

    @Test
    fun `transport death and exceptions are explicit observations with one dispatch`() {
        val failures = listOf(
            DeadObjectException() to RootSuspensionReadback.Unknown(RootSuspensionReadFailure.TRANSPORT_DIED),
            RemoteException("transport unavailable") to RootSuspensionReadback.Unknown(RootSuspensionReadFailure.TRANSPORT_FAILURE),
            IllegalArgumentException("broken parcel") to RootSuspensionReadback.Unknown(RootSuspensionReadFailure.MALFORMED_REPLY),
            SecurityException("caller rejected") to RootSuspensionReadback.Refused(null),
        )
        for ((failure, expected) in failures) {
            val service = RecordingService { throw failure }
            assertEquals(expected, RootSuspensionReadbackClient(service).read(PACKAGE, USER))
            assertEquals(1, service.reads.size)
            assertEquals(0, service.legacyReads)
        }
    }

    @Test
    fun `invalid requests do not dispatch and cancellation propagates`() {
        val failure = CancellationException("caller left")
        val service = RecordingService { throw failure }
        val client = RootSuspensionReadbackClient(service)
        for ((target, user) in listOf("" to USER, "--all" to USER, PACKAGE to -1)) {
            assertEquals(
                RootSuspensionReadback.Unknown(RootSuspensionReadFailure.INVALID_REQUEST),
                client.read(target, user),
            )
        }
        assertTrue(service.reads.isEmpty())
        val caught = try {
            client.read(PACKAGE, USER)
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }
        assertSame(failure, caught)
        assertEquals(1, service.reads.size)
    }

    private fun read(response: SuspensionReadbackResult): RootSuspensionReadback =
        RootSuspensionReadbackClient(RecordingService { response }).read(PACKAGE, USER)

    private class RecordingService(private val response: () -> SuspensionReadbackResult?) : IThorRootService.Default() {
        val reads = mutableListOf<Pair<String, Int>>()
        var legacyReads = 0
        override fun getSuspensionStateForUser(packageName: String, userId: Int): SuspensionReadbackResult? {
            reads += packageName to userId
            return response()
        }
        override fun dumpPackage(packageName: String): String? {
            legacyReads++
            error("The compact read must never fall back to a legacy package dump")
        }
    }

    private companion object {
        const val PACKAGE = "com.example.target"
        const val USER = 10

        fun owner(packageName: String = "com.android.shell", userId: Int = USER) = SuspensionOwner().apply {
            this.packageName = packageName
            this.userId = userId
        }

        fun reply(
            status: Int = Protocol.STATUS_NOT_SUSPENDED,
            reason: Int = Protocol.REASON_NONE,
            owners: Array<SuspensionOwner> = emptyArray(),
        ) = SuspensionReadbackResult().apply {
            protocolVersion = Protocol.VERSION
            packageName = PACKAGE
            userId = USER
            this.status = status
            this.reason = reason
            this.owners = owners
        }
    }
}
