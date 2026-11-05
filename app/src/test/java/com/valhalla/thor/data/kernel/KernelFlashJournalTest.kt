// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.kernel

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KernelFlashJournalTest {
    private fun record(phase: KernelFlashPhase, boot: String? = "boot-one") =
        KernelFlashRecord(phase, boot, "kernel.zip", "digest")

    @Test fun `process death during dispatch becomes unknown and cannot accept another ZIP`() {
        val recovered = recoverKernelFlash(record(KernelFlashPhase.FLASHING), "boot-one")
        assertEquals(KernelFlashPhase.UNKNOWN, recovered)
        assertFalse(KernelFlashState(phase = recovered, dispatched = true).canSelect)
    }

    @Test fun `missing boot identity cannot unlock an uncertain flash`() {
        assertEquals(KernelFlashPhase.UNKNOWN, recoverKernelFlash(record(KernelFlashPhase.FLASHING), null))
        assertEquals(KernelFlashPhase.UNKNOWN, recoverKernelFlash(record(KernelFlashPhase.UNKNOWN, null), "boot-two"))
    }

    @Test fun `a later kernel boot permits a new user initiated operation`() {
        assertEquals(KernelFlashPhase.IDLE, recoverKernelFlash(record(KernelFlashPhase.UNKNOWN), "boot-two"))
        assertEquals(KernelFlashPhase.IDLE, recoverKernelFlash(record(KernelFlashPhase.SUCCESS), "boot-two"))
    }

    @Test fun `terminal receipts survive recreation without allowing another same boot flash`() {
        for (phase in listOf(KernelFlashPhase.SUCCESS, KernelFlashPhase.FAILED)) {
            assertEquals(phase, recoverKernelFlash(record(phase), "boot-one"))
            assertFalse(KernelFlashState(phase = phase, dispatched = true).canSelect)
        }
    }

    @Test fun `unexpected persisted preflight phase fails closed`() {
        assertEquals(KernelFlashPhase.UNKNOWN, recoverKernelFlash(record(KernelFlashPhase.STARTING), "boot-one"))
    }

    @Test fun `older receipts retain compatibility with no pass records`() {
        val old = """{"phase":"SUCCESS","bootId":"boot-one","filename":"kernel.zip","sha256":"digest"}"""
        assertEquals(record(KernelFlashPhase.SUCCESS), Json.decodeFromString<KernelFlashRecord>(old))
    }

    @Test fun `partial both slot receipt round trips and remains locked`() {
        val partial = record(KernelFlashPhase.FAILED).copy(
            slotSelection = KernelSlotSelection.BOTH, targetSlot = "_b, _a",
            plannedSlots = listOf("_b", "_a"),
            passes = listOf(
                KernelFlashPassRecord("_b", KernelFlashPhase.SUCCESS, 0),
                KernelFlashPassRecord("_a", KernelFlashPhase.FAILED, 17),
            ), exitCode = 17,
        )
        val recovered = Json.decodeFromString<KernelFlashRecord>(Json.encodeToString(partial))
        assertEquals(partial, recovered)
        assertEquals(KernelFlashPhase.FAILED, recoverKernelFlash(recovered, "boot-one"))
    }

    @Test fun `crash between passes preserves first outcome without resuming`() {
        val between = record(KernelFlashPhase.FLASHING).copy(
            slotSelection = KernelSlotSelection.BOTH, plannedSlots = listOf("_a", "_b"),
            passes = listOf(KernelFlashPassRecord("_a", KernelFlashPhase.SUCCESS, 0)),
        )
        val recovered = Json.decodeFromString<KernelFlashRecord>(Json.encodeToString(between))
        assertEquals(KernelFlashPhase.UNKNOWN, recoverKernelFlash(recovered, "boot-one"))
        assertEquals(listOf(KernelFlashPassRecord("_a", KernelFlashPhase.SUCCESS, 0)), recovered.passes)
    }

    @Test fun `lost owner marks only in flight pass unknown and preserves earlier results`() {
        val first = KernelFlashPassRecord("_a", KernelFlashPhase.SUCCESS, 0)
        assertEquals(listOf(first, KernelFlashPassRecord("_b", KernelFlashPhase.UNKNOWN)), recoverKernelPasses(
            listOf(first, KernelFlashPassRecord("_b", KernelFlashPhase.FLASHING)),
        ))
        assertEquals(listOf(first), recoverKernelPasses(listOf(first)))
    }
}
