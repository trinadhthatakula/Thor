// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.presentation.installer

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class PortableInstallerInputTest {
    private val uri = "content://fixture.test/kernel.zip".toUri()

    @Test fun `home picks explicitly open the portable installer with the selected document`() {
        val intent = createPortableInstallerIntent(ApplicationProvider.getApplicationContext<Application>(), uri)
        assertEquals(PortableInstallerActivity::class.java.name, intent.component?.className)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(uri, portableInstallerUri(intent))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun `view and single stream shares resolve the supplied document`() {
        assertEquals(uri, portableInstallerUri(Intent(Intent.ACTION_VIEW, uri)))
        assertEquals(uri, portableInstallerUri(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)))
    }

    @Test @Config(sdk = [28]) fun `stream shares work on older supported Android`() {
        assertEquals(uri, portableInstallerUri(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)))
    }

    @Test fun `single clip shares work without an extra`() {
        val intent = Intent(Intent.ACTION_SEND).apply { clipData = ClipData.newRawUri("kernel", uri) }
        assertEquals(uri, portableInstallerUri(intent))
    }

    @Test fun `local package files resolve from views streams and clips`() {
        for (extension in listOf("apk", "apks", "apkm", "apkp", "xapk", "zip")) {
            val fileUri = "file:///sdcard/Download/package.1.2.$extension".toUri()
            assertEquals(fileUri, portableInstallerUri(Intent(Intent.ACTION_VIEW, fileUri)))
            assertEquals(fileUri, portableInstallerUri(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, fileUri)))
            assertEquals(fileUri, portableInstallerUri(Intent(Intent.ACTION_SEND).apply {
                clipData = ClipData.newRawUri("package", fileUri)
            }))
        }
    }

    @Test fun `multiple or unsupported inputs never select a package`() {
        assertNull(portableInstallerUri(Intent(Intent.ACTION_SEND_MULTIPLE)))
        for (unsupported in listOf("https://example.test/kernel.zip", "ftp://example.test/kernel.zip")) {
            val unsupportedUri = unsupported.toUri()
            assertNull(portableInstallerUri(Intent(Intent.ACTION_VIEW, unsupportedUri)))
            assertNull(portableInstallerUri(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, unsupportedUri)))
            assertNull(portableInstallerUri(Intent(Intent.ACTION_SEND).apply {
                clipData = ClipData.newRawUri("kernel", unsupportedUri)
            }))
        }
        assertNull(portableInstallerUri(Intent(Intent.ACTION_VIEW)))
        assertNull(portableInstallerUri(Intent(Intent.ACTION_SEND)))
        assertNull(portableInstallerUri(Intent(Intent.ACTION_SEND).apply {
            clipData = ClipData.newRawUri("one", uri).apply { addItem(ClipData.Item(uri)) }
        }))
    }

    @Test fun `manifest offers the installer for ZIP and APK shares`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        for (type in listOf("application/zip", "application/vnd.android.package-archive")) {
            val matches = context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_SEND).setType(type).setPackage(context.packageName), 0,
            )
            assertTrue(matches.any { it.activityInfo.name == PortableInstallerActivity::class.java.name })
        }
    }

    @Test @Config(sdk = [28, 36]) fun `manifest offers the installer for typed content and local package files`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val types = mapOf(
            "apk" to "application/vnd.android.package-archive",
            "apks" to "application/x-apks",
            "apkm" to "application/vnd.apkm",
            "apkp" to "application/x-apkp",
            "xapk" to "application/x-xapk",
            "zip" to "application/zip",
        )
        for (base in listOf("content://fixture.test/", "file:///sdcard/Download/")) {
            for ((extension, type) in types) {
                val input = "${base}package.1.2.$extension".toUri()
                val matches = context.packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_VIEW).setDataAndType(input, type).setPackage(context.packageName),
                    PackageManager.MATCH_DEFAULT_ONLY,
                )
                assertTrue("installer missing for $input ($type)",
                    matches.any { it.activityInfo.name == PortableInstallerActivity::class.java.name })
            }
        }
    }

    // Robolectric 4.17's manifest parser ignores pathSuffix. Its declarations are guarded in
    // AnyFileOpenerAliasTest; verify long multi-dot names against Android's package manager.
    @Test @Config(sdk = [28, 36]) fun `manifest extension fallback matches generically typed package documents`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        for (extension in listOf("apk", "apks", "apkm", "apkp", "xapk", "zip")) {
            for (name in listOf("package.1.2.$extension", "package.${extension.uppercase()}")) {
                val input = "content://fixture.test/$name".toUri()
                val matches = context.packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_VIEW).setDataAndType(input, "application/x-unknown")
                        .setPackage(context.packageName),
                    PackageManager.MATCH_DEFAULT_ONLY,
                )
                assertTrue("extension fallback did not match $input",
                    matches.any { it.activityInfo.name == PortableInstallerActivity::class.java.name })
            }
        }
        val textMatches = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW).setDataAndType("content://fixture.test/document.txt".toUri(), "application/x-unknown")
                .setPackage(context.packageName),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertFalse("installer must not match an unrelated .txt document",
            textMatches.any { it.activityInfo.name == PortableInstallerActivity::class.java.name })
    }
}
