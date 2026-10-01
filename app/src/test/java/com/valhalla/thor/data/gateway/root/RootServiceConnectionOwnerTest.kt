// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import android.app.Application
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import com.valhalla.thor.rootservice.IThorRootService
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class RootServiceConnectionOwnerTest {
    @Test
    fun `live connection is cached and duplicate connect does not resume twice`() = runTest {
        val binding = FakeBinding()
        val main = StandardTestDispatcher(testScheduler)
        val owner = RootServiceConnectionOwner(binding, main)
        val first = async { owner.getService() }
        runCurrent()
        val connection = binding.connections.single()
        val service = Service()
        withContext(main) {
            binding.connect(connection, service)
            connection.onServiceConnected(null, service.asBinder())
        }

        assertSame(service, first.await())
        assertSame(service, owner.getService())
        assertEquals(1, binding.connections.size)
        assertTrue(binding.unbound.isEmpty())
    }

    @Test
    fun `cancellation releases pending attempt and releases its late registration again`() = runTest {
        val binding = FakeBinding()
        val main = StandardTestDispatcher(testScheduler)
        val owner = RootServiceConnectionOwner(binding, main)
        val first = async { owner.getService() }
        runCurrent()
        val abandoned = binding.connections.single()
        first.cancelAndJoin()
        runCurrent()
        assertEquals(listOf(abandoned), binding.unbound)

        withContext(main) { binding.connect(abandoned, Service()) }
        runCurrent()
        assertEquals(listOf(abandoned, abandoned), binding.unbound)
        assertTrue(binding.registered.isEmpty())
    }

    @Test
    fun `timeout releases mutex and an old callback cannot displace a newer pending attempt`() = runTest {
        val binding = FakeBinding()
        val main = StandardTestDispatcher(testScheduler)
        val owner = RootServiceConnectionOwner(binding, main, bindTimeoutMillis = 1_000)
        val first = async { owner.getService() }
        runCurrent()
        val abandoned = binding.connections.single()
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(first.await())

        val replacement = async { owner.getService() }
        runCurrent()
        val current = binding.connections.last()
        val service = Service()
        withContext(main) {
            binding.connect(abandoned, service)
            binding.connect(current, service)
        }
        assertSame(service, replacement.await())
        assertSame(service, owner.getService())
        assertEquals(listOf(abandoned, abandoned), binding.unbound)
        assertEquals(setOf(current), binding.registered)
    }

    @Test
    fun `old disconnect null and death callbacks preserve replacement with same Binder`() = runTest {
        val binding = FakeBinding()
        val main = StandardTestDispatcher(testScheduler)
        val owner = RootServiceConnectionOwner(binding, main)
        val first = async { owner.getService() }
        runCurrent()
        val abandoned = binding.connections.single()
        first.cancelAndJoin()
        runCurrent()
        val replacement = async { owner.getService() }
        runCurrent()
        val current = binding.connections.last()
        val sharedService = Service()
        withContext(main) {
            binding.connect(current, sharedService)
            binding.connect(abandoned, sharedService)
            binding.disconnect(abandoned)
            abandoned.onNullBinding(null)
            abandoned.onBindingDied(null)
            abandoned.onServiceConnected(null, sharedService.asBinder())
        }

        assertSame(sharedService, replacement.await())
        assertSame(sharedService, owner.getService())
        assertEquals(2, binding.connections.size)
        assertEquals(listOf(abandoned, abandoned), binding.unbound)
        assertEquals(setOf(current), binding.registered)
    }

    @Test
    fun `cancel after connect before delivery releases registered connection once`() = runTest {
        val binding = FakeBinding()
        val main = StandardTestDispatcher(testScheduler)
        val owner = RootServiceConnectionOwner(binding, main)
        val first = async { owner.getService() }
        runCurrent()
        val connection = binding.connections.single()
        withContext(main) {
            binding.connect(connection, Service())
            first.cancel()
        }
        first.join()
        runCurrent()

        assertTrue(first.isCancelled)
        assertEquals(listOf(connection), binding.unbound)
        assertTrue(binding.registered.isEmpty())
        val next = async { owner.getService() }
        runCurrent()
        assertEquals(2, binding.connections.size)
        next.cancelAndJoin()
    }

    @Test
    fun `cancelling a cached result does not retire the existing accepted connection`() = runTest {
        val binding = FakeBinding()
        val main = StandardTestDispatcher(testScheduler)
        val owner = RootServiceConnectionOwner(binding, main)
        val first = async { owner.getService() }
        runCurrent()
        val service = Service()
        withContext(main) { binding.connect(binding.connections.single(), service) }
        assertSame(service, first.await())

        val caller = QueuedDispatcher()
        val cached = async(caller) { owner.getService() }
        caller.drain()
        runCurrent() // main found the cache; its result is queued back to the caller.
        cached.cancel()
        caller.drain()
        runCurrent()

        assertTrue(cached.isCancelled)
        assertSame(service, owner.getService())
        assertTrue(binding.unbound.isEmpty())
    }

    @Test
    fun `main queue stall cannot hold timeout or start its cancelled bind later`() = runTest {
        val binding = FakeBinding()
        val main = QueuedDispatcher()
        val owner = RootServiceConnectionOwner(binding, main, bindTimeoutMillis = 1_000)
        val first = async { owner.getService() }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(first.isCompleted)
        assertNull(first.await())

        val second = async { owner.getService() }
        runCurrent()
        second.cancelAndJoin()
        main.drain()
        assertTrue(binding.connections.isEmpty())
        assertTrue(binding.unbound.isEmpty())
    }

    @Test
    fun `cancelling another mutex waiter leaves the active bind alone`() = runTest {
        val binding = FakeBinding()
        val main = StandardTestDispatcher(testScheduler)
        val owner = RootServiceConnectionOwner(binding, main)
        val first = async { owner.getService() }
        val waiting = async { owner.getService() }
        runCurrent()
        waiting.cancelAndJoin()
        val service = Service()
        withContext(main) { binding.connect(binding.connections.single(), service) }

        assertSame(service, first.await())
        assertEquals(1, binding.connections.size)
        assertTrue(binding.unbound.isEmpty())
    }

    @Test
    fun `dead cached Binder is released before its replacement binds`() = runTest {
        val binding = FakeBinding()
        val main = StandardTestDispatcher(testScheduler)
        val owner = RootServiceConnectionOwner(binding, main)
        val first = async { owner.getService() }
        runCurrent()
        val old = binding.connections.single()
        val service = Service()
        withContext(main) { binding.connect(old, service) }
        first.await()
        service.alive = false
        val replacement = async { owner.getService() }
        runCurrent()

        assertEquals(listOf("bind", "unbind", "bind"), binding.events)
        assertEquals(listOf(old), binding.unbound)
        replacement.cancelAndJoin()
    }

    @Test
    fun `null binding completes immediately and duplicate terminal callbacks are harmless`() = runTest {
        val binding = FakeBinding()
        val main = StandardTestDispatcher(testScheduler)
        val owner = RootServiceConnectionOwner(binding, main)
        val first = async { owner.getService() }
        runCurrent()
        val connection = binding.connections.single()
        withContext(main) {
            connection.onNullBinding(null)
            connection.onNullBinding(null)
            connection.onBindingDied(null)
        }
        assertNull(first.await())
        assertEquals(listOf(connection), binding.unbound)
        val next = async { owner.getService() }
        runCurrent()
        assertEquals(2, binding.connections.size)
        next.cancelAndJoin()
    }

    @Test
    fun `disconnect completes pending waiter and death retires an established connection`() = runTest {
        val binding = FakeBinding()
        val main = StandardTestDispatcher(testScheduler)
        val owner = RootServiceConnectionOwner(binding, main)
        val pending = async { owner.getService() }
        runCurrent()
        val disconnected = binding.connections.single()
        withContext(main) { binding.disconnect(disconnected) }
        assertNull(pending.await())
        val established = async { owner.getService() }
        runCurrent()
        val current = binding.connections.last()
        withContext(main) {
            binding.connect(disconnected, Service())
            binding.connect(current, Service())
        }
        established.await()
        withContext(main) { current.onBindingDied(null) }
        assertEquals(listOf(disconnected, current), binding.unbound)
        assertTrue(binding.registered.isEmpty())
    }

    @Test
    fun `synchronous bind exception cleans its attempt and allows a subsequent bind`() = runTest {
        val failure = IllegalStateException("bind refused")
        val binding = FakeBinding().apply { onBind = { throw failure } }
        val main = StandardTestDispatcher(testScheduler)
        val owner = RootServiceConnectionOwner(binding, main)
        val caught = runCatching { owner.getService() }.exceptionOrNull()
        assertEquals(failure.javaClass, caught?.javaClass)
        assertEquals(failure.message, caught?.message)
        assertEquals(binding.connections, binding.unbound)
        binding.onBind = null
        val next = async { owner.getService() }
        runCurrent()
        assertEquals(2, binding.connections.size)
        assertFalse(next.isCompleted)
        next.cancelAndJoin()
    }

    @Test
    fun `failed cached liveness probe releases stale connection and binds again`() = runTest {
        val binding = FakeBinding()
        val main = StandardTestDispatcher(testScheduler)
        val owner = RootServiceConnectionOwner(binding, main)
        val first = async { owner.getService() }
        runCurrent()
        val old = binding.connections.single()
        val service = Service()
        withContext(main) { binding.connect(old, service) }
        first.await()
        service.livenessFailure = IllegalStateException("invalid Binder")
        val replacement = async { owner.getService() }
        runCurrent()
        val fresh = Service()
        withContext(main) { binding.connect(binding.connections.last(), fresh) }
        assertSame(fresh, replacement.await())
        assertEquals(listOf(old), binding.unbound)
        assertEquals(2, binding.connections.size)
    }

    @Test
    fun `Binder conversion failure completes waiter and releases registration`() = runTest {
        val binding = FakeBinding()
        val main = StandardTestDispatcher(testScheduler)
        val owner = RootServiceConnectionOwner(binding, main)
        val first = async { runCatching { owner.getService() } }
        runCurrent()
        val connection = binding.connections.single()
        val service = Service().apply {
            conversionFailure = IllegalStateException("unreadable Binder")
        }
        withContext(main) { binding.connect(connection, service) }
        assertEquals("unreadable Binder", first.await().exceptionOrNull()?.message)
        assertEquals(listOf(connection), binding.unbound)
        assertTrue(binding.registered.isEmpty())
    }

    private class FakeBinding : RootServiceBinding {
        val connections = mutableListOf<ServiceConnection>()
        val unbound = mutableListOf<ServiceConnection>()
        val registered = mutableSetOf<ServiceConnection>()
        val events = mutableListOf<String>()
        var onBind: ((ServiceConnection) -> Unit)? = null

        override fun bind(connection: ServiceConnection) {
            events += "bind"
            connections += connection
            onBind?.invoke(connection)
        }

        override fun unbind(connection: ServiceConnection) {
            events += "unbind"
            unbound += connection
            registered -= connection
        }

        fun connect(connection: ServiceConnection, service: Service) {
            registered += connection
            connection.onServiceConnected(null, service.asBinder())
        }

        fun disconnect(connection: ServiceConnection) {
            registered -= connection
            connection.onServiceDisconnected(null)
        }
    }

    private class Service : IThorRootService.Default() {
        var alive = true
        var livenessFailure: Exception? = null
        var conversionFailure: Exception? = null
        private val binder = object : Binder() {
            override fun isBinderAlive(): Boolean {
                livenessFailure?.let { throw it }
                return alive
            }
            override fun queryLocalInterface(descriptor: String): IInterface? {
                conversionFailure?.let { throw it }
                return super.queryLocalInterface(descriptor)
            }
        }.apply { attachInterface(this@Service, "com.valhalla.thor.rootservice.IThorRootService") }

        override fun asBinder(): IBinder = binder
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }
}
