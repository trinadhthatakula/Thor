// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.util.Logger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koin.core.annotation.Single

@Single
class AutoFreezeManager(
    private val context: Context,
    private val preferenceRepository: PreferenceRepository,
    private val freezerRepository: FreezerRepository,
    private val manageAppUseCase: ManageAppUseCase,
    private val systemRepository: SystemRepository,
    private val appRepository: AppRepository,
    private val freezerShortcutManager: com.valhalla.thor.data.launcher.FreezerShortcutManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observationJob: Job? = null
    private var isObserving = false
    private var isReceiverRegistered = false

    @Volatile
    private var currentMode: FreezerMode = FreezerMode.FREEZE

    // Guards against overlapping auto-freeze batches. A rapid screen on/off would otherwise
    // stack multiple SCREEN_OFF batches that all contend the privileged shell. While a batch
    // is running, further SCREEN_OFF events are ignored (the running batch already re-reads the
    // current Freezer list, so nothing is missed).
    private val isFreezing = AtomicBoolean(false)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF) return

            Logger.d("AutoFreezeManager", "Screen off event received")

            // Skip if a previous batch is still running so overlapping SCREEN_OFF events
            // (rapid screen on/off) don't stack and contend the shell.
            if (!isFreezing.compareAndSet(false, true)) {
                Logger.d("AutoFreezeManager", "Auto-freeze already in progress. Skipping.")
                return
            }

            // Hand the batch to the app-scoped `scope` and return immediately. This receiver is
            // context-registered from a live process (held by AutoFreezeManager for the app's
            // lifetime), so there is no need for goAsync()/PendingResult — avoiding it keeps a
            // large freeze batch from blowing the ~10s registered-receiver dispatch budget (ANR).
            scope.launch {
                try {
                    // Check if the device is locked (Keyguard active)
                    val keyguardManager =
                        context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    if (!keyguardManager.isDeviceLocked) {
                        Logger.d(
                            "AutoFreezeManager",
                            "Device screen off but keyguard not locked. Skipping auto-freeze."
                        )
                        return@launch
                    }

                    // Check if privilege is available
                    val hasPrivilege = systemRepository.isRootAvailable() ||
                            systemRepository.isShizukuAvailable() ||
                            systemRepository.isDhizukuAvailable()

                    if (!hasPrivilege) {
                        Logger.d(
                            "AutoFreezeManager",
                            "No privilege available. Skipping auto-freeze."
                        )
                        return@launch
                    }

                    val pkgs = freezerRepository.getAllPackageNames()
                    if (pkgs.isEmpty()) {
                        Logger.d("AutoFreezeManager", "No apps in Freezer. Skipping auto-freeze.")
                        return@launch
                    }

                    val pm = context.packageManager
                    val semaphore = Semaphore(5)

                    val jobs = pkgs.map { pkg ->
                        scope.launch {
                            semaphore.withPermit {
                                try {
                                    val detailedApp = appRepository.getAppDetails(pkg)
                                    if (detailedApp != null) {
                                        val isSystem = detailedApp.isSystem
                                        val isUadFailed = isSystem && detailedApp.isUadLoadFailed
                                        val isUnsafe = isSystem && detailedApp.bloatRecommendation?.lowercase() == "unsafe"
                                        if (isSystem && (isUadFailed || isUnsafe)) {
                                            Logger.d("AutoFreezeManager", "Skipping auto-freeze for UNSAFE system app: $pkg")
                                            return@launch
                                        }
                                    }
                                    val appInfo = pm.getApplicationInfo(pkg, 0)
                                    val alreadySuspended = (appInfo.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
                                    // Only act on ACTIVE apps (enabled & not suspended) so auto-freeze
                                    // never stacks disable+suspend into an un-restorable mixed state.
                                    if (appInfo.enabled && !alreadySuspended) {
                                        if (currentMode == FreezerMode.SUSPEND) {
                                            Logger.d("AutoFreezeManager", "Auto-suspending app: $pkg")
                                            val result = manageAppUseCase.setAppSuspended(pkg, true)
                                            if (result.isSuccess) {
                                                Logger.d("AutoFreezeManager", "Auto-suspended: $pkg")
                                                freezerShortcutManager.refreshAppShortcut(pkg)
                                            } else {
                                                Logger.e(
                                                    "AutoFreezeManager",
                                                    "Failed to suspend $pkg: ${result.exceptionOrNull()?.message}"
                                                )
                                            }
                                        } else {
                                            Logger.d("AutoFreezeManager", "Auto-freezing app: $pkg")
                                            val result = manageAppUseCase.setAppDisabled(pkg, true)
                                            if (result.isSuccess) {
                                                Logger.d("AutoFreezeManager", "Auto-froze: $pkg")
                                                freezerShortcutManager.refreshAppShortcut(pkg) // → grey the shortcut icon
                                            } else {
                                                Logger.e(
                                                    "AutoFreezeManager",
                                                    "Failed to freeze $pkg: ${result.exceptionOrNull()?.message}"
                                                )
                                            }
                                        }
                                    }
                                } catch (_: PackageManager.NameNotFoundException) {
                                    Logger.d("AutoFreezeManager", "App $pkg not found, skipping")
                                } catch (e: Exception) {
                                    Logger.e("AutoFreezeManager", "Failed to check/freeze $pkg", e)
                                }
                            }
                        }
                    }
                    jobs.forEach { it.join() }
                    Logger.d("AutoFreezeManager", "Auto-freeze process complete for ${pkgs.size} apps.")
                } catch (e: Exception) {
                    Logger.e("AutoFreezeManager", "Error in auto-freeze process", e)
                } finally {
                    isFreezing.set(false)
                }
            }
        }
    }

    @Synchronized
    fun startObserving() {
        if (isObserving) return
        isObserving = true
        observationJob = mainScope.launch {
            preferenceRepository.userPreferences.collectLatest { prefs ->
                currentMode = prefs.freezerMode
                if (prefs.autoFreezeEnabled) {
                    registerReceiver()
                } else {
                    unregisterReceiver()
                }
            }
        }
    }

    @Synchronized
    fun stopObserving() {
        if (!isObserving) return
        isObserving = false
        observationJob?.cancel()
        observationJob = null
        unregisterReceiver()
    }

    @Synchronized
    private fun registerReceiver() {
        if (isReceiverRegistered) return
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            context.registerReceiver(screenReceiver, filter)
            isReceiverRegistered = true
            Logger.d("AutoFreezeManager", "Successfully registered Screen Off receiver")
        } catch (e: Exception) {
            Logger.e("AutoFreezeManager", "Failed to register Screen Off receiver", e)
        }
    }

    @Synchronized
    private fun unregisterReceiver() {
        if (!isReceiverRegistered) return
        try {
            context.unregisterReceiver(screenReceiver)
            isReceiverRegistered = false
            Logger.d("AutoFreezeManager", "Successfully unregistered Screen Off receiver")
        } catch (e: Exception) {
            Logger.e("AutoFreezeManager", "Failed to unregister Screen Off receiver", e)
        }
    }
}
