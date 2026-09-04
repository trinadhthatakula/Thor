// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ForegroundServicePermissionsManifestTest {

    @Test
    fun `manifest declares exactly the four foreground execution permissions`() {
        val expected = setOf(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.WAKE_LOCK",
        )
        val declared = Regex("""<uses-permission\s+[^>]*android:name="([^"]+)"[^>]*/>""")
            .findAll(manifestText)
            .map { match -> match.groupValues[1] }
            .filter { permission ->
                permission.startsWith("android.permission.FOREGROUND_SERVICE") ||
                    permission == "android.permission.WAKE_LOCK"
            }
            .toSet()

        assertEquals(expected, declared)
    }

    @Test
    fun `manifest has no framework-created queue component before its class exists`() {
        val xml = manifestText

        assertFalse(xml.contains("DataSyncService"))
        assertFalse(xml.contains("PrivilegeSweepService"))
        assertFalse(
            receiverNames(xml).any { receiver ->
                receiver.contains("DataSync", ignoreCase = true) ||
                    receiver.contains("PrivilegeSweep", ignoreCase = true)
            }
        )
    }

    @Test
    fun `manifest test read the real application manifest`() {
        val xml = manifestText

        assertTrue("manifest is implausibly short: ${xml.length} chars", xml.length > 2_000)
        assertTrue(xml.contains(".presentation.tile.FreezerTileService"))
        assertTrue(xml.contains("androidx.work.impl.foreground.SystemForegroundService"))
    }

    private fun receiverNames(xml: String): List<String> =
        Regex("""<receiver\s+[^>]*android:name="([^"]+)"""")
            .findAll(xml)
            .map { match -> match.groupValues[1] }
            .toList()

    private val manifestText: String by lazy {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        var directory = File(workingDirectory).absoluteFile
        val marker = "app/src/main/AndroidManifest.xml"
        repeat(MAX_PARENT_HOPS) {
            val manifest = File(directory, marker)
            if (manifest.isFile) return@lazy manifest.readText()
            directory = directory.parentFile
                ?: throw AssertionError("Could not find $marker from ${System.getProperty("user.dir")}")
        }
        throw AssertionError("Could not find $marker from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val MAX_PARENT_HOPS = 8
    }
}
