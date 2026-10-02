// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.model.RootLaneStatusSource
import com.valhalla.thor.rootservice.IThorRootService
import com.valhalla.thor.rootservice.SuspensionReadbackProtocol
import com.valhalla.thor.rootservice.SuspensionReadbackResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Root checks require odinRoot=true; mutations additionally require the exact disposable fixture. */
@RunWith(AndroidJUnit4::class)
class OdinSuspensionReadbackIntegrationTest {
    @Test
    fun existingTransactionSlotsStayInOrderAndNewReadDoesNotReplayAgainstOldService() {
        // Force the generated Proxy/Parcel path without dispatching any actual package mutation.
        val transactions = mutableListOf<Int>()
        val legacyBinder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                transactions += code
                data.enforceInterface(DESCRIPTOR)
                if (code == IBinder.FIRST_CALL_TRANSACTION + 6) return false
                requireNotNull(reply).writeNoException()
                if (code == IBinder.FIRST_CALL_TRANSACTION + 3) reply.writeString(null)
                else reply.writeInt(0)
                return true
            }
        }
        val service = IThorRootService.Stub.asInterface(legacyBinder)
        service.setAppSuspended(TARGET, false)
        service.clearAppData(TARGET)
        service.setAppSuspendedAs(TARGET, false, null)
        service.dumpPackage(TARGET)
        service.clearAppDataForUser(TARGET, 10)
        service.setAppSuspendedAsForUser(TARGET, false, null, 10)
        assertEquals((0..5).map { IBinder.FIRST_CALL_TRANSACTION + it }, transactions)
        transactions.clear()

        val result = RootSuspensionReadbackClient(service).read(TARGET, 10)

        assertTrue("An old service cannot confirm a new read", result is RootSuspensionReadback.Unknown)
        assertEquals(listOf(IBinder.FIRST_CALL_TRANSACTION + 6), transactions)
    }

    @Test
    fun ownPackageReadIsExplicitCompactAndLegacyDumpSlotRemainsReadable() = runBlocking {
        val (_, service) = readyService()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withContext(Dispatchers.IO) {
            val reply = requireNotNull(service.getSuspensionStateForUser(context.packageName, thorUserId))
            assertEquals(SuspensionReadbackProtocol.VERSION, reply.protocolVersion)
            assertEquals(context.packageName, reply.packageName)
            assertEquals(thorUserId, reply.userId)
            assertEquals(SuspensionReadbackProtocol.STATUS_NOT_SUSPENDED, reply.status)
            assertTrue(reply.owners.isEmpty())
            assertEquals(RootSuspensionReadback.NotSuspended,
                RootSuspensionReadbackClient(service).read(context.packageName, thorUserId))
            val parcel = Parcel.obtain()
            try {
                reply.writeToParcel(parcel, 0)
                assertTrue("The typed response must contain only compact metadata", parcel.dataSize() < 1_024)
                parcel.setDataPosition(0)
                val decoded = SuspensionReadbackResult.CREATOR.createFromParcel(parcel)
                assertEquals(reply.packageName, decoded.packageName)
                assertEquals(reply.status, decoded.status)
            } finally {
                parcel.recycle()
            }
            // Compatibility read only: never save or print a full package dump in evidence.
            assertTrue(service.dumpPackage(context.packageName).orEmpty()
                .contains("Package [${context.packageName}]"))
        }
    }

    @Test
    fun invalidAndOtherUserRequestsAreRefusedWhileMissingPackageIsUnknown() = runBlocking {
        val (_, service) = readyService()
        val ownPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        withContext(Dispatchers.IO) {
            for ((name, user) in listOf(ownPackage to -1, ownPackage to (thorUserId + 1), "-invalid" to thorUserId)) {
                val reply = requireNotNull(service.getSuspensionStateForUser(name, user))
                assertEquals(SuspensionReadbackProtocol.STATUS_REFUSED, reply.status)
                assertTrue(reply.owners.isEmpty())
            }
            val absent = requireNotNull(service.getSuspensionStateForUser("com.valhalla.thor.audit.absent", thorUserId))
            assertEquals("A missing package block is not evidence of an unsuspended user",
                SuspensionReadbackProtocol.STATUS_UNKNOWN, absent.status)
            assertTrue(absent.owners.isEmpty())
        }
    }

    @Test
    fun gatewayRemovesBothRecordedOwnersAndConfirmsDisposableFixtureState() = runBlocking {
        assumeTrue("Explicit suspension fixture opt-in required", InstrumentationRegistry.getArguments()
            .getString("odinSuspensionFixture") == TARGET)
        val (gateway, service) = readyService()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getApplicationInfo(TARGET, 0)
        assertTrue(info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertTrue(info.flags and ApplicationInfo.FLAG_INSTALLED != 0)
        assertFalse(info.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        assertEquals(thorUserId, info.uid / 100_000)
        fun suspended() = context.packageManager.getApplicationInfo(TARGET, 0)
            .flags and ApplicationInfo.FLAG_SUSPENDED != 0
        assertFalse("Fixture must start unsuspended", suspended())
        val ownOwner = RootSuspensionOwner(context.packageName, thorUserId)
        val shellOwner = RootSuspensionOwner("com.android.shell", thorUserId)
        suspend fun read() = withContext(Dispatchers.IO) {
            RootSuspensionReadbackClient(service).read(TARGET, thorUserId)
        }
        assertEquals(RootSuspensionReadback.NotSuspended, read())
        var primary: Throwable? = null
        try {
            gateway.setAppSuspended(TARGET, true).getOrThrow()
            assertTrue(suspended())
            val first = read()
            assertTrue("Own suspension must have a complete readback: $first", first is RootSuspensionReadback.Suspended)
            assertEquals(listOf(ownOwner), (first as RootSuspensionReadback.Suspended).owners)

            assertTrue(withContext(Dispatchers.IO) {
                service.setAppSuspendedAsForUser(TARGET, true, shellOwner.packageName, thorUserId)
            })
            val multiple = read()
            assertTrue(multiple is RootSuspensionReadback.Suspended)
            assertEquals(setOf(ownOwner, shellOwner), (multiple as RootSuspensionReadback.Suspended).owners.toSet())

            gateway.setAppSuspended(TARGET, false).getOrThrow()
            assertFalse(suspended())
            assertEquals(RootSuspensionReadback.NotSuspended, read())
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            // Explicit fixture cleanup names only the two owners created by this test.
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    if (suspended()) {
                        service.setAppSuspendedAsForUser(TARGET, false, ownOwner.packageName, thorUserId)
                        service.setAppSuspendedAsForUser(TARGET, false, shellOwner.packageName, thorUserId)
                    }
                    assertFalse("Fixture suspension must be restored", suspended())
                } catch (cleanup: Throwable) {
                    primary?.addSuppressed(cleanup) ?: throw cleanup
                }
            }
        }
    }

    private suspend fun readyService(): Pair<RootSystemGateway, IThorRootService> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("odinRoot") == "true")
        val koin = GlobalContext.get()
        val lanes = requireNotNull(koin.getOrNull<RootLaneStatusSource>())
        val gateway = requireNotNull(koin.getOrNull<RootSystemGateway>())
        withTimeout(30_000) {
            lanes.statuses.first { it.values.none { lane -> lane.activeCommandClass != null } }
            assertTrue(requireNotNull(koin.getOrNull<PrivilegeManager>()).refreshAndAwait()
                .rootAvailability.canAdmitRoot)
            lanes.statuses.first { it.values.none { lane -> lane.activeCommandClass != null } }
        }
        return gateway to requireNotNull(gateway.getRootService())
    }

    private companion object {
        const val TARGET = "com.valhalla.thor.audit.cleardata"
        const val DESCRIPTOR = "com.valhalla.thor.rootservice.IThorRootService"
    }
}
