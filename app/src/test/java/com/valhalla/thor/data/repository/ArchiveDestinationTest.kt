// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.THORBAK_EXTENSION
import com.valhalla.thor.domain.model.thorbakFileName
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveDestinationTest {

    @Test
    fun `a partial name cannot be mistaken for a finished archive`() {
        val partial = partialName(thorbakFileName("com.example.game", 42))

        // The restore picker filters on the extension, and the launch-time sweep deletes what it
        // finds by this suffix. A partial that still ended in `.thorbak` would be offered as
        // restorable and, worse, a finished archive would be swept.
        assertFalse(partial.endsWith(".$THORBAK_EXTENSION"))
        assertTrue(partial.endsWith(PARTIAL_SUFFIX))
        assertTrue(partial.startsWith("com.example.game-42.$THORBAK_EXTENSION"))
    }

    @Test
    fun `publishing strips exactly the partial suffix`() {
        val finished = thorbakFileName("com.example.game", 42)

        assertEquals(finished, publishedName(partialName(finished)))
    }

    @Test
    fun `a name that is not partial publishes unchanged`() {
        // Defensive: a backend that already writes under the final name (MediaStore's IS_PENDING)
        // must not have its extension chewed off.
        assertEquals("a.thorbak", publishedName("a.thorbak"))
    }

    @Test
    fun `the partial suffix is not a valid archive extension`() {
        // One literal, two consumers — the sweep and the picker. Pinned so a later edit to either
        // cannot quietly make them disagree.
        assertFalse(PARTIAL_SUFFIX.endsWith(THORBAK_EXTENSION))
    }

    // ── isSweepableOrphanName ────────────────────────────────────────────────────────────────────
    //
    // The guard between the launch-time sweep and a folder the user chose. Every name it says yes to
    // is handed to `File(dir, name)` or matched against a DocumentsProvider's display names and then
    // deleted, so each clause below is load-bearing on its own.

    @Test
    fun `the name a backup writes under is sweepable`() {
        assertTrue(isSweepableOrphanName(partialName(thorbakFileName("com.example.game", 42))))
    }

    @Test
    fun `a finished archive is never sweepable`() {
        // The clause that stands between the sweep and a user's completed backup, and the one a later
        // edit is most likely to loosen. `.thorbak` is what the picker offers and what the user keeps.
        assertFalse(isSweepableOrphanName(thorbakFileName("com.example.game", 42)))
        // Belt and braces: a name that carries the partial suffix *and* ends in the real extension —
        // which is what a "rename by appending" bug would produce — is still refused.
        assertFalse(isSweepableOrphanName("com.example.game-42.thorbak.part.thorbak"))
    }

    @Test
    fun `a name with no partial suffix is never sweepable`() {
        assertFalse(isSweepableOrphanName("holiday-photos.zip"))
    }

    @Test
    fun `a name carrying a path component is never sweepable`() {
        // `File(dir, name)` and `DocumentsContract` both accept a separator, so a ledger entry that
        // held one would let the sweep delete outside the folder it was pointed at.
        assertFalse(isSweepableOrphanName("../a.thorbak.part"))
        assertFalse(isSweepableOrphanName("sub/a.thorbak.part"))
        assertFalse(isSweepableOrphanName("sub\\a.thorbak.part"))
    }

    @Test
    fun `the directory entries themselves are never sweepable`() {
        // "." and ".." carry no suffix either, so this is belt and braces — but they are the two names
        // whose deletion takes the user's whole folder with them, so they are pinned explicitly.
        assertFalse(isSweepableOrphanName("."))
        assertFalse(isSweepableOrphanName(".."))
    }

    @Test
    fun `a blank name is never sweepable`() {
        // A truncated ledger write can decode to a blank entry; `File(dir, "")` is the directory.
        assertFalse(isSweepableOrphanName(""))
        assertFalse(isSweepableOrphanName("   "))
    }

    @Test
    fun `a provider that de-duplicated the partial name is still sweepable`() {
        // Why the suffix test is `contains` and not `endsWith`. SAF providers may resolve a collision
        // by appending a counter — ask for `x.thorbak.part`, get `x.thorbak.part (1)` — and the ledger
        // records the name the provider *assigned*. With `endsWith`, that real orphan would survive
        // every sweep Thor ever runs, because nothing else knows its name.
        assertTrue(isSweepableOrphanName("Thor-com.example.game-42.thorbak.part (1)"))
    }

    // ── nonCollidingArchiveName ──────────────────────────────────────────────────────────────────
    //
    // Archive names are deterministic, so a second backup of one app at one version always collides.
    // MediaStore and most SAF providers number the newcomer; `File.renameTo` on legacy Downloads
    // silently deletes the older backup. These pin the rule that makes the third one agree.

    @Test
    fun `a free name is used unchanged`() {
        assertEquals(
            "com.example.game-42.thorbak",
            nonCollidingArchiveName("com.example.game-42.thorbak") { false },
        )
    }

    @Test
    fun `an existing backup is never written over`() {
        val existing = setOf("com.example.game-42.thorbak")

        assertEquals(
            "com.example.game-42 (1).thorbak",
            nonCollidingArchiveName("com.example.game-42.thorbak") { it in existing },
        )
    }

    @Test
    fun `the counter goes before the extension`() {
        // Load-bearing, not cosmetic. `x.thorbak (1)` does not end in `.thorbak`, so the restore
        // picker — which filters on exactly that — would hide the archive Thor had just written, and
        // isSweepableOrphanName's "not a finished archive" clause would stop protecting it.
        val taken = setOf("com.example.game-42.thorbak")
        val chosen = nonCollidingArchiveName("com.example.game-42.thorbak") { it in taken }!!

        assertTrue(chosen.endsWith(".$THORBAK_EXTENSION"))
        assertFalse(isSweepableOrphanName(chosen))
        assertTrue(isSweepableOrphanName(partialName(chosen)))
    }

    @Test
    fun `a partial in the folder is a collision too`() {
        // A `.part` is a backup being written right now. Reusing its name would hand the second job
        // the first one's file and truncate it — the exact data loss this function exists to stop,
        // moved from the finished archive onto the one still being written.
        val taken = setOf(partialName("com.example.game-42.thorbak"))

        assertEquals(
            "com.example.game-42 (1).thorbak",
            nonCollidingArchiveName("com.example.game-42.thorbak") { it in taken },
        )
    }

    @Test
    fun `numbering keeps counting past the first taken variant`() {
        val taken = setOf(
            "com.example.game-42.thorbak",
            "com.example.game-42 (1).thorbak",
            "com.example.game-42 (2).thorbak",
        )

        assertEquals(
            "com.example.game-42 (3).thorbak",
            nonCollidingArchiveName("com.example.game-42.thorbak") { it in taken },
        )
    }

    @Test
    fun `a destination that claims every name yields no name at all`() {
        // Null means "do not write", never "write over one of them" — and it terminates, which a
        // `while (true)` against a provider answering yes to everything would not.
        assertNull(nonCollidingArchiveName("com.example.game-42.thorbak") { true })
    }

    @Test
    fun `a name without the archive extension keeps the shape it arrived with`() {
        // Defensive: `removeSuffix` on a name that never carried the suffix returns it unchanged, so
        // a naive implementation appends a `.thorbak` the caller never asked for.
        val taken = setOf("notes")

        assertEquals("notes (1)", nonCollidingArchiveName("notes") { it in taken })
    }

    // ── BaseDestination ──────────────────────────────────────────────────────────────────────────

    /**
     * `onSettled` is where the ledger entry is forgotten, and it has to run on **both** settle paths.
     * A published archive's `.part` name no longer names anything: left in the ledger, every launch
     * asks the store to delete a name no file carries, and since the delete then fails the name is
     * never forgotten — a permanent tail the sweep retries forever.
     */
    @Test
    fun `publishing settles the ledger entry`() = runTest {
        var settled = 0
        val destination = object : BaseDestination(ByteArrayOutputStream(), onSettled = { settled++ }) {
            override fun onPublish(): Boolean = true
            override fun onDiscard() = error("a published destination must never discard")
        }

        assertTrue(destination.publish())

        assertEquals(1, settled)
        // The calling shape is `try { … publish() } finally { discard() }`, so the trailing discard is
        // the normal path: it must neither discard the file nor settle a second time.
        destination.discard()
        assertEquals(1, settled)
    }

    @Test
    fun `discarding settles the ledger entry`() = runTest {
        var settled = 0
        var discarded = false
        val destination = object : BaseDestination(ByteArrayOutputStream(), onSettled = { settled++ }) {
            override fun onPublish(): Boolean = error("this destination was never published")
            override fun onDiscard() {
                discarded = true
            }
        }

        destination.discard()

        assertTrue(discarded)
        assertEquals(1, settled)
    }

    @Test
    fun `a publish that throws still settles the ledger entry`() = runTest {
        // The `finally` in publish(). A rename can throw — a revoked SAF grant, a volume pulled — and
        // at that point the partial is settled either way: the failure is the caller's to report, not
        // a name the sweep chases forever.
        var settled = 0
        val destination = object : BaseDestination(ByteArrayOutputStream(), onSettled = { settled++ }) {
            override fun onPublish(): Boolean = throw IOException("the provider went away")
            override fun onDiscard() = Unit
        }

        runCatching { destination.publish() }

        assertEquals(1, settled)
    }

    /**
     * The counterpart to `publishing settles the ledger entry`, and the reason "settle once" is not
     * "publish or discard, never both".
     *
     * `renameTo` returns false and `renameDocument` returns null on a failure neither of them throws
     * for. The partial is then still on disk, under the partial name, and the very next line forgets
     * the ledger entry that is the only record of that name — so without the discard the file becomes
     * unreachable to every future sweep. It is a whole app data tree; on a big game that is gigabytes.
     */
    @Test
    fun `a publish that fails deletes the partial before the ledger forgets it`() = runTest {
        var settled = 0
        var discarded = 0
        val destination = object : BaseDestination(ByteArrayOutputStream(), onSettled = { settled++ }) {
            override fun onPublish(): Boolean = false
            override fun onDiscard() {
                discarded++
            }
        }

        assertFalse(destination.publish())

        assertEquals("the partial a failed publish left was not deleted", 1, discarded)
        assertEquals(1, settled)
        // The caller's shape is `try { … publish() } finally { discard() }`, and the failed publish
        // already settled. The trailing discard must not delete a second time or settle again.
        destination.discard()
        assertEquals(1, discarded)
        assertEquals(1, settled)
    }

    @Test
    fun `a publish that throws deletes the partial too`() = runTest {
        // A throw published no less than a `false` did, and leaves the same file behind.
        var discarded = 0
        val destination = object : BaseDestination(ByteArrayOutputStream(), onSettled = {}) {
            override fun onPublish(): Boolean = throw IOException("the provider went away")
            override fun onDiscard() {
                discarded++
            }
        }

        runCatching { destination.publish() }

        assertEquals(1, discarded)
    }

    @Test
    fun `a delete that fails on top of a failed publish does not replace the failure`() = runTest {
        // Cleanup runs where something has already gone wrong. A provider that refuses the delete as
        // well must still let publish() return its own answer — `false` — rather than throwing an
        // IOException about the cleanup out of a function the caller reads as "did it save?".
        var settled = 0
        val destination = object : BaseDestination(ByteArrayOutputStream(), onSettled = { settled++ }) {
            override fun onPublish(): Boolean = false
            override fun onDiscard() = throw IOException("the provider refused the delete too")
        }

        assertFalse(destination.publish())
        assertEquals(1, settled)
    }

    @Test
    fun `a successful publish never discards`() = runTest {
        // The other direction of the same rule, pinned separately from `publishing settles the ledger
        // entry`: the discard added for the failure path must not fire on the path that succeeded, or
        // every backup would delete the archive it had just written.
        val destination = object : BaseDestination(ByteArrayOutputStream(), onSettled = {}) {
            override fun onPublish(): Boolean = true
            override fun onDiscard() = error("a published destination must never discard")
        }

        assertTrue(destination.publish())
    }
}
