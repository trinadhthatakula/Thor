// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import android.app.Application
import android.content.ContextWrapper
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.gateway.root.RootCommand
import com.valhalla.thor.data.gateway.root.RootCommandExecutor
import com.valhalla.thor.data.gateway.root.RootCommandResult
import com.valhalla.thor.data.gateway.root.RootDataClearBarrier
import com.valhalla.thor.data.gateway.root.RootServiceBinding
import com.valhalla.thor.data.gateway.root.RootServiceConnectionOwner
import com.valhalla.thor.data.gateway.root.TestRootAdmission
import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.rootservice.IThorRootService
import com.valhalla.thor.rootservice.RootDataClearResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class RootDataClearGlobalAdmissionCancellationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `clear cancelled while awaiting global admission finishes before that admission is released`() = runTest {
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
            override fun getNoBackupFilesDir(): File = temporary.root
        }
        val journal = RootDataClearBarrier(context).apply { bootIdProvider = { BOOT } }
        val globalEntered = CompletableDeferred<Unit>()
        val releaseGlobal = CompletableDeferred<Unit>()
        val global = async {
            journal.withGlobalLease {
                globalEntered.complete(Unit)
                releaseGlobal.await()
            }
        }
        globalEntered.await()

        val bound = CompletableDeferred<Unit>()
        val service = RecordingService()
        val dispatcher = StandardTestDispatcher(testScheduler)
        var shellCalls = 0
        var binds = 0
        val gateway = RootSystemGateway(
            context,
            object : RootCommandExecutor {
                override suspend fun execute(command: RootCommand): RootCommandResult {
                    shellCalls++
                    error("Cancelled clear must not run a shell command")
                }
            },
            FakePreferenceRepository(), dispatcher, TestRootAdmission(),
            rootServiceConnection = RootServiceConnectionOwner(object : RootServiceBinding {
                override fun bind(connection: ServiceConnection) {
                    binds++
                    connection.onServiceConnected(null, service.asBinder())
                    bound.complete(Unit)
                }

                override fun unbind(connection: ServiceConnection) = Unit
            }, dispatcher),
            dataClearJournal = journal,
        ).apply { userIdProvider = { USER } }
        val observedCancellation = CompletableDeferred<CancellationException>()
        val clear = async {
            try {
                gateway.clearAppData(PACKAGE, PrivilegeExecutionContext())
            } catch (cancelled: CancellationException) {
                observedCancellation.complete(cancelled)
                throw cancelled
            }
        }
        try {
            bound.await()
            // Drain the connected continuation so clear is suspended in begin's admission lock.
            runCurrent()
            assertEquals(1, binds)
            assertFalse(clear.isCompleted)
            assertFalse(global.isCompleted)

            val cancellation = CancellationException("cancel before global admission")
            clear.cancel(cancellation)
            withTimeout(1_000) { clear.join() }

            assertTrue(clear.isCancelled)
            assertEquals(cancellation.message, observedCancellation.await().message)
            assertFalse(releaseGlobal.isCompleted)
            assertFalse(global.isCompleted)
            assertNull(journal.pending(PACKAGE, USER))
            assertFalse(journal.anyPending())
            assertEquals(0, service.submissions)
            assertEquals(0, service.queries)
            assertEquals(0, service.legacyCalls)
            assertEquals(0, shellCalls)
        } finally {
            releaseGlobal.complete(Unit)
            assertEquals(PackageLeaseResult.Acquired(Unit), global.await())
            clear.cancelAndJoin()
        }
    }

    private class RecordingService : IThorRootService.Default() {
        var submissions = 0
        var queries = 0
        var legacyCalls = 0
        private val binder = Binder().apply {
            attachInterface(this@RecordingService, "com.valhalla.thor.rootservice.IThorRootService")
        }

        override fun asBinder(): IBinder = binder

        override fun clearAppDataForUserWithResult(requestId: String, packageName: String, userId: Int): RootDataClearResult? {
            submissions++
            error("Cancelled clear must not submit a wipe")
        }

        override fun getClearAppDataResult(requestId: String, packageName: String, userId: Int): RootDataClearResult? {
            queries++
            error("No dispatched clear exists to query")
        }

        override fun clearAppData(packageName: String): Boolean {
            legacyCalls++
            error("Cancelled clear must not call a legacy mutation")
        }

        override fun clearAppDataForUser(packageName: String, userId: Int): Boolean {
            legacyCalls++
            error("Cancelled clear must not call a legacy mutation")
        }
    }

    private companion object {
        const val PACKAGE = "com.example.target"
        const val USER = 10
        const val BOOT = "66e126cd-c2a4-4b34-b868-10c85443e6bb"
    }
}
