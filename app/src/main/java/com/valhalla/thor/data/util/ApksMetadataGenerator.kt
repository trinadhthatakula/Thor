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
        @SerialName("version_code") val versionCode: Int,
        @SerialName("min_sdk") val minSdkVersion: Int,
        @SerialName("target_sdk") val targetSdkVersion: Int,
    )


    @Serializable
    data class XapkSplitApk(
        @SerialName("file") val file: String,
        @SerialName("id") val id: String
    )

    @Serializable
    data class XapkManifest(
        @SerialName("xapk_version") val xapkVersion: Int = 2,
        @SerialName("package_name") val packageName: String,
        @SerialName("name") val name: String,
        @SerialName("version_code") val versionCode: String,
        @SerialName("version_name") val versionName: String,
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

    fun generateManifestJson(appInfo: AppInfo): String {
        val baseName = appInfo.sourceDir?.substringAfterLast("/") ?: "base.apk"
        val splitApks = mutableListOf<XapkSplitApk>()
        splitApks.add(XapkSplitApk(file = baseName, id = "base"))

        appInfo.splitPublicSourceDirs.forEach { path ->
            val name = path.substringAfterLast("/")
            val id = name.substringBeforeLast(".apk").removePrefix("split_")
            splitApks.add(XapkSplitApk(file = name, id = id))
        }

        val manifest = XapkManifest(
            packageName = appInfo.packageName,
            name = appInfo.appName ?: "",
            versionCode = appInfo.versionCode.toString(),
            versionName = appInfo.versionName ?: "",
            splitApks = splitApks
        )
        return Json.encodeToString(manifest)
    }

    fun generateManifestJson(appInfo: AppInfo, targetFile: File) {
        targetFile.writeText(generateManifestJson(appInfo))
    }

}