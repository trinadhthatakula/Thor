// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.kernel

import java.io.File
import java.util.zip.ZipFile

/** Filename/MIME are hints only. A malformed kernel candidate must not fall back to APK install. */
internal fun inspectPortableKernel(file: File): AnyKernel3Inspection? {
    val candidate = runCatching {
        ZipFile(file).use { zip ->
            zip.getEntry("anykernel.sh") != null || zip.getEntry("tools/ak3-core.sh") != null
        }
    }.getOrDefault(false)
    return if (candidate) AnyKernel3Archive.inspect(file) else null
}
