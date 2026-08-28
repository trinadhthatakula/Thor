// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootSystemGatewayRoutingTest {

    @Test
    fun `Root command classification changes only command class`() {
        val original = PrivilegeExecutionContext(
            lane = PrivilegeExecutionLane.ARCHIVE,
            commandClass = PrivilegeCommandClass("archive.caller"),
            packageName = "com.example.target",
            workRequestId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            sweepRequestId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            commandTimeout = 23.seconds,
        )
        val classified = original.forRootCommand(PrivilegeCommandClass("package.force-stop"))

        assertEquals(
            original.copy(commandClass = PrivilegeCommandClass("package.force-stop")),
            classified,
        )
    }

    @Test
    fun `RootSystemGateway has no direct Odin command submission`() {
        val source = productionSource(ROOT_SYSTEM_GATEWAY)
        val offenders = DIRECT_ODIN_CALLS.filter { it in source }

        assertTrue(
            "RootSystemGateway bypasses RootCommandExecutor through $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `MainShellCommandExecutor is the only ShellRepository owner`() {
        val sources = productionKotlinSources()
        val owners = sources
            .filterValues { SHELL_REPOSITORY_REFERENCE.containsMatchIn(it) }
            .keys
            .map { it.invariantSeparatorsPath.substringAfter("app/src/main/java/") }
            .sorted()

        assertEquals(
            "ShellRepository must stay behind the reviewed MainShell transport seam",
            listOf(MAIN_SHELL_EXECUTOR),
            owners,
        )
    }

    @Test
    fun `Root command failures never record raw commands`() {
        val source = productionSource(ROOT_SYSTEM_GATEWAY)
        val offenders = RAW_COMMAND_EXPOSURES.filter { it in source }

        assertTrue(
            "RootSystemGateway exposes raw command text through $offenders",
            offenders.isEmpty(),
        )
    }

    private fun productionSource(pathSuffix: String): String {
        val matches = productionKotlinSources().filterKeys {
            it.invariantSeparatorsPath.endsWith(pathSuffix)
        }
        assertEquals(
            "expected exactly one production source ending in $pathSuffix",
            1,
            matches.size
        )
        return matches.values.single()
    }

    private fun productionKotlinSources(): Map<File, String> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val root = File(dir, "app/src/main/java")
            if (root.isDirectory) {
                val files = root.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .toList()
                assertTrue("production Kotlin source sweep is unexpectedly small", files.size >= 20)
                return files.associateWith(File::readText)
            }
            dir = dir.parentFile
        }
        error("could not locate app/src/main/java from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val ROOT_SYSTEM_GATEWAY =
            "com/valhalla/thor/data/gateway/RootSystemGateway.kt"
        const val MAIN_SHELL_EXECUTOR =
            "com/valhalla/thor/data/gateway/root/MainShellCommandExecutor.kt"

        val DIRECT_ODIN_CALLS = listOf(
            "shellRepository.exec(",
            "shellRepository.submit(",
            "shellRepository.enqueue(",
        )
        val SHELL_REPOSITORY_REFERENCE =
            Regex("""private\s+val\s+shellRepository\s*:\s*ShellRepository\b""")
        val RAW_COMMAND_EXPOSURES = listOf(
            "Component command failed: \$cmd",
            "Command execution failed: \$cmd",
            "Shell command failed with code \${result.code}: \$cmd",
            "pm trim-caches \$targetFreeBytes failed:",
            "APK file is missing or empty: \$it",
            "Root copy failed: \$command",
            "recorded.joinToString()",
            "remaining.joinToString()",
        )
    }
}
