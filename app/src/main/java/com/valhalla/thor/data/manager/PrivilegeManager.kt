package com.valhalla.thor.data.manager

import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.model.resolvePrivilegeMode
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
@Single
class PrivilegeManager(
    private val systemRepository: SystemRepository,
    private val preferenceRepository: PreferenceRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(PrivilegeState())
    val state: StateFlow<PrivilegeState> = _state.asStateFlow()

    // Bumped to force a re-probe; StateFlow<Int> emits on every distinct value.
    private val refreshTrigger = MutableStateFlow(0)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    init {
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        scope.launch {
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
            }.collect { _state.value = it }
        }
    }

    /** Force a re-probe (e.g. after the user enables root outside the app). */
    fun refresh() {
        refreshTrigger.value += 1
    }

    private data class Availability(val root: Boolean, val shizuku: Boolean, val dhizuku: Boolean)

    private fun availabilityFlow(): Flow<Availability> =
        refreshTrigger
            .map {
                Availability(
                    root = safeProbe { systemRepository.isRootAvailable() },
                    shizuku = safeProbe { systemRepository.isShizukuAvailable() },
                    dhizuku = safeProbe { systemRepository.isDhizukuAvailable() }
                )
            }
            .flowOn(Dispatchers.IO)

    private suspend fun safeProbe(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: Exception) {
        Logger.e("PrivilegeManager", "privilege probe failed", e)
        false
    }
}
