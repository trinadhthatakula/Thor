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

/**
 * What opening a URI produced.
 *
 * Two failures, not one. This used to be a nullable [ArchiveSource], and the port's own KDoc argued
 * that "this file could not be opened" was all a user could act on — which is wrong in the case that
 * actually happens most: a file picker shows every file on the device, so the ordinary mistake is
 * picking something that is not a backup at all. That user has to be told to pick a different file.
 * The user whose backup Thor genuinely could not read has to be told something else entirely, because
 * picking the same file again is exactly what they should do once storage is reachable.
 *
 * The two are distinguishable at no cost: a `ZipException` from a readable stream *is* the first case,
 * and the reader already had to separate them internally to decide whether the copy fallback was
 * worth attempting.
 */
sealed interface ArchiveOpenOutcome {

    /** The container is open. The caller owns it and must `close()` it. */
    data class Opened(val source: ArchiveSource) : ArchiveOpenOutcome

    /**
     * The bytes were readable and are not a zip container, so they are not a `.thorbak` either.
     *
     * A statement about the *file*, and the only one of the three that survives a retry: the same URI
     * will answer this every time.
     */
    data object NotAnArchive : ArchiveOpenOutcome

    /**
     * Nothing could be read from the URI — a revoked grant, a provider that went away, a volume that
     * unmounted, no room in cache for the fallback copy.
     *
     * A statement about the *access*, not about the file, which may be a perfectly good backup.
     */
    data object Unreadable : ArchiveOpenOutcome
}

/** Resolves whatever the platform handed Thor — a `content://` URI, usually — into an [ArchiveSource]. */
interface ArchiveSourceFactory {

    /**
     * @param uriString the URI as a string. String rather than `Uri` for the reason in
     *   [ArchiveSource]'s KDoc; the implementation parses it.
     * @return which of [ArchiveOpenOutcome]'s three states this URI is in. Never null, and never a
     *   throw for an ordinary bad file: the two failures are answers, and each has its own sentence.
     */
    suspend fun open(uriString: String): ArchiveOpenOutcome
}
