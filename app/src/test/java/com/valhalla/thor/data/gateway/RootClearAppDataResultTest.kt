// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import android.app.Application
import android.content.ServiceConnection
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.gateway.root.*
import com.valhalla.thor.domain.model.*
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.rootservice.IThorRootService
import com.valhalla.thor.rootservice.RootDataClearResult
import com.valhalla.thor.rootservice.RootDataClearProtocol as P
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class RootClearAppDataResultTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val journal = RootDataClearBarrier(context)

    @Test fun `one typed clear targets Thor user and removes only its terminal record`() = runBlocking {
        ShadowProcess.setUid(10 * 100_000 + 10_000)
        val service = RecordingService()
        gateway(service).clearAppData(PACKAGE, PrivilegeExecutionContext()).getOrThrow()
        assertEquals(listOf(PACKAGE to 10), service.submits.map { it.packageName to it.userId })
        assertNull(journal.pending(PACKAGE, 10))
        assertEquals(0, service.legacyCalls)
    }

    @Test fun `unknown result survives gateway recreation and queries without another wipe`() = runBlocking {
        val service = RecordingService().apply { unknown = true }
        val first = gateway(service).clearAppData(PACKAGE, PrivilegeExecutionContext())
        assertTrue(first.exceptionOrNull() is RootDataClearUnresolved)
        val pending = checkNotNull(journal.pending(PACKAGE))
        service.unknown = false
        gateway(service).clearAppData(PACKAGE, PrivilegeExecutionContext()).getOrThrow()
        assertEquals(1, service.submits.size)
        assertEquals(listOf(pending.id), service.queries)
        assertNull(journal.pending(PACKAGE))
    }

    @Test fun `binder death and old null reply retain package barrier without fallback`() = runBlocking {
        for (failure in listOf<Exception?>(DeadObjectException(), null)) {
            val target = "com.example.target${if (failure == null) 1 else 2}"
            val service = RecordingService().apply { thrown = failure; nullReply = failure == null }
            assertTrue(gateway(service).clearAppData(target, PrivilegeExecutionContext()).exceptionOrNull() is RootDataClearUnresolved)
            assertNotNull(journal.pending(target))
            assertEquals(1, service.submits.size)
            assertEquals(0, service.legacyCalls)
        }
    }

    @Test fun `negative callback is terminal failure and releases barrier without claiming rollback`() = runBlocking {
        val service = RecordingService().apply { failed = true }
        val result = gateway(service).clearAppData(PACKAGE, PrivilegeExecutionContext())
        assertTrue(result.exceptionOrNull()!!.message!!.contains("partial changes"))
        assertNull(journal.pending(PACKAGE))
        assertEquals(1, service.submits.size)
    }

    @Test fun `cancellation after dispatch propagates and retains recovery identity`() = runBlocking {
        val service = RecordingService().apply { thrown = CancellationException("caller left") }
        val caught = try { gateway(service).clearAppData(PACKAGE, PrivilegeExecutionContext()); null }
            catch (cancelled: CancellationException) { cancelled }
        assertEquals("caller left", caught?.message)
        assertNotNull(journal.pending(PACKAGE))
        assertEquals(1, service.submits.size)
        assertEquals(0, service.legacyCalls)
    }

    @Test fun `self clear and invalid package never bind or dispatch`() = runBlocking {
        val service = RecordingService()
        for (target in listOf(context.packageName, "bad package", "")) {
            assertTrue(gateway(service).clearAppData(target, PrivilegeExecutionContext()).isFailure)
        }
        assertTrue(service.submits.isEmpty())
        assertEquals(0, service.binds)
    }

    @Test fun `lost daemon record remains blocked and never resubmits original identity`() = runBlocking {
        val service = RecordingService().apply { unknown = true }
        gateway(service).clearAppData(PACKAGE, PrivilegeExecutionContext())
        val replacement = RecordingService().apply { missing = true }
        assertTrue(gateway(replacement).clearAppData(PACKAGE, PrivilegeExecutionContext()).isFailure)
        assertTrue(replacement.submits.isEmpty())
        assertEquals(1, replacement.queries.size)
        assertNotNull(journal.pending(PACKAGE))
    }

    private fun gateway(service: RecordingService) = RootSystemGateway(
        context, object : RootCommandExecutor {
            override suspend fun execute(command: RootCommand): RootCommandResult = error("A clear must never fall back to shell")
        }, FakePreferenceRepository(), Dispatchers.IO, TestRootAdmission(),
        rootServiceConnection = RootServiceConnectionOwner(object : RootServiceBinding {
            override fun bind(connection: ServiceConnection) { service.binds++; connection.onServiceConnected(null, service.asBinder()) }
            override fun unbind(connection: ServiceConnection) = Unit
        }, Dispatchers.Default), dataClearJournal = journal,
    )

    private class RecordingService : IThorRootService.Default() {
        val submits = mutableListOf<RootDataClearRecord>()
        val queries = mutableListOf<String>()
        var binds = 0
        var legacyCalls = 0
        var unknown = false
        var failed = false
        var nullReply = false
        var missing = false
        var thrown: Exception? = null
        private val daemon = UUID.randomUUID().toString()
        private val binder = Binder().apply { attachInterface(this@RecordingService, "com.valhalla.thor.rootservice.IThorRootService") }
        override fun asBinder(): IBinder = binder
        override fun clearAppDataForUser(packageName: String, userId: Int): Boolean { legacyCalls++; return true }
        override fun clearAppDataForUserWithResult(requestId: String, packageName: String, userId: Int): RootDataClearResult? {
            submits += RootDataClearRecord(requestId, packageName, userId, null)
            thrown?.let { throw it }
            return if (nullReply) null else result(requestId, packageName, userId)
        }
        override fun getClearAppDataResult(requestId: String, packageName: String, userId: Int): RootDataClearResult {
            queries += requestId
            return result(requestId, packageName, userId)
        }
        private fun result(id: String, target: String, user: Int) = RootDataClearResult().apply {
            protocolVersion = P.VERSION; requestId = id; daemonInstanceId = daemon; packageName = target; userId = user
            status = if (unknown || missing) P.STATUS_UNKNOWN else if (failed) P.STATUS_FAILED else P.STATUS_CLEARED
            dispatchState = if (missing) P.DISPATCH_UNKNOWN else P.DISPATCH_ACCEPTED
            callbackState = if (unknown || missing) P.CALLBACK_NONE else if (failed) P.CALLBACK_FAILED else P.CALLBACK_SUCCEEDED
            reason = if (missing) P.REASON_NOT_FOUND else if (unknown) P.REASON_AWAITING_CALLBACK else if (failed) P.REASON_CALLBACK_FAILED else P.REASON_NONE
        }
    }

    private companion object { const val PACKAGE = "com.example.target" }
}
