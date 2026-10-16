// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.kernel

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PortableKernelDetectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val kernel = mapOf(
        "anykernel.sh" to "IS_SLOT_DEVICE=1; BLOCK=auto;",
        "tools/ak3-core.sh" to "${'$'}IS_SLOT_DEVICE ${'$'}SLOT_SELECT inactive)",
        "META-INF/com/google/android/update-binary" to "#!/bin/sh\nexit 0\n",
    )

    @Test fun `contents recognize kernels even when the extension claims APK`() {
        val file = archive(kernel, "renamed.apk")
        assertEquals(AnyKernel3Archive.inspect(file), inspectPortableKernel(file))
    }

    @Test fun `ordinary APKs and app bundles keep the application route`() {
        for (entries in listOf(
            mapOf("AndroidManifest.xml" to "manifest", "classes.dex" to "dex"),
            mapOf("base.apk" to "apk", "split_config.arm64.apk" to "split"),
            mapOf("manifest.json" to "{}", "app.apk" to "apk"),
        )) assertNull(inspectPortableKernel(archive(entries)))
    }

    @Test fun `nested assets do not claim kernel installation`() {
        assertNull(inspectPortableKernel(archive(mapOf("assets/anykernel.sh" to "example"))))
    }

    @Test fun `incomplete kernel candidates cannot fall back to APK installation`() {
        assertThrows(IOException::class.java) {
            inspectPortableKernel(archive(mapOf("anykernel.sh" to "incomplete", "base.apk" to "apk")))
        }
    }

    @Test fun `root modules and unsafe paths are rejected by full kernel validation`() {
        for (extra in listOf("module.prop", "../escape")) {
            assertThrows(IOException::class.java) { inspectPortableKernel(archive(kernel + (extra to "no"))) }
        }
    }

    @Test fun `non archives are left to the existing package parser`() {
        assertNull(inspectPortableKernel(temporary.newFile().apply { writeText("not a zip") }))
    }

    private fun archive(entries: Map<String, String>, name: String? = null): File {
        val file = if (name == null) temporary.newFile() else temporary.newFile(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry()
            }
        }
        return file
    }
}
