// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.service

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSyncServiceManifestTest {

    @Test
    fun `data service is non-exported and declares the dataSync foreground type`() {
        val service = components("service").single { it.name.endsWith(".DataSyncService") }

        assertEquals("false", service.exported)
        assertEquals("dataSync", service.foregroundServiceType)
    }

    @Test
    fun `data cancellation receiver is non-exported`() {
        val receiver = components("receiver").single { it.name.endsWith(".DataTaskCancelReceiver") }

        assertEquals("false", receiver.exported)
    }

    @Test
    fun `released WorkManager foreground service remains declared for compatibility drainage`() {
        val service = components("service").single {
            it.name == "androidx.work.impl.foreground.SystemForegroundService"
        }

        assertEquals("dataSync", service.foregroundServiceType)
    }

    private fun components(tag: String): List<Component> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        return (0 until document.getElementsByTagName(tag).length).map { index ->
            val element = document.getElementsByTagName(tag).item(index)
            Component(
                name = requireNotNull(element.attributes.getNamedItem("android:name")).nodeValue,
                exported = element.attributes.getNamedItem("android:exported")?.nodeValue,
                foregroundServiceType = element.attributes
                    .getNamedItem("android:foregroundServiceType")?.nodeValue,
            )
        }
    }

    private val manifest: File by lazy {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            File(directory, "app/src/main/AndroidManifest.xml").takeIf(File::isFile)?.let {
                return@lazy it
            }
            directory = directory.parentFile ?: error("Could not find project root")
        }
        error("Could not find app/src/main/AndroidManifest.xml")
    }

    private data class Component(
        val name: String,
        val exported: String?,
        val foregroundServiceType: String?,
    )
}
