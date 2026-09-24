// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.appops

import com.valhalla.thor.domain.model.AppOpDefinition
import com.valhalla.thor.domain.model.AppOpMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOpsParserTest {
    private val definitions = listOf(
        AppOpDefinition(0, "COARSE_LOCATION", "android:coarse_location",
            listOf("FINE_LOCATION", "android:fine_location", "GPS"),
            listOf("android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"), AppOpMode.ALLOW, true),
        AppOpDefinition(26, "CAMERA", "android:camera", emptyList(), listOf("android.permission.CAMERA"), AppOpMode.ALLOW, true),
        AppOpDefinition(63, "RUN_IN_BACKGROUND", "android:run_in_background", emptyList(), emptyList(), AppOpMode.ALLOW, true),
        AppOpDefinition(119, "ACCESS_RESTRICTED_SETTINGS", "android:access_restricted_settings", emptyList(), emptyList(), AppOpMode.DEFAULT, true),
    )

    @Test
    fun parsesApi28IndependentPackageAndUidDumpsWithHistory() {
        val entries = parse(
            """
                CAMERA: allow; time=+1m12s30ms ago; duration=+10s
                ACCESS_RESTRICTED_SETTINGS: default; rejectTime=+4d23h6m26s81ms ago
            """.trimIndent(),
            "CAMERA: foreground",
        )

        val camera = entries.single { it.definition.code == 26 }
        assertEquals(AppOpMode.ALLOW, camera.packageMode)
        assertEquals(AppOpMode.FOREGROUND, camera.uidMode)
        assertEquals(AppOpMode.FOREGROUND, camera.displayedMode)
        assertTrue(camera.observed)
        assertTrue(camera.isChanged)
        assertEquals(AppOpMode.DEFAULT, entries.last().packageMode)
    }

    @Test
    fun api36PrefixAppliesToAllUidEntriesUntilPackageBoundary() {
        val entries = parse(
            """
                Uid mode: COARSE_LOCATION: ignore
                CAMERA: foreground
                RUN_IN_BACKGROUND: ignore
                CAMERA: allow; time=+1s ago (running)
                ACCESS_RESTRICTED_SETTINGS: default; rejectTime=0 ago
            """.trimIndent(),
            """
                Uid mode: COARSE_LOCATION: ignore
                CAMERA: foreground
                RUN_IN_BACKGROUND: ignore
            """.trimIndent(),
        )

        val camera = entries.single { it.definition.code == 26 }
        assertEquals(AppOpMode.ALLOW, camera.packageMode)
        assertEquals(AppOpMode.FOREGROUND, camera.uidMode)
        assertNull(entries.first().packageMode)
        assertEquals(AppOpMode.IGNORE, entries.first().uidMode)
        assertNull(entries.single { it.definition.code == 63 }.packageMode)
    }

    @Test
    fun api36UidOnlyDumpDoesNotInventPackageModes() {
        val output = "Uid mode: CAMERA: ignore\nRUN_IN_BACKGROUND: foreground"
        val entries = parse(output, output)

        assertTrue(entries.all { it.packageMode == null })
        assertEquals(AppOpMode.IGNORE, entries.single { it.definition.code == 26 }.uidMode)
    }

    @Test
    fun explicitNoOperationsUsesDeviceDefaultsAndPermissionRelevance() {
        val entries = parse("No operations.", "No operations.\nDefault mode: allow",
            setOf("android.permission.ACCESS_FINE_LOCATION"))

        assertTrue(entries.first().isRelevant)
        assertFalse(entries.first().observed)
        assertFalse(entries.first().isChanged)
        assertNull(entries.first().packageMode)
        assertNull(entries.first().uidMode)
        assertEquals(AppOpMode.DEFAULT, entries.last().displayedMode)
        assertFalse(entries.last().isRelevant)
    }

    @Test
    fun aliasHistoryMarksRelevanceWithoutOverwritingControllerMode() {
        val entries = parse("COARSE_LOCATION: ignore\nFINE_LOCATION: allow; time=+2s ago\nGPS: allow (running)")
        val location = entries.first()

        assertEquals(AppOpMode.IGNORE, location.packageMode)
        assertTrue(location.observed)
        assertTrue(location.isRelevant)
        assertEquals(1, entries.count { it.observed })
    }

    @Test
    fun aliasOnlyHistoryLeavesAbsentControllerAtPlatformDefault() {
        val location = parse("GPS: allow; duration=+12ms").first()

        assertNull(location.packageMode)
        assertEquals(AppOpMode.ALLOW, location.displayedMode)
        assertTrue(location.observed)
    }

    @Test
    fun preservesLiteralDefaultModeAndParsesDenySynonyms() {
        assertEquals(AppOpMode.DEFAULT, parse("CAMERA: default").single { it.definition.code == 26 }.packageMode)
        listOf("deny", "error", "errored").forEach { mode ->
            assertEquals(AppOpMode.DENY, parse("CAMERA: $mode").single { it.definition.code == 26 }.packageMode)
        }
    }

    @Test
    fun uidDefaultDoesNotOverridePackageSetting() {
        val camera = parse("CAMERA: ignore", "CAMERA: allow").single { it.definition.code == 26 }

        assertFalse(camera.hasUidOverride)
        assertEquals(AppOpMode.IGNORE, camera.displayedMode)
    }

    @Test
    fun rejectsConcurrentUidChangesInsteadOfMislabelingScope() {
        listOf(
            "Uid mode: CAMERA: ignore" to "Uid mode: CAMERA: foreground",
            "Uid mode: CAMERA: ignore" to "No operations.",
            "CAMERA: allow" to "Uid mode: CAMERA: ignore",
            "Uid mode: CAMERA: ignore" to "Uid mode: CAMERA: ignore\nRUN_IN_BACKGROUND: ignore",
        ).forEach { (packages, uids) ->
            assertThrows(IllegalArgumentException::class.java) { parse(packages, uids) }
        }
    }

    @Test
    fun rejectsFailuresMalformedOutputAndUnsupportedModes() {
        listOf(
            "", "Error: No UID for com.example.app in user 0", "Security exception: denied",
            "CAMERA: mode=7", "CAMERA: allow; something=unexpected", "CAMERA: allow\nError: failed",
            "No operations.\nCAMERA: allow", "No operations.\nDefault mode: oem_mode",
            "CAMERA: allow\nCAMERA: ignore", "UNKNOWN_OPERATION: allow",
        ).forEach { output ->
            assertThrows("Accepted malformed response: $output", IllegalArgumentException::class.java) { parse(output) }
        }
    }

    @Test
    fun rejectsUnknownUidOperationEvenWithKnownPackageHistory() {
        assertThrows(IllegalArgumentException::class.java) { parse("CAMERA: allow", "OEM_UNKNOWN: ignore") }
    }

    @Test
    fun xiaomiVendorRecordsDoNotHideStandardModesOrBecomeEditableOperations() {
        val result = AppOpsParser.parseSnapshot(
            "CAMERA: foreground; time=+2s ago\nMIUIOP(10004): ignore\nMIUIOP(10017): ask",
            "No operations.", definitions, emptySet(),
        )

        assertEquals(2, result.unsupportedOperationCount)
        assertEquals(definitions.size, result.entries.size)
        assertEquals(AppOpMode.FOREGROUND, result.entries.single { it.definition.code == 26 }.packageMode)
    }

    @Test
    fun vendorUidRecordsStayInBoundaryComparisonAndCountOnlyOnceAcrossScopes() {
        val result = AppOpsParser.parseSnapshot(
            """
                Uid mode: MIUIOP(10004): ask
                CAMERA: ignore
                MIUIOP(10017): ignore
                CAMERA: allow
                MIUIOP(10004): ignore; time=+1s ago
            """.trimIndent(),
            "Uid mode: MIUIOP(10004): ask\nCAMERA: ignore\nMIUIOP(10017): ignore",
            definitions, emptySet(),
        )

        assertEquals(2, result.unsupportedOperationCount)
        val camera = result.entries.single { it.definition.code == 26 }
        assertEquals(AppOpMode.IGNORE, camera.uidMode)
        assertEquals(AppOpMode.ALLOW, camera.packageMode)
    }

    @Test
    fun vendorOnlyUidDumpDoesNotInventPackageEntries() {
        val output = "Uid mode: MIUIOP(10004): ask\nMIUIOP(10017): ignore"
        val result = AppOpsParser.parseSnapshot(output, output, definitions, emptySet())

        assertEquals(2, result.unsupportedOperationCount)
        assertTrue(result.entries.all { it.packageMode == null && it.uidMode == null && !it.observed })
    }

    @Test
    fun differentUnsupportedVendorModesStillDetectConcurrentUidChanges() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("Uid mode: MIUIOP(10004): ask", "Uid mode: MIUIOP(10004): vendor")
        }
    }

    @Test
    fun rejectsVendorCollisionsDuplicatesAndMalformedRecords() {
        listOf(
            "MIUIOP(26): ask", "MIUIOP(9999): ignore", "MIUIOP(-10004): ask",
            "MIUIOP(99999999999999999999): ask", "OTHEROP(10004): ignore",
            "MIUIOP(10004): ask; unexpected=1", "MIUIOP(10004): ask\nMIUIOP(10004): ignore",
            "MIUIOP(10004): ask\nMIUIOP(010004): ignore", "CAMERA: ask\nMIUIOP(10004): ask",
            "UNKNOWN_OPERATION: allow\nMIUIOP(10004): ask",
        ).forEach { output ->
            assertThrows("Accepted unsafe vendor response: $output", IllegalArgumentException::class.java) {
                parse(output)
            }
        }
    }

    @Test
    fun vendorWrappersCannotConcealKnownControllersOrNumericAliases() {
        listOf(
            definitions + definitions[1].copy(code = 10004, debugName = "VENDOR_CAMERA", publicName = null),
            listOf(definitions[1].copy(aliasCodes = listOf(10004))),
            listOf(definitions[1].copy(aliases = listOf("MIUIOP(10004)"))),
        ).forEach { catalog ->
            assertThrows(IllegalArgumentException::class.java) {
                AppOpsParser.parseSnapshot("MIUIOP(10004): ask", "No operations.", catalog, emptySet())
            }
        }
    }

    @Test
    fun numericAliasHistoryDoesNotOverwriteControllerMode() {
        val result = AppOpsParser.parseSnapshot(
            "0: ignore\n1: allow", "No operations.",
            listOf(definitions.first().copy(aliasCodes = listOf(1, 2))), emptySet(),
        )

        assertEquals(AppOpMode.IGNORE, result.entries.single().packageMode)
        assertTrue(result.entries.single().observed)
        assertEquals(0, result.unsupportedOperationCount)
    }

    private fun parse(
        packages: String,
        uids: String = "No operations.",
        permissions: Set<String> = emptySet(),
    ) = AppOpsParser.parseSnapshot(packages, uids, definitions, permissions).entries
}
