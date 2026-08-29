// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import com.valhalla.thor.data.gateway.root.RootCommand
import com.valhalla.thor.data.gateway.root.RootCommandExecutor
import com.valhalla.thor.data.gateway.root.RootCommandResult
import com.valhalla.thor.domain.gateway.ComponentEnabledState
import com.valhalla.thor.domain.model.GET_INSTALLED_APPS_PERMISSION
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.ShellCommandCancelled
import com.valhalla.thor.domain.model.ShellLaneBusy
import com.valhalla.thor.domain.model.ShellTransportDied
import com.valhalla.thor.presentation.FakeContext
import com.valhalla.thor.presentation.FakePreferenceRepository
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RootSystemGatewayRoutingTest {

    @Test
    fun `Root command classification changes only command class`() {
        val original = execution("archive.caller")
        val classified = original.forRootCommand(PrivilegeCommandClass("package.force-stop"))

        assertEquals(
            original.copy(commandClass = PrivilegeCommandClass("package.force-stop")),
            classified,
        )
    }

    @Test
    fun `archive force stop preserves its semantic class and typed failure identity`() = runTest {
        val failure = ShellTransportDied(PrivilegeExecutionLane.ARCHIVE)
        val executor = RecordingRootCommandExecutor(failure = failure)
        val execution = execution("archive.force_stop").copy(commandTimeout = null)

        val result = gateway(executor).forceStopApp("com.example.target", execution)

        assertSame(failure, result.exceptionOrNull())
        assertEquals(execution, executor.commands.single().execution)
    }

    @Test
    fun `interactive force stop keeps its command class and fallback`() = runTest {
        val executor = RecordingRootCommandExecutor()

        val result = gateway(executor).forceStopApp(
            packageName = "com.example.target",
            execution = PrivilegeExecutionContext(),
        )

        assertTrue(result.isFailure)
        assertEquals(
            PrivilegeCommandClass("package.force-stop"),
            executor.commands.single().execution.commandClass,
        )
    }

    @Test
    fun `raw shell preserves an explicit semantic class and converts the result`() = runTest {
        val executor = RecordingRootCommandExecutor(
            result = RootCommandResult(7, listOf("visible"), listOf("fallback")),
        )
        val execution = execution("archive.tar")

        val result = gateway(executor).executeShellCommand("sensitive command", execution)

        assertEquals(7 to "visible", result.getOrThrow())
        assertEquals(execution, executor.commands.single().execution)
    }

    @Test
    fun `raw shell assigns its fallback class only to an unclassified caller`() = runTest {
        val executor = RecordingRootCommandExecutor()

        gateway(executor).executeShellCommand("true", PrivilegeExecutionContext()).getOrThrow()

        assertEquals(
            PrivilegeCommandClass("extension.raw-shell"),
            executor.commands.single().execution.commandClass,
        )
    }

    @Test
    fun `clear all caches preserves caller metadata on both Root commands`() = runTest {
        val executor = RecordingRootCommandExecutor()
        val execution = execution("caller.clear-all")

        gateway(executor).clearAllCaches(42L, execution).getOrThrow()

        assertEquals(
            listOf(
                execution.copy(commandClass = PrivilegeCommandClass("cache.trim")),
                execution.copy(commandClass = PrivilegeCommandClass("cache.sweep")),
            ),
            executor.commands.map { it.execution },
        )
    }

    @Test
    fun `install session preserves caller metadata`() = runTest {
        val apk = File.createTempFile("thor-routing", ".apk").apply { writeText("apk") }
        try {
            val executor = RecordingRootCommandExecutor()
            val execution = execution("caller.install")

            gateway(executor).installApp(
                apkPath = apk.absolutePath,
                canDowngrade = true,
                grantAllPermissions = null,
                execution = execution,
            ).getOrThrow()

            assertEquals(
                execution.copy(commandClass = PrivilegeCommandClass("package.install-session")),
                executor.commands.single().execution,
            )
        } finally {
            apk.delete()
        }
    }

    @Test
    fun `permission app-op probe preserves context and throws typed routing failure`() = runTest {
        val failure = ShellLaneBusy(PrivilegeExecutionLane.ARCHIVE)
        val executor = RecordingRootCommandExecutor(failure = failure)
        val execution = execution("caller.permission")
        val gateway = gateway(executor).also {
            it.packageUserIdProvider = { 10 }
        }

        val caught = try {
            gateway.grantPermission(
                packageName = "com.example.target",
                permissionName = GET_INSTALLED_APPS_PERMISSION,
                execution = execution,
            )
            null
        } catch (actual: Throwable) {
            actual
        }

        assertSame(failure, caught)
        assertEquals(2, executor.commands.size)
        assertEquals(
            execution.copy(commandClass = PrivilegeCommandClass("permission.app-op-grant")),
            executor.commands.last().execution,
        )
    }

    @Test
    fun `ordinary Root command returns a typed routing failure unchanged`() = runTest {
        val failure = ShellLaneBusy(PrivilegeExecutionLane.ARCHIVE)
        val executor = RecordingRootCommandExecutor(failure = failure)
        val execution = execution("caller.reboot")

        val result = gateway(executor).rebootDevice("review", execution)

        assertSame(failure, result.exceptionOrNull())
        assertEquals(
            execution.copy(commandClass = PrivilegeCommandClass("device.reboot")),
            executor.commands.single().execution,
        )
    }

    @Test
    fun `component command returns a typed transport failure unchanged`() = runTest {
        val failure = ShellTransportDied(PrivilegeExecutionLane.SWEEP)
        val executor = RecordingRootCommandExecutor(failure = failure)
        val execution = execution("caller.component")

        val result = gateway(executor).setComponentEnabled(
            packageName = "com.example.target",
            className = "com.example.target.FeatureReceiver",
            state = ComponentEnabledState.DISABLED,
            userId = 10,
            execution = execution,
        )

        assertSame(failure, result.exceptionOrNull())
        assertEquals(
            execution.copy(commandClass = PrivilegeCommandClass("component.state")),
            executor.commands.single().execution,
        )
    }

    @Test
    fun `Root copy throws the original typed transport failure`() = runTest {
        val failure = ShellTransportDied(PrivilegeExecutionLane.ARCHIVE)
        val executor = RecordingRootCommandExecutor(failure = failure)
        val execution = execution("archive.copy")

        val caught = try {
            gateway(executor).copyFile("/private/source", "/private/destination", execution)
            null
        } catch (actual: Throwable) {
            actual
        }

        assertSame(failure, caught)
        assertEquals(
            execution.copy(commandClass = PrivilegeCommandClass("file.copy")),
            executor.commands.single().execution,
        )
    }

    @Test
    fun `Root cancellation remains structured cancellation`() = runTest {
        val cancellation = ShellCommandCancelled(
            PrivilegeCommandClass("device.reboot"),
            CancellationException("cancelled by caller"),
        )
        val executor = RecordingRootCommandExecutor(failure = cancellation)

        val caught = try {
            gateway(executor).rebootDevice("review", execution("caller.reboot"))
            null
        } catch (actual: CancellationException) {
            actual
        }

        assertSame(cancellation, caught)
    }

    @Test
    fun `Root availability uses caller metadata and does not collapse typed failure`() = runTest {
        val execution = execution("caller.preflight")
        val availableExecutor = RecordingRootCommandExecutor(
            result = RootCommandResult(0, listOf("0"), emptyList()),
        )

        assertTrue(gateway(availableExecutor).isRootAvailable(execution))
        assertEquals(
            execution.copy(commandClass = PrivilegeCommandClass("root.availability")),
            availableExecutor.commands.single().execution,
        )

        val failure = ShellLaneBusy(PrivilegeExecutionLane.ARCHIVE)
        val failedExecutor = RecordingRootCommandExecutor(failure = failure)
        val caught = try {
            gateway(failedExecutor).isRootAvailable(execution)
            null
        } catch (actual: Throwable) {
            actual
        }
        assertSame(failure, caught)
    }

    @Test
    fun `nonzero Root command failure redacts command output and arguments`() = runTest {
        val executor = RecordingRootCommandExecutor(
            result = RootCommandResult(19, emptyList(), listOf("private-output")),
        )

        val failure = gateway(executor)
            .rebootDevice("private-reason", execution("caller.reboot"))
            .exceptionOrNull()
        assertNotNull("expected a failed command result", failure)

        val message = requireNotNull(failure).message.orEmpty()
        assertFalse(message.contains("private-output"))
        assertFalse(message.contains("private-reason"))
        assertFalse(message.contains("svc power reboot"))
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

    private fun execution(commandClass: String) = PrivilegeExecutionContext(
        lane = PrivilegeExecutionLane.ARCHIVE,
        commandClass = PrivilegeCommandClass(commandClass),
        packageName = "com.example.target",
        workRequestId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        sweepRequestId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        commandTimeout = 23.seconds,
    )

    private fun gateway(executor: RootCommandExecutor) = RootSystemGateway(
        context = FakeContext(File(".")),
        rootCommands = executor,
        preferenceRepository = FakePreferenceRepository(),
        ioDispatcher = Dispatchers.Unconfined,
    ).also { gateway ->
        gateway.userIdProvider = { 0 }
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

    private class RecordingRootCommandExecutor(
        private val result: RootCommandResult = RootCommandResult(0, emptyList(), emptyList()),
        private val failure: Throwable? = null,
    ) : RootCommandExecutor {
        val commands = mutableListOf<RootCommand>()

        override suspend fun execute(command: RootCommand): RootCommandResult {
            commands += command
            failure?.let { throw it }
            return result
        }
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
