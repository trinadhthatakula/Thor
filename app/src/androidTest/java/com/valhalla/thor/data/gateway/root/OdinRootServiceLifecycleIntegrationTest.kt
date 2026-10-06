// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.superuser.ipc.RootService
import com.valhalla.superuser.ktx.getShellAwait
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.RootAdmissionController
import com.valhalla.thor.rootservice.RootServiceLifecycleFixture
import com.valhalla.thor.rootservice.RootServiceLifecycleProtocol as Protocol
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Host-coordinated, opt-in checks. Every invocation requires its own UUID run directory. */
@RunWith(AndroidJUnit4::class)
class OdinRootServiceLifecycleIntegrationTest {
    @Test
    fun probeGatewayRebindAndReportIdentity() = runBlocking {
        val run = prepareRun()
        val fixture = FixtureConnection(run.context)
        val binding = RecordedBinding(OdinRootServiceBinding(run.context))
        val commands = mutableListOf<RootCommand>()
        val result = try {
            val client = FixtureClient(fixture.connect())
            val before = client.identity().also { run.assertIdentity(it) }
            val gateway = RootSystemGateway(
                context = run.context,
                rootCommands = object : RootCommandExecutor {
                    override suspend fun execute(command: RootCommand): RootCommandResult {
                        commands += command
                        error("Binding must not issue a shell command: ${command.text}")
                    }
                },
                preferenceRepository = unusedDependency(PreferenceRepository::class.java),
                ioDispatcher = Dispatchers.IO,
                rootAdmission = unusedDependency(RootAdmissionController::class.java),
                rootServiceConnection = RootServiceConnectionOwner(binding),
            )
            val first = requireNotNull(withTimeout(WAIT_MS) { gateway.getRootService() })
            assertTrue(withContext(Dispatchers.IO) {
                first.dumpPackage(run.context.packageName).orEmpty().contains(run.context.packageName)
            })
            assertSame(first, gateway.getRootService())
            assertEquals(1, binding.count())

            binding.releaseCurrent()
            val rebound = requireNotNull(withTimeout(WAIT_MS) { gateway.getRootService() })
            assertTrue(withContext(Dispatchers.IO) {
                rebound.dumpPackage(run.context.packageName).orEmpty().contains(run.context.packageName)
            })
            assertSame(rebound, gateway.getRootService())
            assertEquals(2, binding.count())
            assertTrue("Binding must not attempt any shell command, even if its failure is caught", commands.isEmpty())
            val after = client.identity().also { run.assertIdentity(it) }
            assertSameLifetime(before, after)
            after.put("gatewayBindings", 2)
        } finally {
            withContext(NonCancellable) {
                try {
                    binding.close()
                } finally {
                    fixture.close()
                }
            }
        }
        run.result("probe", result)
    }

    @Test
    fun holdAcknowledgedWorkUntilHostRelease() = runBlocking {
        val run = prepareRun()
        val fixture = FixtureConnection(run.context)
        val result = try {
            val client = FixtureClient(fixture.connect())
            val before = client.identity().also { run.assertIdentity(it) }
            val held = client.hold(run.token, run.holdMillis)
            assertTrue("Host must release the acknowledged root operation before its bound", held.getBoolean("released"))
            run.assertIdentity(held)
            assertSameLifetime(before, held)
            val after = client.identity().also { run.assertIdentity(it) }
            assertSameLifetime(before, after)
            after
        } finally {
            withContext(NonCancellable) {
                File(run.directory, Protocol.RELEASE).writeText(run.token)
                fixture.close()
            }
        }
        run.result("hold", result)
    }

    private suspend fun prepareRun(): Run {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit app-root opt-in required", arguments.getString("odinRoot") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = requireNotNull(arguments.getString("odinRunId"))
        val expectedBuild = requireNotNull(arguments.getString("odinExpectedBuild"))
        val expectedUser = requireNotNull(arguments.getString("odinExpectedUserId")).toInt()
        val holdMillis = arguments.getString("odinHoldTimeoutMs")?.toLong() ?: Protocol.MAX_HOLD_MILLIS
        require(holdMillis in 1..Protocol.MAX_HOLD_MILLIS)
        assertEquals("Instrumentation must run in the requested Android user", expectedUser, context.applicationInfo.uid / 100_000)
        val shell = withTimeout(WAIT_MS) { getShellAwait() }
        assertTrue("Thor itself must have root in this Android user", shell.isRoot)
        val directory = Protocol.directory(context, token)
        check(!directory.exists() && directory.mkdirs()) { "Use a fresh UUID for each invocation" }
        for (name in listOf(Protocol.ENTERED, Protocol.RELEASE, Protocol.COMPLETED)) {
            check(File(directory, name).createNewFile())
        }
        return Run(context, token, expectedBuild, expectedUser, holdMillis, directory)
    }

    private class Run(
        val context: Context,
        val token: String,
        val expectedBuild: String,
        val expectedUser: Int,
        val holdMillis: Long,
        val directory: File,
    ) {
        fun assertIdentity(identity: JSONObject) {
            assertEquals("The root process must load the expected APK code", expectedBuild, identity.getString("build"))
            assertEquals(0, identity.getInt("rootUid"))
            assertEquals(context.applicationInfo.uid, identity.getInt("callingUid"))
            assertEquals("Odin must attach this user's package context", context.applicationInfo.uid, identity.getInt("contextUid"))
            assertEquals(expectedUser, identity.getInt("contextUid") / 100_000)
            assertEquals(File(context.applicationInfo.dataDir).canonicalPath, File(identity.getString("dataDir")).canonicalPath)
            assertTrue(identity.getInt("pid") > 0)
            assertTrue(identity.getString("instance").isNotEmpty())
        }

        fun result(mode: String, identity: JSONObject) {
            File(directory, Protocol.RESULT).writeText(
                identity.put("status", "passed").put("mode", mode).put("token", token).toString(),
            )
        }
    }

    private class FixtureClient(private val binder: IBinder) {
        suspend fun identity(): JSONObject = transact(Protocol.IDENTITY)

        suspend fun hold(token: String, timeoutMillis: Long): JSONObject = transact(Protocol.HOLD) {
            writeString(token)
            writeLong(timeoutMillis)
        }

        private suspend fun transact(code: Int, payload: Parcel.() -> Unit = {}): JSONObject =
            withContext(Dispatchers.IO) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(Protocol.DESCRIPTOR)
                    data.payload()
                    check(binder.transact(code, data, reply, 0)) { "Fixture transaction was rejected" }
                    reply.readException()
                    JSONObject(requireNotNull(reply.readString()))
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
    }

    private class FixtureConnection(context: Context) : ServiceConnection {
        private val intent = Intent(context, RootServiceLifecycleFixture::class.java)
        private val connected = CompletableDeferred<IBinder>()
        private var closed = false

        suspend fun connect(): IBinder {
            try {
                withContext(Dispatchers.Main.immediate) { RootService.bind(intent, callbacks, this@FixtureConnection) }
                return withTimeout(WAIT_MS) { connected.await() }
            } catch (failure: Throwable) {
                withContext(NonCancellable) { close() }
                throw failure
            }
        }

        suspend fun close() = withContext(Dispatchers.Main.immediate) {
            closed = true
            RootService.unbind(this@FixtureConnection)
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (closed) {
                RootService.unbind(this)
            } else if (service == null) {
                onNullBinding(name)
            } else {
                connected.complete(service)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connected.completeExceptionally(IllegalStateException("Fixture disconnected before binding"))
        }

        override fun onNullBinding(name: ComponentName?) {
            RootService.unbind(this)
            connected.completeExceptionally(IllegalStateException("Fixture returned a null Binder"))
        }

        override fun onBindingDied(name: ComponentName?) = onNullBinding(name)
    }

    private class RecordedBinding(private val delegate: RootServiceBinding) : RootServiceBinding {
        private val records = mutableListOf<Record>()

        override fun bind(connection: ServiceConnection) {
            val record = Record(connection)
            records += record
            delegate.bind(record.remote)
        }

        override fun unbind(connection: ServiceConnection) {
            delegate.unbind(records.single { it.owner === connection }.remote)
        }

        suspend fun count(): Int = withContext(Dispatchers.Main.immediate) { records.size }

        suspend fun releaseCurrent() {
            val record = withContext(Dispatchers.Main.immediate) {
                records.last().also { delegate.unbind(it.remote) }
            }
            withTimeout(WAIT_MS) { record.disconnected.await() }
        }

        suspend fun close() = withContext(Dispatchers.Main.immediate) {
            records.forEach { delegate.unbind(it.remote) }
        }

        private class Record(val owner: ServiceConnection) {
            val disconnected = CompletableDeferred<Unit>()
            val remote = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) = owner.onServiceConnected(name, service)
                override fun onNullBinding(name: ComponentName?) = owner.onNullBinding(name)
                override fun onBindingDied(name: ComponentName?) = owner.onBindingDied(name)
                override fun onServiceDisconnected(name: ComponentName?) {
                    owner.onServiceDisconnected(name)
                    disconnected.complete(Unit)
                }
            }
        }
    }

    private companion object {
        const val WAIT_MS = 30_000L
        val callbacks = Executor { callback -> Handler(Looper.getMainLooper()).post(callback) }

        fun <T> unusedDependency(type: Class<T>): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                error("Binding must not access ${type.simpleName}.${method.name}")
            },
        )

        fun assertSameLifetime(before: JSONObject, after: JSONObject) {
            assertEquals("The bound root process must survive", before.getInt("pid"), after.getInt("pid"))
            assertEquals("The bound fixture must survive", before.getString("instance"), after.getString("instance"))
            assertEquals(before.getString("build"), after.getString("build"))
        }
    }
}
