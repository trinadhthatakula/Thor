// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.launcher

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskQueueLaunchActivityTest {

    @Test
    fun `canonical task id is accepted`() {
        assertEquals(TASK_ID, canonicalTaskId(TASK_ID.toString()))
    }

    @Test
    fun `missing malformed and noncanonical task ids are rejected`() {
        assertNull(canonicalTaskId(null))
        assertNull(canonicalTaskId("not-a-uuid"))
        assertNull(canonicalTaskId(" ${TASK_ID} "))
        assertNull(canonicalTaskId(TASK_ID.toString().uppercase()))
    }

    private companion object {
        val TASK_ID: UUID = UUID.fromString("abcdefab-cdef-abcd-efab-cdefabcdefab")
    }
}
