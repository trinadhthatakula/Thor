package com.valhalla.thor.data.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.valhalla.thor.util.Logger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Logger.d("BootReceiver", "Boot completed broadcast received. ThorApplication will initialize AutoFreezeManager if enabled.")
        }
    }
}
