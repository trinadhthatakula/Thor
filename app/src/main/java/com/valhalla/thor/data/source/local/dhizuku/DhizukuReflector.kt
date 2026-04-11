package com.valhalla.thor.data.source.local.dhizuku

import android.content.Context
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.util.Logger

class DhizukuReflector(
    private val context: Context
) {

    fun forceStop(packageName: String): Boolean {
        return try {
            DhizukuHelper.forceStopApp(context, packageName)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Logger.e("DhizukuReflector", "forceStop failed", e)
            false
        }
    }

    fun clearCache(packageName: String): Boolean {
        return try {
            DhizukuHelper.clearCache(packageName)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Logger.e("DhizukuReflector", "clearCache failed", e)
            false
        }
    }

    fun clearData(packageName: String): Boolean {
        return try {
            DhizukuHelper.clearAppData(packageName)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Logger.e("DhizukuReflector", "clearData failed", e)
            false
        }
    }

    fun setAppEnabled(packageName: String, enabled: Boolean): Boolean {
        return try {
            DhizukuHelper.setAppDisabled(context, packageName, !enabled)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Logger.e("DhizukuReflector", "setAppEnabled failed", e)
            false
        }
    }

    fun uninstallApp(packageName: String): Boolean {
        return try {
            DhizukuHelper.uninstallApp(packageName)
        } catch (_: Exception) {
            false
        }
    }
}