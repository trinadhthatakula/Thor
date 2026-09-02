// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.valhalla.bypass.Bypass
import java.io.File
import java.security.MessageDigest

internal enum class ArchivePackageParsingStrategy {
    PLATFORM,
    LEGACY_CLUSTER,
}

internal fun archivePackageParsingStrategy(
    apiLevel: Int,
    isDirectory: Boolean,
): ArchivePackageParsingStrategy = if (
    isDirectory && apiLevel < Build.VERSION_CODES.R
) {
    ArchivePackageParsingStrategy.LEGACY_CLUSTER
} else {
    ArchivePackageParsingStrategy.PLATFORM
}

/** Parse an APK file or an extracted APK cluster through the platform package parser. */
internal fun PackageManager.readArchivePackageInfo(
    archive: File,
    flags: Int,
): PackageInfo? = when (archivePackageParsingStrategy(Build.VERSION.SDK_INT, archive.isDirectory)) {
    ArchivePackageParsingStrategy.PLATFORM -> readPlatformArchivePackageInfo(archive, flags)
    ArchivePackageParsingStrategy.LEGACY_CLUSTER -> readLegacyArchiveClusterPackageInfo(archive, flags)
}

private fun PackageManager.readPlatformArchivePackageInfo(
    archive: File,
    flags: Int,
): PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getPackageArchiveInfo(
        archive.absolutePath,
        PackageManager.PackageInfoFlags.of(flags.toLong()),
    )
} else {
    @Suppress("DEPRECATION")
    getPackageArchiveInfo(archive.absolutePath, flags)
}

/**
 * Android 9 and 10 route the public archive API through `parseMonolithicPackage`, so a directory
 * containing a valid base plus splits is always rejected. Invoke those releases' own cluster parser
 * instead; `collectCertificates` verifies every selected APK and rejects inconsistent signatures.
 * Thor initializes and exempts `android.content.pm` through [Bypass] before this singleton is used.
 */
@SuppressLint("PrivateApi")
private fun PackageManager.readLegacyArchiveClusterPackageInfo(
    archive: File,
    flags: Int,
): PackageInfo? = runCatching {
    val parserClass = Class.forName("android.content.pm.PackageParser")
    val packageClass = Class.forName("android.content.pm.PackageParser\$Package")
    val callbackClass = Class.forName("android.content.pm.PackageParser\$Callback")
    val callbackImplClass = Class.forName("android.content.pm.PackageParser\$CallbackImpl")
    val userStateClass = Class.forName("android.content.pm.PackageUserState")
    val parser = Bypass.newInstance<Any>(parserClass)
    val callback = Bypass.newInstance<Any>(
        callbackImplClass,
        arrayOf(PackageManager::class.java),
        this,
    )
    Bypass.invoke<Unit>(
        parserClass,
        parser,
        "setCallback",
        arrayOf(callbackClass),
        callback,
    )
    val parsedPackage = Bypass.invoke<Any>(
        parserClass,
        parser,
        "parsePackage",
        arrayOf(File::class.java, Int::class.javaPrimitiveType!!),
        archive,
        0,
    )
    Bypass.invoke<Unit>(
        parserClass,
        null,
        "collectCertificates",
        arrayOf(packageClass, Boolean::class.javaPrimitiveType!!),
        parsedPackage,
        false,
    )
    val userState = Bypass.newInstance<Any>(userStateClass)
    Bypass.invoke<PackageInfo?>(
        parserClass,
        null,
        "generatePackageInfo",
        arrayOf(
            packageClass,
            IntArray::class.java,
            Int::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            Set::class.java,
            userStateClass,
        ),
        parsedPackage,
        null,
        flags,
        0L,
        0L,
        null,
        userState,
    )
}.getOrNull()

/** SHA-256 identities of the APK content signers, without accepting signing history. */
internal fun PackageInfo.currentSignerSha256(): Set<String> =
    signingInfo?.apkContentsSigners
        ?.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02X".format(byte) }
        }
        .orEmpty()
