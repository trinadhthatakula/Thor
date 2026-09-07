// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.service

import org.junit.Assert.assertEquals
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
    fun `manifest activates both independent foreground queue component pairs`() {
        val xml = manifestText
        val receivers = receiverNames(xml)

        assertTrue(xml.contains("DataSyncService"))
        assertTrue(receivers.any { it.contains("DataTaskCancelReceiver") })
        assertTrue(xml.contains("PrivilegeSweepService"))
        assertTrue(receivers.any { it.contains("PrivilegeSweepCancelReceiver") })
    }

    @Test
    fun `task queue launch trampoline is private transient and task neutral`() {
        val declaration = Regex(
            """<activity\b(?=[^>]*android:name="\.presentation\.launcher\.TaskQueueLaunchActivity")[^>]*/>""",
        ).find(manifestText)?.value
            ?: throw AssertionError("TaskQueueLaunchActivity declaration missing")

        assertTrue(declaration.contains("android:exported=\"false\""))
        assertTrue(declaration.contains("android:excludeFromRecents=\"true\""))
        assertTrue(declaration.contains("android:noHistory=\"true\""))
        assertTrue(declaration.contains("android:taskAffinity=\"\""))
        assertTrue(declaration.contains("@android:style/Theme.Translucent.NoTitleBar"))
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
