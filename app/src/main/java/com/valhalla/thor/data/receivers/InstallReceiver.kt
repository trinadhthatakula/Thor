// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.valhalla.thor.data.ACTION_INSTALL_STATUS
import com.valhalla.thor.data.manager.PendingInstallIntent
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Receives the async installation status result from the Android System.
 * This receiver is not exported for security; it is triggered via a targeted PendingIntent.
 */
class InstallReceiver : BroadcastReceiver(), KoinComponent {

    private val eventBus: InstallerEventBus by inject()
    private val pendingInstallIntent: PendingInstallIntent by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                emitInstallSessionStatus(intent, eventBus, pendingInstallIntent)
            } finally {
                pendingResult.finish()
            }
        }
    }

    internal companion object {
        const val EXTRA_INSTALL_TOKEN = "com.valhalla.thor.extra.INSTALL_TOKEN"
    }
}

/** Maps platform callbacks without treating progress or an unrecognized status as completion. */
internal suspend fun emitInstallSessionStatus(
    intent: Intent,
    eventBus: InstallerEventBus,
    pendingInstallIntent: PendingInstallIntent,
) {
    if (intent.action != ACTION_INSTALL_STATUS) return
    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
    when (status) {
        PackageInstaller.STATUS_SUCCESS,
        in PackageInstaller.STATUS_FAILURE..PackageInstaller.STATUS_FAILURE_TIMEOUT -> {
            val state = if (status == PackageInstaller.STATUS_SUCCESS) {
                InstallState.Success
            } else {
                installFailure(intent, status)
            }
            eventBus.emitSessionResult(
                intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1),
                intent.getStringExtra(InstallReceiver.EXTRA_INSTALL_TOKEN),
                state,
            )
        }

        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
            val confirmIntent: Intent? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
            eventBus.emitSessionPendingUserAction(
                intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1),
                intent.getStringExtra(InstallReceiver.EXTRA_INSTALL_TOKEN),
                publishConfirmation = confirmIntent?.let { confirmation ->
                    { pendingInstallIntent.set(confirmation) }
                },
            )
        }

        // Framework STATUS_PENDING_STREAMING is hidden from the public SDK. It is progress,
        // even though Thor's ordinary file-backed sessions do not request a DataLoader.
        -2 -> Unit
        // Missing or unknown statuses prove neither failure nor completion and must not replace
        // a different operation's presentation while this attempt keeps its ownership.
        else -> Unit
    }
}

private fun installFailure(intent: Intent, status: Int): InstallState.Error {
    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Unknown Error"
    return InstallState.Error(UiText.DynamicString("Install Failed ($status): $message"))
}
