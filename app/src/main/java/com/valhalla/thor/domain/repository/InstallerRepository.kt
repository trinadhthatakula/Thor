package com.valhalla.thor.domain.repository

import android.net.Uri

enum class InstallMode {
    NORMAL,
    SHIZUKU,
    DHIZUKU,
    ROOT
}

/**
 * The Repository Contract.
 * The Domain layer doesn't care about PackageInstaller APIs, only that we can install a URI.
 */
interface InstallerRepository {
    suspend fun installPackage(uri: Uri, mode: InstallMode, canDowngrade: Boolean = false)
}