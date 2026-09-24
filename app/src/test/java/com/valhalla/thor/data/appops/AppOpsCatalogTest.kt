// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.appops

import com.valhalla.thor.domain.model.AppOpMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOpsCatalogTest {
    @Test
    fun groupsAliasesByDeviceSwitchAndKeepsTheirPermissions() {
        val definitions = AppOpsCatalog.fromOperations(listOf(
            operation(0, "COARSE_LOCATION", permission = "android.permission.ACCESS_COARSE_LOCATION"),
            operation(1, "FINE_LOCATION", switchCode = 0, permission = "android.permission.ACCESS_FINE_LOCATION"),
            operation(2, "GPS", switchCode = 0, publicName = null),
            operation(99, "OEM_OPERATION", defaultMode = AppOpMode.IGNORE),
        ))

        assertEquals(listOf(0, 99), definitions.map { it.code })
        val location = definitions.first()
        assertEquals("COARSE_LOCATION", location.debugName)
        assertEquals(listOf("FINE_LOCATION", "android:fine_location", "GPS"), location.aliases)
        assertEquals(listOf(1, 2), location.aliasCodes)
        assertEquals(listOf("android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"), location.relatedPermissions)
        assertEquals(AppOpMode.IGNORE, definitions.last().platformDefault)
    }

    @Test
    fun resetUsesControllerMetadataAndUnknownDefaultCannotBeReset() {
        val definitions = AppOpsCatalog.fromOperations(listOf(
            operation(0, "COARSE_LOCATION", defaultMode = AppOpMode.ALLOW, allowsReset = false),
            operation(1, "FINE_LOCATION", switchCode = 0, defaultMode = AppOpMode.DEFAULT),
            operation(99, "OEM_OPERATION", defaultMode = AppOpMode.UNKNOWN),
        ))

        assertEquals(AppOpMode.ALLOW, definitions.first().platformDefault)
        assertFalse(definitions.first().allowsReset)
        assertFalse(definitions.last().allowsReset)
    }

    @Test
    fun preservesLiteralDefaultAsDifferentFromAllow() {
        val definition = AppOpsCatalog.fromOperations(listOf(
            operation(23, "SYSTEM_ALERT_WINDOW", defaultMode = AppOpMode.DEFAULT),
        )).single()

        assertEquals(AppOpMode.DEFAULT, definition.platformDefault)
        assertTrue(definition.allowsReset)
    }

    @Test
    fun skipsAndroid36RemovedSlotWithoutRenumberingLaterOperations() {
        val definitions = AppOpsCatalog.fromOperations(listOf(
            operation(95, "LOADER_USAGE_STATS"),
            operation(96, "", switchCode = -1, publicName = "", defaultMode = AppOpMode.IGNORE),
            operation(97, "AUTO_REVOKE_PERMISSIONS_IF_UNUSED", defaultMode = AppOpMode.DEFAULT),
        ))

        assertEquals(listOf(95, 97), definitions.map { it.code })
        assertEquals("AUTO_REVOKE_PERMISSIONS_IF_UNUSED", definitions.last().debugName)
        assertEquals(AppOpMode.DEFAULT, definitions.last().platformDefault)
    }

    @Test
    fun retainsNamedOperationsWithoutPublicNamesOnOlderAndroid() {
        val definition = AppOpsCatalog.fromOperations(listOf(
            operation(46, "PROJECT_MEDIA", publicName = null),
        )).single()

        assertEquals(46, definition.code)
        assertEquals("PROJECT_MEDIA", definition.debugName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsActiveOperationWhoseControllerIsARemovedSlot() {
        AppOpsCatalog.fromOperations(listOf(
            operation(96, "", switchCode = -1, publicName = "", defaultMode = AppOpMode.IGNORE),
            operation(97, "OEM_OPERATION", switchCode = 96),
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unnamedActiveOperationIsNotMistakenForARemovedSlot() {
        AppOpsCatalog.fromOperations(listOf(operation(97, "", publicName = "android:oem_operation")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingController() {
        AppOpsCatalog.fromOperations(listOf(operation(1, "FINE_LOCATION", switchCode = 0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSwitchChainsRatherThanChoosingTheWrongWriteTarget() {
        AppOpsCatalog.fromOperations(listOf(
            operation(0, "COARSE_LOCATION"),
            operation(1, "FINE_LOCATION", switchCode = 0),
            operation(2, "GPS", switchCode = 1),
        ))
    }

    private fun operation(
        code: Int,
        name: String,
        switchCode: Int = code,
        publicName: String? = "android:${name.lowercase()}",
        permission: String? = null,
        defaultMode: AppOpMode = AppOpMode.ALLOW,
        allowsReset: Boolean = true,
    ) = CatalogOperation(code, switchCode, name, publicName, permission, defaultMode, allowsReset)
}
