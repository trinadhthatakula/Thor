// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.util

import com.valhalla.thor.domain.model.AppInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single
import java.io.File

@Single
class ApksMetadataGenerator {

    @Serializable
    data class ApksMetadata(
        @SerialName("info_version") val infoVersion: Int = 1,
        @SerialName("package_name") val packageName: String,
        @SerialName("display_name") val displayName: String,
        @SerialName("version_name") val versionName: String,
        // Long, so a versionCodeMajor-carrying app's real code is written out. Widening the JSON
        // number is backward compatible for readers; a truncated one was simply wrong.
        @SerialName("version_code") val versionCode: Long,
        @SerialName("min_sdk") val minSdkVersion: Int,
        @SerialName("target_sdk") val targetSdkVersion: Int,
    )


    @Serializable
    data class XapkSplitApk(
        @SerialName("file") val file: String,
        @SerialName("id") val id: String
    )

    /**
     * The APKPure `manifest.json`, `xapk_version: 2`.
     *
     * The four fields between `version_name` and `split_apks` are nullable because the same shape
     * is written into two containers: a real `.xapk`, which wants the full descriptor, and the
     * `manifest.json` inside Thor's own `.apks`, which is read back by `InstallerRepositoryImpl`
     * and should keep exactly the bytes it has always had. A null is dropped from the JSON
     * entirely, whereas a placeholder `total_size: 0` in a container whose size nobody measured
     * would be a lie a reader could act on.
     */
    @Serializable
    data class XapkManifest(
        @SerialName("xapk_version") val xapkVersion: Int = 2,
        @SerialName("package_name") val packageName: String,
        @SerialName("name") val name: String,
        @SerialName("version_code") val versionCode: String,
        @SerialName("version_name") val versionName: String,
        // Strings, not Ints: a real APKPure manifest quotes both SDK levels, exactly as it quotes
        // version_code above. Wire-format fidelity, not an oversight — do not "fix" these to Int.
        @SerialName("min_sdk_version") val minSdkVersion: String? = null,
        @SerialName("target_sdk_version") val targetSdkVersion: String? = null,
        @SerialName("total_size") val totalSize: Long? = null,
        @SerialName("icon") val icon: String? = null,
        @SerialName("split_apks") val splitApks: List<XapkSplitApk> = emptyList()
    )

    fun generateJson(appInfo: AppInfo) = Json.encodeToString(
        ApksMetadata(
            packageName = appInfo.packageName,
            displayName = appInfo.appName ?: "",
            versionName = appInfo.versionName ?: "",
            versionCode = appInfo.versionCode,
            minSdkVersion = appInfo.minSdk,
            targetSdkVersion = appInfo.targetSdk
        )
    )

    fun generateJson(appInfo: AppInfo, targetFile: File) {
        targetFile.writeText(generateJson(appInfo))
    }

    // A `.xapk` is encoded with defaults on, because `xapk_version` — the field the format is named
    // after, and what a reader uses to tell a v1 layout from a v2 one — equals its property default,
    // and kotlinx's default config silently drops those. `explicitNulls = false` then keeps an
    // absent field absent rather than writing `"icon": null`. The `.apks` manifest keeps the plain
    // Json so its bytes do not move.
    private val xapkJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    /** The `manifest.json` that goes inside Thor's own `.apks`. */
    fun generateManifestJson(appInfo: AppInfo, entryNames: List<String>? = null): String =
        Json.encodeToString(xapkManifest(appInfo, entryNames))

    /**
     * The `manifest.json` for a real `.xapk`.
     *
     * [totalSize] is the summed length of the APKs the zip will hold — only the builder knows it.
     * [iconName] must name an entry the builder actually writes at the zip root; pass null when it
     * wrote none, so the field is left out rather than pointing a reader at a missing entry.
     * [entryNames] is the same contract for the APKs — see [xapkManifest].
     */
    fun generateManifestJson(
        appInfo: AppInfo,
        totalSize: Long,
        iconName: String? = "icon.png",
        entryNames: List<String>? = null,
    ): String = xapkJson.encodeToString(
        xapkManifest(appInfo, entryNames).copy(
            minSdkVersion = appInfo.minSdk.toString(),
            targetSdkVersion = appInfo.targetSdk.toString(),
            totalSize = totalSize,
            icon = iconName
        )
    )

    fun generateManifestJson(appInfo: AppInfo, targetFile: File, entryNames: List<String>? = null) {
        targetFile.writeText(generateManifestJson(appInfo, entryNames))
    }

    /**
     * [entryNames] are the zip entries the builder actually managed to stage. Copying is
     * per-file and can fail one split at a time (no root, a protected mount), so declaring
     * the app's full split list would name entries the container does not hold — which Thor's
     * own installer detects and works around, but SAI and APKPure simply trust. Pass null only
     * when the caller has no such list.
     */
    private fun xapkManifest(appInfo: AppInfo, entryNames: List<String>? = null): XapkManifest {
        // publicSourceDir first, matching how the builder resolves the base APK it copies.
        // If the two disagreed, the base entry would be filtered out below as "not staged".
        val baseName = (appInfo.publicSourceDir ?: appInfo.sourceDir)
            ?.substringAfterLast("/") ?: "base.apk"
        val declared = mutableListOf<XapkSplitApk>()
        declared.add(XapkSplitApk(file = baseName, id = "base"))

        appInfo.splitPublicSourceDirs.forEach { path ->
            val name = path.substringAfterLast("/")
            val id = name.substringBeforeLast(".apk").removePrefix("split_")
            declared.add(XapkSplitApk(file = name, id = id))
        }

        val staged = entryNames?.toSet()
        return XapkManifest(
            packageName = appInfo.packageName,
            name = appInfo.appName ?: "",
            versionCode = appInfo.versionCode.toString(),
            versionName = appInfo.versionName ?: "",
            splitApks = if (staged == null) declared else declared.filter { it.file in staged }
        )
    }

}