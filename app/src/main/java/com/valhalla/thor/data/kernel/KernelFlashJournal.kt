// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.kernel

import kotlinx.serialization.Serializable

enum class KernelFlashPhase { LOADING, IDLE, PREPARING, READY, STARTING, FLASHING, SUCCESS, FAILED, UNKNOWN }

@Serializable
data class KernelFlashPassRecord(
    val targetSlot: String?,
    val phase: KernelFlashPhase,
    val exitCode: Int? = null,
)

@Serializable
data class KernelFlashRecord(
    val phase: KernelFlashPhase,
    val bootId: String?,
    val filename: String,
    val sha256: String,
    val exitCode: Int? = null,
    val slotSelection: KernelSlotSelection = KernelSlotSelection.PACKAGE_DEFAULT,
    val targetSlot: String? = null,
    val passes: List<KernelFlashPassRecord> = emptyList(),
    val plannedSlots: List<String?> = emptyList(),
)

/** A lost owner cannot claim that an unfinished pass is still being observed. */
internal fun recoverKernelPasses(passes: List<KernelFlashPassRecord>): List<KernelFlashPassRecord> =
    passes.map { if (it.phase == KernelFlashPhase.FLASHING) it.copy(phase = KernelFlashPhase.UNKNOWN) else it }

/** A missing receipt is never evidence that boot partition writes stopped or rolled back. */
internal fun recoverKernelFlash(record: KernelFlashRecord, bootId: String?): KernelFlashPhase =
    if (bootId != null && record.bootId != null && bootId != record.bootId) {
        KernelFlashPhase.IDLE
    } else when (record.phase) {
        KernelFlashPhase.SUCCESS, KernelFlashPhase.FAILED -> record.phase
        else -> KernelFlashPhase.UNKNOWN
    }

data class KernelFlashState(
    val phase: KernelFlashPhase = KernelFlashPhase.LOADING,
    val filename: String = "",
    val sha256: String = "",
    val bytes: Long = 0,
    val log: List<String> = emptyList(),
    val error: String? = null,
    val dispatched: Boolean = false,
    val slotInfo: KernelSlotInfo = KernelSlotInfo(),
    val supportsSlotSelection: Boolean = false,
    val slotSelection: KernelSlotSelection = KernelSlotSelection.PACKAGE_DEFAULT,
    val targetSlot: String? = null,
    val passes: List<KernelFlashPassRecord> = emptyList(),
) {
    val busy: Boolean get() = phase in setOf(
        KernelFlashPhase.LOADING, KernelFlashPhase.PREPARING, KernelFlashPhase.STARTING, KernelFlashPhase.FLASHING,
    )
    val canSelect: Boolean get() = !busy && !dispatched
}
