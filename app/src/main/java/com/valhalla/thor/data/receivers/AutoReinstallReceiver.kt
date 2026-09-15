// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.valhalla.thor.data.repository.readInstallerOfRecord
import com.valhalla.thor.domain.model.FixStoreRoute
import com.valhalla.thor.domain.model.fixStoreRoute
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.util.Logger
import com.valhalla.superuser.utils.escapeForShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AutoReinstallReceiver : BroadcastReceiver(), KoinComponent {

    private val preferenceRepository: PreferenceRepository by inject()
    private val systemRepository: SystemRepository by inject()
    private val privilege: PrivilegeStateProvider by inject()

    companion object {
        private const val TAG = "AutoReinstallReceiver"
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
                // Leave time to finish the pending broadcast even when a privilege probe stalls.
                withTimeout(8_000) {
                    if (!preferenceRepository.userPreferences.first().autoReinstallEnabled) {
                        return@withTimeout
                    }
                    preserveGooglePlayInstaller(
                        packageName = packageName,
                        privilege = privilege,
                        currentInstaller = {
                            context.packageManager.readInstallerOfRecord(packageName)
                        },
                        execute = { command -> systemRepository.executeShellCommand(command) },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Logger.e(TAG, "Error in AutoReinstallReceiver process.", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

}

private const val GOOGLE_PLAY_STORE = "com.android.vending"

/** The background equivalent of Fix Store must honor the same capability and readback. */
internal suspend fun preserveGooglePlayInstaller(
    packageName: String,
    privilege: PrivilegeStateProvider,
    currentInstaller: () -> String?,
    execute: suspend (String) -> Result<Pair<Int, String?>>,
): Boolean {
    val active = privilege.state.first { it.isReady }.active
    if (fixStoreRoute(active) != FixStoreRoute.PRIVILEGED) return false
    if (currentInstaller() == GOOGLE_PLAY_STORE) return true

    val escapedPackage = packageName.escapeForShell()
    // The active gateway can change after the probe. Do not attempt set-installer if routing
    // falls back to an ordinary device-owner UID in the meantime.
    val command = "( case \"\$(id -u)\" in 0|2000) " +
        "pm set-installer $escapedPackage $GOOGLE_PLAY_STORE;; *) exit 1;; esac )"
    val result = execute(command)
    result.exceptionOrNull()?.let { error ->
        if (error is CancellationException) throw error
        Logger.e("AutoReinstallReceiver", "Failed to run set-installer command", error)
        return false
    }
    val (exitCode, output) = result.getOrThrow()
    val verified = exitCode == 0 && currentInstaller() == GOOGLE_PLAY_STORE
    if (verified) {
        Logger.i("AutoReinstallReceiver", "Verified Google Play installer for $packageName")
    } else {
        Logger.e("AutoReinstallReceiver", "Installer was not changed for $packageName ($exitCode): $output")
    }
    return verified
}
