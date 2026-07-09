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

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF) return

            Logger.d("AutoFreezeManager", "Screen off event received")
            val pendingResult = goAsync()

            scope.launch {
                try {
                    // Check if the device is locked (Keyguard active)
                    val keyguardManager =
                        ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
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

                    val pm = ctx.packageManager
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
                    pendingResult.finish()
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
