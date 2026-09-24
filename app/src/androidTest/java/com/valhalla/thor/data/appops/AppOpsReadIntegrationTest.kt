// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.appops

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.domain.repository.AppOpsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Opt-in, read-only diagnostic of the production catalog and Root/Shizuku gateway.
 * Pass appOpsReadTest=true with Thor already authorized. Only Thor's own App Ops
 * are read; no fixture or operation-mode changes are needed.
 */
@RunWith(AndroidJUnit4::class)
class AppOpsReadIntegrationTest {
    @Test
    fun readThorAppOpsThroughProductionRepository() = runBlocking<Unit> {
        assumeTrue(
            "Explicit read-only App Ops opt-in required",
            InstrumentationRegistry.getArguments().getString("appOpsReadTest") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        // Resolve the real application graph without substituting a test repository.
        val repository = requireNotNull(GlobalContext.get().getOrNull<AppOpsRepository>()) {
            "ThorApplication must load the production AppOpsRepository binding"
        }
        val snapshot = repository.getAppOps(context.packageName).getOrThrow()
        assertEquals("Snapshot must belong to Thor", Process.myUid(), snapshot.uid)
        assertTrue("Device catalog must contain operations", snapshot.entries.isNotEmpty())
        assertEquals(
            "Aliases must not produce duplicate controls",
            snapshot.entries.size,
            snapshot.entries.map { it.definition.code }.distinct().size,
        )
        val expectedMapping = InstrumentationRegistry.getArguments()
            .getString("appOpsExpectRuntimeMapping")?.toBooleanStrict()
        if (expectedMapping != null) {
            assertEquals(
                "Handover must follow the device's runtime-permission policy",
                expectedMapping,
                snapshot.entries.single { it.definition.debugName == "ACCEPT_HANDOVER" }
                    .definition.isRuntimePermissionControlled,
            )
            assertEquals(
                "Usage access must remain independently editable",
                false,
                snapshot.entries.single { it.definition.debugName == "GET_USAGE_STATS" }
                    .definition.isRuntimePermissionControlled,
            )
        }
    }
}
