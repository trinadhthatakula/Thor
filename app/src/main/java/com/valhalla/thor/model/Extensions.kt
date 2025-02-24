package com.valhalla.thor.model

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream


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

fun Drawable.toFile(file: File): Boolean {
    return try {
        file.outputStream().use { output ->
            this.toBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

/**
 * Generates a list of random [Color] objects.
 *
 * @param count The number of colors to generate.
 * @param unique Whether the generated colors should be unique. Defaults to `true`.
 * @param defColors A vararg of default colors (as Int) to include in the result list.
 *                  These colors will be added to the list before generating random colors.
 * @return A list of [Color] objects. The size of the list will be equal to `count`,
 *         and if `unique` is true, all generated colors will be different. If `defColors` are provided,
 *         they will be added to the beginning of the result list, then random colors will be generated
 *         to complete the `count`. If `count` is less than the number of provided `defColors`,
 *         only the first `count` `defColors` will be returned.
 */
fun generateRandomColors(count: Int, unique: Boolean = true, vararg defColors: Int): List<Color> {
    val results = mutableListOf<Color>()
    results.addAll(defColors.map { Color(it) })
    while (results.size < count) {
        val color = Color(
            alpha = 255,
            red = (1..255).random(),
            green = (1..255).random(),
            blue = (1..255).random()
        )
        if (!unique || results.contains(color).not())
            results.add(color)
    }
    return results
}

fun calculateCrc32(file: File?): Long {
    val crc32 = CRC32()
    val buffer = ByteArray(1024 * 50)
    CheckedInputStream(file?.inputStream(), crc32).use { cis ->
        @Suppress("ControlFlowWithEmptyBody")
        while (cis.read(buffer) >= 0) {
        }
    }
    return crc32.value
}

fun calculateCrc32(stream: InputStream?): Long {
    val crc32 = CRC32()
    val buffer = ByteArray(1024 * 50)
    CheckedInputStream(stream, crc32).use { cis ->
        @Suppress("ControlFlowWithEmptyBody")
        while (cis.read(buffer) >= 0) {
        }
    }
    return crc32.value
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