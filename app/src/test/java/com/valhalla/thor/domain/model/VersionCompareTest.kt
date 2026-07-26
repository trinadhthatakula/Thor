// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the installer's downgrade predicate.
 *
 * Context: a community report of "the Portable Installer treats an upgrade as a downgrade". The
 * comparison itself was already versionCode-based and correct; what was missing was the
 * "unknown version code" gate — the analyzer yields null when it cannot read a code out of the
 * picked file, and any number substituted for it loses against every installed app, so an
 * unreadable file read as a downgrade of whatever was installed.
 */
class VersionCompareTest {

    @Test
    fun unknownVersionCodeIsNeverADowngrade() {
        // The regression this guards: "analyzer could not read a code" is null, and ungated it
        // would have to be some number — whereupon EVERY installed app looks downgraded.
        assertFalse(isVersionDowngrade(newVersionCode = null, installedVersionCode = 4210L))
    }

    @Test
    fun realVersionCodeZeroIsStillCompared() {
        // The counterpart to the test above, and the reason unknown is null rather than 0: the
        // platform's APK parser defaults a manifest with no `android:versionCode` to zero, and
        // Android sequences that value numerically like any other. Installing it over 4210 is a
        // genuine downgrade the OS will refuse, so Thor has to warn instead of silently passing it
        // off as "unknown, probably fine".
        assertTrue(isVersionDowngrade(newVersionCode = 0L, installedVersionCode = 4210L))
    }

    @Test
    fun unknownAgainstAZeroInstallIsNotADowngrade() {
        // Not the fresh-install case — the caller short-circuits on `existing == null` before it
        // ever gets here. This is "unreadable file over an installed app that declared no version
        // code at all", where there is nothing to compare and nothing to warn about.
        assertFalse(isVersionDowngrade(newVersionCode = null, installedVersionCode = 0L))
    }

    @Test
    fun zeroOverZeroIsNotADowngrade() {
        // Two version-code-less APKs: equal, so a reinstall rather than a downgrade.
        assertFalse(isVersionDowngrade(newVersionCode = 0L, installedVersionCode = 0L))
    }

    @Test
    fun strictlyLowerKnownCodeIsADowngrade() {
        assertTrue(isVersionDowngrade(newVersionCode = 4200L, installedVersionCode = 4210L))
    }

    @Test
    fun equalCodeIsNotADowngrade() {
        // Android permits same-versionCode reinstall; only a strictly lower code is a downgrade.
        assertFalse(isVersionDowngrade(newVersionCode = 4210L, installedVersionCode = 4210L))
    }

    @Test
    fun higherCodeIsNotADowngrade() {
        assertFalse(isVersionDowngrade(newVersionCode = 4300L, installedVersionCode = 4210L))
    }

    @Test
    fun versionCodeMajorIsRespected() {
        // longVersionCode packs versionCodeMajor into the HIGH 32 bits, so both operands must stay
        // Long. The operands are picked so an Int-truncating implementation INVERTS the verdict:
        // (1L shl 32) truncates to 0, so a truncating compare asks "5 < 0" and answers false where
        // the truth is "5 < 4294967296" — a downgrade. Operands that survive truncation (e.g.
        // 0x1_00000001 vs 0x1_00000005, which truncate to 1 and 5 and keep their order) would pin
        // nothing at all.
        val installedWithMajor = 1L shl 32 // versionCodeMajor = 1, versionCode = 0
        assertTrue(
            isVersionDowngrade(newVersionCode = 5L, installedVersionCode = installedWithMajor)
        )
        // Converse: bumping versionCodeMajor is an upgrade, not a downgrade.
        assertFalse(
            isVersionDowngrade(newVersionCode = installedWithMajor, installedVersionCode = 5L)
        )
    }

    @Test
    fun newerLookingVersionNameWithLowerCodeIsStillADowngrade() {
        // Pinned so nobody "fixes" the com.rebelroot.omni report by comparing version NAMES.
        //
        // Real numbers, read out of github.com/REBEL-ROOT/omni-browser at the two release tags the
        // reporter named — this is not reverse-engineered:
        //
        //   v1.2.4.6  versionName "1.2.4.6"  versionCode 26
        //   v1.2.4.7  versionName "1.2.4.7"  versionCode 27   <- installed
        //   v1.2.5.1  versionName "1.2.5.1"  versionCode 25   <- picked
        //   1.2.6.0   versionName "1.2.6"    versionCode 25
        //
        // Their versionCode is hand-maintained and simply went backwards. Android sequences
        // updates by code alone, so 25 over 27 is a downgrade and the platform installer refuses
        // it too — Thor is not wrong here, the upstream app is. (Their v1.2.3.4 also shipped
        // versionCode 1002004 from a scheme they abandoned, which is why a 1002005-style code
        // shows up in old listings of this app.)
        assertTrue(isVersionDowngrade(newVersionCode = 25L, installedVersionCode = 27L))
    }
}
