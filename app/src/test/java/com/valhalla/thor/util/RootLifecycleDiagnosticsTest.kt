// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.util

import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.RootConfirmation
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import com.valhalla.thor.domain.model.RootRefreshStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootLifecycleDiagnosticsTest {
    @Test
    fun `release gate does not call the output sink`() {
        writeRootLifecycleEvent(false, RootLifecycleEvent.Binding(RootBindingPhase.CONNECTED)) {
            throw AssertionError("A normal release must never write lifecycle diagnostics")
        }
    }

    @Test
    fun `development output is a bounded versioned line of allowlisted fields`() {
        val events = RootBindingPhase.entries.map { RootLifecycleEvent.Binding(it) } +
            RootShellPhase.entries.map { RootLifecycleEvent.Shell(PrivilegeExecutionLane.ARCHIVE, it, Long.MAX_VALUE) } +
            RootRefreshPhase.entries.map { RootLifecycleEvent.Refresh(it, Long.MAX_VALUE, Long.MAX_VALUE,
                RootConfirmation.UNKNOWN, RootRefreshStatus.TIMED_OUT) } +
            RootJobOutcomeKind.entries.map { RootLifecycleEvent.IsolatedOutcome(PrivilegeExecutionLane.SWEEP,
                it, true, false, false, false) } +
            RootLanePhase.entries.map { RootLifecycleEvent.Lane(PrivilegeExecutionLane.SWEEP, it) }
        val lines = mutableListOf<String>()
        events.forEach { writeRootLifecycleEvent(true, it, lines::add) }
        assertEquals(events.size, lines.size)
        for (line in lines) {
            assertTrue(line, line.startsWith("v=1 event="))
            assertTrue(line, line.length <= 256)
            assertTrue(line, line.matches(Regex("[A-Za-z0-9_= ]+")))
        }
    }
}
