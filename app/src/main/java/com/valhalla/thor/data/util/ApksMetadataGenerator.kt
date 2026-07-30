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
     * One APK the builder staged: where it came from, and the entry name it was actually written
     * under.
     *
     * The two are not always the same string, which is the whole reason this type exists rather
     * than a list of names. `stagedApkNames` renames a leaf collision — two source directories each
     * holding a `base.apk` — to `base_2.apk`, and a manifest rebuilt from the *source* paths and
     * then filtered by the staged names would drop that APK from `split_apks` while the zip still
     * carried it. A `.xapk` whose manifest omits a split it contains installs missing that split,
     * and the reader has no way to know: `split_apks` is the manifest of record.
     *
     * [sourcePath] is still needed for the split *id*, which is the split's identity in the app and
     * has nothing to do with what the container calls the file.
     */
    data class StagedApk(val sourcePath: String, val entryName: String)

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
    fun generateManifestJson(appInfo: AppInfo, staged: List<StagedApk>? = null): String =
        Json.encodeToString(xapkManifest(appInfo, staged))

    /**
     * The `manifest.json` for a real `.xapk`.
     *
     * [totalSize] is the summed length of the APKs the zip will hold — only the builder knows it.
     * [iconName] must name an entry the builder actually writes at the zip root; pass null when it
     * wrote none, so the field is left out rather than pointing a reader at a missing entry.
     * [staged] is the same contract for the APKs — see [xapkManifest].
     */
    fun generateManifestJson(
        appInfo: AppInfo,
        totalSize: Long,
        iconName: String? = "icon.png",
        staged: List<StagedApk>? = null,
    ): String = xapkJson.encodeToString(
        xapkManifest(appInfo, staged).copy(
            minSdkVersion = appInfo.minSdk.toString(),
            targetSdkVersion = appInfo.targetSdk.toString(),
            totalSize = totalSize,
            icon = iconName
        )
    )

    fun generateManifestJson(appInfo: AppInfo, targetFile: File, staged: List<StagedApk>? = null) {
        targetFile.writeText(generateManifestJson(appInfo, staged))
    }

    /**
     * [staged] is what the builder actually wrote into the container, in order, and it is the only
     * honest source for `split_apks`: it is the one list that knows both that an APK is in the zip
     * *and* what the zip calls it. Deriving the names from [AppInfo] instead — as this used to —
     * describes the app rather than the artifact, and the two disagree whenever a name had to be
     * disambiguated. Thor's own analyzer works around a manifest that misdescribes its container;
     * SAI and APKPure simply trust it.
     *
     * Pass null only when the caller genuinely has no such list; every declared split is then named
     * and the manifest is a claim about the app.
     */
    private fun xapkManifest(appInfo: AppInfo, staged: List<StagedApk>? = null): XapkManifest {
        // publicSourceDir first, matching how the builder resolves the base APK it copies.
        val basePath = appInfo.publicSourceDir ?: appInfo.sourceDir
        val splitApks = staged?.map { apk ->
            XapkSplitApk(
                file = apk.entryName,
                // From the source leaf, never the staged one: `base_2.apk` is a container detail,
                // whereas the id is the split's identity inside the app and is what an installer
                // matches on.
                id = if (apk.sourcePath == basePath) "base" else splitIdFor(apk.sourcePath)
            )
        } ?: declaredApks(appInfo, basePath)

        return XapkManifest(
            packageName = appInfo.packageName,
            name = appInfo.appName ?: "",
            versionCode = appInfo.versionCode.toString(),
            versionName = appInfo.versionName ?: "",
            splitApks = splitApks
        )
    }

    /** Every APK the app declares, named as its source path names it. */
    private fun declaredApks(appInfo: AppInfo, basePath: String?): List<XapkSplitApk> = buildList {
        add(XapkSplitApk(file = basePath?.substringAfterLast("/") ?: "base.apk", id = "base"))
        appInfo.splitPublicSourceDirs.forEach { path ->
            add(XapkSplitApk(file = path.substringAfterLast("/"), id = splitIdFor(path)))
        }
    }

    private fun splitIdFor(path: String): String =
        path.substringAfterLast("/").substringBeforeLast(".apk").removePrefix("split_")

}