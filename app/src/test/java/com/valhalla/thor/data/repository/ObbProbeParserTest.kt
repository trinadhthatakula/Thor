// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.ObbFile
import com.valhalla.thor.domain.model.ObbProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObbProbeParserTest {

    private fun listing(vararg lines: String) = lines.joinToString("\n")

    @Test
    fun `a normal listing yields Present with sizes`() {
        val probe = parseObbProbe(
            0,
            listing(
                "THOR_OBB 1048576 /storage/emulated/0/Android/obb/com.example.game/main.12.com.example.game.obb",
                "THOR_OBB 2048 /storage/emulated/0/Android/obb/com.example.game/patch.12.com.example.game.obb",
                "THOR_OTHER 0",
                "THOR_END"
            )
        )

        assertEquals(
            ObbProbe.Present(
                files = listOf(
                    ObbFile("main.12.com.example.game.obb", 1048576L),
                    ObbFile("patch.12.com.example.game.obb", 2048L)
                ),
                otherEntryCount = 0
            ),
            probe
        )
    }

    @Test
    fun `THOR_NODIR means the app genuinely has no OBB`() {
        assertEquals(ObbProbe.None, parseObbProbe(0, listing("THOR_NODIR")))
    }

    @Test
    fun `an empty OBB directory is None`() {
        assertEquals(ObbProbe.None, parseObbProbe(0, listing("THOR_OTHER 0", "THOR_END")))
    }

    @Test
    fun `THOR_NOPRIV is Undetermined, never None`() {
        val probe = parseObbProbe(0, listing("THOR_NOPRIV"))
        assertTrue("$probe should be Undetermined", probe is ObbProbe.Undetermined)
    }

    @Test
    fun `a listing without the end sentinel is Undetermined`() {
        // Truncated output — the shell died mid-script, or the gateway dropped the tail. Reading
        // this as None is the exact failure the tri-state exists to prevent: it would offer a
        // .xapk and silently build it without the game data.
        val probe = parseObbProbe(
            0,
            listing("THOR_OBB 10 /storage/emulated/0/Android/obb/com.example.game/main.obb")
        )
        assertTrue("$probe should be Undetermined", probe is ObbProbe.Undetermined)
    }

    @Test
    fun `a stat failure is Undetermined, not an empty directory`() {
        // What the shell produces when `stat` cannot describe a file the *.obb glob matched: the
        // sentinel, no size line, and — because the script exits early — no THOR_END either. The
        // trailing THOR_OTHER 0 is the shape the *unguarded* script produced, and reading it as None
        // is GH#164: an expansion file exists, Thor cannot measure it, and the export would offer a
        // .xapk and pack nothing.
        assertTrue(
            parseObbProbe(0, listing("THOR_STATFAIL")) is ObbProbe.Undetermined
        )
        assertTrue(
            parseObbProbe(
                0,
                listing("THOR_STATFAIL", "THOR_OTHER 0", "THOR_END")
            ) is ObbProbe.Undetermined
        )
    }

    @Test
    fun `the stat call in the command fails closed`() {
        val command = obbProbeCommand("/storage/emulated/0", "com.example.game")!!

        // The guard, not just the sentinel: `stat` writes its complaint to stderr and the loop would
        // otherwise carry on to print THOR_OTHER 0 and THOR_END with exit code 0.
        assertTrue(command, command.contains("|| { echo $SENTINEL_STATFAIL; exit 0; }"))
        // And the format string is built from the constant the parser reads, so the two cannot drift.
        assertTrue(command, command.contains("stat -c '${PREFIX_OBB}%s %n'"))
    }

    @Test
    fun `THOR_BADNAME is Undetermined, not an empty directory`() {
        assertTrue(parseObbProbe(0, listing(SENTINEL_BADNAME)) is ObbProbe.Undetermined)
    }

    @Test
    fun `the command refuses a line terminator in an obb name before stat runs`() {
        val command = obbProbeCommand("/storage/emulated/0", "com.example.game")!!

        // `stat -c %n` prints the name raw, so a file called `main.obb<LF>suffix.obb` would emit two
        // lines and the head would be a well-formed record for a file that does not exist. Both
        // terminators are refused, and both patterns still end in `.obb` so a control character in a
        // name Thor never prints is merely counted.
        val badName = "*\"\n\"*.obb|*\"\r\"*.obb) echo $SENTINEL_BADNAME; exit 0 ;;"
        assertTrue(command, command.contains(badName))
        // Order is the whole point: this arm has to precede the *.obb arm or `case` takes the first
        // match and stats the file anyway.
        assertTrue(
            command,
            command.indexOf(badName) < command.indexOf("*.obb) stat -c")
        )
    }

    @Test
    fun `a THOR_NODIR planted by a file name does not read as an empty directory`() {
        // What an *unguarded* `stat -c %n` would print for a file called `main.obb<LF>THOR_NODIR`:
        // one well-formed OBB record, then a line the parser would otherwise trust as the script's
        // own "this app has no OBB directory" verdict. Reading it as None is GH#164 handed to the
        // target app as a switch — name one file that way and Thor offers a .xapk and packs no game
        // data. The give-away is that the genuine THOR_NODIR branch exits before the listing loop, so
        // it can never be accompanied by THOR_OTHER or THOR_END.
        val probe = parseObbProbe(
            0,
            listing(
                "THOR_OBB 5 /storage/emulated/0/Android/obb/com.example.game/main.obb",
                SENTINEL_NODIR,
                "THOR_OTHER 0",
                "THOR_END"
            )
        )

        assertTrue("$probe should be Undetermined", probe is ObbProbe.Undetermined)
    }

    @Test
    fun `a non-zero exit is Undetermined`() {
        assertTrue(parseObbProbe(1, listing("THOR_NODIR")) is ObbProbe.Undetermined)
    }

    @Test
    fun `null and blank output are Undetermined`() {
        assertTrue(parseObbProbe(0, null) is ObbProbe.Undetermined)
        assertTrue(parseObbProbe(0, "   ") is ObbProbe.Undetermined)
    }

    @Test
    fun `a file name containing spaces survives, because only the first space is a separator`() {
        val probe = parseObbProbe(
            0,
            listing(
                "THOR_OBB 99 /storage/emulated/0/Android/obb/com.example.game/main 1.obb",
                "THOR_OTHER 0",
                "THOR_END"
            )
        )

        assertEquals(listOf(ObbFile("main 1.obb", 99L)), (probe as ObbProbe.Present).files)
    }

    @Test
    fun `garbage lines are ignored rather than fatal`() {
        // Shells warn on stderr-merged streams and toybox versions differ; an unrecognised line
        // must not turn a good listing into a refusal.
        //
        // This is the counterpart to the two Undetermined tests below, and the line between them is
        // the prefix: a line WITHOUT THOR_OBB is somebody else's output and is skipped, while a
        // malformed line WITH it is Thor's own and is fatal. Making noise fatal would refuse good
        // listings on every chatty ROM.
        val probe = parseObbProbe(
            0,
            listing(
                "sh: something harmless",
                "THOR_OBB 5 /storage/emulated/0/Android/obb/com.example.game/main.obb",
                "THOR_OTHER 2",
                "THOR_END"
            )
        )

        assertEquals(ObbProbe.Present(listOf(ObbFile("main.obb", 5L)), 2), probe)
    }

    @Test
    fun `a non-obb name on an OBB line makes the probe Undetermined`() {
        // One way to reach this line is a file name whose newline falls before the suffix —
        // `main<LF>1.obb` splits into a head that has lost its extension and a tail that is garbage.
        // (A newline *after* the suffix does not: see the THOR_BADNAME tests below, which is why the
        // shell refuses such names outright rather than leaving this check to catch them.) The shell
        // only prints THOR_OBB for a *.obb glob match, so this line is proof an expansion file exists
        // that we could not characterise. Dropping it would let the builder pack a .xapk missing that
        // file — GH#164 from a new direction — so it fails closed instead.
        val probe = parseObbProbe(
            0,
            listing(
                "THOR_OBB 5 /storage/emulated/0/Android/obb/com.example.game/main.obb.part",
                "THOR_OTHER 1",
                "THOR_END"
            )
        )

        assertTrue("$probe should be Undetermined", probe is ObbProbe.Undetermined)
    }

    @Test
    fun `an obb Thor could not safely copy makes the probe Undetermined`() {
        // The parser is gated on the same predicate as the copy command, so Present means "this is
        // capturable" rather than merely "these files exist". Without that, a name the shell
        // command refuses would be reported as Present, the export sheet would offer .xapk on the
        // strength of it, and the export would then fail at staging time.
        //
        // A quote is the case that matters: the target app writes its own Android/obb directory
        // with no permission at all, so the name is attacker-chosen input to a root shell.
        val probe = parseObbProbe(
            0,
            listing(
                "THOR_OBB 5 /storage/emulated/0/Android/obb/com.example.game/main'; id #.obb",
                "THOR_OTHER 0",
                "THOR_END"
            )
        )

        assertTrue("$probe should be Undetermined", probe is ObbProbe.Undetermined)
    }

    @Test
    fun `an unparseable size makes the probe Undetermined`() {
        // Present(emptyList(), 0) would be the worst available answer here: it claims the directory
        // holds nothing at all, when in fact it holds an expansion file whose size we could not read.
        val probe = parseObbProbe(
            0,
            listing(
                "THOR_OBB notanumber /storage/emulated/0/Android/obb/com.example.game/main.obb",
                "THOR_OTHER 0",
                "THOR_END"
            )
        )

        assertTrue("$probe should be Undetermined", probe is ObbProbe.Undetermined)
    }

    @Test
    fun `the command quotes both interpolated values`() {
        val command = obbProbeCommand("/storage/emulated/0", "com.example.game")

        assertTrue(command!!, command.contains("'/storage/emulated/0/Android/obb'"))
        assertTrue(command, command.contains("'/storage/emulated/0/Android/obb/com.example.game'"))
        assertTrue(command, command.contains("THOR_END"))
    }

    @Test
    fun `a package name that is not a package name yields no command at all`() {
        // This string is assembled into a shell command. It comes from PackageManager rather than
        // from user input, but a validator here means the shell does not have to be trusted with
        // that assumption.
        assertNull(obbProbeCommand("/storage/emulated/0", "com.example.game; rm -rf /"))
        assertNull(obbProbeCommand("/storage/emulated/0", "../../etc"))
        assertNull(obbProbeCommand("/storage/emulated/0", ""))
        assertNull(obbProbeCommand("/storage/emulated/0", "com..example"))
    }

    @Test
    fun `an external storage dir containing a quote is rejected too`() {
        assertNull(obbProbeCommand("/storage/emu'lated/0", "com.example.game"))
    }
}
