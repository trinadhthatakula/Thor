package com.valhalla.thor.domain.repository

import android.net.Uri
import com.valhalla.thor.domain.model.CorePatchAuthorization

enum class InstallMode {
    NORMAL,
    SHIZUKU,
    DHIZUKU,
    ROOT,
    EXTERNAL
}

/**
 * The Repository Contract.
 * The Domain layer doesn't care about PackageInstaller APIs, only that we can install a URI.
 */
interface InstallerRepository {
    /**
     * @param corePatch when non-null AND CorePatch is master-enabled, the install runs inside the
     *   arm/disarm bracket (transient Play-Protect + Strombringer hook window). It is honoured ONLY
     *   on the synchronous root path; every other [InstallMode] ignores it. Default null keeps all
     *   existing callers unaffected.
     */
    suspend fun installPackage(
        uri: Uri,
        mode: InstallMode,
        canDowngrade: Boolean = false,
        corePatch: CorePatchAuthorization? = null,
    )
}