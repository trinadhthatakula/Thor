// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two properties [BundleFormat] exists to hold still.
 *
 * [BundleFormat.autoFor] replaced an inline `if (splits.isEmpty()) apk else apks` that used to live
 * inside `AppBundleBuilderImpl`. Every share and every export that does not name a format goes
 * through it, so the tests below are a parity check against that old rule, not a description of a
 * new one: if `autoFor` ever starts answering XAPK, thousands of installs of a default export
 * silently change container for one fewer installer accepts.
 *
 * The mime table is the bug that actually shipped. A `.apks`/`.xapk` is a zip no system component
 * can install; typing one as `application/vnd.android.package-archive` is what makes the receiving
 * app offer to install a bundle it will then choke on.
 */
class BundleFormatTest {

    private val packageArchive = "application/vnd.android.package-archive"

    private fun app(vararg splits: String) = AppInfo(
        packageName = "com.example.app",
        appName = "Example",
        versionName = "1.0",
        publicSourceDir = "/data/app/com.example.app-1/base.apk",
        splitPublicSourceDirs = splits.toList()
    )

    // --- autoFor: parity with the rule it replaced -------------------------------------------

    @Test
    fun `an app with no splits still defaults to a monolithic apk`() {
        assertEquals(BundleFormat.APK, BundleFormat.autoFor(app()))
    }

    @Test
    fun `an app with splits still defaults to apks`() {
        assertEquals(
            BundleFormat.APKS,
            BundleFormat.autoFor(app("/data/app/com.example.app-1/split_config.arm64_v8a.apk"))
        )
    }

    @Test
    fun `one split is enough to make it a bundle`() {
        // The old rule read `isEmpty()`, not a count or a "has an abi split" test. A threshold
        // here would export a two-APK app as a base-only .apk and lose the split on reinstall.
        assertEquals(BundleFormat.APKS, BundleFormat.autoFor(app("/data/app/x/split_ui.apk")))
    }

    @Test
    fun `the default is never xapk, whatever the app looks like`() {
        // XAPK is only ever an explicit choice. This is the guard on "a default export still
        // produces what it always did" — a new heuristic (has an obb, is a game, has many splits)
        // that reaches for XAPK has to fail here first.
        val shapes = listOf(
            app(),
            app("/data/app/x/split_config.arm64_v8a.apk"),
            app("/data/app/x/split_a.apk", "/data/app/x/split_b.apk", "/data/app/x/split_c.apk"),
            AppInfo(packageName = "com.no.source"),
            AppInfo(packageName = "com.system", isSystem = true, publicSourceDir = "/system/app/a.apk")
        )

        shapes.forEach { shape ->
            assertNotEquals(
                "autoFor picked XAPK for ${shape.packageName}",
                BundleFormat.XAPK,
                BundleFormat.autoFor(shape)
            )
        }
    }

    // --- The mime table ----------------------------------------------------------------------

    @Test
    fun `only a monolithic apk is typed as a package archive`() {
        assertEquals(packageArchive, BundleFormat.APK.mime)

        // Written over `entries` rather than as two literal assertions so a fourth container added
        // later cannot copy the APK row and reintroduce the same bug.
        BundleFormat.entries.filter { it != BundleFormat.APK }.forEach { format ->
            assertEquals(
                "$format is a zip; typing it as a package-archive offers an install that fails",
                "application/octet-stream",
                format.mime
            )
        }
    }

    @Test
    fun `every format carries a distinct dotless extension`() {
        // Callers write "$name.${format.extension}", so a leading dot produces "name..apk".
        BundleFormat.entries.forEach { format ->
            assertTrue("$format has no extension", format.extension.isNotEmpty())
            assertFalse("${format.extension} carries its own dot", format.extension.startsWith("."))
        }
        assertEquals(
            BundleFormat.entries.size,
            BundleFormat.entries.map { it.extension }.toSet().size
        )
        assertEquals("apk", BundleFormat.APK.extension)
        assertEquals("apks", BundleFormat.APKS.extension)
        assertEquals("xapk", BundleFormat.XAPK.extension)
    }
}
