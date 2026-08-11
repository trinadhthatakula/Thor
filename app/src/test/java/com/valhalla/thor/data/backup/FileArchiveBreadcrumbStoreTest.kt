// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileArchiveBreadcrumbStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(dir: File = temp.newFolder("files")) = FileArchiveBreadcrumbStore(dir)

    @Test
    fun `a written breadcrumb reads back`() = runTest {
        val store = store()

        store.write("com.example.app", "Example")

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
    fun `a write into a directory that does not exist yet still reads back`() = runTest {
        // `filesDir` exists in production, but the store creates its directory rather than assuming
        // it: a `write` that silently does nothing is a breadcrumb that lies by omission, and the
        // whole point of §8.5 is that its absence means "nothing was interrupted".
        val dir = File(temp.newFolder("files"), "nested")

        val store = store(dir)
        store.write("com.example.app", "Example")

        assertEquals("com.example.app", store.read()!!.packageName)
    }
}
