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
 * "unknown version code" gate — the analyzer yields 0 when it cannot read a code out of the picked
 * file, and 0 loses against every installed app, so an unreadable file read as a downgrade of
 * whatever was installed.
 */
class VersionCompareTest {

    @Test
    fun unknownVersionCodeIsNeverADowngrade() {
        // The regression this guards: 0 is "analyzer could not read a code", not "version zero".
        // Ungated, `0 < 4210` is true and EVERY installed app looks downgraded.
        assertFalse(isVersionDowngrade(newVersionCode = 0L, installedVersionCode = 4210L))
    }

    @Test
    fun negativeVersionCodeIsTreatedAsUnknown() {
        assertFalse(isVersionDowngrade(newVersionCode = -1L, installedVersionCode = 4210L))
    }

    @Test
    fun unknownVersionCodeIsNotADowngradeEvenAgainstAFreshInstall() {
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
        // longVersionCode packs versionCodeMajor into the high 32 bits. Both operands must stay
        // Long: truncating to Int here is what made an earlier revision mis-detect downgrades.
        val installed = (1L shl 32) or 1L
        val newer = (1L shl 32) or 5L
        assertFalse(isVersionDowngrade(newVersionCode = newer, installedVersionCode = installed))
        assertTrue(isVersionDowngrade(newVersionCode = installed, installedVersionCode = newer))
    }

    @Test
    fun newerLookingVersionNameWithLowerCodeIsStillADowngrade() {
        // The com.rebelroot.omni report. Its scheme encodes major/minor/build and DROPS the third
        // component: 1.2.4.7 -> 1002007, 1.2.5.1 -> 1002001. The name reads as an upgrade while the
        // code goes backwards, and Android sequences updates by code alone — so Thor is right to
        // call this a downgrade. Pinned so nobody "fixes" it by comparing version names.
        assertTrue(isVersionDowngrade(newVersionCode = 1002001L, installedVersionCode = 1002007L))
    }
}
