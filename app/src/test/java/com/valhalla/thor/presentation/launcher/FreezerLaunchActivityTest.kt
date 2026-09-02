// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.launcher

import com.valhalla.thor.R
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchRejection
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchResult
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class FreezerLaunchActivityTest {
    @Test
    fun `shortcut reports accepted enqueue without waiting for completion`() {
        val accepted = PrivilegeSweepLaunchResult.Accepted(
            requestId = UUID(0L, 1L),
            workId = UUID(1L, 1L),
            coalesced = false,
        )

        assertEquals(R.string.sweep_queued, shortcutBulkMessageRes(accepted))
    }

    @Test
    fun `shortcut reports notification gate rejection visibly`() {
        val rejected = PrivilegeSweepLaunchResult.Rejected(
            PrivilegeSweepLaunchRejection.NotificationsRequired
        )

        assertEquals(
            R.string.notification_access_needed_subtitle,
            shortcutBulkMessageRes(rejected),
        )
    }
}
