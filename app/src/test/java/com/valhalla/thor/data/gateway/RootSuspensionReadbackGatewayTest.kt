// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import android.app.Application
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.gateway.root.RootCommand
import com.valhalla.thor.data.gateway.root.RootCommandExecutor
import com.valhalla.thor.data.gateway.root.RootCommandResult
import com.valhalla.thor.data.gateway.root.RootServiceBinding
import com.valhalla.thor.data.gateway.root.RootServiceConnectionOwner
import com.valhalla.thor.data.gateway.root.TestRootAdmission
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.rootservice.IThorRootService
import com.valhalla.thor.rootservice.SuspensionOwner
import com.valhalla.thor.rootservice.SuspensionReadbackProtocol as Protocol
import com.valhalla.thor.rootservice.SuspensionReadbackResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class RootSuspensionReadbackGatewayTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ShadowProcess.setUid(USER * 100_000 + 10_000)
    }

    @Test
    fun `unknown refused absent stale and cross-user ownership stop before all writes`() = runTest {
        installSuspended()
        val responses = listOf(
            null,
            reply(Protocol.STATUS_UNKNOWN, reason = Protocol.REASON_MISSING_USER),
            reply(Protocol.STATUS_REFUSED, reason = Protocol.REASON_USER_MISMATCH),
            reply(Protocol.STATUS_NOT_INSTALLED),
            reply(Protocol.STATUS_SUSPENDED, owners = arrayOf(owner("android", 0))),
            reply(Protocol.STATUS_SUSPENDED, owners = arrayOf(owner(), owner("android", 0))),
            reply(Protocol.STATUS_NOT_SUSPENDED).apply { userId = 0 },
        )
        for (response in responses) {
            val fixture = fixture(RecordingService { response })

            assertTrue(fixture.gateway.setAppSuspended(PACKAGE, false, EXECUTION).isFailure)

            assertEquals(listOf(PACKAGE to USER), fixture.service.reads)
            assertTrue(fixture.service.mutations.isEmpty())
            assertTrue(fixture.commands.isEmpty())
            assertEquals(1, fixture.binds)
            assertEquals(0, fixture.service.legacyReads)
        }
    }

    @Test
    fun `local not suspended avoids binding while local suspended vetoes contradictory read`() = runTest {
        installSuspended()
        val contradictory = fixture(RecordingService { reply(Protocol.STATUS_NOT_SUSPENDED) })
        assertTrue(contradictory.gateway.setAppSuspended(PACKAGE, false, EXECUTION).isFailure)
        assertTrue(contradictory.service.mutations.isEmpty())

        setLocalSuspended(false)
        val alreadyDone = fixture(RecordingService { error("No read needed") })
        alreadyDone.gateway.setAppSuspended(PACKAGE, false, EXECUTION).getOrThrow()
        assertEquals(0, alreadyDone.binds)
        assertTrue(alreadyDone.commands.isEmpty())
    }

    @Test
    fun `an uninstalled local package cannot masquerade as an unsuspended package`() = runTest {
        installSuspended()
        localInfo().flags = 0
        val fixture = fixture(RecordingService { reply(Protocol.STATUS_NOT_INSTALLED) })

        assertTrue(fixture.gateway.setAppSuspended(PACKAGE, false, EXECUTION).isFailure)
        assertEquals(1, fixture.service.reads.size)
        assertTrue(fixture.service.mutations.isEmpty())
    }

    @Test
    fun `same-user owners are each removed once and explicit complete read verifies success`() = runTest {
        val service = RecordingService { index ->
            if (index == 0) reply(
                Protocol.STATUS_SUSPENDED,
                owners = arrayOf(owner("com.valhalla.thor.debug"), owner(), owner("root")),
            ) else reply(Protocol.STATUS_NOT_SUSPENDED)
        }
        val fixture = fixture(service)

        fixture.gateway.setAppSuspended(PACKAGE, false, EXECUTION).getOrThrow()

        assertEquals(
            listOf("com.valhalla.thor.debug", "com.android.shell", "root").map { Mutation(PACKAGE, false, it, USER) },
            service.mutations,
        )
        assertEquals(listOf(PACKAGE to USER, PACKAGE to USER), service.reads)
        assertEquals(1, fixture.binds)
        assertTrue(fixture.commands.isEmpty())
    }

    @Test
    fun `empty malformed and unavailable post-read never prove removal`() = runTest {
        val afterReads = listOf(
            reply(Protocol.STATUS_SUSPENDED),
            reply(Protocol.STATUS_UNKNOWN, reason = Protocol.REASON_READ_FAILED),
            reply(Protocol.STATUS_NOT_INSTALLED),
            null,
        )
        for (after in afterReads) {
            val service = RecordingService { index ->
                if (index == 0) reply(Protocol.STATUS_SUSPENDED, owners = arrayOf(owner())) else after
            }
            val fixture = fixture(service)
            assertTrue(fixture.gateway.setAppSuspended(PACKAGE, false, EXECUTION).isFailure)
            assertEquals(1, service.mutations.size)
            assertEquals(2, service.reads.size)
            assertTrue(fixture.commands.isEmpty())
        }
    }

    @Test
    fun `local suspended vetoes complete post-read and recorded remaining owners veto local false`() = runTest {
        installSuspended()
        for (remainingOwners in listOf(false, true)) {
            setLocalSuspended(true)
            val service = RecordingService { index ->
                if (index == 0 || remainingOwners) reply(Protocol.STATUS_SUSPENDED, owners = arrayOf(owner()))
                else reply(Protocol.STATUS_NOT_SUSPENDED)
            }.apply {
                mutate = {
                    if (remainingOwners) setLocalSuspended(false)
                    true
                }
            }
            val fixture = fixture(service)
            assertTrue(fixture.gateway.setAppSuspended(PACKAGE, false, EXECUTION).isFailure)
            assertEquals(1, service.mutations.size)
            assertTrue(fixture.commands.isEmpty())
        }
    }

    @Test
    fun `dispatch failure stops later owners with no rebind or shell replay`() = runTest {
        for (failure in listOf(DeadObjectException(), RemoteException("reply lost"))) {
            val service = RecordingService { index ->
                if (index == 0) reply(Protocol.STATUS_SUSPENDED, owners = arrayOf(owner(), owner("android")))
                else throw failure
            }.apply { mutate = { throw failure } }
            val fixture = fixture(service)

            assertTrue(fixture.gateway.setAppSuspended(PACKAGE, false, EXECUTION).isFailure)

            assertEquals(1, service.mutations.size)
            assertEquals(2, service.reads.size)
            assertEquals(1, fixture.binds)
            assertTrue(fixture.commands.isEmpty())
            assertEquals(0, service.legacyReads)
        }
    }

    @Test
    fun `legacy false stops later writes and independent local read can confirm late success`() = runTest {
        installSuspended()
        val service = RecordingService { index ->
            if (index == 0) reply(Protocol.STATUS_SUSPENDED, owners = arrayOf(owner(), owner("android")))
            else throw RemoteException("reply lost")
        }.apply {
            mutate = {
                setLocalSuspended(false)
                false
            }
        }
        val fixture = fixture(service)

        fixture.gateway.setAppSuspended(PACKAGE, false, EXECUTION).getOrThrow()

        assertEquals(1, service.mutations.size)
        assertEquals(2, service.reads.size)
        assertEquals(1, fixture.binds)
        assertTrue(fixture.commands.isEmpty())
    }

    @Test
    fun `cancellation during read or mutation propagates with no subsequent transaction`() = runTest {
        for (cancelRead in listOf(true, false)) {
            val failure = CancellationException("cancelled")
            val service = RecordingService {
                if (cancelRead) throw failure
                reply(Protocol.STATUS_SUSPENDED, owners = arrayOf(owner(), owner("android")))
            }.apply { mutate = { throw failure } }
            val fixture = fixture(service)

            val caught = try {
                fixture.gateway.setAppSuspended(PACKAGE, false, EXECUTION)
                null
            } catch (cancelled: CancellationException) {
                cancelled
            }

            // Coroutine stack-trace recovery may copy exceptions across withContext. Verify
            // cancellation propagation, not object identity, while still forbidding another call.
            assertEquals(failure.message, caught?.message)
            assertEquals(1, service.reads.size)
            assertEquals(if (cancelRead) 0 else 1, service.mutations.size)
            assertTrue(fixture.commands.isEmpty())
        }
    }

    @Test
    @Config(sdk = [28])
    fun `API 28 retains one shell removal for the current Android user`() = runTest {
        installSuspended()
        val fixture = fixture(RecordingService { error("API 28 must not bind") }) {
            setLocalSuspended(false)
        }

        fixture.gateway.setAppSuspended(PACKAGE, false, EXECUTION).getOrThrow()

        assertEquals("pm unsuspend --user $USER '$PACKAGE'", fixture.commands.single().text)
        assertEquals(0, fixture.binds)
        assertTrue(fixture.service.mutations.isEmpty())
    }

    private fun TestScope.fixture(
        service: RecordingService,
        shellAction: () -> Unit = {},
    ): Fixture {
        val fixture = Fixture(service)
        fixture.gateway = RootSystemGateway(
            context = context,
            rootCommands = object : RootCommandExecutor {
                override suspend fun execute(command: RootCommand): RootCommandResult {
                    fixture.commands += command
                    shellAction()
                    return RootCommandResult(0, emptyList(), emptyList())
                }
            },
            preferenceRepository = FakePreferenceRepository(),
            ioDispatcher = Dispatchers.Unconfined,
            rootAdmission = TestRootAdmission(),
            rootServiceConnection = RootServiceConnectionOwner(
                object : RootServiceBinding {
                    override fun bind(connection: ServiceConnection) {
                        fixture.binds++
                        connection.onServiceConnected(null, service.asBinder())
                    }
                    override fun unbind(connection: ServiceConnection) = Unit
                },
                StandardTestDispatcher(testScheduler),
            ),
        )
        return fixture
    }

    private fun installSuspended() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = PACKAGE
            applicationInfo = ApplicationInfo().apply {
                packageName = PACKAGE
                flags = ApplicationInfo.FLAG_INSTALLED or ApplicationInfo.FLAG_SUSPENDED
                uid = USER * 100_000 + 10_001
            }
        })
    }

    private fun localInfo(): ApplicationInfo = requireNotNull(
        shadowOf(context.packageManager).getInternalMutablePackageInfo(PACKAGE).applicationInfo,
    )

    private fun setLocalSuspended(suspended: Boolean) {
        localInfo().flags = ApplicationInfo.FLAG_INSTALLED or
            (if (suspended) ApplicationInfo.FLAG_SUSPENDED else 0)
    }

    private class Fixture(val service: RecordingService) {
        lateinit var gateway: RootSystemGateway
        val commands = mutableListOf<RootCommand>()
        var binds = 0
    }

    private data class Mutation(val packageName: String, val suspended: Boolean, val owner: String?, val userId: Int)

    private class RecordingService(
        private val read: (Int) -> SuspensionReadbackResult?,
    ) : IThorRootService.Default() {
        private val binder = Binder().apply {
            attachInterface(this@RecordingService, "com.valhalla.thor.rootservice.IThorRootService")
        }
        val reads = mutableListOf<Pair<String, Int>>()
        val mutations = mutableListOf<Mutation>()
        var legacyReads = 0
        var mutate: () -> Boolean = { true }
        override fun asBinder(): IBinder = binder
        override fun getSuspensionStateForUser(packageName: String, userId: Int): SuspensionReadbackResult? {
            reads += packageName to userId
            return read(reads.lastIndex)
        }
        override fun setAppSuspendedAsForUser(
            packageName: String,
            suspended: Boolean,
            suspendingPackage: String?,
            userId: Int,
        ): Boolean {
            mutations += Mutation(packageName, suspended, suspendingPackage, userId)
            return mutate()
        }
        override fun dumpPackage(packageName: String): String? {
            legacyReads++
            error("Gateway must not request a raw package dump")
        }
    }

    private companion object {
        const val PACKAGE = "com.example.target"
        const val USER = 10
        val EXECUTION = PrivilegeExecutionContext()

        fun owner(packageName: String = "com.android.shell", userId: Int = USER) = SuspensionOwner().apply {
            this.packageName = packageName
            this.userId = userId
        }

        fun reply(
            status: Int,
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
