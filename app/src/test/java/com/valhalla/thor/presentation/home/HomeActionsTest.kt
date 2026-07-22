// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.home

import com.valhalla.thor.presentation.home.components.HomeAction.CLEAR_CACHE
import com.valhalla.thor.presentation.home.components.HomeAction.EXTENSIONS
import com.valhalla.thor.presentation.home.components.HomeAction.INSTALL
import com.valhalla.thor.presentation.home.components.HomeAction.REINSTALL
import com.valhalla.thor.presentation.home.components.homeActionRows
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeActionsTest {
    @Test fun noPrivilege_onlyInstallFullWidth() {
        assertEquals(listOf(listOf(INSTALL)), homeActionRows(reinstallVisible = false, isRoot = false, hasPrivilege = false))
    }

    @Test fun shizukuOrDhizuku_withReinstall_extensionsFullWidth() {
        assertEquals(
            listOf(listOf(REINSTALL, INSTALL), listOf(EXTENSIONS)),
            homeActionRows(reinstallVisible = true, isRoot = false, hasPrivilege = true)
        )
    }

    @Test fun shizukuOrDhizuku_noReinstall() {
        assertEquals(
            listOf(listOf(INSTALL), listOf(EXTENSIONS)),
            homeActionRows(reinstallVisible = false, isRoot = false, hasPrivilege = true)
        )
    }

    @Test fun root_withReinstall_fullGrid() {
        assertEquals(
            listOf(listOf(REINSTALL, INSTALL), listOf(CLEAR_CACHE, EXTENSIONS)),
            homeActionRows(reinstallVisible = true, isRoot = true, hasPrivilege = true)
        )
    }

    @Test fun root_noReinstall() {
        assertEquals(
            listOf(listOf(INSTALL), listOf(CLEAR_CACHE, EXTENSIONS)),
            homeActionRows(reinstallVisible = false, isRoot = true, hasPrivilege = true)
        )
    }
}
