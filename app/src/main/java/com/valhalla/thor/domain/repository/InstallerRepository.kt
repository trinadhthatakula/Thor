package com.valhalla.thor.domain.repository

import android.net.Uri

/**
 * The Repository Contract.
 * The Domain layer doesn't care about PackageInstaller APIs, only that we can install a URI.
 */
interface InstallerRepository {
    suspend fun installPackage(uri: Uri)
}