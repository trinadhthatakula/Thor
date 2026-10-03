// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import android.app.Application
import android.content.ContextWrapper
import android.os.DeadObjectException
import android.os.RemoteException
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.rootservice.IThorRootService
import com.valhalla.thor.rootservice.RootDataClearProtocol as P
import com.valhalla.thor.rootservice.RootDataClearResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class RootDataClearPackageBarrierTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = object : ContextWrapper(null) {
        override fun getNoBackupFilesDir(): File = temporary.root
        override fun getPackageName(): String = CONTROLLER
    }

    @Before
    fun setUser() {
        ShadowProcess.setUid(USER * 100_000 + 10_000)
    }

    @Test
    fun `late terminal observations retire only the existing record through one read-only query`() = runTest {
        for (status in listOf(P.STATUS_CLEARED, P.STATUS_FAILED, P.STATUS_REFUSED)) {
            val journal = journal()
            val record = journal.markBinder(journal.begin(PACKAGE, USER))
            val service = RecordingService { terminal(record, status) }
            val guard = guard(journal, service)

            assertFalse(guard.isBlocked(PACKAGE, PackageOperationOwner.ARCHIVE_RESTORE))

            assertNull(journal.pending(PACKAGE, USER))
            assertEquals(listOf(Triple(record.id, PACKAGE, USER)), service.queries)
            assertEquals(0, service.submissions)
            assertEquals(0, service.legacyCalls)
        }
    }

    @Test
    fun `repeated clear gesture recovers the old result but cannot become a second wipe`() = runTest {
        val journal = journal()
        val record = journal.markBinder(journal.begin(PACKAGE, USER))
        val service = RecordingService { terminal(record) }
        val guard = guard(journal, service)

        assertTrue(guard.isBlocked(PACKAGE, PackageOperationOwner.CLEAR_DATA))
        assertNull(journal.pending(PACKAGE, USER))
        assertFalse(guard.isBlocked(PACKAGE, PackageOperationOwner.CLEAR_DATA))

        assertEquals(listOf(Triple(record.id, PACKAGE, USER)), service.queries)
        assertEquals(0, service.submissions)
        assertEquals(0, service.legacyCalls)
    }

    @Test
    fun `prepared work retires before admitting another operation and its stale owner cannot dispatch`() = runTest {
        val journal = journal()
        val record = journal.begin(PACKAGE, USER)
        val service = RecordingService { error("No query can resolve a prepared record") }
        val guard = guard(journal, service)

        assertFalse(guard.isBlocked(PACKAGE, PackageOperationOwner.REINSTALL))
        assertFalse(guard.isBlocked("com.example.other", PackageOperationOwner.REINSTALL))
        assertFalse(guard.isGlobalBlocked())
        assertNull(journal.pending(PACKAGE, USER))
        val staleOwnerFailure = try {
            journal.markBinder(record)
            null
        } catch (failure: RootDataClearJournalUnavailable) {
            failure
        }
        assertTrue(staleOwnerFailure is RootDataClearJournalUnavailable)
        assertNull(journal.pending(PACKAGE, USER))
        assertTrue(service.queries.isEmpty())
        assertEquals(0, service.submissions)
        assertEquals(0, service.legacyCalls)
    }

    @Test
    fun `clear gesture retires prepared work and refuses once without querying or dispatching`() = runTest {
        val journal = journal()
        journal.begin(PACKAGE, USER)
        val service = RecordingService { error("Prepared recovery must not query or mutate") }
        val guard = guard(journal, service)

        assertTrue(guard.isBlocked(PACKAGE, PackageOperationOwner.CLEAR_DATA))
        assertNull(journal.pending(PACKAGE, USER))
        assertFalse(guard.isGlobalBlocked())
        assertFalse(guard.isBlocked(PACKAGE, PackageOperationOwner.CLEAR_DATA))

        assertTrue(service.queries.isEmpty())
        assertEquals(0, service.submissions)
        assertEquals(0, service.legacyCalls)
    }

    @Test
    fun `missing service or a new daemon with no record keeps ownership retained`() = runTest {
        val journal = journal()
        val record = journal.markBinder(journal.begin(PACKAGE, USER))
        val absent = guard(journal, null)
        assertTrue(absent.isBlocked(PACKAGE, PackageOperationOwner.UNINSTALL))
        assertEquals(record, journal.pending(PACKAGE, USER))

        val replacement = RecordingService {
            terminal(record).apply {
                daemonInstanceId = NEW_DAEMON
                status = P.STATUS_UNKNOWN
                dispatchState = P.DISPATCH_UNKNOWN
                callbackState = P.CALLBACK_NONE
                reason = P.REASON_NOT_FOUND
            }
        }
        assertTrue(guard(journal, replacement).isBlocked(PACKAGE, PackageOperationOwner.REINSTALL))
        assertEquals(record, journal.pending(PACKAGE, USER))
        assertEquals(1, replacement.queries.size)
        assertEquals(0, replacement.submissions)
    }

    @Test
    fun `old null malformed and uncertain replies never release the retained record`() = runTest {
        val journal = journal()
        val record = journal.markBinder(journal.begin(PACKAGE, USER))
        val replies = listOf(
            null,
            terminal(record).apply { requestId = NEW_DAEMON },
            terminal(record).apply { userId = USER + 1 },
            terminal(record).apply { callbackState = P.CALLBACK_NONE },
            terminal(record).apply {
                status = P.STATUS_UNKNOWN
                dispatchState = P.DISPATCH_ACCEPTED
                callbackState = P.CALLBACK_NONE
                reason = P.REASON_WAIT_EXPIRED
            },
        )
        for (reply in replies) {
            val service = RecordingService { reply }
            assertTrue(guard(journal, service).isBlocked(PACKAGE, PackageOperationOwner.ARCHIVE_BACKUP))
            assertEquals(record, journal.pending(PACKAGE, USER))
            assertEquals(1, service.queries.size)
            assertEquals(0, service.submissions)
            assertEquals(0, service.legacyCalls)
        }
    }

    @Test
    fun `query transport failures remain blocked and cancellation propagates without removing ownership`() = runTest {
        val journal = journal()
        val record = journal.markBinder(journal.begin(PACKAGE, USER))
        for (failure in listOf(DeadObjectException(), RemoteException("reply lost"))) {
            val service = RecordingService { throw failure }
            assertTrue(guard(journal, service).isBlocked(PACKAGE, PackageOperationOwner.REINSTALL))
            assertEquals(record, journal.pending(PACKAGE, USER))
            assertEquals(1, service.queries.size)
            assertEquals(0, service.submissions)
        }
        val cancelled = CancellationException("cancel query")
        val service = RecordingService { throw cancelled }
        val caught = try {
            guard(journal, service).isBlocked(PACKAGE, PackageOperationOwner.ARCHIVE_RESTORE)
            null
        } catch (failure: CancellationException) {
            failure
        }
        assertEquals(cancelled.message, caught?.message)
        assertEquals(record, journal.pending(PACKAGE, USER))
        assertEquals(1, service.queries.size)
        assertEquals(0, service.submissions)
    }

    @Test
    fun `self destructive operations protect both runtime and build identities without a pending record`() = runTest {
        val service = RecordingService { error("Control-plane refusal must not query or mutate") }
        val guard = guard(journal(), service)
        for (packageName in setOf(CONTROLLER, BuildConfig.APPLICATION_ID)) {
            for (owner in listOf(PackageOperationOwner.CLEAR_DATA, PackageOperationOwner.UNINSTALL, PackageOperationOwner.ARCHIVE_RESTORE)) {
                assertTrue(guard.isBlocked(packageName, owner))
            }
            assertFalse(guard.isBlocked(packageName, PackageOperationOwner.FORCE_STOP))
        }
        assertTrue(service.queries.isEmpty())
        assertEquals(0, service.submissions)
    }

    @Test
    fun `unreadable journal blocks targeted and global operations without querying or invoking`() = runTest {
        val journal = journal()
        journal.begin(PACKAGE, USER)
        File(temporary.root, "root_data_clear_recovery/pending.json").writeText("{corrupt")
        val service = RecordingService { error("No service can repair unreadable ownership") }
        val guard = guard(journal, service)
        var invoked = false

        assertTrue(guard.isBlocked("com.example.other", PackageOperationOwner.REINSTALL))
        assertTrue(guard.isGlobalBlocked())
        assertEquals(PackageLeaseResult.Busy(PackageOperationOwner.CLEAR_DATA), guard.withGlobalLease { invoked = true })

        assertFalse(invoked)
        assertTrue(service.queries.isEmpty())
        assertEquals(0, service.submissions)
    }

    private fun journal() = RootDataClearBarrier(context).apply { bootIdProvider = { BOOT } }

    private fun guard(journal: RootDataClearBarrier, service: IThorRootService?) =
        RootDataClearPackageBarrier(context, journal).apply { serviceProvider = { service } }

    private class RecordingService(private val response: () -> RootDataClearResult?) : IThorRootService.Default() {
        val queries = mutableListOf<Triple<String, String, Int>>()
        var submissions = 0
        var legacyCalls = 0
        override fun getClearAppDataResult(requestId: String, packageName: String, userId: Int): RootDataClearResult? {
            queries += Triple(requestId, packageName, userId)
            return response()
        }
        override fun clearAppDataForUserWithResult(requestId: String, packageName: String, userId: Int): RootDataClearResult? {
            submissions++
            error("Reconciliation must never submit another wipe")
        }
        override fun clearAppData(packageName: String): Boolean {
            legacyCalls++
            error("Reconciliation must never use a legacy mutation")
        }
        override fun clearAppDataForUser(packageName: String, userId: Int): Boolean {
            legacyCalls++
            error("Reconciliation must never use a legacy mutation")
        }
    }

    private companion object {
        const val CONTROLLER = "com.example.controller"
        const val PACKAGE = "com.example.target"
        const val USER = 10
        const val BOOT = "66e126cd-c2a4-4b34-b868-10c85443e6bb"
        const val DAEMON = "05bcae28-a75b-4426-9a1b-ee5e38c050b3"
        const val NEW_DAEMON = "b58eec9d-06d8-4642-a34f-c167f22e0730"

        fun terminal(record: RootDataClearRecord, status: Int = P.STATUS_CLEARED) = RootDataClearResult().apply {
            protocolVersion = P.VERSION
            requestId = record.id
            daemonInstanceId = DAEMON
            packageName = record.packageName
            userId = record.userId
            this.status = status
            dispatchState = if (status == P.STATUS_REFUSED) P.DISPATCH_NOT_STARTED else P.DISPATCH_ACCEPTED
            callbackState = when (status) {
                P.STATUS_CLEARED -> P.CALLBACK_SUCCEEDED
                P.STATUS_FAILED -> P.CALLBACK_FAILED
                else -> P.CALLBACK_NONE
            }
            reason = when (status) {
                P.STATUS_FAILED -> P.REASON_CALLBACK_FAILED
                P.STATUS_REFUSED -> P.REASON_PREPARATION_FAILED
                else -> P.REASON_NONE
            }
        }
    }
}
