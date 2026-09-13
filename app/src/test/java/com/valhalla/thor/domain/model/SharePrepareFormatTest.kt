// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import com.valhalla.thor.data.backup.job.DataTaskExecutionPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class SharePrepareFormatTest {
    @Test
    fun `automatic share of a monolithic app remains an APK`() {
        assertEquals(BundleFormat.APK, SharePrepareFormat.AUTO.resolve(app()))
    }

    @Test
    fun `automatic share of a split app preserves every split in APKS`() {
        assertEquals(BundleFormat.APKS, SharePrepareFormat.AUTO.resolve(app("split_config.en.apk")))
    }

    @Test
    fun `automatic share never switches large or system apps to XAPK`() {
        val apps = listOf(
            app().copy(isSystem = true),
            app("split_config.en.apk", "split_config.arm64_v8a.apk", "split_feature.apk"),
        )

        assertEquals(
            listOf(BundleFormat.APK, BundleFormat.APKS),
            apps.map(SharePrepareFormat.AUTO::resolve),
        )
    }

    @Test
    fun `explicit APK remains exact even for a split app`() {
        assertEquals(BundleFormat.APK, SharePrepareFormat.APK.resolve(app("split_feature.apk")))
    }

    @Test
    fun `explicit APKS remains exact even for a monolithic app`() {
        assertEquals(BundleFormat.APKS, SharePrepareFormat.APKS.resolve(app()))
    }

    @Test
    fun `explicit XAPK remains exact for both monolithic and split apps`() {
        assertEquals(BundleFormat.XAPK, SharePrepareFormat.XAPK.resolve(app()))
        assertEquals(BundleFormat.XAPK, SharePrepareFormat.XAPK.resolve(app("split_feature.apk")))
    }

    @Test
    fun `persisted automatic policy survives the execution payload until app resolution`() {
        val detail = StoredDataTaskDetail.SharePrepare(
            requestedFormat = SharePrepareFormat.AUTO,
            publicationPolicy = DataTaskPublicationPolicy.PRIVATE_SHARE_WITH_24_HOUR_EXPIRY,
            deterministicStagingIdentity = "stage-test",
        )
        val payload = DataTaskExecutionPayload.SharePrepare(
            requestedFormat = detail.requestedFormat,
            publicationPolicy = detail.publicationPolicy,
        )

        assertEquals(BundleFormat.APK, payload.requestedFormat.resolve(app()))
        assertEquals(BundleFormat.APKS, payload.requestedFormat.resolve(app("split_feature.apk")))
    }

    private fun app(vararg splits: String) = AppInfo(
        packageName = "com.example.app",
        appName = "Example",
        publicSourceDir = "/data/app/com.example.app/base.apk",
        splitPublicSourceDirs = splits.toList(),
    )
}
