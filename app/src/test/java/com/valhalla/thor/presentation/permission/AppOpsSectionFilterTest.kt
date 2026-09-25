// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.permission

import com.valhalla.thor.domain.model.AppOpDefinition
import com.valhalla.thor.domain.model.AppOpEntry
import com.valhalla.thor.domain.model.AppOpMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AppOpsSectionFilterTest {
    private val changed = entry(1, "OP_READ_CLIPBOARD", packageMode = AppOpMode.IGNORE)
    private val requested = entry(
        2,
        "OP_CAMERA",
        relatedPermissions = listOf("android.permission.CAMERA"),
        permissionRequested = true,
    )
    private val observed = entry(3, "OP_RUN_IN_BACKGROUND", observed = true)
    private val unrelated = entry(4, "OP_GPS", aliases = listOf("FINE_LOCATION"))
    private val undeclaredObserved = entry(
        5,
        "OP_RECORD_AUDIO",
        relatedPermissions = listOf("android.permission.RECORD_AUDIO"),
        observed = true,
    )
    private val undeclaredChanged = entry(
        6,
        "OP_ACCEPT_HANDOVER",
        relatedPermissions = listOf("android.permission.ACCEPT_HANDOVER"),
        packageMode = AppOpMode.IGNORE,
        observed = true,
    )
    private val all = listOf(unrelated, undeclaredChanged, observed, undeclaredObserved, requested, changed)

    @Test
    fun `relevant includes declared permissions and observed or changed operations without permissions`() {
        assertEquals(
            listOf(changed, requested, observed),
            visibleAppOps(all, "", AppOpsFilter.RELEVANT),
        )
    }

    @Test
    fun `all retains undeclared operations after relevant ones and changed still exposes overrides`() {
        assertEquals(
            listOf(changed, requested, observed, undeclaredChanged, undeclaredObserved, unrelated),
            visibleAppOps(all, "", AppOpsFilter.ALL),
        )
        assertEquals(listOf(changed, undeclaredChanged), visibleAppOps(all, "", AppOpsFilter.CHANGED))
    }

    @Test
    fun `search covers readable name permission and aliases`() {
        assertEquals("Read Clipboard", appOpDisplayName(changed.definition))
        assertEquals(listOf(changed), visibleAppOps(all, "read clipboard", AppOpsFilter.ALL))
        assertEquals(listOf(requested), visibleAppOps(all, "android.permission.CAMERA", AppOpsFilter.ALL))
        assertEquals(listOf(unrelated), visibleAppOps(all, "fine_location", AppOpsFilter.ALL))
        assertEquals(emptyList<AppOpEntry>(), visibleAppOps(all, "gps", AppOpsFilter.RELEVANT))
    }

    @Test
    fun `search does not make an undeclared permission relevant but all and changed retain it`() {
        val permission = "android.permission.ACCEPT_HANDOVER"
        assertEquals(emptyList<AppOpEntry>(), visibleAppOps(all, permission, AppOpsFilter.RELEVANT))
        assertEquals(listOf(undeclaredChanged), visibleAppOps(all, permission, AppOpsFilter.ALL))
        assertEquals(listOf(undeclaredChanged), visibleAppOps(all, permission, AppOpsFilter.CHANGED))
    }

    @Test
    fun `a UID override does not make an undeclared permission relevant`() {
        val uidOverride = undeclaredObserved.copy(uidMode = AppOpMode.IGNORE, observed = false)
        assertEquals(emptyList<AppOpEntry>(), visibleAppOps(listOf(uidOverride), "", AppOpsFilter.RELEVANT))
        assertEquals(listOf(uidOverride), visibleAppOps(listOf(uidOverride), "", AppOpsFilter.ALL))
        assertEquals(listOf(uidOverride), visibleAppOps(listOf(uidOverride), "", AppOpsFilter.CHANGED))

        val nowRequested = uidOverride.copy(permissionRequested = true)
        assertEquals(listOf(nowRequested), visibleAppOps(listOf(nowRequested), "", AppOpsFilter.RELEVANT))
    }

    @Test
    fun `linked operations show debug aliases without duplicate public names`() {
        val definition = requested.definition.copy(
            aliases = listOf("FINE_LOCATION", "android:fine_location", "GPS", "android:gps", "GPS"),
        )
        assertEquals(listOf("FINE_LOCATION", "GPS"), groupedAliasDebugNames(definition))
    }

    private fun entry(
        code: Int,
        debugName: String,
        relatedPermissions: List<String> = emptyList(),
        aliases: List<String> = emptyList(),
        packageMode: AppOpMode? = null,
        observed: Boolean = false,
        permissionRequested: Boolean = false,
    ) = AppOpEntry(
        definition = AppOpDefinition(
            code = code,
            debugName = debugName,
            publicName = null,
            aliases = aliases,
            relatedPermissions = relatedPermissions,
            platformDefault = AppOpMode.ALLOW,
            allowsReset = true,
        ),
        packageMode = packageMode,
        uidMode = null,
        observed = observed,
        permissionRequested = permissionRequested,
    )
}
