package com.valhalla.thor.domain.model

import kotlinx.serialization.Serializable

/**
 * Root of the extension store catalog (schema v1), fetched over HTTPS from the
 * Thor-Extensions repo. Decoded with `Json { ignoreUnknownKeys = true }` so newer
 * server-side fields never break an older client.
 */
@Serializable
data class ExtensionCatalog(
    val schemaVersion: Int = 1,
    val extensions: List<CatalogEntry> = emptyList()
)

/**
 * A single publishable extension in the store catalog.
 *
 * An entry is installable only when it is [verified] AND has a non-blank [apkUrl].
 * Entries with `verified == false` or an empty [apkUrl] are "source only" — the UI shows
 * a build-it-yourself state and no install button.
 *
 * @param apkUrl HTTPS URL of the dedicated-key-signed extension APK, or "" for source-only.
 * @param sha256 Expected SHA-256 of the APK file (uppercase/lowercase hex, no separators),
 *   or "" when unknown. When present it is verified before the signer pin check.
 * @param versionCode The published APK's Android `versionCode`. Compared against the installed
 *   copy's versionCode to offer an update; `0` means unknown (never offers an update).
 * @param minThorVersionCode Minimum Thor `versionCode` required to install this extension.
 * @param minSdk Minimum Android SDK the extension supports.
 * @param requiresLSPosed True when the extension is an LSPosed/Xposed module and only works
 *   with an active LSPosed manager.
 * @param sourcePath Path (within the extensions repo) to the extension's source.
 */
@Serializable
data class CatalogEntry(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val author: String = "",
    val version: String = "",
    val versionCode: Long = 0,
    val verified: Boolean = false,
    val requiresLSPosed: Boolean = false,
    val minThorVersionCode: Int = 0,
    val minSdk: Int = 0,
    val apkUrl: String = "",
    val sha256: String = "",
    val sourcePath: String = ""
) {
    /** Installable only when verified and an APK is actually published. */
    val isInstallable: Boolean
        get() = verified && apkUrl.isNotBlank()
}
