// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.dhizuku

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import androidx.core.content.ContextCompat
import com.rosan.dhizuku.api.Dhizuku
import com.valhalla.thor.data.source.local.privileged.PrivilegedInstallerTransport
import com.valhalla.thor.data.source.local.privileged.PrivilegedPackageInstallers
import com.valhalla.thor.data.source.local.shizuku.SystemAppRemovalOutcome
import com.valhalla.thor.data.source.local.thorUserId
import java.util.UUID

/** The receiver belongs to this call, not the app-wide installation event bus. */
internal suspend fun uninstallWithDeviceOwner(
    context: Context,
    packageName: String,
    timeoutMillis: Long,
): SystemAppRemovalOutcome {
    val appContext = context.applicationContext
    val action = "${appContext.packageName}.DHIZUKU_UNINSTALL.${UUID.randomUUID()}"
    val installer = PrivilegedPackageInstallers.packageInstaller(
        transport = PrivilegedInstallerTransport.DHIZUKU,
        installerPackageName = Dhizuku.getOwnerPackageName(),
        userId = thorUserId,
    )
    var receiver: BroadcastReceiver? = null
    var pendingIntent: PendingIntent? = null
    return try {
        awaitPackageOperation<SystemAppRemovalOutcome>(packageName, timeoutMillis) { completed ->
            val resultReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val resultPackage = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                        ?: return
                    val status = intent.getIntExtra(
                        PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE,
                    )
                    // A request for interactive confirmation is not a silent uninstall. Do not
                    // launch its embedded intent; the gateway's existing UI fallback handles it.
                    completed(resultPackage, SystemAppRemovalOutcome(
                        succeeded = status == PackageInstaller.STATUS_SUCCESS,
                        exitCode = status,
                        platformMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                    ))
                }
            }
            ContextCompat.registerReceiver(
                appContext, resultReceiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiver = resultReceiver
            // Explicit package plus an unguessable per-call action prevents result cross-delivery.
            // Mutable is required so PackageInstaller can attach its result extras.
            val callback = PendingIntent.getBroadcast(
                appContext, 0, Intent(action).setPackage(appContext.packageName),
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            pendingIntent = callback
            installer.uninstall(packageName, callback.intentSender)
        } ?: SystemAppRemovalOutcome(
            succeeded = false, exitCode = PackageInstaller.STATUS_FAILURE,
            platformMessage = "Timed out awaiting device-owner uninstall result",
        )
    } finally {
        pendingIntent?.cancel()
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
    }
}
