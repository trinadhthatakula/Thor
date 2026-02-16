package com.valhalla.thor.data.source.local.dhizuku

import android.content.Context
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.util.Logger

class DhizukuReflector(
    private val context: Context
) {

    fun forceStop(packageName: String) {
        try {
            DhizukuHelper.forceStopApp(context, packageName)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Logger.e("DhizukuReflector", "forceStop failed", e)
        }
    }

    fun clearCache(packageName: String) {
        try {
            DhizukuHelper.clearCache(packageName)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Logger.e("DhizukuReflector", "clearCache failed", e)
        }
    }

    fun setAppEnabled(packageName: String, enabled: Boolean) {
        try {
            DhizukuHelper.setAppDisabled(context, packageName, !enabled)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Logger.e("DhizukuReflector", "setAppEnabled failed", e)
        }
    }

    fun uninstallApp(packageName: String): Boolean {
        return try {
            DhizukuHelper.uninstallApp(context, packageName)
        } catch (e: Exception) {
            false
        }
    }
}