package com.valhalla.thor.presentation.utils

import android.content.Context
import android.graphics.drawable.Drawable
import com.valhalla.thor.BuildConfig

fun getAppIcon(packageName: String?, context: Context): Drawable? {
    return packageName?.let {
        try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                e.printStackTrace()
            null
        }
    }
}
