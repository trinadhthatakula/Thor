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
    private val observed = entry(3, "OP_RECORD_AUDIO", observed = true)
    private val unrelated = entry(4, "OP_GPS", aliases = listOf("FINE_LOCATION"))
    private val all = listOf(unrelated, observed, requested, changed)

    @Test
    fun `relevant is the initial subset with changes first`() {
        assertEquals(
            listOf(changed, requested, observed),
            visibleAppOps(all, "", AppOpsFilter.RELEVANT),
        )
    }

    @Test
    fun `all retains unrelated operations after relevant ones`() {
        assertEquals(
            listOf(changed, requested, observed, unrelated),
            visibleAppOps(all, "", AppOpsFilter.ALL),
        )
        assertEquals(listOf(changed), visibleAppOps(all, "", AppOpsFilter.CHANGED))
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
