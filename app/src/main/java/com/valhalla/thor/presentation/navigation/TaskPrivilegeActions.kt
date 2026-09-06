// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.navigation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.valhalla.thor.data.source.local.dhizuku.DhizukuHelper
import com.valhalla.thor.domain.model.PrivilegeManagerApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import rikka.shizuku.Shizuku

internal class TaskShizukuRequestRegistry<L : Any>(
    private val firstRequestCode: Int,
    private val onAbandoned: (String) -> Unit = {},
    private val removeListener: (L) -> Unit = {},
) {
    data class Registration<L>(
        val requestCode: Int,
        val listener: L,
        val inserted: Boolean,
    )

    private data class Pending<L>(val requestCode: Int, val listener: L)

    private val lock = Any()
    private val pending = linkedMapOf<String, Pending<L>>()
    private var nextRequestCode = firstRequestCode

    init {
        require(firstRequestCode > 0)
    }

    fun register(ownerKey: String, listenerFactory: (Int) -> L): Registration<L> =
        synchronized(lock) {
            pending[ownerKey]?.let { existing ->
                return@synchronized Registration(
                    existing.requestCode,
                    existing.listener,
                    inserted = false,
                )
            }
            val requestCode = allocateRequestCode()
            val listener = listenerFactory(requestCode)
            pending[ownerKey] = Pending(requestCode, listener)
            Registration(requestCode, listener, inserted = true)
        }

    fun remove(ownerKey: String, requestCode: Int): L? = synchronized(lock) {
        val current = pending[ownerKey]?.takeIf { it.requestCode == requestCode }
            ?: return@synchronized null
        pending.remove(ownerKey)
        current.listener
    }

    fun retire(ownerKey: String): Boolean {
        val retired = synchronized(lock) { pending.remove(ownerKey) } ?: return false
        runCatching { removeListener(retired.listener) }
        return true
    }

    fun abandonAll() {
        val abandoned = synchronized(lock) {
            pending.map { (ownerKey, entry) -> ownerKey to entry.listener }
                .also { pending.clear() }
        }
        abandoned.forEach { (ownerKey, listener) ->
            runCatching { removeListener(listener) }
            onAbandoned(ownerKey)
        }
    }

    private fun allocateRequestCode(): Int {
        repeat(pending.size + 1) {
            val candidate = nextRequestCode
            nextRequestCode = if (candidate == Int.MAX_VALUE) firstRequestCode else candidate + 1
            if (pending.values.none { it.requestCode == candidate }) return candidate
        }
        error("No Shizuku request code available")
    }
}

internal class TaskDhizukuRequestRegistry {
    data class Registration(
        val generation: Long,
        val inserted: Boolean,
    )

    private enum class State {
        STARTING,
        ISSUED,
        RETURNED,
    }

    private data class Pending(
        val generation: Long,
        var state: State,
    )

    private val lock = Any()
    private val pending = mutableMapOf<String, Pending>()
    private var nextGeneration = 0L

    fun register(ownerKey: String): Registration = synchronized(lock) {
        pending[ownerKey]?.let { current ->
            return@synchronized Registration(current.generation, inserted = false)
        }
        check(nextGeneration < Long.MAX_VALUE) { "Dhizuku request generation exhausted" }
        val generation = ++nextGeneration
        pending[ownerKey] = Pending(generation, State.STARTING)
        Registration(generation, inserted = true)
    }

    fun markIssued(ownerKey: String, generation: Long): Boolean = synchronized(lock) {
        val current = pending[ownerKey]?.takeIf { it.generation == generation }
            ?: return@synchronized false
        if (current.state == State.STARTING) current.state = State.ISSUED
        true
    }

    fun complete(ownerKey: String, generation: Long): Boolean = synchronized(lock) {
        val current = pending[ownerKey]?.takeIf { it.generation == generation }
            ?: return@synchronized false
        if (current.state == State.RETURNED) return@synchronized false
        current.state = State.RETURNED
        true
    }

    fun removeIfStarting(ownerKey: String, generation: Long): Boolean = synchronized(lock) {
        val current = pending[ownerKey]?.takeIf {
            it.generation == generation && it.state == State.STARTING
        } ?: return@synchronized false
        pending.remove(ownerKey)
        true
    }

    fun fail(ownerKey: String, generation: Long): Boolean = synchronized(lock) {
        val current = pending[ownerKey]?.takeIf {
            it.generation == generation && it.state != State.RETURNED
        } ?: return@synchronized false
        pending.remove(ownerKey)
        true
    }

    fun acknowledge(ownerKey: String): Boolean = synchronized(lock) {
        val current = pending[ownerKey]?.takeIf { it.state == State.RETURNED }
            ?: return@synchronized false
        pending.remove(ownerKey)
        true
    }

    fun retire(ownerKey: String): Boolean = synchronized(lock) {
        pending.remove(ownerKey) != null
    }
}

internal class TaskExternalResultMailbox<K : Any> {
    private val lock = Any()
    private val pending = mutableSetOf<K>()
    private val listeners = mutableMapOf<K, () -> Unit>()

    fun addListener(key: K, listener: () -> Unit) {
        val replay = synchronized(lock) {
            listeners[key] = listener
            key in pending
        }
        if (replay) listener()
    }

    fun removeListener(key: K, listener: () -> Unit) {
        synchronized(lock) {
            if (listeners[key] === listener) listeners.remove(key)
        }
    }

    fun complete(key: K) {
        val listener = synchronized(lock) {
            pending += key
            listeners[key]
        }
        listener?.invoke()
    }

    fun acknowledge(key: K): Boolean = synchronized(lock) { pending.remove(key) }

    fun discard(key: K) {
        synchronized(lock) {
            pending.remove(key)
            listeners.remove(key)
        }
    }
}

/** Foreground-only bridges to the privilege manager controls already exposed from Home. */
@Single
internal open class TaskPrivilegeActions(
    private val packageManager: PackageManager,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) {
    private val shizukuResults = TaskExternalResultMailbox<String>()
    private val shizukuRequests = TaskShizukuRequestRegistry(
        firstRequestCode = TASK_SHIZUKU_REQUEST_CODE_BASE,
        onAbandoned = shizukuResults::complete,
        removeListener = Shizuku::removeRequestPermissionResultListener,
    )
    private val dhizukuResults = TaskExternalResultMailbox<String>()
    private val dhizukuRequests = TaskDhizukuRequestRegistry()
    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuRequests.abandonAll()
    }

    init {
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
    }

    sealed interface SetupTarget {
        data object RefreshOnly : SetupTarget
        data object ShizukuPermission : SetupTarget
        data object DhizukuPermission : SetupTarget
        data class ManagerApp(val intent: Intent) : SetupTarget
    }

    open suspend fun resolveSetupTarget(): SetupTarget = withContext(ioDispatcher) {
        val shizukuAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (shizukuAlive) {
            val granted = runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            return@withContext if (granted) {
                SetupTarget.RefreshOnly
            } else {
                SetupTarget.ShizukuPermission
            }
        }

        PrivilegeManagerApp.findInstalledManagers(packageManager).forEach { manager ->
            if (manager.app == PrivilegeManagerApp.DHIZUKU) {
                return@withContext SetupTarget.DhizukuPermission
            }
            packageManager.getLaunchIntentForPackage(manager.installedPackageName)?.let { intent ->
                return@withContext SetupTarget.ManagerApp(intent)
            }
        }
        SetupTarget.RefreshOnly
    }

    fun requestShizuku(ownerKey: String): Boolean = runCatching {
        if (!Shizuku.pingBinder()) return@runCatching false

        lateinit var bridge: Shizuku.OnRequestPermissionResultListener
        val registration = shizukuRequests.register(ownerKey) { requestCode ->
            bridge = Shizuku.OnRequestPermissionResultListener { returnedCode, _ ->
                if (returnedCode == requestCode &&
                    shizukuRequests.remove(ownerKey, requestCode) === bridge
                ) {
                    runCatching { Shizuku.removeRequestPermissionResultListener(bridge) }
                    shizukuResults.complete(ownerKey)
                }
            }
            bridge
        }
        if (!registration.inserted) return@runCatching true

        try {
            Shizuku.addRequestPermissionResultListener(registration.listener)
            Shizuku.requestPermission(registration.requestCode)
            true
        } catch (exception: Exception) {
            shizukuRequests.remove(ownerKey, registration.requestCode)
            runCatching {
                Shizuku.removeRequestPermissionResultListener(registration.listener)
            }
            throw exception
        }
    }.getOrDefault(false)

    fun addShizukuResultListener(ownerKey: String, listener: () -> Unit) {
        shizukuResults.addListener(ownerKey, listener)
    }

    fun removeShizukuResultListener(ownerKey: String, listener: () -> Unit) {
        shizukuResults.removeListener(ownerKey, listener)
    }

    fun acknowledgeShizukuResult(ownerKey: String) {
        shizukuResults.acknowledge(ownerKey)
    }

    fun addDhizukuResultListener(requestKey: String, listener: () -> Unit) {
        dhizukuResults.addListener(requestKey, listener)
    }

    fun removeDhizukuResultListener(requestKey: String, listener: () -> Unit) {
        dhizukuResults.removeListener(requestKey, listener)
    }

    fun acknowledgeDhizukuResult(requestKey: String) {
        dhizukuResults.acknowledge(requestKey)
        dhizukuRequests.acknowledge(requestKey)
    }

    fun retireResultOwner(requestKey: String) {
        shizukuRequests.retire(requestKey)
        shizukuResults.discard(requestKey)
        dhizukuRequests.retire(requestKey)
        dhizukuResults.discard(requestKey)
    }

    suspend fun startDhizukuRequest(context: Context, requestKey: String): Boolean {
        val registration = dhizukuRequests.register(requestKey)
        if (!registration.inserted) return true

        return try {
            val started = requestDhizuku(
                context = context,
                onIssued = {
                    dhizukuRequests.markIssued(requestKey, registration.generation)
                },
                onReturned = {
                    if (dhizukuRequests.complete(requestKey, registration.generation)) {
                        dhizukuResults.complete(requestKey)
                    }
                },
            )
            if (!started) dhizukuRequests.fail(requestKey, registration.generation)
            started
        } catch (exception: CancellationException) {
            dhizukuRequests.removeIfStarting(requestKey, registration.generation)
            throw exception
        } catch (_: Exception) {
            dhizukuRequests.fail(requestKey, registration.generation)
            false
        }
    }

    open suspend fun requestDhizuku(
        context: Context,
        onIssued: () -> Boolean,
        onReturned: () -> Unit,
    ): Boolean = withContext(ioDispatcher) {
        try {
            if (!onIssued()) return@withContext false
            DhizukuHelper.requestPermission(context) { _ -> onReturned() }
            true
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            false
        }
    }
}

private const val TASK_SHIZUKU_REQUEST_CODE_BASE = 16_453
