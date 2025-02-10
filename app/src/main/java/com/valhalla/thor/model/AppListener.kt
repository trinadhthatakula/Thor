package com.valhalla.thor.model

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AppListener : BroadcastReceiver() {

    companion object {
        var INSTANCE: AppListener? = null

        fun getInstance() = INSTANCE ?: synchronized(this) {
            val instance = AppListener()
            INSTANCE = instance
            instance
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d("Receiver", "onReceive: invoked ${intent?.data}")
        if (context?.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                ?.getBoolean("can_reinstall", false) == true
        ) intent?.action?.let { action ->
            Log.d("Receiver", "onReceive: invoked ${intent.data}")
            if (action == "android.intent.action.PACKAGE_INSTALL" || action == "android.intent.action.PACKAGE_REMOVED" || action == "android.intent.action.PACKAGE_ADDED" || action == "android.intent.action.PACKAGE_REPLACED") {
                context.packageManager.let { pm ->
                    val packageName = intent.data.toString()
                    val appInfoGrabber = AppInfoGrabber(context)
                    val userApps = appInfoGrabber.getUserApps()
                    val systemApps = appInfoGrabber.getSystemApps()
                    userApps.firstOrNull { it.packageName == packageName }
                        ?: systemApps.firstOrNull { it.packageName == packageName }
                            ?.let { appInfo ->
                                if (appInfo.installerPackageName != "com.android.vending") {
                                    reInstallWithGoogle(appInfo, observer = {
                                        Log.d("Receiver", "onReceive: $it")
                                    }, exit = {
                                        Log.d("Receiver", "done")
                                    })
                                }
                            } ?: run {
                            Log.d("Receiver", "onReceive: installed app $packageName not found")
                        }

                }
            }
        }
    }
}