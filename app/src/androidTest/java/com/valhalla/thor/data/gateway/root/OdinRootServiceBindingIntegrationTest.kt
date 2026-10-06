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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.superuser.Shell
import com.valhalla.superuser.ipc.RootService
import com.valhalla.superuser.ktx.getShellAwait
import com.valhalla.thor.rootservice.IThorRootService
import com.valhalla.thor.rootservice.ThorRootService
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run this class in its own instrumentation invocation with odinRoot=true. */
@RunWith(AndroidJUnit4::class)
class OdinRootServiceBindingIntegrationTest {
    @Test
    fun delayedStartupReleasesAbandonedConnectionsAndPreservesReplacement() = runBlocking {
        assumeTrue(
            "Explicit app-root opt-in required",
            InstrumentationRegistry.getArguments().getString("odinRoot") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shell = withTimeout(WAIT_MS) { getShellAwait() }
        assertTrue("Thor itself must have a root shell", shell.isRoot)
        val binding = HeldStartupBinding(context, shell, this)
        val owner = RootServiceConnectionOwner(binding)
        val requests = mutableListOf<Deferred<IThorRootService?>>()

        try {
            val cancelled = async { owner.getService() }.also(requests::add)
            val first = binding.nextAttempt()
            withContext(Dispatchers.Main.immediate) {
                assertNotNull(
                    "A fresh instrumentation process must reach Odin's pending startup path",
                    binding.heldStartup,
                )
            }
            cancelled.cancelAndJoin()
            withTimeout(WAIT_MS) { first.firstUnbind.await() }

            val timedOut = async { owner.getService() }.also(requests::add)
            val second = binding.nextAttempt()
            assertNull(
                "The held root startup must reach the owner's bind timeout",
                withTimeout(WAIT_MS) { timedOut.await() },
            )
            withTimeout(WAIT_MS) { second.firstUnbind.await() }
            assertFalse("The cancelled bind must still be pending in Odin", first.connected.isCompleted)
            assertFalse("The timed-out bind must still be pending in Odin", second.connected.isCompleted)

            val current = async { owner.getService() }.also(requests::add)
            val third = binding.nextAttempt()
            binding.releaseStartup()
            val service = requireNotNull(withTimeout(WAIT_MS) { current.await() })

            // Odin registers its pending connections only after startup. Earlier unbinds could
            // not remove them; both abandoned owners must release their exact late connections.
            for (attempt in listOf(first, second)) {
                withTimeout(WAIT_MS) {
                    attempt.connected.await()
                    attempt.secondUnbind.await()
                    attempt.disconnected.await()
                }
            }
            assertReadable(service, context.packageName)
            assertSame("Old callbacks must preserve the current cached service", service, owner.getService())
            assertEquals(3, binding.attemptCount())

            // Ordinary unbind emitted the real old disconnects above. These additional stale
            // platform notifications must also leave the replacement connection untouched.
            withContext(Dispatchers.Main.immediate) {
                first.owner.onNullBinding(binding.component)
                second.owner.onBindingDied(binding.component)
                assertEquals("Cancellation cleanup must be idempotent", 2, first.unbindCount)
                assertEquals("Timeout cleanup must be idempotent", 2, second.unbindCount)
            }
            assertSame("Stale null/death callbacks must preserve the cache", service, owner.getService())
            assertEquals(3, binding.attemptCount())
            assertReadable(service, context.packageName)

            withContext(Dispatchers.Main.immediate) { binding.unbind(third.owner) }
            withTimeout(WAIT_MS) { third.disconnected.await() }
            val rebound = requireNotNull(withTimeout(WAIT_MS) { owner.getService() })
            assertEquals("A released connection must be bound again", 4, binding.attemptCount())
            assertReadable(rebound, context.packageName)
            assertSame("The rebound service must be reusable", rebound, owner.getService())
            assertEquals(4, binding.attemptCount())
        } finally {
            withContext(NonCancellable) {
                requests.forEach { it.cancelAndJoin() }
                // Even an assertion failure before startup must release the held task so Odin can
                // finish registration and the abandoned owners can clean up their late bindings.
                try {
                    binding.releaseStartup()
                    withTimeoutOrNull(WAIT_MS) { binding.awaitConnections() }
                } finally {
                    withContext(Dispatchers.Main.immediate) { binding.unbindAll() }
                }
            }
        }
    }

    private suspend fun assertReadable(service: IThorRootService, packageName: String) {
        val dump = withContext(Dispatchers.IO) { service.dumpPackage(packageName) }
        assertTrue("The real root Binder must remain alive", service.asBinder().isBinderAlive)
        assertTrue("The root Binder must read Thor's own package", dump.orEmpty().contains(packageName))
    }

    /** Delays only Odin's returned launch task; its pending registration and Binder are real. */
    private class HeldStartupBinding(
        context: Context,
        private val shell: Shell,
        private val scope: CoroutineScope,
    ) : RootServiceBinding {
        val component = ComponentName(context, ThorRootService::class.java)
        private val intent = Intent().setComponent(component)
        private val handler = Handler(Looper.getMainLooper())
        private val callbacks = Executor { callback -> handler.post(callback) }
        private val attempts = mutableListOf<Attempt>()
        private val started = Channel<Attempt>(Channel.UNLIMITED)
        private var holdStartup = true
        var heldStartup: Shell.Task? = null
            private set

        override fun bind(connection: ServiceConnection) {
            checkMain()
            val attempt = Attempt(connection)
            attempts += attempt
            val task = RootService.bindOrTask(intent, callbacks, attempt.remote)
            if (task != null) {
                if (holdStartup) {
                    check(heldStartup == null) { "Only one root startup may be held" }
                    heldStartup = task
                } else {
                    scope.launch(Dispatchers.IO) { shell.execTask(task) }
                }
            }
            check(started.trySend(attempt).isSuccess)
        }

        override fun unbind(connection: ServiceConnection) {
            checkMain()
            val attempt = attempts.single { it.owner === connection }
            RootService.unbind(attempt.remote)
            attempt.unbindCount++
            if (attempt.unbindCount == 1) attempt.firstUnbind.complete(Unit)
            if (attempt.unbindCount == 2) attempt.secondUnbind.complete(Unit)
        }

        suspend fun nextAttempt(): Attempt = withTimeout(WAIT_MS) { started.receive() }

        suspend fun attemptCount(): Int = withContext(Dispatchers.Main.immediate) { attempts.size }

        suspend fun releaseStartup() = withContext(NonCancellable) {
            // Keep ownership through both dispatcher handoffs: cancellation must not consume the
            // only startup task before it is submitted, stranding Odin's pending connections.
            val task = withContext(Dispatchers.Main.immediate) {
                holdStartup = false
                heldStartup.also { heldStartup = null }
            }
            if (task != null) withContext(Dispatchers.IO) { shell.execTask(task) }
        }

        suspend fun awaitConnections() {
            val snapshot = withContext(Dispatchers.Main.immediate) { attempts.toList() }
            snapshot.forEach { it.connected.await() }
        }

        fun unbindAll() {
            checkMain()
            attempts.forEach { RootService.unbind(it.remote) }
        }

        private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
    }

    private class Attempt(val owner: ServiceConnection) {
        val connected = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Unit>()
        val firstUnbind = CompletableDeferred<Unit>()
        val secondUnbind = CompletableDeferred<Unit>()
        var unbindCount = 0
        val remote = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                owner.onServiceConnected(name, service)
                connected.complete(Unit)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                owner.onServiceDisconnected(name)
                disconnected.complete(Unit)
            }

            override fun onNullBinding(name: ComponentName?) = owner.onNullBinding(name)

            override fun onBindingDied(name: ComponentName?) = owner.onBindingDied(name)
        }
    }

    private companion object {
        const val WAIT_MS = 30_000L
    }
}
