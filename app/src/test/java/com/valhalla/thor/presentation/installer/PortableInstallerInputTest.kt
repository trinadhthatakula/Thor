// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.presentation.installer

import android.app.Application
import android.content.ClipData
import android.content.Intent
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

    @Test fun `multiple or remote inputs never select a package`() {
        assertNull(portableInstallerUri(Intent(Intent.ACTION_SEND_MULTIPLE)))
        assertNull(portableInstallerUri(Intent(Intent.ACTION_VIEW, "https://example.test/kernel.zip".toUri())))
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
}
