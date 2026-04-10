package com.valhalla.thor.data.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.valhalla.thor.data.ACTION_INSTALL_STATUS
import com.valhalla.thor.data.source.local.room.AppDao
import com.valhalla.thor.data.source.local.room.AppEntity
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.model.AppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Receives the async result from the Android System.
 */
class InstallReceiver : BroadcastReceiver(), KoinComponent {

    private val eventBus: InstallerEventBus by inject()
    private val appDao: AppDao by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == ACTION_INSTALL_STATUS) {
            handleInstallStatus(intent)
        } else {
            handlePackageChange(context, action, intent)
        }
    }

    private fun handleInstallStatus(intent: Intent) {
        val pendingResult = goAsync()
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (status) {
                    PackageInstaller.STATUS_SUCCESS -> {
                        eventBus.emit(InstallState.Success)
                    }

                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirmIntent: Intent? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(
                                    Intent.EXTRA_INTENT,
                                    Intent::class.java
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(Intent.EXTRA_INTENT)
                            }
                        if (confirmIntent != null) {
                            eventBus.emit(InstallState.UserConfirmationRequired(confirmIntent))
                        }
                    }

                    else -> {
                        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                            ?: "Unknown Error"
                        eventBus.emit(InstallState.Error("Install Failed ($status): $msg"))
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handlePackageChange(context: Context, action: String, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    Intent.ACTION_PACKAGE_REMOVED, Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                        appDao.deleteApp(packageName)
                    }

                    Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED, Intent.ACTION_PACKAGE_CHANGED -> {
                        val pm = context.packageManager
                        try {
                            val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()
                            val packInfo =
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    pm.getPackageInfo(
                                        packageName,
                                        PackageManager.PackageInfoFlags.of(flags)
                                    )
                                } else {
                                    pm.getPackageInfo(
                                        packageName,
                                        PackageManager.MATCH_UNINSTALLED_PACKAGES
                                    )
                                }
                            val appInfo = packInfo.applicationInfo ?: return@launch
                            val mapped = AppInfo.mapToAppInfo(packInfo, appInfo, pm, isLightweight = true)
                            appDao.insertApps(listOf(AppEntity.fromDomain(mapped)))
                        } catch (_: Exception) {
                            // Package might be gone
                            appDao.deleteApp(packageName)
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
