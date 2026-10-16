// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.kernel

enum class KernelSlotSelection(val installerValue: String?) {
    PACKAGE_DEFAULT(null), CURRENT("active"), INACTIVE("inactive"), BOTH(null),
}

data class KernelSlotInfo(val current: String? = null, val isAbDevice: Boolean? = null) {
    val inactive: String? get() = when (current) { "_a" -> "_b"; "_b" -> "_a"; else -> null }
}

internal fun kernelSlotInfo(suffix: String, slot: String, ab: String): KernelSlotInfo {
    val current = if (suffix.isNotEmpty()) {
        suffix.takeIf { it == "_a" || it == "_b" }
    } else when (slot) { "a" -> "_a"; "b" -> "_b"; else -> null }
    return KernelSlotInfo(current, if (current != null) true else ab.trim().toBooleanStrictOrNull())
}

/** Returns a requested target, never a claim about what arbitrary package scripts actually wrote. */
internal fun KernelSlotSelection.target(info: KernelSlotInfo, packageSupportsSlots: Boolean): String? {
    if (this == KernelSlotSelection.PACKAGE_DEFAULT) return null
    check(packageSupportsSlots && info.isAbDevice == true && info.current != null) {
        "Explicit slot selection is unavailable for this device or package."
    }
    return when (this) {
        KernelSlotSelection.CURRENT -> info.current
        KernelSlotSelection.INACTIVE -> checkNotNull(info.inactive)
        KernelSlotSelection.BOTH -> "${info.current}, ${checkNotNull(info.inactive)}"
        KernelSlotSelection.PACKAGE_DEFAULT -> null
    }
}

internal data class KernelFlashPass(val selection: KernelSlotSelection, val target: String?)

internal fun KernelSlotSelection.plan(info: KernelSlotInfo, packageSupportsSlots: Boolean): List<KernelFlashPass> {
    target(info, packageSupportsSlots)
    val selections = if (this == KernelSlotSelection.BOTH) {
        listOf(KernelSlotSelection.CURRENT, KernelSlotSelection.INACTIVE)
    } else listOf(this)
    return selections.map { KernelFlashPass(it, it.target(info, packageSupportsSlots)) }
}

/** A failure, unknown result or exception stops the plan; an entered pass is never retried. */
internal suspend fun executeKernelPasses(
    plan: List<KernelFlashPass>, execute: suspend (Int, KernelFlashPass) -> Int,
): Int {
    require(plan.isNotEmpty())
    plan.forEachIndexed { index, pass ->
        val code = execute(index, pass)
        if (code != 0) return code
    }
    return 0
}

/** Conservative recognition of the ordinary AK3 contract; custom scripts remain trusted root code. */
internal fun supportsKernelSlotSelection(config: String, core: String): Boolean {
    val assignments = Regex("(?m)(?:^|;)\\s*(?:export\\s+)?(IS_SLOT_DEVICE|is_slot_device)\\s*=([^;\\n]*)")
        .findAll(config).toList()
    val assignment = assignments.singleOrNull() ?: return false
    val variable = assignment.groupValues[1]
    val value = assignment.groupValues[2].substringBefore('#').trim()
    if (value !in setOf("1", "auto", "'1'", "'auto'", "\"1\"", "\"auto\"")) return false
    val override = Regex("(?m)(?:^|;)\\s*(?:export\\s+)?SLOT_SELECT\\s*=")
    val mutation = Regex("(?m)(?:^|;)\\s*(?:unset|readonly|local|declare)\\s+[^;\\n]*(?:SLOT_SELECT|IS_SLOT_DEVICE|is_slot_device)")
    val partitions = Regex("(?m)(?:^|;)\\s*(?:export\\s+)?(?:BLOCK|block)\\s*=([^;\\n]*)")
        .findAll(config).toList()
    // Multiple candidates and shell-expanded block paths cannot be resolved statically.
    val partition = partitions.singleOrNull()?.groupValues?.get(1)
        ?.substringBefore('#')?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: return false
    if (!Regex("[A-Za-z0-9_./-]+").matches(partition) || Regex("_[ab]$").containsMatchIn(partition)) return false
    return !override.containsMatchIn(config) && !mutation.containsMatchIn(config) &&
        core.contains("\$SLOT_SELECT") && core.contains("inactive)") && core.contains("\$$variable")
}
