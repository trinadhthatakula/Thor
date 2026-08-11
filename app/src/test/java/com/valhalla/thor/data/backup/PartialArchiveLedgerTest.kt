// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PartialArchiveLedgerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun ledger(dir: File = temp.newFolder("files")) = PartialArchiveLedger(dir)

    @Test
    fun `an added name reads back`() = runTest {
        val ledger = ledger()

        ledger.add("Thor-com.example.app-100.thorbak.part")

        assertEquals(setOf("Thor-com.example.app-100.thorbak.part"), ledger.names())
    }

    @Test
    fun `two backups in flight are both recorded`() = runTest {
        // Jobs serialise on one chain, so this is not the common case — but a job cancelled after
        // `add` and before `forget` leaves its name behind, and the next backup must not erase it.
        val ledger = ledger()
        ledger.add("a.thorbak.part")

        ledger.add("b.thorbak.part")

        assertEquals(setOf("a.thorbak.part", "b.thorbak.part"), ledger.names())
    }

    @Test
    fun `forget removes one name and leaves the others`() = runTest {
        val ledger = ledger()
        ledger.add("a.thorbak.part")
        ledger.add("b.thorbak.part")

        ledger.forget("a.thorbak.part")

        assertEquals(setOf("b.thorbak.part"), ledger.names())
    }

    @Test
    fun `an empty ledger reads as an empty set`() = runTest {
        assertEquals(emptySet<String>(), ledger().names())
    }

    @Test
    fun `an unreadable ledger reads as empty and is removed`() = runTest {
        // Otherwise a truncated write makes every launch attempt to delete names it cannot parse.
        val dir = temp.newFolder("files")
        File(dir, PartialArchiveLedger.FILE_NAME).writeText("[ truncated")

        assertEquals(emptySet<String>(), ledger(dir).names())
        assertEquals(false, File(dir, PartialArchiveLedger.FILE_NAME).exists())
    }

    @Test
    fun `forgetting a name that was never added is not an error`() = runTest {
        ledger().forget("never-there.part")
    }

    /**
     * Not in the brief. `forget` writing an empty set deletes the file, and the next `add` has to
     * recreate it — the shape a "delete the file instead of writing `[]`" optimisation quietly
     * breaks.
     */
    @Test
    fun `a name added after the ledger emptied itself still reads back`() = runTest {
        val ledger = ledger()
        ledger.add("a.thorbak.part")
        ledger.forget("a.thorbak.part")

        ledger.add("b.thorbak.part")

        assertEquals(setOf("b.thorbak.part"), ledger.names())
    }

    /**
     * Not in the brief. The ledger is `filesDir`-backed precisely so it survives a process death, so
     * the property that matters is that a *second instance* over the same directory sees the names —
     * an in-memory-only implementation passes every test above and none of this one.
     */
    @Test
    fun `a fresh instance over the same directory reads what the last one wrote`() = runTest {
        val dir = temp.newFolder("files")
        PartialArchiveLedger(dir).add("a.thorbak.part")

        assertEquals(setOf("a.thorbak.part"), PartialArchiveLedger(dir).names())
    }
}
