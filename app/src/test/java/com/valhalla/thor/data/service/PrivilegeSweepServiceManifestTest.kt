// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.service

import android.content.pm.ServiceInfo
import com.valhalla.thor.data.freezer.PrivilegeSweepService
import com.valhalla.thor.data.freezer.privilegeForegroundServiceType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeSweepServiceManifestTest {

    @Test
    fun `privilege service and cancellation receiver match exact special-use contract`() {
        val service = Regex(
            """<service\s+android:name="\.data\.freezer\.PrivilegeSweepService"([\s\S]*?)</service>"""
        ).find(manifestText)?.value ?: error("PrivilegeSweepService declaration missing")
        val receiver = Regex(
            """<receiver\s+android:name="\.data\.freezer\.PrivilegeSweepCancelReceiver"([\s\S]*?)/>"""
        ).find(manifestText)?.value ?: error("PrivilegeSweepCancelReceiver declaration missing")

        assertTrue(service.contains("android:exported=\"false\""))
        assertTrue(service.contains("android:foregroundServiceType=\"specialUse\""))
        assertTrue(service.contains("android:name=\"android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE\""))
        assertTrue(service.contains("android:value=\"$EXACT_SUBTYPE\""))
        assertTrue(receiver.contains("android:exported=\"false\""))
    }

    @Test
    fun `manifest declares special-use permission and no data-sync type on privilege service`() {
        assertTrue(
            manifestText.contains(
                "android:name=\"android.permission.FOREGROUND_SERVICE_SPECIAL_USE\""
            )
        )
        val service = manifestText.substringAfter(
            "android:name=\".data.freezer.PrivilegeSweepService\""
        ).substringBefore("</service>")
        assertFalse(service.contains("dataSync"))
    }

    @Test
    fun `service remains public no-argument constructible and has no timeout callback`() {
        val constructor = PrivilegeSweepService::class.java.getConstructor()

        assertTrue(java.lang.reflect.Modifier.isPublic(constructor.modifiers))
        assertFalse(
            PrivilegeSweepService::class.java.declaredMethods.any { it.name == "onTimeout" }
        )
    }

    @Test
    @Suppress("InlinedApi")
    fun `special-use foreground type is requested only where platform supports it`() {
        assertEquals(0, privilegeForegroundServiceType(33))
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            privilegeForegroundServiceType(34),
        )
    }

    private val manifestText: String by lazy {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val manifest = File(directory, "app/src/main/AndroidManifest.xml")
            if (manifest.isFile) return@lazy manifest.readText()
            directory = directory.parentFile ?: error("application manifest not found")
        }
        error("application manifest not found")
    }

    private companion object {
        const val EXACT_SUBTYPE =
            "Executes explicit user-requested queued app-management operations such as freeze, " +
                    "unfreeze, per-app cache clearing, and verified reinstall through a " +
                    "user-configured privileged gateway, with visible progress and cancellation."
    }
}
