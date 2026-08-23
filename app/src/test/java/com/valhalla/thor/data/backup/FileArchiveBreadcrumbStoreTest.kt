// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.domain.repository.ArchiveBreadcrumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// `UnconfinedTestDispatcher`, for the two `observe` tests that need a collector attached before the
// call they are watching. House style is the class-level opt-in — see AppBackupViewModelTest.
@OptIn(ExperimentalCoroutinesApi::class)
class FileArchiveBreadcrumbStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    // `Unconfined` so a `withContext` inside the store does not park work on `runTest`'s scheduler:
    // these tests assert on the filesystem right after the call returns, not on virtual time.
    private fun store(dir: File = temp.newFolder("files")) =
        FileArchiveBreadcrumbStore(dir, Dispatchers.Unconfined)

    @Test
    fun `a written breadcrumb reads back`() = runTest {
        val store = store()

        assertTrue(store.write("com.example.app", "Example"))

        val crumb = store.read()!!
        assertEquals("com.example.app", crumb.packageName)
        assertEquals("Example", crumb.appLabel)
    }

    @Test
    fun `a breadcrumb is stamped with a real time`() = runTest {
        val store = store()

        store.write("com.example.app", "Example")

        assertTrue(store.read()!!.startedAt > 0L)
    }

    @Test
    fun `no breadcrumb reads as null, not as an empty one`() = runTest {
        assertNull(store().read())
    }

    @Test
    fun `clear removes it`() = runTest {
        val store = store()
        store.write("com.example.app", "Example")

        store.clear()

        assertNull(store.read())
    }

    @Test
    fun `clearing when there is nothing is not an error`() = runTest {
        // Called on every success path and from the launch sweep. Throwing here would turn a clean
        // restore into a crash on its last line.
        store().clear()
    }

    @Test
    fun `an unreadable breadcrumb reads as null and is removed`() = runTest {
        // A truncated write — the process died mid-`write` — must not make Thor report an interrupted
        // restore of a package it cannot name, forever.
        val dir = temp.newFolder("files")
        File(dir, FileArchiveBreadcrumbStore.FILE_NAME).writeText("{ truncated")

        assertNull(store(dir).read())
        assertFalse(File(dir, FileArchiveBreadcrumbStore.FILE_NAME).exists())
    }

    @Test
    fun `a second write replaces the first`() = runTest {
        val store = store()
        store.write("com.first.app", "First")

        store.write("com.second.app", "Second")

        assertEquals("com.second.app", store.read()!!.packageName)
    }

    @Test
    fun `a write that cannot land reports false rather than presenting as success`() = runTest {
        // The caller cannot make this write succeed, but it must be able to tell the user that an
        // interruption from here on will not be reported. A silent no-op lets the destructive phase
        // run with no notice behind it and nobody knows — the exact silence §8.5 exists to prevent.
        val notADirectory = temp.newFile("files-that-is-a-file")

        val store = FileArchiveBreadcrumbStore(notADirectory, Dispatchers.Unconfined)

        assertFalse(store.write("com.example.app", "Example"))
    }

    @Test
    fun `a breadcrumb that cannot be read is left in place, not deleted`() = runTest {
        // Only a file that *decodes* to nothing is debris. Deleting on a transient read failure erases
        // a valid interruption notice — the one record that this app's data may be half-replaced —
        // and no breadcrumb is indistinguishable from "nothing was interrupted".
        val dir = temp.newFolder("files")
        val unreadable = File(dir, FileArchiveBreadcrumbStore.FILE_NAME)
        assertTrue(unreadable.mkdirs())

        assertNull(store(dir).read())

        assertTrue(unreadable.exists())
    }

    @Test
    fun `a write that fails leaves the previous breadcrumb intact`() = runTest {
        // `writeText` truncates before it writes, so a kill inside the breadcrumb's own write left a
        // partial file that `read()` then deleted. The write therefore lands on a sibling and is
        // renamed into place, so the real file is only ever replaced whole; blocking the sibling is
        // how a JVM test reaches that path without killing a process.
        val dir = temp.newFolder("files")
        val store = store(dir)
        assertTrue(store.write("com.first.app", "First"))
        assertTrue(File(dir, FileArchiveBreadcrumbStore.TEMP_FILE_NAME).mkdirs())

        assertFalse(store.write("com.second.app", "Second"))

        assertEquals("com.first.app", store.read()!!.packageName)
    }

    @Test
    fun `a write into a directory that does not exist yet still reads back`() = runTest {
        // `filesDir` exists in production, but the store creates its directory rather than assuming
        // it: a `write` that silently does nothing is a breadcrumb that lies by omission, and the
        // whole point of §8.5 is that its absence means "nothing was interrupted".
        val dir = File(temp.newFolder("files"), "nested")

        val store = store(dir)
        store.write("com.example.app", "Example")

        assertEquals("com.example.app", store.read()!!.packageName)
    }

    @Test
    fun `observe emits what read would answer, without waiting for a change`() = runTest {
        // A one-shot read is the common use; `observe` has to be a drop-in for it or every caller
        // needs an initial value of its own.
        val store = store()
        store.write("com.example.app", "Example")

        assertEquals("com.example.app", store.observe().first()!!.packageName)
    }

    @Test
    fun `observe re-emits when the breadcrumb is cleared underneath a collector`() = runTest {
        // The I2 case. The restore sheet is hosted above the Settings section, so its banner and the
        // sheet are composed at the same time: the user acknowledges the notice in the sheet, and a
        // Settings banner that read once would go on naming an app whose breadcrumb no longer exists.
        val store = store()
        store.write("com.example.app", "Example")
        val seen = mutableListOf<ArchiveBreadcrumb?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.observe().toList(seen)
        }

        store.clear()

        assertEquals("com.example.app", seen.first()!!.packageName)
        assertNull(seen.last())
    }

    @Test
    fun `observe re-emits when a restore writes a new breadcrumb`() = runTest {
        // The other direction: a breadcrumb written underneath a live collector reaches it, rather
        // than waiting for the next entry into composition. This is the store's contract, not a
        // statement about the banner — a breadcrumb is written at the *start* of the destructive
        // phase, so the Settings notice must NOT go up here; `ObserveInterruptedRestoreUseCase` holds
        // it back until that app has no live restore. What this emission is for is the moment the
        // restore ends badly, when the same crumb is the notice.
        val store = store()
        val seen = mutableListOf<ArchiveBreadcrumb?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.observe().toList(seen)
        }

        store.write("com.example.app", "Example")

        assertNull(seen.first())
        assertEquals("com.example.app", seen.last()!!.packageName)
    }
}
