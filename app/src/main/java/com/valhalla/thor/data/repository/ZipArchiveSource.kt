// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.repository.ArchiveSource
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * [ArchiveSource] over a real file.
 *
 * Split from [UriArchiveSourceFactory] so that everything except the `content://` resolution is
 * JVM-testable: this class takes a `java.io.File`, and `ZipFile` is a JDK type.
 *
 * @param onClose runs once, when this source is closed. [UriArchiveSourceFactory] uses it to close
 *   the `ParcelFileDescriptor` whose `/proc/self/fd` entry [file] names — closing that fd twice would
 *   close a number the process may have already reused.
 */
class ZipArchiveSource(
    file: File,
    override val displayName: String,
    private val onClose: () -> Unit = {},
) : ArchiveSource {

    // Constructed eagerly, so a file that is not a zip fails here rather than on the first read —
    // by which point the UI has already told the user the archive is being opened.
    private val zip = ZipFile(file)
    private val closed = AtomicBoolean(false)

    override fun entryNames(): List<String> = zip.entries().toList().map { it.name }

    override fun openEntry(name: String): InputStream? =
        zip.getEntry(name)?.let(zip::getInputStream)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { zip.close() }
        onClose()
    }
}
