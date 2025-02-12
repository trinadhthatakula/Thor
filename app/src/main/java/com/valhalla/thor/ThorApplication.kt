package com.valhalla.thor

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.util.Log
import com.valhalla.thor.model.AppListener
import com.valhalla.thor.model.getPermissions
import com.valhalla.thor.model.initStandardIntegrityProvider
import com.valhalla.thor.model.rootAvailable

class ThorApplication : Application() {


    override fun onCreate() {
        super.onCreate()

        initStandardIntegrityProvider()

        if (rootAvailable())
            getPermissions(
                arrayOf(
                    "android.permission.BROADCAST_PACKAGE_ADDED",
                    "android.permission.BROADCAST_PACKAGE_CHANGED",
                    "android.permission.BROADCAST_PACKAGE_REMOVED",
                    "android.permission.BROADCAST_PACKAGE_REPLACED",
                )
            )

    }

    override fun onTerminate() {
        try {
            if (getSharedPreferences("prefs", MODE_PRIVATE)
                    .getBoolean("can_reinstall", false) == true
            ) {
                unregisterReceiver(AppListener.getInstance())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onTerminate()
    }

}

fun Context.registerReceiver(receiver: BroadcastReceiver) {
    val intentFilter = IntentFilter()
    intentFilter.addAction("android.intent.action.PACKAGE_INSTALL")
    intentFilter.addAction("android.intent.action.PACKAGE_ADDED")
    intentFilter.addAction("android.intent.action.PACKAGE_REPLACED")
    intentFilter.addAction("android.intent.action.PACKAGE_REMOVED")
    registerReceiver(receiver, intentFilter)
    Log.d("ApplicationFile", "registerReceiver: registered receiver")
}