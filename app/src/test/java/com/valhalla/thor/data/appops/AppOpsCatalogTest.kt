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
    fun runtimePermissionPolicyRequiresEnabledFlagAndExactRuntimeMapping() {
        val operations = listOf(
            operation(74, "ACCEPT_HANDOVER", permission = "android.permission.ACCEPT_HANDOVER"),
            operation(43, "GET_USAGE_STATS", permission = "android.permission.PACKAGE_USAGE_STATS"),
            operation(29, "READ_CLIPBOARD"),
            operation(200, "HANDOVER_HISTORY", permission = "android.permission.ACCEPT_HANDOVER"),
        )
        val definitions = AppOpsCatalog.fromOperations(AppOpsCatalog.withRuntimePermissionPolicy(
            operations = operations,
            mappingEnabled = true,
            runtimePermissionOpCode = { permission ->
                if (permission == "android.permission.ACCEPT_HANDOVER") 74 else null
            },
        )).associateBy { it.code }

        assertTrue(definitions.getValue(74).isRuntimePermissionControlled)
        assertFalse(definitions.getValue(43).isRuntimePermissionControlled)
        assertFalse(definitions.getValue(29).isRuntimePermissionControlled)
        assertFalse(definitions.getValue(200).isRuntimePermissionControlled)
    }

    @Test
    fun absentRuntimeMappingPolicyDoesNotQueryPermissionsOrRestrictOperations() {
        val definitions = AppOpsCatalog.fromOperations(AppOpsCatalog.withRuntimePermissionPolicy(
            operations = listOf(operation(74, "ACCEPT_HANDOVER", permission = "android.permission.ACCEPT_HANDOVER")),
            mappingEnabled = false,
            runtimePermissionOpCode = { error("Older platforms must not query the runtime mapping") },
        ))

        assertFalse(definitions.single().isRuntimePermissionControlled)
        assertFalse(definitions.single().isRuntimePermissionControlUncertain)
    }

    @Test
    fun unknownRuntimePolicyRestrictsOnlyMatchingControllingOperations() {
        val definitions = AppOpsCatalog.fromOperations(AppOpsCatalog.withRuntimePermissionPolicy(
            operations = listOf(
                operation(74, "ACCEPT_HANDOVER", permission = "android.permission.ACCEPT_HANDOVER"),
                operation(43, "GET_USAGE_STATS", permission = "android.permission.PACKAGE_USAGE_STATS"),
                operation(29, "READ_CLIPBOARD"),
                operation(200, "HANDOVER_HISTORY", permission = "android.permission.ACCEPT_HANDOVER"),
                operation(201, "VENDOR_RUNTIME_ALIAS", switchCode = 29, permission = "android.permission.VENDOR_RUNTIME"),
            ),
            mappingEnabled = null,
            runtimePermissionOpCode = { permission ->
                when (permission) {
                    "android.permission.ACCEPT_HANDOVER" -> 74
                    "android.permission.VENDOR_RUNTIME" -> 201
                    else -> null
                }
            },
        )).associateBy { it.code }

        assertTrue(definitions.getValue(74).isRuntimePermissionControlUncertain)
        assertFalse(definitions.getValue(74).isRuntimePermissionControlled)
        for (code in listOf(43, 29, 200)) {
            assertFalse(definitions.getValue(code).isRuntimePermissionEditBlocked)
        }
    }

    @Test
    fun runtimePermissionEditabilityFollowsTheSwitchUsedByBothWriteScopes() {
        val definitions = AppOpsCatalog.fromOperations(AppOpsCatalog.withRuntimePermissionPolicy(
            operations = listOf(
                operation(0, "COARSE_LOCATION", permission = "android.permission.ACCESS_COARSE_LOCATION"),
                operation(1, "FINE_LOCATION", switchCode = 0, permission = "android.permission.ACCESS_FINE_LOCATION"),
                operation(29, "READ_CLIPBOARD"),
                operation(200, "VENDOR_RUNTIME_ALIAS", switchCode = 29, permission = "android.permission.VENDOR_RUNTIME"),
            ),
            mappingEnabled = true,
            runtimePermissionOpCode = { permission ->
                when (permission) {
                    "android.permission.ACCESS_COARSE_LOCATION" -> 0
                    "android.permission.ACCESS_FINE_LOCATION" -> 1
                    "android.permission.VENDOR_RUNTIME" -> 200
                    else -> null
                }
            },
        )).associateBy { it.code }

        assertTrue(definitions.getValue(0).isRuntimePermissionControlled)
        // Android converts 200 to its switch 29 before consulting the runtime mapping.
        assertFalse(definitions.getValue(29).isRuntimePermissionControlled)
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
