// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `a ledger that is not a ledger reads as empty and is removed`() = runTest {
        // Otherwise a truncated write makes every launch attempt to delete names it cannot parse.
        val dir = temp.newFolder("files")
        File(dir, PartialArchiveLedger.FILE_NAME).writeText("[ truncated")

        assertEquals(emptySet<String>(), ledger(dir).names())
        assertEquals(false, File(dir, PartialArchiveLedger.FILE_NAME).exists())
    }

    /**
     * I3. A read that *fails* is not a ledger that is corrupt, and the two used to share one
     * `runCatching`. The directory shape is the JVM-portable way to get an [java.io.IOException] out
     * of `readText` while leaving something behind to assert on: `exists()` is true and the open
     * throws. An empty directory is also deletable, so the old `file.delete()` really did remove it.
     */
    @Test
    fun `a ledger Thor could not read is left alone rather than deleted`() = runTest {
        val dir = temp.newFolder("files")
        val unreadable = File(dir, PartialArchiveLedger.FILE_NAME)
        assertTrue(unreadable.mkdirs())

        assertEquals(emptySet<String>(), ledger(dir).names())

        assertTrue("an unreadable ledger must survive to be read again", unreadable.exists())
    }

    /**
     * The other half of I3, and the one with teeth: reading an unreadable ledger as "no names" and
     * then writing on top of it forgets every `.part` container in flight. `add` refuses instead, and
     * says so in its return value.
     */
    @Test
    fun `a name is refused rather than written over a ledger that could not be read`() = runTest {
        val dir = temp.newFolder("files")
        assertTrue(File(dir, PartialArchiveLedger.FILE_NAME).mkdirs())

        assertFalse(ledger(dir).add("a.thorbak.part"))
    }

    /**
     * I2. Pins the *route* rather than the outcome: with the temp name unusable the write must fail
     * with the previous ledger untouched. A `writeText` straight into the destination ignores the
     * temp entirely and rewrites the file, which is the shape a kill can truncate.
     *
     * The temp is a **non-empty** directory on purpose — an empty one is deletable, and `read()`
     * sweeps a stale temp before every write.
     */
    @Test
    fun `a write that cannot use its temp leaves the previous ledger whole`() = runTest {
        val dir = temp.newFolder("files")
        val ledger = ledger(dir)
        assertTrue(ledger.add("first.thorbak.part"))
        val blocked = File(dir, PartialArchiveLedger.TEMP_FILE_NAME)
        assertTrue(blocked.mkdirs())
        File(blocked, "not-empty").writeText("x")

        assertFalse(ledger.add("second.thorbak.part"))

        assertEquals(setOf("first.thorbak.part"), ledger.names())
    }

    /**
     * M3. A process killed between the write and the rename leaves the temp behind and nothing
     * deleted it. Swept under the same mutex every other access holds, so it cannot race a live write.
     */
    @Test
    fun `a temp file left by a killed write is swept`() = runTest {
        val dir = temp.newFolder("files")
        val stale = File(dir, PartialArchiveLedger.TEMP_FILE_NAME).apply { writeText("[\"half\"") }

        ledger(dir).names()

        assertFalse(stale.exists())
    }

    /** The successful route leaves no temp behind either — the rename consumes it. */
    @Test
    fun `a completed write leaves no temp file behind`() = runTest {
        val dir = temp.newFolder("files")

        ledger(dir).add("a.thorbak.part")

        assertFalse(File(dir, PartialArchiveLedger.TEMP_FILE_NAME).exists())
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
