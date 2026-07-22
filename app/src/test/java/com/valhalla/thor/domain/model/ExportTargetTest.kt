// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportTargetTest {

    @Test
    fun noSavedDir_usesDownloads_noClear() {
        val r = resolveExportTarget(savedUri = null, isSavedValid = false)
        assertEquals(ExportTargetChoice.Downloads, r.choice)
        assertFalse(r.clearSavedDir)
    }

    @Test
    fun savedValid_usesCustom_noClear() {
        val r = resolveExportTarget(savedUri = "content://tree/abc", isSavedValid = true)
        assertEquals(ExportTargetChoice.Custom("content://tree/abc"), r.choice)
        assertFalse(r.clearSavedDir)
    }

    @Test
    fun savedInvalid_fallsBackToDownloads_andClears() {
        val r = resolveExportTarget(savedUri = "content://tree/gone", isSavedValid = false)
        assertEquals(ExportTargetChoice.Downloads, r.choice)
        assertTrue(r.clearSavedDir)
    }
}
