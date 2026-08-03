// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `dumpsys package <pkg>` suspender readback, and the ownership rule it feeds.
 *
 * This is the foundation of the fix for the report "suspended via Shizuku, switched to root, now the
 * app is stuck": Android keys a suspension on the *suspending package name*, and from API 30 a
 * caller may only lift its own entry. Lifting one you do not own is not an error — it produces
 * `changed == false`, the package is left out of the returned failure array, and every call site in
 * Thor reads that empty array as success. So the only way to know whether an unsuspend worked is to
 * read who owns it before, and read again after; this parser is that read.
 *
 * All four dump shapes are covered because Thor's minSdk is 28 and the field moved twice on the way
 * to 37 — inline on the `User N:` line, then a `Suspend params:` block, then the same block keyed by
 * `UserPackage.toString()`. The fixtures are multi-line because [parseSuspendingPackages] is a state
 * machine: the block carries no user id of its own and inherits the section it follows, so a
 * one-line fixture would not exercise the part most likely to be wrong.
 *
 * `dumpsys` output is a String, so none of this needs a device — which is the whole reason the
 * parser lives in `domain/model` as a pure function. `:app` has no mocking library and no
 * Robolectric by policy, and a parser that could only be tested on a phone would not be tested.
 */
class SuspenderReadbackTest {

    /** The oldest device Thor supports, where the suspender is printed inline. */
    private val pie = 28

    /** Android 10: still inline, but the trailing key is `dialogInfo=`. */
    private val q = 29

    /** Android 11: `Suspend params:` block, and the first level that enforces ownership. */
    private val r = 30

    /** Android 15: the block's key becomes `UserPackage.toString()`. */
    private val vanillaIceCream = 35

    /**
     * API 28. The suspender rides on the `User N:` line itself and the trailing key is
     * `dialogMessage=` — android-9.0.0_r61 `Settings.java:4775-4778`. No `distractionFlags` yet;
     * that arrives in Q.
     */
    private val api28Dump = """
        Packages:
          Package [com.example.chat] (5f3a1c7):
            userId=10214
            pkg=Package{9a1b2c3 com.example.chat}
            codePath=/data/app/com.example.chat-1
            versionCode=421 minSdk=23 targetSdk=28
            versionName=4.2.1
            dataDir=/data/user/0/com.example.chat
            timeStamp=2024-03-11 09:12:44
            firstInstallTime=2024-03-11 09:12:44
            signatures=PackageSignatures{2f1c0aa version:2, signatures:[a3d90f11]}
            installPermissionsFixed=true
            pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
            User 0: ceDataInode=1310749 installed=true hidden=false suspended=true suspendingPackage=com.android.shell dialogMessage=null stopped=false notLaunched=false enabled=0 instant=false virtual=false
              gids=[3003]
              runtime permissions:
                android.permission.CAMERA: granted=true
    """.trimIndent()

    /**
     * API 29. Same line, `dialogInfo=` in place of `dialogMessage=` — android-10.0.0_r47
     * `Settings.java:4757-4759` — and a real `SuspendDialogInfo.toString()` in it, spaces and all,
     * sitting between the name we want and the rest of the line.
     */
    private val api29Dump = """
        Packages:
          Package [com.example.chat] (5f3a1c7):
            userId=10214
            versionCode=421 minSdk=23 targetSdk=29
            dataDir=/data/user/0/com.example.chat
            pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
            User 0: ceDataInode=1310749 installed=true hidden=false suspended=true suspendingPackage=com.android.shell dialogInfo=SuspendDialogInfo: {mTitleResId = 0, mDialogMessage = Paused by your admin} distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false
              gids=[3003]
    """.trimIndent()

    /**
     * API 30-34. `PackageUserState.suspendParams` is a map now, so the suspenders move into their
     * own block below the user line and there can be more than one.
     */
    private val api30Dump = """
        Packages:
          Package [com.example.chat] (5f3a1c7):
            userId=10214
            versionCode=421 minSdk=23 targetSdk=31
            dataDir=/data/user/0/com.example.chat
            pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
            User 0: ceDataInode=1310749 installed=true hidden=false suspended=true distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false
            Suspend params:
              suspendingPackage=com.android.shell dialogInfo=null
              gids=[3003]
    """.trimIndent()

    /**
     * API 35-37. The map is keyed by `UserPackage`, whose `toString()` is
     * `"<" + userId + ">" + packageName`, and `quarantined=` joins the tail.
     */
    private val api35Dump = """
        Packages:
          Package [com.example.chat] (5f3a1c7):
            userId=10214
            versionCode=421 minSdk=24 targetSdk=36
            dataDir=/data/user/0/com.example.chat
            pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
            User 0: ceDataInode=1310749 installed=true hidden=false suspended=true distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false
            Suspend params:
              suspendingPackage=<0>com.android.shell dialogInfo=null quarantined=false
              gids=[3003]
    """.trimIndent()

    @Test
    fun theInlineApi28ShapeParses() {
        // The whole point of supporting this one: minSdk is 28, so it is an ordinary device rather
        // than a corner case, and it is the only shape where the answer never appears on a line of
        // its own.
        assertEquals(setOf("com.android.shell"), parseSuspendingPackages(api28Dump))
    }

    @Test
    fun theInlineApi29ShapeParsesPastItsDialogInfo() {
        // `dialogInfo=` is free text the suspending app chose, it contains spaces, and on this shape
        // it sits on the same line as the name we want. Reading tokens after it — or letting a
        // greedy match run into it — is how the trailing junk ends up in a package name.
        assertEquals(setOf("com.android.shell"), parseSuspendingPackages(api29Dump))
    }

    @Test
    fun theApi30BlockShapeParses() {
        assertEquals(setOf("com.android.shell"), parseSuspendingPackages(api30Dump))
    }

    @Test
    fun theApi35UserPackagePrefixIsStrippedFromTheName() {
        // The highest-value assertion in this file. A regex written against the 30-34 block captures
        // `<0>com.android.shell` verbatim on 35+, and that string then goes into a `pm unsuspend`
        // argument as if it were a package name. No such package exists, so nothing is removed — and
        // because naming a suspender that owns nothing is indistinguishable from naming one that
        // does not exist, nothing fails either. The app stays suspended and Thor reports success.
        val suspenders = parseSuspendingPackages(api35Dump)

        assertEquals(setOf("com.android.shell"), suspenders)
        assertFalse(
            "the UserPackage prefix reached the package name",
            suspenders.contains("<0>com.android.shell")
        )
        assertTrue(suspenders.none { it.startsWith("<") })
    }

    @Test
    fun everyRecordedSuspenderIsReturnedNotJustTheFirst() {
        // From API 30 a package can be suspended by several callers at once and stays suspended
        // while any entry remains. Returning only the first is the same bug in a different costume:
        // Thor lifts its own entry, the app is still suspended, and the caller was told it worked.
        val dump = """
            Packages:
              Package [com.example.chat] (5f3a1c7):
                userId=10214
                User 0: ceDataInode=1310749 installed=true hidden=false suspended=true distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false
                Suspend params:
                  suspendingPackage=<0>com.android.shell dialogInfo=null quarantined=false
                  suspendingPackage=<0>com.valhalla.thor dialogInfo=SuspendDialogInfo: {mTitleResId = 0} quarantined=false
                  suspendingPackage=<0>android dialogInfo=null quarantined=true
                  gids=[3003]
        """.trimIndent()

        assertEquals(
            setOf("com.android.shell", "com.valhalla.thor", "android"),
            parseSuspendingPackages(dump)
        )
    }

    @Test
    fun aWorkProfileSuspensionIsNotAttributedToUserZero() {
        // `Suspend params:` carries no user id; it belongs to the `User N:` section above it. Both
        // profiles here have the app suspended, by different owners, and asking about one must never
        // hand back the other's — unsuspending as the wrong identity is a guaranteed silent no-op.
        val dump = """
            Packages:
              Package [com.example.chat] (5f3a1c7):
                userId=10214
                User 0: ceDataInode=1310749 installed=true hidden=false suspended=true distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false
                Suspend params:
                  suspendingPackage=<0>com.android.shell dialogInfo=null quarantined=false
                User 10: ceDataInode=1441822 installed=true hidden=false suspended=true distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false
                Suspend params:
                  suspendingPackage=<10>com.example.dpc dialogInfo=null quarantined=false
        """.trimIndent()

        assertEquals(setOf("com.android.shell"), parseSuspendingPackages(dump, userId = 0))
        assertEquals(setOf("com.example.dpc"), parseSuspendingPackages(dump, userId = 10))
    }

    @Test
    fun theUserPackagePrefixItselfFiltersByUser() {
        // The digits are not decoration: they say which user the *suspending* package lives in, so a
        // work-profile DPC's entry can appear inside user 0's own section. Stripping the prefix
        // without reading it would report `com.example.dpc` as a suspender of user 0's copy, and the
        // unsuspend aimed at it would quietly do nothing.
        val dump = """
            Packages:
              Package [com.example.chat] (5f3a1c7):
                userId=10214
                User 0: ceDataInode=1310749 installed=true hidden=false suspended=true distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false
                Suspend params:
                  suspendingPackage=<0>com.android.shell dialogInfo=null quarantined=false
                  suspendingPackage=<10>com.example.dpc dialogInfo=null quarantined=false
        """.trimIndent()

        assertEquals(setOf("com.android.shell"), parseSuspendingPackages(dump, userId = 0))
    }

    @Test
    fun aUserSectionThatIsNotSuspendedContributesNothing() {
        // AOSP prints the block only inside `if (ps.getSuspended(userId))`, so text surviving a
        // `suspended=false` is stale — an OEM dump quirk or a torn read. Reporting a suspender for
        // an app that is not suspended sends the unsuspend path chasing an entry that is gone.
        val dump = """
            Packages:
              Package [com.example.chat] (5f3a1c7):
                userId=10214
                User 0: ceDataInode=1310749 installed=true hidden=false suspended=false distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false
                Suspend params:
                  suspendingPackage=<0>com.android.shell dialogInfo=null quarantined=false
        """.trimIndent()

        assertEquals(emptySet<String>(), parseSuspendingPackages(dump))
    }

    @Test
    fun freeTextInADialogCannotForgeAField() {
        // `dialogInfo` is a string the suspending app controls. It is printed after the name we
        // want, so everything from it onward is cut before any key is read — otherwise an app whose
        // pause dialog happens to read "suspended=false" would delete Thor's answer for it.
        val dump = """
            Packages:
              Package [com.example.chat] (5f3a1c7):
                userId=10214
                User 0: ceDataInode=1310749 installed=true hidden=false suspended=true suspendingPackage=com.android.shell dialogInfo=SuspendDialogInfo: {mDialogMessage = suspended=false suspendingPackage=com.evil.app} distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false
        """.trimIndent()

        assertEquals(setOf("com.android.shell"), parseSuspendingPackages(dump))
    }

    @Test
    fun aDeeperIndentedDumpStillParses() {
        // `Settings.dumpPackageLPr` takes its indentation as a `prefix` argument and the hidden
        // system package section passes a deeper one, so column counts belong to the call site, not
        // to the format. A parser keyed on them would drop entries from a well-formed dump.
        val dump = """
            Hidden system packages:
                  Package [com.example.chat] (5f3a1c7):
                    userId=10214
                        User 0: ceDataInode=1310749 installed=true hidden=false suspended=true distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false
                        Suspend params:
                            suspendingPackage=<0>com.android.shell dialogInfo=null quarantined=false
        """.trimIndent()

        assertEquals(setOf("com.android.shell"), parseSuspendingPackages(dump))
    }

    @Test
    fun aBlockWithNoUserSectionOfItsOwnIsNotAttributedToThePreviousPackage() {
        // An updated system app is dumped twice, and the second copy is a different PackageSetting
        // whose user state may be stale or absent. An entry we cannot attribute to a user is
        // unknown, and unknown must not become "user 0 said so".
        val dump = """
            Packages:
              Package [com.example.chat] (5f3a1c7):
                userId=10214
                User 0: ceDataInode=1310749 installed=true hidden=false suspended=true distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false
                Suspend params:
                  suspendingPackage=<0>com.android.shell dialogInfo=null quarantined=false
            Hidden system packages:
              Package [com.example.chat] (0b4e29a):
                userId=10214
                Suspend params:
                  suspendingPackage=<0>com.example.stale dialogInfo=null quarantined=false
        """.trimIndent()

        assertEquals(setOf("com.android.shell"), parseSuspendingPackages(dump))
    }

    @Test
    fun anUnreadableDumpIsEmptyRatherThanAThrow() {
        // Each of these is a *different* reason to know nothing, and all three land on the same
        // empty set — which is exactly why callers must read empty as "unknown" and fail closed.
        // The permission denial is what the app process gets: `dumpsys package` needs
        // android.permission.DUMP, which root and Shizuku's shell uid have and Thor does not.
        val denied = """
            Permission Denial: can't dump package from from pid=9142, uid=10214 without permission android.permission.DUMP
        """.trimIndent()
        val truncated = """
            Packages:
              Package [com.example.chat] (5f3a1c7):
                userId=10214
                User 0: ceDataInode=1310749 installed=true hidden=false suspended=true distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false
                Suspend params:
        """.trimIndent()
        val garbage = """
            /system/bin/sh: dumpsys: inaccessible or not found
            <0>com.android.shell
            suspendingPackage
        """.trimIndent()

        assertEquals(emptySet<String>(), parseSuspendingPackages(""))
        assertEquals(emptySet<String>(), parseSuspendingPackages("   \n\n  "))
        assertEquals(emptySet<String>(), parseSuspendingPackages(denied))
        assertEquals(emptySet<String>(), parseSuspendingPackages(truncated))
        assertEquals(emptySet<String>(), parseSuspendingPackages(garbage))
    }

    @Test
    fun anAppThatIsSimplyNotSuspendedIsEmptyToo() {
        // The ordinary case, and the reason the sentence above matters: it is indistinguishable from
        // every failure in the previous test, so "empty" can never be spent as evidence of success.
        val dump = """
            Packages:
              Package [com.example.chat] (5f3a1c7):
                userId=10214
                User 0: ceDataInode=1310749 installed=true hidden=false suspended=false distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false
                  gids=[3003]
        """.trimIndent()

        assertEquals(emptySet<String>(), parseSuspendingPackages(dump))
    }

    @Test
    fun rootCanLiftASuspensionItDoesNotOwn() {
        // `enforceCanSetPackagesSuspendedAsUser` early-returns for ROOT_UID before it validates the
        // suspender name, on every level from 28 to main. This is the line that makes the reported
        // bug fixable at all: a Shizuku-era suspension recorded as com.android.shell can be lifted
        // after the user switches Thor to root.
        assertTrue(canLiftSuspension(SHELL_SUSPENDER_IDENTITY, isRoot = true, sdkInt = 37))
        assertTrue(canLiftSuspension(THOR_SUSPENDER_IDENTITY, isRoot = true, sdkInt = 37))
        assertTrue(canLiftSuspension("android", isRoot = true, sdkInt = vanillaIceCream))
        assertTrue(canLiftSuspension(LEGACY_ROOT_SUSPENDER_IDENTITY, isRoot = true, sdkInt = r))
    }

    @Test
    fun shellCanOnlyLiftItsOwnSuspensionsFromApi30() {
        // The reverse direction, and the one that is genuinely unfixable: Shizuku is uid 2000 and
        // `isCallerSameApp` pins it to com.android.shell, so a root-era suspension cannot be lifted
        // in Shizuku mode at all. The requirement is that the UI says so, naming the owner, instead
        // of running an unsuspend that removes nothing and reports success.
        assertTrue(canLiftSuspension(SHELL_SUSPENDER_IDENTITY, isRoot = false, sdkInt = r))
        assertTrue(canLiftSuspension(SHELL_SUSPENDER_IDENTITY, isRoot = false, sdkInt = 37))
        assertFalse(canLiftSuspension(THOR_SUSPENDER_IDENTITY, isRoot = false, sdkInt = r))
        assertFalse(canLiftSuspension(THOR_SUSPENDER_IDENTITY, isRoot = false, sdkInt = 37))
        assertFalse(canLiftSuspension("android", isRoot = false, sdkInt = 34))
        THOR_SUSPENDER_IDENTITIES.forEach {
            assertFalse(
                "$it must not look liftable without root on API 30+",
                canLiftSuspension(it, isRoot = false, sdkInt = r)
            )
        }
    }

    @Test
    fun ownershipIsNotEnforcedBelowApi30() {
        // The version boundary, asserted from both sides. Before R, `setSuspended(false)` clears the
        // single slot whatever the caller is called, so refusing to try there would strand API 28
        // and 29 users on a suspension Thor could actually have lifted.
        assertTrue(canLiftSuspension(THOR_SUSPENDER_IDENTITY, isRoot = false, sdkInt = pie))
        assertTrue(canLiftSuspension(THOR_SUSPENDER_IDENTITY, isRoot = false, sdkInt = q))
        assertTrue(canLiftSuspension("android", isRoot = false, sdkInt = q))
        assertFalse(canLiftSuspension(THOR_SUSPENDER_IDENTITY, isRoot = false, sdkInt = r))
    }

    @Test
    fun thorsOwnIdentitiesIncludeTheOneThisBuildRunsUnder() {
        // The debug build carries applicationIdSuffix = ".debug", so it records a suspender name the
        // constant set does not contain and would not recognise its own suspensions. "root" stays in
        // the set forever: pre-GH#239 builds wrote it, and those suspensions are still on devices.
        assertEquals(setOf("com.valhalla.thor", "root"), THOR_SUSPENDER_IDENTITIES)
        assertTrue(
            thorSuspenderIdentities("com.valhalla.thor.debug")
                .containsAll(THOR_SUSPENDER_IDENTITIES + "com.valhalla.thor.debug")
        )
    }
}
