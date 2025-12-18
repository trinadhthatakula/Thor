package com.valhalla.thor.domain.model

import android.graphics.drawable.Drawable

data class ApkDetails(
    val appName: String?,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val appIcon: Drawable?,
    val permissions: List<String>?,
    val minSdk: Int?,
    val targetSdk: Int?
)
