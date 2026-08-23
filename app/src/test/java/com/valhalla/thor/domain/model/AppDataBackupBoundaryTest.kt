// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boundaries the backup half of the shell surface is pinned on **both** sides.
 *
 * `AppDataCommandsTest` covers what each builder emits; this covers where each rule stops. Two of the
 * defects it exists for were invisible to a one-sided test:
 *
 * - the backup filter and the restore refusal rule disagreed about the platform's `lib` symlink, so
 *   Thor wrote archives its own restore rejects — a test that never ran a member list through *both*
 *   sides could not see it;
 * - the root-depth guard accepted a 2-segment path, and mutating it to the correct 4 killed no test
 *   at all, because nothing asserted a real root was *accepted*.
 */
class AppDataBackupBoundaryTest {

    private val pkg = "com.example.game"
    private val ceRoot = "/data/user/0/$pkg"
    private val refusal = Regex(ARCHIVE_MEMBER_REFUSAL_PATTERN)

    /** One `tar -tv` line, in the shape the restore side parses. */
    private fun tvLine(name: String, link: String? = null): String {
        val mode = if (link == null) "drwxrwx--x" else "lrwxrwxrwx"
        val arrow = if (link == null) "" else " -> $link"
        return "$mode root/root 0 2026-08-10 12:00 $name$arrow"
    }

    // ---------------------------------------------------------------------------------------------
    // The library symlink: one class, checked through both halves.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a real CE listing produces no member the restore rule refuses`() {
        // The listing a stock app's CE root gives, `lib` and all. `installd` creates that link on
        // every app, pointing at an ABSOLUTE path under /data/app, and `tarCreateCommand` passes no
        // -h — so tar stored it as a symlink member carrying that target, and the restore refused the
        // whole class on it. This is the round trip that catches it: the exact member list the backup
        // side produces, run through the exact rule the restore side applies.
        val listing = "cache\ncode_cache\ndatabases\nfiles\nlib\nno_backup\nshared_prefs"

        val kept = filterBackupEntries(DataClass.CE, listing).kept

        assertEquals(listOf("databases", "files", "shared_prefs"), kept)
        kept.forEach { name ->
            assertEquals(name, false, refusal.containsMatchIn(tvLine(name)))
        }
    }

    @Test
    fun `the member the library link would have produced is still refused at restore`() {
        // The other side of the same boundary, and the reason the fix went on the backup half: this
        // line MUST stay refused. Accepting an absolute link target to make the round trip pass would
        // have been a security regression — the next member written through that link lands wherever
        // it points, with root's privilege.
        val line = tvLine("lib", link = "/data/app/~~AbC==/$pkg-XyZ==/lib/arm64")

        assertTrue(line, refusal.containsMatchIn(line))
    }

    @Test
    fun `a lib entry outside the internal classes is the app's own and is kept`() {
        // The exclusion is scoped to where the platform actually creates the link. Under
        // Android/data/<pkg> a directory called `lib` is the app's, and dropping it would be silent
        // data loss dressed up as a fix.
        assertEquals(listOf("lib"), filterBackupEntries(DataClass.EXTERNAL_DATA, "lib").kept)
        assertEquals(listOf("lib"), filterBackupEntries(DataClass.EXTERNAL_MEDIA, "lib").kept)
        assertEquals(emptyList<String>(), filterBackupEntries(DataClass.DE, "lib").kept)
    }

    @Test
    fun `the platform entries are dropped without a row, like the volatile ones`() {
        // Every app has them, they were never going to be packed, and a row per archive for something
        // that is not the user's data buries the rows that are.
        assertEquals(emptyList<ArchiveSkip>(), filterBackupEntries(DataClass.CE, "lib").skipped)
    }

    // ---------------------------------------------------------------------------------------------
    // Root depth: the guard that accepted /data.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a real class root is accepted by every builder that normalises one`() {
        // The half the mutation record showed was missing: `>= 2` -> `>= 4` killed nothing, because
        // no test asserted that a legitimate root gets through. Without this, tightening the guard
        // and breaking the feature outright are the same green.
        listOf(ceRoot, "/data/user_de/0/$pkg", "/storage/emulated/0/Android/data/$pkg").forEach { root ->
            assertNotNull(root, stagingDirPath(root))
            assertNotNull(root, chownRecursiveCommand(root, 10234))
            assertNotNull(root, restoreconCommand(root))
            assertNotNull(root, swapStagedEntriesCommand(root))
            assertNotNull(root, extractCommand(root, "/data/data/com.valhalla.thor/cache/ce.tar", true))
        }
    }

    @Test
    fun `a root shallower than a package directory is refused by every builder that normalises one`() {
        // 4 segments is `/data/user/0/<pkg>`, the shallowest thing that is one app's data. `/data`,
        // `/data/user` and `/data/user/0` are the *platform's* directories: swapStagedEntriesCommand
        // deletes every entry at its root, so accepting `/data/user/0` there is "delete every user's
        // apps' data". The old guard accepted both of those.
        listOf("/", "/data", "/data/user", "/data/user/0", "/storage", "/storage/emulated").forEach { root ->
            assertNull(root, stagingDirPath(root))
            assertNull(root, chownRecursiveCommand(root, 10234))
            assertNull(root, restoreconCommand(root))
            assertNull(root, swapStagedEntriesCommand(root))
            assertNull(root, extractCommand(root, "/data/data/com.valhalla.thor/cache/ce.tar", true))
        }
    }

    @Test
    fun `depth alone is not enough - a dot dot component is still refused at any length`() {
        // Otherwise the count would be trivially satisfiable by climbing: six segments, two of them
        // `..`, resolving to `/data`.
        assertNull(stagingDirPath("/data/user/0/$pkg/../../.."))
        assertNull(chownRecursiveCommand("/data/user/0/../../data/local/tmp", 10234))
    }

    // ---------------------------------------------------------------------------------------------
    // Absence: out of band, and the same answer at both parse sites.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `absence travels as an exit status that no filename can forge`() {
        val command = classSizeCommand(ceRoot, emptyList())!!

        // A subshell, not a bare `exit`: Thor's root channel is one long-lived `su` session, and a
        // bare exit would end the session rather than the command.
        assertTrue(command, command.startsWith("("))
        assertTrue(command, command.endsWith(")"))
        assertTrue(command, command.contains("[ -d '$ceRoot' ] || exit $THOR_ABSENT_EXIT"))
        // 3..125 is the range neither the shell nor a POSIX utility produces.
        assertTrue("$THOR_ABSENT_EXIT", THOR_ABSENT_EXIT in 3..125)
    }

    @Test
    fun `a file named after the old marker no longer empties the archive`() {
        // The regression that motivated the change. An app can create any file it likes in its own
        // data directory, including one named exactly like Thor's control word.
        val entries = filterBackupEntries(DataClass.CE, "THOR_ABSENT\ndatabases")

        assertEquals(false, entries.rootAbsent)
        assertEquals(listOf("THOR_ABSENT", "databases"), entries.kept)
    }

    @Test
    fun `the listing command silences stderr, like its sibling`() {
        // RootSystemGateway substitutes stderr for a blank stdout, so on an EMPTY class root a `su`
        // banner would be handed to the filter and parsed as filenames.
        val command = listClassEntriesCommand(ceRoot)!!

        assertEquals("ls -A '$ceRoot' 2>/dev/null", command)
    }

    // ---------------------------------------------------------------------------------------------
    // The measurement measures what the archive packs.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `everything the measurement excludes is something the filter drops`() {
        // The two lists are derived from one constant; this is what keeps them derived. A drift here
        // is either an inflated size (measuring what is not packed) or a refusal that never fires.
        DataClass.entries.forEach { dataClass ->
            measuredExclusions(dataClass).forEach { child ->
                assertEquals(
                    "$dataClass/$child",
                    emptyList<String>(),
                    filterBackupEntries(dataClass, child).kept,
                )
            }
        }
        assertEquals(listOf("cache", "code_cache", "no_backup"), measuredExclusions(DataClass.CE))
        // External media keeps its cache, so there is nothing to subtract from its measurement.
        assertEquals(emptyList<String>(), measuredExclusions(DataClass.EXTERNAL_MEDIA))
    }

    @Test
    fun `the excluded children are measured before the root, and subtracted from it`() {
        val command = classSizeCommand(ceRoot, listOf("cache", "no_backup"))!!

        // Guarded with -e: a missing `no_backup` is the common case, not an error, and `du` exits
        // non-zero on an operand that is not there.
        assertTrue(command, command.contains("[ -e '$ceRoot/cache' ] && du -s -k '$ceRoot/cache'"))
        // The root LAST, so the total is still the last numeric line — which is what makes a shell
        // banner printed first harmless.
        assertTrue(command, command.indexOf("'$ceRoot/no_backup'") < command.lastIndexOf("'$ceRoot'"))
        assertTrue(command, command.endsWith("du -s -k '$ceRoot' 2>/dev/null )"))
        assertEquals(false, command.contains("-b"))
    }

    @Test
    fun `the reported size is the root minus the children that will not be packed`() {
        val reply = "300\t$ceRoot/cache\n40\t$ceRoot/no_backup\n1024\t$ceRoot"

        // 1024 - 340 = 684 KiB. Reporting 1024 here is what refused a 20 MB backup over a 3 GB
        // browser cache the archive was never going to contain.
        assertEquals(DataClassSize.Known(684L * 1024), parseClassSize(0, reply))
    }

    @Test
    fun `a banner before the numbers is ignored rather than counted`() {
        val reply = "su: permission granted\n300\t$ceRoot/cache\n1024\t$ceRoot"

        assertEquals(DataClassSize.Known(724L * 1024), parseClassSize(0, reply))
    }

    @Test
    fun `a child measured larger than its root clamps at zero rather than going negative`() {
        // Hard links counted in both, or a `du` that followed something the root walk did not.
        assertEquals(DataClassSize.Known(0), parseClassSize(0, "900\t$ceRoot/cache\n400\t$ceRoot"))
    }

    @Test
    fun `a size probe that failed is never rendered as a number`() {
        assertEquals(DataClassSize.Undetermined, parseClassSize(1, "du: permission denied"))
        assertEquals(DataClassSize.Undetermined, parseClassSize(0, null))
        assertEquals(DataClassSize.Undetermined, parseClassSize(0, "300\t$ceRoot/cache\nnot a number"))
    }

    @Test
    fun `the capability marker must be a line of its own`() {
        // `contains` matched the marker anywhere, including inside a path the reply happened to echo.
        assertTrue(parseCapabilityProbe(0, "$THOR_OK\n"))
        assertEquals(false, parseCapabilityProbe(0, "ls: /data/user/0/${THOR_OK}_app: denied"))
    }

    // ---------------------------------------------------------------------------------------------
    // A filename containing a line break.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a filename split by the listing is dropped and recorded, not packed as two`() {
        // `ls -A` output is line-split before it ever reaches Kotlin, so a file called
        // "report\n2024.pdf" arrives as two names that both look real. Both pass every filter, and
        // handing them to tar loses the file AND makes tar exit 1 — which the outcome classifier then
        // reports as "files that changed while being read", a cause that is not the cause.
        val listing = filterBackupEntries(DataClass.CE, "databases\nreport\n2024.pdf")
        assertEquals(listOf("2024.pdf", "databases", "report"), listing.kept)

        val verified = applyEntryVerification(
            dataClass = DataClass.CE,
            listing = listing,
            exitCode = 0,
            output = "report\n2024.pdf",
        )

        assertEquals(listOf("databases"), verified.kept)
        assertEquals(listOf("2024.pdf", "report"), verified.skipped.map { it.name }.sorted())
        assertTrue(verified.skipped.all { it.reason.isNotBlank() })
        assertTrue(verified.skipped.all { it.dataClass == DataClass.CE.id })
    }

    @Test
    fun `the verification asks about each entry and reports only the missing`() {
        val command = verifyEntriesCommand(ceRoot, listOf("databases", "shared prefs"))!!

        assertEquals(
            "[ -e '$ceRoot/databases' ] || [ -L '$ceRoot/databases' ] || echo 'databases' ; " +
                "[ -e '$ceRoot/shared prefs' ] || [ -L '$ceRoot/shared prefs' ] || echo 'shared prefs'",
            command,
        )
    }

    @Test
    fun `a dangling symlink is a real member and is not verified away`() {
        // -e is false for a symlink whose target is gone; -L is true. Without the second test, tar
        // would have been robbed of a member it can pack perfectly well.
        assertTrue(verifyEntriesCommand(ceRoot, listOf("files"))!!.contains("[ -L '$ceRoot/files' ]"))
    }

    @Test
    fun `the verification refuses what it cannot quote and has nothing to say about nothing`() {
        assertNull(verifyEntriesCommand(ceRoot, emptyList()))
        assertNull(verifyEntriesCommand(ceRoot, listOf("it's")))
        assertNull(verifyEntriesCommand(ceRoot, listOf("-rf")))
        assertNull(verifyEntriesCommand("/data/user/0/it's", listOf("files")))
    }

    @Test
    fun `a verification that could not run leaves the listing alone`() {
        // Fail open, in both directions. A refinement step must never be able to empty a backup, and
        // a channel that could not answer has not said anything about the entries.
        val listing = ClassEntries(kept = listOf("databases"), skipped = emptyList(), rootAbsent = false)

        assertEquals(listing, applyEntryVerification(DataClass.CE, listing, 1, "databases"))
        assertEquals(listing, applyEntryVerification(DataClass.CE, listing, 0, null))
        assertEquals(listing, applyEntryVerification(DataClass.CE, listing, 0, ""))
    }

    @Test
    fun `the verification cannot delete an entry it was never given`() {
        // The reply shares stdout with any shell chatter, and RootSystemGateway substitutes stderr
        // for a blank stdout. Intersecting with what was sent makes the worst case a no-op.
        val listing = ClassEntries(kept = listOf("databases"), skipped = emptyList(), rootAbsent = false)

        val verified = applyEntryVerification(DataClass.CE, listing, 0, "files\nshared_prefs")

        assertEquals(listing, verified)
    }
}
