package com.valhalla.thor.model

import android.content.Context
import android.graphics.drawable.Drawable
import java.io.File

fun File.copyTo(file: File): Boolean {
    return try {
        file.outputStream().use { output ->
            this.inputStream().copyTo(output)
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

fun getAppIcon(packageName: String?, context: Context): Drawable? {
    return packageName?.let {
        try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

val popularInstallers = mapOf<String, String>(
    "com.android.vending" to "Google Play Store",
    "com.sec.android.app.samsungapps" to "Samsung Store",
    "com.huawei.appmarket" to "Huawei Store",
    "com.amazon.venezia" to "Amazon App Store",
    "com.miui.supermarket" to "Xiaomi Store",
    "com.xiaomi.discover" to "Xiaomi Discover",
    "com.oppo.market" to "Oppo Store",
    "com.vivo.sdkplugin" to "Vivo Store",
    "com.oneplus.appstore" to "OnePlus Store",
    "com.qualcomm.qti.appstore" to "Qualcomm Store",
    "com.sonymobile.playanywhere" to "Sony Store",
    "com.asus.appmarket" to "Asus Store",
    "com.zte.appstore" to "ZTE Store",
    "com.lenovo.leos.appstore" to "Lenovo Store",
    "com.htc.appmarket" to "HTC Store",
    "com.lge.appbox.client" to "LG Store",
    "com.nokia.nstore" to "Nokia Store",
    "com.miui.packageinstaller" to "Xiaomi Package Installer",
    "com.google.android.packageinstaller" to "Google Package Installer",
    "com.android.packageinstaller" to "Android Package Installer",
    "com.samsung.android.packageinstaller" to "Samsung Package Installer",
)