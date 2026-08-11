// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privileged shell surface of app-data backup, kept pure so it can be checked without a device.
 *
 * The same reasoning as `ObbPlacementTest`: none of this is validated by the thing that runs it, so
 * each hostile input gets its own named test rather than being trusted to have been refused earlier
 * in the call chain.
 */
class AppDataCommandsTest {

    private val pkg = "com.example.game"
    private val ext = "/storage/emulated/0"

    @Test
    fun `each class names the platform's own directory for it`() {
        assertEquals("/data/user/0/$pkg", dataClassRoot(DataClass.CE, pkg, 0, ext))
        assertEquals("/data/user_de/0/$pkg", dataClassRoot(DataClass.DE, pkg, 0, ext))
        assertEquals("$ext/Android/data/$pkg", dataClassRoot(DataClass.EXTERNAL_DATA, pkg, 0, ext))
        assertEquals("$ext/Android/media/$pkg", dataClassRoot(DataClass.EXTERNAL_MEDIA, pkg, 0, ext))
    }

    @Test
    fun `a secondary user's data is read from that user's directory`() {
        // userId is the Android multi-user id, and it appears in the path. It is NOT the Linux uid
        // that chown takes — confusing the two is the bug this test exists to keep out.
        assertEquals("/data/user/10/$pkg", dataClassRoot(DataClass.CE, pkg, 10, ext))
    }

    @Test
    fun `an unusable package name yields no root at all`() {
        assertNull(dataClassRoot(DataClass.CE, "com.example.game; rm -rf /", 0, ext))
        assertNull(dataClassRoot(DataClass.CE, "../../data/local/tmp", 0, ext))
        assertNull(dataClassRoot(DataClass.CE, "com..example", 0, ext))
        assertNull(dataClassRoot(DataClass.CE, "", 0, ext))
    }

    @Test
    fun `a negative user id yields no root`() {
        // ApplicationInfo.uid / 100000 on a package that has gone away can arrive as -1.
        assertNull(dataClassRoot(DataClass.CE, pkg, -1, ext))
    }

    @Test
    fun `an external root that is not a quotable absolute path yields no root`() {
        assertNull(dataClassRoot(DataClass.EXTERNAL_DATA, pkg, 0, "/storage/emu'lated/0"))
        assertNull(dataClassRoot(DataClass.EXTERNAL_MEDIA, pkg, 0, "storage/emulated/0"))
        assertNull(dataClassRoot(DataClass.EXTERNAL_DATA, pkg, 0, ""))
        // CE and DE never touch it, so they are unaffected by a bad external root.
        assertEquals("/data/user/0/$pkg", dataClassRoot(DataClass.CE, pkg, 0, ""))
    }

    @Test
    fun `the capability probe reads Thor's own data directory and prints a marker`() {
        assertEquals(
            "ls -1 '/data/user/0/com.valhalla.thor' >/dev/null 2>&1 && echo $THOR_OK",
            capabilityProbeCommand("com.valhalla.thor", 0)
        )
    }

    @Test
    fun `the probe is believed only on a zero exit AND its own marker`() {
        // RootSystemGateway.execute() folds a *throw* into `-1 to stackTraceToString()`, so an exit
        // code alone can be a Thor stack trace rather than a shell verdict; and a gateway that
        // returns 0 with no output has not proved anything.
        assertTrue(parseCapabilityProbe(0, THOR_OK))
        assertTrue(parseCapabilityProbe(0, "$THOR_OK\n"))
        assertEquals(false, parseCapabilityProbe(0, ""))
        assertEquals(false, parseCapabilityProbe(0, null))
        assertEquals(false, parseCapabilityProbe(1, THOR_OK))
        assertEquals(false, parseCapabilityProbe(-1, "java.lang.SecurityException"))
    }

    @Test
    fun `the size probe tests for the directory before measuring it`() {
        // `du` on a missing path exits nonzero, which is indistinguishable from a failed probe —
        // and a legitimately absent class rendered as "size unknown" is a lie in the other
        // direction. A dedicated exit status separates the two, out of band from any text an app
        // could choose.
        val command = classSizeCommand("/data/user/0/$pkg", emptyList())!!

        assertTrue(command, command.startsWith("( [ -d '/data/user/0/$pkg' ] || exit "))
        assertTrue(command, command.contains("exit $THOR_ABSENT_EXIT"))
        // POSIX -k. `du -b` is a GNU extension and is not safe to assume on toybox.
        assertTrue(command, command.contains("du -s -k '/data/user/0/$pkg'"))
        assertEquals(false, command.contains("-b"))
    }

    @Test
    fun `an absent class root is Empty, not Undetermined`() {
        assertEquals(DataClassSize.Empty, parseClassSize(THOR_ABSENT_EXIT, null))
        assertEquals(DataClassSize.Empty, parseClassSize(THOR_ABSENT_EXIT, "\n"))
    }

    @Test
    fun `a measured class is reported in bytes`() {
        assertEquals(DataClassSize.Known(2048L * 1024), parseClassSize(0, "2048\t/data/user/0/$pkg"))
        // Some shells separate with spaces rather than a tab.
        assertEquals(DataClassSize.Known(512L * 1024), parseClassSize(0, "512 /data/user/0/$pkg"))
    }

    @Test
    fun `an unreadable class is Undetermined and never zero`() {
        assertEquals(DataClassSize.Undetermined, parseClassSize(1, "du: permission denied"))
        assertEquals(DataClassSize.Undetermined, parseClassSize(0, ""))
        assertEquals(DataClassSize.Undetermined, parseClassSize(0, null))
        assertEquals(DataClassSize.Undetermined, parseClassSize(0, "not a number"))
    }

    @Test
    fun `the volatile directories are dropped from the classes that have them`() {
        val listing = "cache\ncode_cache\nno_backup\ndatabases\nshared_prefs\nfiles"

        val ce = filterBackupEntries(DataClass.CE, listing)

        assertEquals(listOf("databases", "files", "shared_prefs"), ce.kept)
        // Dropped by design, so not reported as skipped — skippedEntries is for entries Thor
        // *refused*, and three rows on every archive would bury the ones that matter.
        assertEquals(emptyList<ArchiveSkip>(), ce.skipped)
    }

    @Test
    fun `the restore staging directory is never packed into a backup, for any class`() {
        // It exists only when a restore died between extracting and swapping. Packing it would produce
        // an archive that ARCHIVE_MEMBER_REFUSAL_PATTERN refuses **in whole**, so one interrupted
        // restore would make every later backup of that app permanently unrestorable.
        DataClass.entries.forEach { dataClass ->
            val entries = filterBackupEntries(dataClass, "databases\n$STAGING_DIR_NAME\nfiles")

            assertEquals(dataClass.id, listOf("databases", "files"), entries.kept)
            // Recorded, unlike the routine volatile-directory exclusions. Its presence means a restore
            // died mid-swap, so at this moment it holds entries the class root itself does not: an
            // archive that omitted them silently would read as a complete backup of the app.
            assertEquals(dataClass.id, 1, entries.skipped.size)
            assertEquals(dataClass.id, STAGING_DIR_NAME, entries.skipped.single().name)
        }
    }

    @Test
    fun `external media keeps everything the user can see`() {
        val listing = "cache\nWhatsApp Images"

        val media = filterBackupEntries(DataClass.EXTERNAL_MEDIA, listing)

        assertEquals(listOf("WhatsApp Images", "cache"), media.kept)
    }

    @Test
    fun `an entry Thor cannot quote is refused and recorded`() {
        val listing = "good\nit's bad\n-rf\nalso good"

        val entries = filterBackupEntries(DataClass.CE, listing)

        assertEquals(listOf("also good", "good"), entries.kept)
        assertEquals(2, entries.skipped.size)
        assertTrue(entries.skipped.any { it.name == "it's bad" })
        // A leading dash would be read as a tar option rather than as an operand.
        assertTrue(entries.skipped.any { it.name == "-rf" })
        assertTrue(entries.skipped.all { it.dataClass == DataClass.CE.id })
        assertTrue(entries.skipped.all { it.reason.isNotBlank() })
    }

    @Test
    fun `the filter never declares a root absent, because it cannot know`() {
        // Absence is the listing command's exit status now, not a word in its output. It has to be:
        // the only channel a marker could travel on is the one carrying filenames the app being
        // backed up chose, and a file called THOR_ABSENT used to drop the whole class from the
        // archive. Nothing this function is given can set the flag.
        assertEquals(false, filterBackupEntries(DataClass.CE, "").rootAbsent)
        assertEquals(false, filterBackupEntries(DataClass.CE, "THOR_ABSENT").rootAbsent)
        assertEquals(listOf("THOR_ABSENT"), filterBackupEntries(DataClass.CE, "THOR_ABSENT").kept)
        assertEquals(emptyList<String>(), filterBackupEntries(DataClass.CE, "").kept)
    }

    @Test
    fun `tar names each survivor as its own quoted operand`() {
        val command = tarCreateCommand(
            root = "/data/user/0/$pkg",
            outPath = "/data/data/com.valhalla.thor/cache/backup/ce.tar.gz",
            entries = listOf("databases", "shared prefs"),
            compress = true,
        )!!

        assertEquals(
            "tar -czf '/data/data/com.valhalla.thor/cache/backup/ce.tar.gz' " +
                "-C '/data/user/0/$pkg' 'databases' 'shared prefs'",
            command
        )
    }

    @Test
    fun `the uncompressed fallback is the same command without z`() {
        // Deliberately not `tar --exclude`: that bets on toybox's option surface, where
        // enumerate-then-list is a pure List<String> -> String? function.
        val command = tarCreateCommand("/data/user/0/$pkg", "/tmp/ce.tar", listOf("files"), false)!!

        assertTrue(command, command.startsWith("tar -cf '/tmp/ce.tar' -C "))
    }

    @Test
    fun `tar refuses an empty entry list rather than writing an empty archive`() {
        assertNull(tarCreateCommand("/data/user/0/$pkg", "/tmp/ce.tar", emptyList(), true))
    }

    @Test
    fun `tar refuses an entry it cannot quote`() {
        assertNull(tarCreateCommand("/data/user/0/$pkg", "/tmp/ce.tar", listOf("it's"), true))
        assertNull(tarCreateCommand("/data/user/0/$pkg", "/tmp/ce.tar", listOf("-rf"), true))
        assertNull(tarCreateCommand("/data/user/0/$pkg", "/tmp/ce.tar", listOf(""), true))
    }

    @Test
    fun `the staged tar is handed to Thor's own uid and to nobody else`() {
        // The shell creates the file, so root owns it; Thor has to read it back. 600 because the
        // staged tar is plaintext app data.
        val command = chownFileCommand("/data/data/com.valhalla.thor/cache/backup/ce.tar.gz", 10234)!!

        assertEquals(
            "chown 10234:10234 '/data/data/com.valhalla.thor/cache/backup/ce.tar.gz' && " +
                "chmod 600 '/data/data/com.valhalla.thor/cache/backup/ce.tar.gz'",
            command
        )
    }

    @Test
    fun `a negative uid yields no chown`() {
        // ApplicationInfo.uid is -1 for a package that vanished between two calls.
        assertNull(chownFileCommand("/tmp/ce.tar", -1))
    }

    @Test
    fun `every command builder in this file refuses an unquotable path`() {
        // The sweep `PerUserCommandsTest` runs for "every builder names its user", for the property
        // that matters here. A builder added later without the quotability guard fails this test
        // rather than shipping an injection.
        val cls = Class.forName("com.valhalla.thor.domain.model.AppDataCommandsKt")
        val hostile = "/data/user/0/it's"
        val checked = mutableListOf<String>()

        for (method in cls.declaredMethods) {
            // `contains`, not `endsWith`: Kotlin may append a module suffix to an internal name.
            if (!method.name.contains("Command")) continue
            if (!Modifier.isStatic(method.modifiers)) continue
            val types = method.parameterTypes
            if (types.isEmpty() || types[0] != String::class.java) continue

            val args = ArrayList<Any?>(types.size)
            var usable = true
            types.forEachIndexed { index, type ->
                args += when {
                    index == 0 -> hostile
                    type == String::class.java -> "safe"
                    type == Int::class.javaPrimitiveType -> 0
                    type == Long::class.javaPrimitiveType -> 1L
                    type == Boolean::class.javaPrimitiveType -> true
                    type == List::class.java -> listOf("entry")
                    else -> { usable = false; null }
                }
            }
            if (!usable) continue

            method.isAccessible = true
            checked += method.name
            assertNull(method.name, method.invoke(null, *args.toTypedArray()))
        }

        // A reflective sweep that matched nothing is a green test proving nothing.
        assertTrue("only checked $checked", checked.size >= 5)
    }
}
