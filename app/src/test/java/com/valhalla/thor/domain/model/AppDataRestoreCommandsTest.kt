// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The destructive half. Every one of these commands runs as root against a real app's data directory,
 * so the same rule as `ObbPlacementTest` applies: an input that cannot be quoted produces **no
 * command**, not a quoted-and-hoped-for-the-best one.
 */
class AppDataRestoreCommandsTest {

    private val root = "/data/user/0/com.example.app"

    /**
     * The refusal ERE compiled as a Java regex.
     *
     * That is only legitimate because the pattern is written with a literal space rather than
     * `[[:space:]]` — Java's regex engine does not understand POSIX bracket expressions, and would
     * silently read `[[:space:]]` as a class of the characters `:space`. Keeping the two dialects in
     * agreement is what lets these tests pin the pattern's *behaviour* and not merely its text.
     */
    private val refusal = Regex(ARCHIVE_MEMBER_REFUSAL_PATTERN)

    /**
     * A plausible `tar -tv` line, so the tests exercise what grep will actually see.
     *
     * [owner] and [stamp] are parameters because `uname`/`gname` are 32-byte **attacker-controlled**
     * fields in the tar header and the field widths differ between GNU, toybox and busybox. A fixture
     * that hard-codes one shape proves the pattern works against that shape and nothing else.
     */
    private fun listing(
        name: String,
        mode: String = "-rw-r--r--",
        owner: String = "0/0",
        size: String = "1024",
        stamp: String = "2024-01-01 00:00:00",
    ) = "$mode $owner  $size $stamp $name"

    @Test
    fun `the staging directory is a hidden child of the class root`() {
        assertEquals("$root/.thorbak-staging", stagingDirPath(root))
    }

    @Test
    fun `extract command for a compressed member is exactly right`() {
        // Exact equality pins the ordering — mkdir, symlink guard, listing, refusal grep, extract —
        // and pins that the listing declares the SAME compression the extraction does. A `-tf`
        // listing against a gzipped archive is an unstated bet on read-side auto-detection.
        val tarPath = "/data/data/com.valhalla.thor/cache/x/ce.tar"
        val staging = "$root/.thorbak-staging"
        assertEquals(
            "rm -rf '$staging' && mkdir -p '$staging' && [ ! -L '$staging' ] && " +
                "( ( tar -tvzf '$tarPath' || echo $THOR_LIST_FAILED ) | " +
                "grep -qE '$ARCHIVE_MEMBER_REFUSAL_PATTERN' ; [ \$? -eq 1 ] ) && " +
                "tar -mxzf '$tarPath' -C '$staging'",
            extractCommand(root, tarPath, compressed = true),
        )
    }

    @Test
    fun `extract command for an uncompressed member is exactly right`() {
        val tarPath = "/tmp/a.tar"
        val staging = "$root/.thorbak-staging"
        assertEquals(
            "rm -rf '$staging' && mkdir -p '$staging' && [ ! -L '$staging' ] && " +
                "( ( tar -tvf '$tarPath' || echo $THOR_LIST_FAILED ) | " +
                "grep -qE '$ARCHIVE_MEMBER_REFUSAL_PATTERN' ; [ \$? -eq 1 ] ) && " +
                "tar -mxf '$tarPath' -C '$staging'",
            extractCommand(root, tarPath, compressed = false),
        )
    }

    @Test
    fun `extraction runs only when grep exits 1, never on any other non-zero status`() {
        // The `!` this replaced inverted EVERY non-zero status, so grep exiting 2 (a pattern it cannot
        // compile) or 127 (no grep on the device) read as "no bad member" and extraction ran with no
        // member check at all — the same fail-open shape as the tar listing, one stage to the right.
        val command = extractCommand(root, "/tmp/a.tar", compressed = true)!!

        assertTrue(command.contains("; [ \$? -eq 1 ] ) && tar -mxzf"))
        assertFalse("the ! inversion must be gone", command.contains("! ("))
    }

    @Test
    fun `a stale staging directory is removed before the extraction, not merged with`() {
        // `mkdir -p` exits 0 on an existing directory, so debris from a restore that died between
        // extracting and swapping would survive — and the swap then moves it into the class root
        // alongside this archive's contents, silently mixing two backups into one app.
        val staging = "$root/.thorbak-staging"
        val command = extractCommand(root, "/tmp/a.tar", compressed = true)!!

        assertTrue(command.startsWith("rm -rf '$staging' && mkdir -p '$staging' &&"))
    }

    @Test
    fun `the refusal pattern is exactly this string`() {
        // Typed out once, here, rather than repeated in every command assertion: those reference the
        // constant, so a wrong pattern would sail past them. This is the one place text drift is caught,
        // and the behavioural tests below are what say the text is the RIGHT text.
        assertEquals(
            "THOR_LIST_FAILED|(^| )/|(^| |/)\\.\\.(/|\$| )|(^| |/)\\.thorbak-staging(/|\$| )",
            ARCHIVE_MEMBER_REFUSAL_PATTERN,
        )
    }

    @Test
    fun `a listing that tar could not produce is refused by the same pattern`() {
        // The guard used to fold the listing into a pipeline whose exit status was grep's, not tar's:
        // a tar that failed or listed partially produced "no match", `!` inverted it to 0, and the
        // extraction ran anyway. The sentinel is what closes that, and it only closes it while the
        // string the command echoes is the string the pattern matches.
        //
        // `refusal.containsMatchIn(THOR_LIST_FAILED)` — what this used to assert — is vacuous: the
        // constant IS the pattern's first alternative, so it matches itself by construction and the
        // assertion would go on passing while the command echoed something else entirely. So the
        // sentinel is read back out of the command, and the check is the one grep performs: line by
        // line over the stream a half-finished tar leaves behind.
        val command = extractCommand(root, "/tmp/a.tar", compressed = true)!!
        val echoed = Regex("\\|\\| echo (\\S+) \\)").find(command)?.groupValues?.get(1)

        assertEquals(THOR_LIST_FAILED, echoed)

        // grep is line-oriented, so the refusal has to come from the sentinel line itself and not
        // from the innocuous listing tar managed to emit before it died.
        val partial = listOf(listing("databases/", mode = "drwxrwx---"), listing("databases/app.db"))
        assertFalse(partial.any { refusal.containsMatchIn(it) })
        assertTrue((partial + echoed!!).any { refusal.containsMatchIn(it) })
    }

    @Test
    fun `every spelling of a dot-dot component is refused`() {
        // The previous pattern hand-wrote the slash cases and so missed a member named exactly `..`:
        // `^\.\./` needs a trailing slash and `/\.\.$` needs a leading one. Anchoring on the component
        // boundary covers all four spellings with one alternative.
        listOf("..", "../x", "x/..", "x/../y", "a/b/../../../etc").forEach { name ->
            assertTrue(name, refusal.containsMatchIn(listing(name)))
        }
    }

    @Test
    fun `an absolute member name is refused`() {
        assertTrue(refusal.containsMatchIn(listing("/etc/passwd")))
        assertTrue(refusal.containsMatchIn(listing("/")))
    }

    @Test
    fun `a link whose target escapes the tree is refused, and one that stays inside is not`() {
        // `-C '<staging>'` does NOT bound this: a symlink member plus a later member written *through*
        // it lands wherever the link points, with root's privilege. The rule is deliberately targeted
        // rather than blanket — Thor's backup half tars whatever the app had, so refusing every symlink
        // would leave Thor unable to restore its own archives.
        assertTrue(refusal.containsMatchIn(listing("link -> /data/user/0/other.app", "lrwxrwxrwx")))
        assertTrue(refusal.containsMatchIn(listing("link -> ../../other.app", "lrwxrwxrwx")))
        assertTrue(refusal.containsMatchIn(listing("link -> sub/../../x", "lrwxrwxrwx")))
        assertTrue(refusal.containsMatchIn(listing("hard link to /etc/shadow", "hrw-r--r--")))

        assertFalse(refusal.containsMatchIn(listing("link -> databases/app.db", "lrwxrwxrwx")))
        assertFalse(refusal.containsMatchIn(listing("link -> sub/dir/file", "lrwxrwxrwx")))
    }

    @Test
    fun `a member named after the staging directory is refused`() {
        // The composition defect: the swap protects exactly `! -name '.thorbak-staging'`, so an archive
        // carrying a member by that name wipes the class root and then fails the `mv` onto itself — a
        // destructive no-op that leaves the app with nothing. Both guards have to know the name.
        assertTrue(refusal.containsMatchIn(listing(STAGING_DIR_NAME)))
        assertTrue(refusal.containsMatchIn(listing("$STAGING_DIR_NAME/", "drwxr-xr-x")))
        assertTrue(refusal.containsMatchIn(listing("$STAGING_DIR_NAME/x")))
        assertTrue(refusal.containsMatchIn(listing("sub/$STAGING_DIR_NAME")))
    }

    @Test
    fun `the pattern survives every -tv field shape, including an attacker-chosen owner`() {
        // `uname`/`gname` are 32-byte fields in the tar header, so the owner column is attacker-chosen.
        // The KDoc must not claim the fixed fields are safe by construction — what makes the pattern
        // sound is the DIRECTION: matching is per line and each alternative only ever ADDS a match, so
        // a crafted field can cause an extra refusal and can never suppress a real one.
        val fieldShapes = listOf(
            listing("x/../y", owner = "0/0"),
            listing("x/../y", owner = "u0_a123/u0_a123"),
            listing("x/../y", owner = "0/0", size = "9223372036854775807"),
            listing("x/../y", owner = "0/0", stamp = "2024-01-01 00:00"),
            listing("x/../y", owner = "a".repeat(32) + "/" + "b".repeat(32)),
            listing("x/../y", mode = "crw-rw-rw-", size = "10,  59"),
        )
        fieldShapes.forEach { line -> assertTrue(line, refusal.containsMatchIn(line)) }

        // A hostile owner name produces a refusal, never an acceptance.
        assertTrue(refusal.containsMatchIn(listing("databases/app.db", owner = "a /b/a /b")))
        assertFalse(refusal.containsMatchIn(listing("databases/app.db", owner = "u0_a123/u0_a123")))
    }

    @Test
    fun `the documented over-refusal is real, and only ever in the refusing direction`() {
        // Matching the name inside a verbose line rather than against a `^` anchor costs exactly this:
        // a name containing a space immediately followed by `/` or `..` is refused. It is documented in
        // the KDoc as a deviation, so it is pinned here rather than left to be discovered as a bug.
        assertTrue(refusal.containsMatchIn(listing("My Dir /f")))
        assertTrue(refusal.containsMatchIn(listing("foo ../x")))

        // The near misses that must still restore.
        assertFalse(refusal.containsMatchIn(listing("My Dir/f")))
        assertFalse(refusal.containsMatchIn(listing("foo/..bar")))
    }

    @Test
    fun `the pattern uses only portable ERE syntax`() {
        // Compiling as a Java regex is proved by the `refusal` field above. This is weaker and should
        // not be read as its counterpart: a blacklist of PCRE-isms cannot prove grep compiles the
        // pattern — `\<`, an unbalanced `(`, and a stray `\+` would all pass it. What it catches is the
        // realistic regression, someone reaching for `\d` or `\s` while editing the pattern in an IDE
        // that only knows Java's engine. Since the guard proceeds only on grep's exit 1, an ERE grep
        // rejects fails CLOSED — no unchecked extraction — but a restore that refuses every archive is
        // still a broken restore, so the cheap half of the check is worth having.
        listOf("\\d", "\\s", "\\w", "\\b", "[[:", "*?", "+?", "(?", "\\1").forEach {
            assertFalse(it, ARCHIVE_MEMBER_REFUSAL_PATTERN.contains(it))
        }
    }

    @Test
    fun `ordinary app data member names are not refused`() {
        // A refusal pattern that fires on real data turns the whole restore path into a silent no-op,
        // which is the same class of failure as one that never fires. `a..b` and `...` are legal names.
        listOf(
            "databases/app.db",
            "shared_prefs/a..b.xml",
            "files/...hidden",
            "files/",
            "files/My Photos/img.jpg",
            "no_backup/x",
        ).forEach { name ->
            assertFalse(name, refusal.containsMatchIn(listing(name)))
        }
    }

    @Test
    fun `the name the swap protects is exactly the name the extraction creates`() {
        // Three string literals that must agree: the directory the extract creates, the name the swap
        // excludes from its deletion, and the member name the refusal pattern rejects. If any drifts,
        // the swap deletes the staged data and the restore reports success over an empty directory.
        val extract = extractCommand(root, "/tmp/a.tar", compressed = true)!!
        val swap = swapStagedEntriesCommand(root)!!

        assertTrue(extract.contains("'$root/$STAGING_DIR_NAME'"))
        assertTrue(swap.contains("! -name '$STAGING_DIR_NAME'"))
        assertTrue(refusal.containsMatchIn(listing(STAGING_DIR_NAME)))
    }

    @Test
    fun `swap command is exactly right`() {
        // Exact equality pins the ordering — non-empty guard, then delete, then move, then rmdir.
        // Reversing the rm and mv stages destroys the data the restoration just extracted. A contains
        // check passes even with the stages reversed, because the depth flags appear in both find
        // invocations and the rm/mv substrings float freely.
        val staging = "$root/.thorbak-staging"
        assertEquals(
            "[ -n \"\$(ls -A '$staging')\" ] && " +
                "find '$root' -mindepth 1 -maxdepth 1 ! -name '.thorbak-staging' -exec rm -rf {} + && " +
                "find '$staging' -mindepth 1 -maxdepth 1 -exec mv -f {} '$root/' \\; && " +
                "rmdir '$staging'",
            swapStagedEntriesCommand(root),
        )
    }

    @Test
    fun `chown is recursive, does not follow symlinks, and applies one id to both owner and group`() {
        // -h: without it, toybox's chown follows a symlink planted in the restored tree and hands
        // another app's data directory to the restored uid.
        assertEquals("chown -Rh 10123:10123 '$root'", chownRecursiveCommand(root, 10123))
    }

    @Test
    fun `restorecon is recursive and forced`() {
        // -F, not just -R: without the force, an already-labelled file keeps whatever context it was
        // extracted with, and the app still cannot read it. Omitting this is the most common reason a
        // restore "succeeds" and the app crashes on launch.
        assertEquals("restorecon -RF '$root'", restoreconCommand(root))
    }

    @Test
    fun `every restore command refuses a root that is not quotable or is not normalised`() {
        // The last four inputs pass isQuotableAbsolutePath but are rejected by isNormalisedRoot:
        // "/" and a single-segment path have too few segments; the "../" path climbs out of the
        // data directory via a ".." component.
        val hostile = listOf(
            "/data/user/0/it's",
            "relative/path",
            "/data\n/user",
            "",
            "/",
            "/data",
            "/data/user/0/com.example.app/../../../system",
        )

        hostile.forEach { bad ->
            assertNull(bad, stagingDirPath(bad))
            assertNull(bad, extractCommand(bad, "/tmp/a.tar", compressed = true))
            assertNull(bad, swapStagedEntriesCommand(bad))
            assertNull(bad, chownRecursiveCommand(bad, 10123))
            assertNull(bad, restoreconCommand(bad))
        }
    }

    @Test
    fun `extraction refuses a tar path that is not a quotable absolute path`() {
        assertNull(extractCommand(root, "cache/a.tar", compressed = true))
        assertNull(extractCommand(root, "/tmp/it's.tar", compressed = true))
        assertNull(extractCommand(root, "", compressed = true))
    }

    @Test
    fun `chown refuses a negative uid`() {
        // `appUid` returns null for a missing package; a caller that turned that into -1 would emit
        // `chown -Rh -1:-1`, which some toybox builds accept as an option.
        assertNull(chownRecursiveCommand(root, -1))
    }
}
