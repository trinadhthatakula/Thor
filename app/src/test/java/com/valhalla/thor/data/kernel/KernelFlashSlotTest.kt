// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.kernel

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class KernelFlashSlotTest {
    private val core = """
        if [ "${'$'}IS_SLOT_DEVICE" = 1 ]; then
          case "${'$'}SLOT_SELECT" in inactive) SLOT=_b;; esac
        fi
    """.trimIndent()

    @Test fun `properties normalize the AK3 current slot`() {
        assertEquals(KernelSlotInfo("_a", true), kernelSlotInfo("_a", "", "true"))
        assertEquals(KernelSlotInfo("_b", true), kernelSlotInfo("", "b", ""))
        assertEquals(KernelSlotInfo(null, false), kernelSlotInfo("", "", "false"))
        assertEquals(KernelSlotInfo(), kernelSlotInfo("", "", ""))
    }

    @Test fun `unrecognized nonempty suffix cannot fall back differently from AK3`() {
        assertNull(kernelSlotInfo("_c", "a", "true").current)
        assertNull(kernelSlotInfo("none", "b", "true").current)
        assertNull(kernelSlotInfo("a", "", "true").current)
        assertNull(kernelSlotInfo("", "_a", "true").current)
    }

    @Test fun `current and inactive resolve opposite targets without changing boot slot`() {
        for ((current, inactive) in listOf("_a" to "_b", "_b" to "_a")) {
            val info = KernelSlotInfo(current, true)
            assertEquals(current, KernelSlotSelection.CURRENT.target(info, true))
            assertEquals(inactive, KernelSlotSelection.INACTIVE.target(info, true))
            assertNull(KernelSlotSelection.PACKAGE_DEFAULT.target(info, true))
        }
    }

    @Test fun `explicit targets require known AB slot and package support`() {
        for (slot in listOf(KernelSlotSelection.CURRENT, KernelSlotSelection.INACTIVE, KernelSlotSelection.BOTH)) {
            assertThrows(IllegalStateException::class.java) { slot.target(KernelSlotInfo(), true) }
            assertThrows(IllegalStateException::class.java) { slot.target(KernelSlotInfo("_a", false), true) }
            assertThrows(IllegalStateException::class.java) { slot.target(KernelSlotInfo("_a", true), false) }
        }
        assertNull(KernelSlotSelection.PACKAGE_DEFAULT.target(KernelSlotInfo(), false))
    }

    @Test fun `both slots always plans current then inactive with concrete targets`() {
        for ((current, inactive) in listOf("_a" to "_b", "_b" to "_a")) {
            val info = KernelSlotInfo(current, true)
            assertEquals(listOf(
                KernelFlashPass(KernelSlotSelection.CURRENT, current),
                KernelFlashPass(KernelSlotSelection.INACTIVE, inactive),
            ), KernelSlotSelection.BOTH.plan(info, true))
        }
        assertThrows(IllegalStateException::class.java) { KernelSlotSelection.BOTH.plan(KernelSlotInfo(), true) }
        assertThrows(IllegalStateException::class.java) { KernelSlotSelection.BOTH.plan(KernelSlotInfo("_a", true), false) }
    }

    @Test fun `single target plans never add another pass`() {
        val info = KernelSlotInfo("_b", true)
        for (selection in listOf(KernelSlotSelection.PACKAGE_DEFAULT, KernelSlotSelection.CURRENT, KernelSlotSelection.INACTIVE)) {
            assertEquals(listOf(KernelFlashPass(selection, selection.target(info, true))), selection.plan(info, true))
        }
    }

    @Test fun `both successful passes execute once in order`() = runBlocking {
        val plan = KernelSlotSelection.BOTH.plan(KernelSlotInfo("_a", true), true)
        val entered = mutableListOf<Pair<Int, KernelFlashPass>>()
        assertEquals(0, executeKernelPasses(plan) { index, pass -> entered += index to pass; 0 })
        assertEquals(plan.mapIndexed { index, pass -> index to pass }, entered)
    }

    @Test fun `first failure or unknown stops without retry or second pass`() = runBlocking {
        val plan = KernelSlotSelection.BOTH.plan(KernelSlotInfo("_a", true), true)
        for (failure in listOf(17, -1, 125)) {
            val entered = mutableListOf<KernelFlashPass>()
            assertEquals(failure, executeKernelPasses(plan) { _, pass -> entered += pass; failure })
            assertEquals(listOf(plan.first()), entered)
        }
    }

    @Test fun `second failure retains successful first callback result`() = runBlocking {
        val plan = KernelSlotSelection.BOTH.plan(KernelSlotInfo("_b", true), true)
        val observed = mutableListOf<Pair<String?, Int>>()
        assertEquals(17, executeKernelPasses(plan) { index, pass ->
            val code = if (index == 0) 0 else 17
            observed += pass.target to code
            code
        })
        assertEquals(listOf("_b" to 0, "_a" to 17), observed)
    }

    @Test fun `exception never retries an entered pass or starts the next`() {
        val plan = KernelSlotSelection.BOTH.plan(KernelSlotInfo("_a", true), true)
        for (throwAt in 0..1) {
            val entered = mutableListOf<Int>()
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    executeKernelPasses(plan) { index, _ ->
                        entered += index
                        check(index != throwAt) { "fixture interrupted" }
                        0
                    }
                }
            }
            assertEquals((0..throwAt).toList(), entered)
        }
    }

    @Test fun `standard literal slot contract is recognized`() {
        for (value in listOf("1", "auto", "'1'", "\"auto\"")) {
            assertTrue(supportsKernelSlotSelection("IS_SLOT_DEVICE=$value;\nBLOCK=auto;", core))
        }
        assertTrue(supportsKernelSlotSelection("is_slot_device=1; block=boot;", core.replace("IS_SLOT_DEVICE", "is_slot_device")))
    }

    @Test fun `conflicting dynamic or mismatched declarations disable explicit choice`() {
        val configs = listOf(
            "IS_SLOT_DEVICE=1; IS_SLOT_DEVICE=0; BLOCK=auto;",
            "IS_SLOT_DEVICE=1;\nIS_SLOT_DEVICE=1;\nBLOCK=auto;",
            "is_slot_device=1; BLOCK=auto;",
            "IS_SLOT_DEVICE=${'$'}enabled; BLOCK=auto;",
            "IS_SLOT_DEVICE=0; BLOCK=auto;",
            "IS_SLOT_DEVICE=1; BLOCK=auto; unset SLOT_SELECT;",
            "IS_SLOT_DEVICE=1; BLOCK=auto; readonly SLOT_SELECT=active;",
            "IS_SLOT_DEVICE=1; BLOCK=auto; unset IS_SLOT_DEVICE;",
            "# IS_SLOT_DEVICE=1;\nBLOCK=auto;",
            "IS_SLOT_DEVICE=1; BLOCK=boot; SLOT_SELECT=active;",
            "IS_SLOT_DEVICE=1; BLOCK=boot;\nexport SLOT_SELECT=inactive;",
        )
        for (config in configs) assertFalse(config, supportsKernelSlotSelection(config, core))
        assertFalse(supportsKernelSlotSelection("IS_SLOT_DEVICE=1; BLOCK=auto;", "# old core"))
    }

    @Test fun `fixed partitions candidate lists or expanded block paths disable explicit choice`() {
        for (block in listOf("/dev/block/by-name/boot_a", "'boot_b'", "'boot_a boot'", "'boot boot_b'", "\"${'$'}device\"")) {
            assertFalse(block, supportsKernelSlotSelection("IS_SLOT_DEVICE=1; BLOCK=$block;", core))
        }
        assertFalse(supportsKernelSlotSelection("IS_SLOT_DEVICE=1; BLOCK=auto; BLOCK=boot;", core))
    }
}
