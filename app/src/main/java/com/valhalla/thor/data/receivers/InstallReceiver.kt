package com.valhalla.thor.data.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.valhalla.thor.data.ACTION_INSTALL_STATUS
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.InstallState
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

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        val pendingResult = goAsync()
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (status) {
                    PackageInstaller.STATUS_SUCCESS -> {
                        eventBus.emit(InstallState.Success)
                    }
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirmIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        }
                        if (confirmIntent != null) {
                            eventBus.emit(InstallState.UserConfirmationRequired(confirmIntent))
                        }
                    }
                    else -> {
                        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Unknown Error"
                        eventBus.emit(InstallState.Error("Install Failed ($status): $msg"))
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}