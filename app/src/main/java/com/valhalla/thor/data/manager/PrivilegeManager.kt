// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.manager

import com.valhalla.thor.BuildConfig
import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.model.resolvePrivilegeMode
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.util.Logger
import com.valhalla.thor.util.PrivilegeProbeTier
import com.valhalla.thor.util.PrivilegeProbeTrace
import com.valhalla.thor.util.timeProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import rikka.shizuku.Shizuku

/**
 * Single reactive source of truth for privilege availability + the active mode.
 *
 * Re-probes root/Shizuku/Dhizuku off the main thread on init, on [refresh], on
 * Shizuku binder/permission events (it owns those listeners), and whenever the
 * preferred mode changes. As a process-lifetime @Single it never unregisters its
 * Shizuku listeners (they live for the app), so consumers created before a
 * first-launch grant still see it once granted.
 */
@Single(binds = [PrivilegeStateProvider::class])
class PrivilegeManager(
    private val systemRepository: SystemRepository,
    private val preferenceRepository: PreferenceRepository,
    // `scope` below is process-lifetime and carries the whole combine/collect pipeline, so this
    // is the dispatcher the probe actually runs on. Note that injecting it does not make this
    // class unit-testable, which was measured rather than assumed: the `init` block touches
    // `rikka.shizuku.Shizuku`, whose static initializer builds a Binder, and the stub
    // android.jar answers `Binder.attachInterface` with "not mocked" — construction throws
    // ExceptionInInitializerError before any dispatcher matters.
    @Named("default") private val defaultDispatcher: CoroutineDispatcher,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) : PrivilegeStateProvider {
    private val scope = CoroutineScope(SupervisorJob() + defaultDispatcher)

    private val _state = MutableStateFlow(PrivilegeState())
    override val state: StateFlow<PrivilegeState> = _state.asStateFlow()

    // Bumped to force a re-probe; StateFlow<Int> emits on every distinct value.
    private val refreshTrigger = MutableStateFlow(0)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    init {
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        scope.launch {
            // Cold-start marker, present only in traced builds. Every later emission (refresh,
            // preference change) also
            // carries isReady = true, so the first one has to be latched; a collector-local flag
            // rather than re-reading _state, which would race a concurrent refresh() emission.
            var firstReadyLogged = false
            combine(availabilityFlow(), preferenceRepository.userPreferences) { avail, prefs ->
                PrivilegeState(
                    root = avail.root,
                    shizuku = avail.shizuku,
                    dhizuku = avail.dhizuku,
                    active = resolvePrivilegeMode(
                        prefs.preferredPrivilegeMode,
                        avail.root,
                        avail.shizuku,
                        avail.dhizuku
                    ),
                    isReady = true
                )
            }.collect { newState ->
                _state.value = newState
                // Logged after publishing, because it is the publish that releases the loaders
                // every isLoading = !priv.isReady consumer is holding.
                if (BuildConfig.PRIVILEGE_TRACE && !firstReadyLogged && newState.isReady) {
                    firstReadyLogged = true
                    PrivilegeProbeTrace.logFirstReady(newState.active.name)
                }
            }
        }
    }

    /** Force a re-probe (e.g. after the user enables root outside the app). */
    fun refresh() {
        // Called from Shizuku's binder/permission listeners on arbitrary threads, so the
        // bump must be atomic — `value +=` is a non-atomic read-modify-write that can drop
        // a concurrent trigger.
        refreshTrigger.update { it + 1 }
    }

    private data class Availability(val root: Boolean, val shizuku: Boolean, val dhizuku: Boolean)

    private fun availabilityFlow(): Flow<Availability> =
        refreshTrigger
            .map {
                // Started before the coroutineScope so `total` includes the async dispatch — that
                // is latency the caller waits for, and hiding it would flatter the measurement.
                // Null (and compiled out) in release; see PrivilegeProbeTrace.
                val trace = PrivilegeProbeTrace.start()
                // Probe the three sources concurrently: the root probe can spawn a shell
                // (100-500ms), so running them in parallel keeps cold-start latency at
                // max(probe) instead of the sum.
                coroutineScope {
                    val root = async {
                        trace.timeProbe(PrivilegeProbeTier.ROOT) {
                            safeProbe { systemRepository.isRootAvailable() }
                        }
                    }
                    val shizuku = async {
                        trace.timeProbe(PrivilegeProbeTier.SHIZUKU) {
                            safeProbe { systemRepository.isShizukuAvailable() }
                        }
                    }
                    val dhizuku = async {
                        trace.timeProbe(PrivilegeProbeTier.DHIZUKU) {
                            safeProbe { systemRepository.isDhizukuAvailable() }
                        }
                    }
                    Availability(root.await(), shizuku.await(), dhizuku.await())
                        .also { trace?.logRun(it.root, it.shizuku, it.dhizuku) }
                }
            }
            .flowOn(ioDispatcher)

    private suspend fun safeProbe(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e // never swallow cancellation — it breaks cooperative coroutine cancellation
    } catch (e: Exception) {
        Logger.e("PrivilegeManager", "privilege probe failed", e)
        false
    }
}
