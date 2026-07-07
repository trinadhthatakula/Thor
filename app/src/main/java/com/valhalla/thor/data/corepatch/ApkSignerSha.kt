// ApkSignerSha.kt
package com.valhalla.thor.data.corepatch

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

fun ByteArray.toSignerSha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02X".format(it) }

object ApkSignerSha {

    private fun firstSignerSha(signatures: Array<Signature>?): String? =
        signatures?.firstOrNull()?.toByteArray()?.toSignerSha256Hex()

    @Suppress("DEPRECATION")
    fun ofApk(context: Context, apkPath: String): String? = runCatching {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apkPath, flags) ?: return null
        val sigs = if (Build.VERSION.SDK_INT >= 28)
            info.signingInfo?.apkContentsSigners else info.signatures
        firstSignerSha(sigs)
    }.getOrNull()

    @Suppress("DEPRECATION")
    fun ofInstalled(context: Context, packageName: String): String? = runCatching {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageInfo(packageName, flags)
        val sigs = if (Build.VERSION.SDK_INT >= 28)
            info.signingInfo?.apkContentsSigners else info.signatures
        firstSignerSha(sigs)
    }.getOrNull()
}
