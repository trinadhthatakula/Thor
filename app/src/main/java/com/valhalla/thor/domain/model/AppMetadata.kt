package com.valhalla.thor.domain.model

import android.graphics.Bitmap

data class AppMetadata(
    val label: String,
    val packageName: String,
    val version: String,
    val versionCode: Long,
    val icon: Bitmap?,
    val permissions: List<String> = emptyList()
)