// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.util.Logger
import com.valhalla.superuser.utils.escapeForShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Collections

class AutoReinstallReceiver : BroadcastReceiver(), KoinComponent {

    private val preferenceRepository: PreferenceRepository by inject()
    private val systemRepository: SystemRepository by inject()

    companion object {
        private const val TAG = "AutoReinstallReceiver"
        private const val GOOGLE_PLAY_STORE = "com.android.vending"
        private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9._]+$")
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REPLACED) return

        val packageName = intent.data?.schemeSpecificPart ?: return
        if (packageName.isBlank() || !PACKAGE_NAME_REGEX.matches(packageName)) return

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Check if preference is enabled
                val prefs = preferenceRepository.userPreferences.first()
                if (!prefs.autoReinstallEnabled) return@launch

                // 2. Inspect Current Installer of Record
                val currentInstaller = getInstallerOfRecord(context, packageName)
                if (currentInstaller == GOOGLE_PLAY_STORE) {
                    Logger.d(TAG, "Package $packageName is already mapped to Google Play Store.")
                    return@launch
                }

                Logger.d(TAG, "Package $packageName has installer '$currentInstaller'. Overriding to Google Play Store...")

                // 3. Overwrite Installer of Record using Thor System Executor
                val escapedPackage = packageName.escapeForShell()
                val command = "pm set-installer $escapedPackage $GOOGLE_PLAY_STORE"
                
                val result = systemRepository.executeShellCommand(command)
                result.fold(
                    onSuccess = { (exitCode, output) ->
                        if (exitCode == 0) {
                            Logger.i(TAG, "Successfully set installer of record for $packageName to Google Play Store.")
                        } else {
                            Logger.e(TAG, "Failed to set installer (Exit code: $exitCode). Output: $output")
                        }
                    },
                    onFailure = { error ->
                        Logger.e(TAG, "Failed to run set-installer command.", error)
                    }
                )
            } catch (t: Throwable) {
                Logger.e(TAG, "Error in AutoReinstallReceiver process.", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun getInstallerOfRecord(context: Context, packageName: String): String? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                pm.getInstallSourceInfo(packageName).installingPackageName
            }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                pm.getInstallerPackageName(packageName)
            }.getOrNull()
        }
    }
}
