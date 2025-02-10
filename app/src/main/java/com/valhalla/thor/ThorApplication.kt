package com.valhalla.thor

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import com.valhalla.thor.model.AppListener
import com.valhalla.thor.model.initIntegrityManager
import com.valhalla.thor.model.initStandardIntegrityProvider

class ThorApplication : Application() {

    val receiver: AppListener = AppListener.getInstance()

    override fun onCreate() {
        super.onCreate()

        initStandardIntegrityProvider()

        if (getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("can_reinstall", false) == true
        ) {
            registerReceiver(receiver)
        }

    }

    override fun onTerminate() {
        try {
            if (getSharedPreferences("prefs", MODE_PRIVATE)
                    .getBoolean("can_reinstall", false) == true
            ) {
                unregisterReceiver(receiver)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onTerminate()
    }

}

fun Context.registerReceiver(receiver: BroadcastReceiver){
    val intentFilter = IntentFilter()
    intentFilter.addAction("android.intent.action.PACKAGE_INSTALL")
    intentFilter.addAction("android.intent.action.PACKAGE_ADDED")
    registerReceiver(receiver, intentFilter)
}