// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import java.io.Closeable
import java.io.InputStream

/**
 * A `.thorbak` container open for reading, addressed by **exact entry name**.
 *
 * Random access, not sequential: the header is the container's *last* entry (Task 10 writes it last
 * so its byte counts can be final), and the members are read after it. A `ZipInputStream` view would
 * need a second full pass over a file that can be tens of gigabytes.
 *
 * No `android.net.Uri` here, deliberately — the same rule as [AppArchiveStore]. A port that returns
 * or accepts a `Uri` cannot be faked in a JVM test, because `android.net.Uri` throws "not mocked",
 * and that would take the whole restore happy path off the test classpath.
 */
interface ArchiveSource : Closeable {

    /** What to call this file in a message to the user. Never a path. */
    val displayName: String

    /** Every entry in the container, in stored order. */
    fun entryNames(): List<String>

    /**
     * Open one entry by its exact name, or null if the container has no such entry.
     *
     * Null is an ordinary answer, not an error: a header can legitimately hold three of the four
     * classes.
     */
    fun openEntry(name: String): InputStream?
}

/** Resolves whatever the platform handed Thor — a `content://` URI, usually — into an [ArchiveSource]. */
interface ArchiveSourceFactory {

    /**
     * @param uriString the URI as a string. String rather than `Uri` for the reason in
     *   [ArchiveSource]'s KDoc; the implementation parses it.
     * @return null when the URI cannot be opened or does not contain a zip. The caller reports that
     *   as "this file could not be opened", which is all a user can act on.
     */
    suspend fun open(uriString: String): ArchiveSource?
}
