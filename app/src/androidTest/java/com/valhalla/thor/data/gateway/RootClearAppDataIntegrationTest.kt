// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.superuser.ipc.RootService
import com.valhalla.superuser.ktx.getShellAwait
import com.valhalla.thor.data.gateway.root.RootCommand
import com.valhalla.thor.data.gateway.root.RootCommandExecutor
import com.valhalla.thor.data.gateway.root.RootCommandResult
import com.valhalla.thor.data.gateway.root.RootDataClearBarrier
import com.valhalla.thor.data.gateway.root.RootDataClearPackageBarrier
import com.valhalla.thor.data.gateway.root.RootServiceBinding
import com.valhalla.thor.data.gateway.root.RootServiceConnectionOwner
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.RootDataClearUnresolved
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.RootAdmissionController
import com.valhalla.thor.domain.repository.PackageOperationCoordinator
import com.valhalla.thor.rootservice.RootDataClearDelayedObserverFixture as Fixture
import com.valhalla.thor.rootservice.RootDataClearProtocol as Protocol
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Opt-in destructive checks restricted to one installed, disposable, debuggable fixture. */
@RunWith(AndroidJUnit4::class)
class RootClearAppDataIntegrationTest {
    private lateinit var context: Context
    private lateinit var preferences: PreferenceRepository
    private val noShell = object : RootCommandExecutor {
        override suspend fun execute(command: RootCommand): RootCommandResult =
            error("Typed clear-data must not invoke a shell fallback")
    }

    @Before
    fun requireDisposableFixtureAndAppRoot() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit root clear-data fixture opt-in required",
            arguments.getString("odinRoot") == "true" && arguments.getString("odinClearDataTestPackage") == TARGET)
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertFalse(TARGET == context.packageName)
        val info = context.packageManager.getApplicationInfo(TARGET, 0)
        assertTrue(info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertFalse(info.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        assertTrue(info.flags and ApplicationInfo.FLAG_INSTALLED != 0)
        assertEquals(thorUserId, info.uid / 100_000)
        val koin = GlobalContext.get()
        withTimeout(30_000) { requireNotNull(koin.getOrNull<PrivilegeManager>()).refreshAndAwait() }
        withTimeout(10_000) { assertTrue("Thor itself must have root", getShellAwait().isRoot) }
        preferences = requireNotNull(koin.getOrNull<PreferenceRepository>())
    }

    @Test fun typedGatewayClearErasesFixtureMarker() = runBlocking {
        val marker = createMarker()
        try {
            withTimeout(45_000) { gateway().clearAppData(TARGET, PrivilegeExecutionContext()).getOrThrow() }
            assertErased(marker)
            assertNull(RootDataClearBarrier(context).pending(TARGET))
        } finally { fixtureShell("rm -f files/$marker") }
    }

    @Test fun readOnlyQueryDoesNotClearNewData() = runBlocking {
        val service = assertNotNullService(gateway())
        val id = UUID.randomUUID().toString()
        val marker = createMarker()
        var after: String? = null
        try {
            val result = withContext(Dispatchers.IO) { service.clearAppDataForUserWithResult(id, TARGET, thorUserId) }
            assertEquals(Protocol.STATUS_CLEARED, result.status)
            assertEquals(Protocol.DISPATCH_ACCEPTED, result.dispatchState)
            assertEquals(Protocol.CALLBACK_SUCCEEDED, result.callbackState)
            assertErased(marker)
            after = createMarker()
            val query = withContext(Dispatchers.IO) { service.getClearAppDataResult(id, TARGET, thorUserId) }
            assertEquals(Protocol.STATUS_CLEARED, query.status)
            assertEquals(result.daemonInstanceId, query.daemonInstanceId)
            assertEquals(id, query.requestId)
            assertEquals("./files/$after", fixtureShell("find . -name $after"))
            // Duplicate submission is also observational for this retained daemon identity.
            val duplicate = withContext(Dispatchers.IO) { service.clearAppDataForUserWithResult(id, TARGET, thorUserId) }
            assertEquals(Protocol.STATUS_CLEARED, duplicate.status)
            assertEquals("./files/$after", fixtureShell("find . -name $after"))
        } finally {
            fixtureShell("rm -f files/$marker")
            after?.let { fixtureShell("rm -f files/$it") }
        }
    }

    @Test fun realDelayedObserverRetainsBarrierAndRecoversWithoutAnotherClear() = runBlocking {
        val connection = RootServiceConnectionOwner(object : RootServiceBinding {
            override fun bind(connection: ServiceConnection) {
                RootService.bind(Intent(context, Fixture::class.java), connection)
            }
            override fun unbind(connection: ServiceConnection) = RootService.unbind(connection)
        })
        val gateway = RootSystemGateway(context, noShell, preferences, Dispatchers.IO,
            requireNotNull(GlobalContext.get().getOrNull<RootAdmissionController>()), rootServiceConnection = connection)
        val service = assertNotNullService(gateway)
        val marker = createMarker()
        var after: String? = null
        var request: String? = null
        try {
            val result = withTimeout(30_000) { gateway.clearAppData(TARGET, PrivilegeExecutionContext()) }
            assertTrue("Observation timeout must retain an uncertain result", result.exceptionOrNull() is RootDataClearUnresolved)
            val recoveredJournal = RootDataClearBarrier(context)
            val record = requireNotNull(recoveredJournal.pending(TARGET))
            request = record.id
            withTimeout(15_000) {
                while (!fixtureControl(service.asBinder(), Fixture.OBSERVED, record.id)) delay(25)
            }
            assertErased(marker)
            after = createMarker()
            val productionPackages = requireNotNull(GlobalContext.get().getOrNull<PackageOperationCoordinator>())
            assertEquals(PackageLeaseResult.Busy(PackageOperationOwner.CLEAR_DATA),
                productionPackages.withPackageLease(TARGET, PackageOperationOwner.ARCHIVE_RESTORE, Duration.ZERO) {
                    error("Production DI must retain the unresolved package barrier")
                })
            // A fresh client/journal uses the real persisted identity; only callback delivery is held.
            val barrier = RootDataClearPackageBarrier(context, recoveredJournal).apply { serviceProvider = { service } }
            val packages = DefaultPackageOperationCoordinator(barrier)
            val blocked = packages.withPackageLease(TARGET, PackageOperationOwner.ARCHIVE_RESTORE, Duration.ZERO) {
                error("Conflicting work must not start before acknowledgement")
            }
            assertEquals(PackageLeaseResult.Busy(PackageOperationOwner.CLEAR_DATA), blocked)
            assertTrue(fixtureControl(service.asBinder(), Fixture.RELEASE, record.id))
            val allowed = packages.withPackageLease(TARGET, PackageOperationOwner.ARCHIVE_RESTORE, Duration.ZERO) { "admitted" }
            assertEquals(PackageLeaseResult.Acquired("admitted"), allowed)
            assertNull(recoveredJournal.pending(TARGET))
            assertEquals("./files/$after", fixtureShell("find . -name $after"))
        } finally {
            // Deliver the genuine held callback even on assertion failure. Retire the record only
            // if the real ledger confirms completion; never delete unresolved recovery metadata.
            request?.let { id ->
                fixtureControl(service.asBinder(), Fixture.RELEASE, id)
                val result = withContext(Dispatchers.IO) { service.getClearAppDataResult(id, TARGET, thorUserId) }
                if (result.status == Protocol.STATUS_CLEARED || result.status == Protocol.STATUS_FAILED) {
                    val journal = RootDataClearBarrier(context)
                    journal.pending(TARGET)?.takeIf { it.id == id }?.let { journal.finish(it) }
                }
            }
            fixtureShell("rm -f files/$marker")
            after?.let { fixtureShell("rm -f files/$it") }
        }
    }

    private fun gateway() = RootSystemGateway(context, noShell, preferences, Dispatchers.IO,
        requireNotNull(GlobalContext.get().getOrNull<RootAdmissionController>()))

    private suspend fun assertNotNullService(gateway: RootSystemGateway) = withContext(Dispatchers.IO) {
        gateway.getRootService().also { assertNotNull("Root service must bind", it) }!!
    }

    private suspend fun fixtureControl(binder: IBinder, code: Int, id: String): Boolean = withContext(Dispatchers.IO) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(Fixture.DESCRIPTOR)
            data.writeString(id)
            check(binder.transact(code, data, reply, 0))
            reply.readException()
            reply.readInt() == 1
        } finally { data.recycle(); reply.recycle() }
    }

    private fun createMarker(): String = "thor-clear-${UUID.randomUUID()}".also {
        fixtureShell("mkdir -p files")
        fixtureShell("touch files/$it")
        assertEquals("./files/$it", fixtureShell("find . -name $it"))
    }

    private fun assertErased(marker: String) {
        assertEquals("", fixtureShell("find . -name $marker"))
        assertTrue(context.packageManager.getApplicationInfo(TARGET, 0).flags and ApplicationInfo.FLAG_INSTALLED != 0)
    }

    private fun fixtureShell(arguments: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("/system/bin/toybox timeout 10 run-as $TARGET $arguments")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
    }

    private companion object { const val TARGET = "com.valhalla.thor.audit.cleardata" }
}
