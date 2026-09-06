// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

enum class SharePrepareFormat {
    AUTO, APK, APKS, XAPK;

    /** AUTO alone inspects fresh installed metadata; explicit intent is never silently changed. */
    fun resolve(appInfo: AppInfo): BundleFormat = when (this) {
        AUTO -> BundleFormat.autoFor(appInfo)
        APK -> BundleFormat.APK
        APKS -> BundleFormat.APKS
        XAPK -> BundleFormat.XAPK
    }

    fun accepts(displayName: String, mimeType: String): Boolean {
        val formats = when (this) {
            AUTO -> listOf(BundleFormat.APK, BundleFormat.APKS)
            APK -> listOf(BundleFormat.APK)
            APKS -> listOf(BundleFormat.APKS)
            XAPK -> listOf(BundleFormat.XAPK)
        }
        return formats.any { displayName.endsWith(".${it.extension}") && mimeType == it.mime }
    }
}
