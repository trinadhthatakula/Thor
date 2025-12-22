package com.valhalla.thor.domain.repository

import android.net.Uri
import com.valhalla.thor.domain.model.AppMetadata

interface AppAnalyzer {
    /**
     * Extracts metadata from a URI (APK, XAPK, APKS) without installing it.
     */
    suspend fun analyze(uri: Uri): Result<AppMetadata>
}