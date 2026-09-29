// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.AppOpDefinition
import com.valhalla.thor.domain.model.AppOpMode
import com.valhalla.thor.domain.model.AppOpScope
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.ShellCommandTimedOut
import com.valhalla.thor.domain.model.ShellLaneBusy
import com.valhalla.thor.domain.model.ShellTransportDied
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOpsCatalogProviderTest {
    @Test
    fun `known reflected policy needs no shell query and is cached`() = runTest {
        for (enabled in listOf(true, false)) {
            var builds = 0
            val provider = AppOpsCatalogProvider({ enabled }) {
                builds++
                listOf(definitionForPolicy(it))
            }
            val session = AppOpsCommandSession { error("Known reflection needs no policy shell query") }
            val first = provider.load(session)

            assertEquals(enabled, first.single().isRuntimePermissionControlled)
            assertFalse(first.single().isRuntimePermissionControlUncertain)
            assertSame(first, provider.load(session))
            assertEquals(1, builds)
        }
    }

    @Test
    fun `missing app flag class uses active aflags value before device config and caches it`() = runTest {
        for ((mode, expected) in listOf("enabled" to true, "disabled" to false)) {
            val commands = mutableListOf<String>()
            val provider = provider()
            val session = AppOpsCommandSession { command ->
                commands += command
                check(command == AppOpsCatalogProvider.AFLAGS_COMMAND)
                0 to row(mode)
            }

            val first = provider.load(session)
            assertEquals(expected, first.single().isRuntimePermissionControlled)
            assertSame(first, provider.load(session))
            assertEquals(listOf(AppOpsCatalogProvider.AFLAGS_COMMAND), commands)
        }
    }

    @Test
    fun `unavailable aflags falls back to the exact full device config key`() = runTest {
        val commands = mutableListOf<String>()
        val provider = provider()
        val definitions = provider.load(AppOpsCommandSession { command ->
            commands += command
            if (command == AppOpsCatalogProvider.AFLAGS_COMMAND) 1 to "must be root"
            else 0 to "true\n"
        })

        assertTrue(definitions.single().isRuntimePermissionControlled)
        assertEquals(listOf(
            AppOpsCatalogProvider.AFLAGS_COMMAND,
            "device_config get permissions android.permission.flags.runtime_permission_appops_mapping_enabled",
        ), commands)
    }

    @Test
    fun `unknown or malformed policy is not cached as disabled`() = runTest {
        for (value in listOf("null", "", "TRUE", "true\nfalse", "Permission denied")) {
            var commands = 0
            val provider = provider()
            val session = AppOpsCommandSession { command ->
                commands++
                if (command == AppOpsCatalogProvider.AFLAGS_COMMAND) 0 to "unrelated.flag enabled"
                else 0 to value
            }

            repeat(2) {
                val definition = provider.load(session).single()
                assertFalse(definition.isRuntimePermissionControlled)
                assertTrue(definition.isRuntimePermissionControlUncertain)
            }
            assertEquals(4, commands)
        }
    }

    @Test
    fun `aflags parser rejects duplicate partial staged or similarly named records`() {
        for (invalid in listOf(
            "${row("enabled")}\n${row("disabled")}",
            "${AppOpsCatalogProvider.POLICY_KEY} enabled",
            "${AppOpsCatalogProvider.POLICY_KEY}.other enabled - default read-only system",
            row("true"),
            row("enabled").replace(" - ", " (->disabled) "),
            row("enabled").replace("read-only", "read-write"),
            row("enabled").replace("system", "vendor"),
        )) {
            assertNull(AppOpsCatalogProvider.parseAflagsPolicy(invalid))
        }
    }

    @Test
    fun `lane transport timeout and cancellation errors escape without fallback queries`() = runTest {
        for (failure in listOf(
            ShellLaneBusy(PrivilegeExecutionLane.INTERACTIVE),
            ShellTransportDied(PrivilegeExecutionLane.INTERACTIVE),
            ShellCommandTimedOut(PrivilegeCommandClass("app_ops.manage")),
            CancellationException("stop"),
        )) {
            var commands = 0
            val provider = provider()
            val result = runCatching {
                provider.load(AppOpsCommandSession { commands++; throw failure })
            }

            assertSame(failure, result.exceptionOrNull())
            assertEquals(1, commands)
        }
    }

    @Test
    fun `write uses its session for policy and refuses runtime changes before any appops command`() = runTest {
        val commands = mutableListOf<String>()
        val provider = provider()
        val controller = AppOpsController(
            currentUserId = { 0 },
            loadTarget = { AppOpsTarget(12345, emptySet(), emptyList()) },
            loadCatalog = provider::load,
            openSession = {
                AppOpsCommandSession { command -> commands += command; 0 to row("enabled") }
            },
        )

        assertTrue(controller.setMode("com.example.app", 74, AppOpScope.UID, AppOpMode.ALLOW).isFailure)
        assertEquals(listOf(AppOpsCatalogProvider.AFLAGS_COMMAND), commands)
    }

    @Test
    fun `unresolved policy refuses writes before any appops command`() = runTest {
        val commands = mutableListOf<String>()
        val controller = AppOpsController(
            currentUserId = { 0 },
            loadTarget = { AppOpsTarget(12345, emptySet(), emptyList()) },
            loadCatalog = provider()::load,
            openSession = {
                AppOpsCommandSession { command ->
                    commands += command
                    0 to if (command == AppOpsCatalogProvider.AFLAGS_COMMAND) "unrelated.flag enabled" else "null"
                }
            },
        )

        assertTrue(controller.setMode("com.example.app", 74, AppOpScope.UID, AppOpMode.ALLOW).isFailure)
        assertEquals(listOf(
            AppOpsCatalogProvider.AFLAGS_COMMAND,
            AppOpsCatalogProvider.DEVICE_CONFIG_COMMAND,
        ), commands)
    }

    private fun provider() = AppOpsCatalogProvider({ null }) {
        listOf(definitionForPolicy(it))
    }

    private fun definitionForPolicy(policy: Boolean?) = DEFINITION.copy(
        isRuntimePermissionControlled = policy == true,
        isRuntimePermissionControlUncertain = policy == null,
    )

    private fun row(mode: String) = "${AppOpsCatalogProvider.POLICY_KEY} $mode - default read-only system"

    companion object {
        private val DEFINITION = AppOpDefinition(
            74, "ACCEPT_HANDOVER", "android:accept_handover", emptyList(),
            listOf("android.permission.ACCEPT_HANDOVER"), AppOpMode.ALLOW, true,
        )
    }
}
