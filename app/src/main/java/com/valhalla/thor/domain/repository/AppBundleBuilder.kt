package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.AppInfo
import java.io.File

/**
 * Domain port for building a shareable/exportable app bundle: a single `.apk`
 * for apps with no splits, an `.apks` (base + splits + metadata.json +
 * manifest.json) for split apps. Keeps the export/share use cases free of
 * Android cache-dir / file-I/O concerns — the concrete impl lives in the data
 * layer. Signatures use only [File]/String/domain [AppInfo], no Android types.
 */
interface AppBundleBuilder {
    suspend fun build(appInfo: AppInfo, cacheSubDir: String = "share_temp"): Result<File>
}
