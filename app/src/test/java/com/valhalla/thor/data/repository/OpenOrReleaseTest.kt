// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The cancellation contract of [openOrRelease], which is `UriArchiveSourceFactory.open`'s cleanup
 * guarantee with the Android half lifted out.
 *
 * `open` itself cannot be unit-tested: `Uri.parse`, `ContentResolver.openFileDescriptor` and
 * `ParcelFileDescriptor.getFd` all throw "not mocked" on the JVM classpath, and this project has no
 * Robolectric and no mocking library on purpose. So the property is pinned where it lives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenOrReleaseTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Stands in for `thorbak_read_copy.zip` — the cache copy the fallback path leaves behind. */
    private fun cacheCopy(): File {
        val file = temp.newFile("thorbak_read_copy.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("thorbak.json"))
            out.write("{}".toByteArray())
            out.closeEntry()
        }
        return file
    }

    @Test
    fun `a source built while the coroutine is cancelled is closed and its cache copy deleted`() =
        runTest {
            // The real scenario: the user backs out of the restore screen while `open()` is running
            // on the IO dispatcher. `build` has no suspension points, so the cancellation lands
            // *inside* it and stays invisible unless something checks — which is the entire reason
            // the guard exists. Cancelling from within `build` reproduces that timing exactly.
            val copy = cacheCopy()
            var descriptorClosed = false
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler))

            val call = scope.async {
                openOrRelease(
                    build = {
                        scope.cancel()
                        ZipArchiveSource(copy, "cancelled.thorbak", onClose = { copy.delete() })
                    },
                    release = { source ->
                        source?.close()
                        descriptorClosed = true
                    },
                )
            }
            advanceUntilIdle()

            assertTrue("the cancellation must reach the caller", call.isCancelled)
            assertTrue("the ParcelFileDescriptor must be closed, not leaked", descriptorClosed)
            assertFalse(
                "the cache copy must not be orphaned: ${copy.absolutePath}",
                copy.exists(),
            )
        }

    @Test
    fun `a source built without cancellation is handed back and never released`() = runTest {
        // The other half of the contract: there must be no path where a successful open deletes the
        // file it just handed to the caller. A `release` that fired here would close the zip the
        // restore screen is about to read from.
        val copy = cacheCopy()
        var released = false

        val source = openOrRelease(
            build = { ZipArchiveSource(copy, "ok.thorbak", onClose = { copy.delete() }) },
            release = { released = true },
        )

        assertNotNull("a successful build must be handed back", source)
        assertFalse("a successful open must not release what it hands back", released)
        assertTrue("the cache copy must survive for the caller to read", copy.exists())
        source!!.close()
    }

    @Test
    fun `a build that produces nothing still releases, so the descriptor is not leaked`() = runTest {
        // The fd path can fail with no fallback (a ZipException gives up immediately), leaving
        // `build` returning null. Cancellation on that path must still run `release`, because the
        // descriptor is the caller's to close and nothing else holds it.
        var releases = 0
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))

        val call = scope.async {
            openOrRelease(
                build = {
                    scope.cancel()
                    null
                },
                release = { releases++ },
            )
        }
        advanceUntilIdle()

        assertTrue("the cancellation must reach the caller", call.isCancelled)
        assertEquals("release must run once even when build produced nothing", 1, releases)
    }
}
