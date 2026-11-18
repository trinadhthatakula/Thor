// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.kernel

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises only the generated command with host fixtures; never acquires a Shell or invokes su. */
class KernelFlashRunnerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `newline free output is bounded before collection and installer failure is preserved`() {
        val result = runFixture(
            """
                count=0
                while [ "${'$'}count" -lt 8193 ]; do
                  printf x
                  count=${'$'}((count + 1))
                done
                exit 17
            """.trimIndent(),
        )
        assertEquals(17, result.code)
        val lines = result.output.lineSequence().toList()
        assertTrue("Output must be split before reaching the app", lines.size > 4)
        assertTrue("No output line may exceed 1024 bytes", lines.all { it.length <= 1024 })
        assertEquals("The entire output must be drained", "x".repeat(8193), lines.joinToString(""))
        assertFalse("FIFO is removed after completion", File(result.directory, "output.pipe").exists())
    }

    @Test
    fun `success preserves both output streams and exits zero`() {
        val result = runFixture("printf 'stdout message\\n'; printf 'stderr message\\n' >&2; exit 0")
        assertEquals(0, result.code)
        assertEquals(listOf("stdout message", "stderr message"), result.output.lineSequence().filter { it.isNotEmpty() }.toList())
        assertFalse(File(result.directory, "output.pipe").exists())
    }

    @Test
    fun `workdir and executable paths with spaces and apostrophes remain literal`() {
        val result = runFixture(
            """
                [ "${'$'}1" = 3 ] || exit 61
                [ "${'$'}2" = 1 ] || exit 62
                [ "${'$'}3" = "${'$'}PWD/package.zip" ] || exit 63
                [ "${'$'}AKHOME" = "${'$'}PWD/unpacked" ] || exit 64
                [ -f "${'$'}3" ] || exit 65
                printf 'quoted paths preserved\n'
                exit 0
            """.trimIndent(),
            directoryName = "kernel work's directory",
        )
        assertEquals(0, result.code)
        assertEquals("quoted paths preserved\n", result.output)
        assertFalse(File(result.directory, "output.pipe").exists())
    }

    @Test
    fun `failed output drain cannot be reported as a successful installation`() {
        val result = runFixture("exit 0", failReader = true)
        assertEquals(125, result.code)
        assertFalse(File(result.directory, "output.pipe").exists())
    }

    @Test
    fun `explicit selection exports only the AK3 slot contract`() {
        for ((selection, expected) in listOf(KernelSlotSelection.CURRENT to "active", KernelSlotSelection.INACTIVE to "inactive")) {
            val result = runFixture("printf '%s' \"${'$'}SLOT_SELECT\"", directoryName = selection.name, slot = selection)
            assertEquals(0, result.code)
            assertEquals(expected, result.output.trimEnd())
        }
    }

    @Test
    fun `package default clears an inherited slot override`() {
        val result = runFixture("printf '%s' \"${'$'}{SLOT_SELECT-unset}\"")
        assertEquals(0, result.code)
        assertEquals("unset", result.output.trimEnd())
    }

    @Test
    fun `short output reaches the reader before the installer exits`() {
        val result = runFixture(
            """
                printf 'ready\n'
                count=0
                while [ ! -f "${'$'}PWD/output-observed" ]; do
                  [ "${'$'}count" -lt 100 ] || exit 77
                  /bin/sleep 0.02
                  count=${'$'}((count + 1))
                done
                printf 'acknowledged\n'
            """.trimIndent(),
            releaseOnOutput = true,
        )
        assertEquals(0, result.code)
        assertEquals("ready\nacknowledged\n", result.output)
    }

    private data class Result(val code: Int, val output: String, val directory: File)

    @Test fun `both selection cannot be dispatched as a single installer command`() {
        assertThrows(IllegalArgumentException::class.java) {
            KernelFlashRunner.command(temporary.root, "/fixture/busybox", KernelSlotSelection.BOTH)
        }
    }

    @Test fun `separate passes use fresh work trees with the same quoted archive`() {
        val archive = temporary.newFile("shared package's.zip").apply { writeText("original fixture bytes") }
        for (slot in listOf(KernelSlotSelection.CURRENT, KernelSlotSelection.INACTIVE)) {
            val result = runFixture(
                """
                    [ "${'$'}3" != "${'$'}PWD/package.zip" ] || exit 71
                    [ -f "${'$'}3" ] || exit 72
                    [ ! -e "${'$'}AKHOME" ] || exit 73
                    /bin/mkdir "${'$'}AKHOME" || exit 74
                    printf '%s\n' "${'$'}SLOT_SELECT"
                """.trimIndent(),
                directoryName = "fresh-${slot.name}", slot = slot, archive = archive,
            )
            assertEquals(0, result.code)
            assertEquals(slot.installerValue + "\n", result.output)
        }
        assertEquals("original fixture bytes", archive.readText())
    }

    private fun runFixture(
        updater: String,
        directoryName: String = "kernel-work",
        failReader: Boolean = false,
        releaseOnOutput: Boolean = false,
        slot: KernelSlotSelection = KernelSlotSelection.PACKAGE_DEFAULT,
        archive: File? = null,
    ): Result {
        val executables = listOf("/bin/sh", "/bin/bash", "/bin/sleep", "/usr/bin/mkfifo", "/bin/rm")
        assumeTrue("Requires Unix fixture executables", executables.all { File(it).canExecute() })
        val directory = temporary.newFolder(directoryName)
        assertTrue(File(directory, "bootstrap").mkdir())
        File(directory, "package.zip").writeText("harmless fixture: never opened or executed")
        File(directory, "update-binary").writeText("#!/bin/sh\n$updater\n")
        val busybox = File(directory, "fake BusyBox's executable")
        busybox.writeText(
            """
                #!/bin/sh
                applet=${'$'}1
                shift
                case "${'$'}applet" in
                  id) printf '0\n' ;;
                  ash)
                    [ "${'$'}THOR_TEST_FAIL_READER" = 1 ] && [ "${'$'}1" = -c ] && exit 23
                    exec /bin/bash "${'$'}@"
                    ;;
                  mkfifo) exec /usr/bin/mkfifo "${'$'}@" ;;
                  rm) exec /bin/rm "${'$'}@" ;;
                  *) printf 'Unexpected fixture applet: %s\n' "${'$'}applet" >&2; exit 99 ;;
                esac
            """.trimIndent() + "\n",
        )
        assertTrue("Make only the harmless fixture executable", busybox.setExecutable(true, true))
        val command = KernelFlashRunner.command(directory, busybox.absolutePath, slot, archive ?: File(directory, "package.zip"))
        val process = ProcessBuilder("/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .apply {
                environment()["LC_ALL"] = "C"
                environment()["SLOT_SELECT"] = "inherited-untrusted-value"
                environment()["THOR_TEST_FAIL_READER"] = if (failReader) "1" else "0"
            }
            .start()
        process.outputStream.close()
        val reader = Executors.newSingleThreadExecutor { task ->
            Thread(task, "kernel-fixture-output").apply { isDaemon = true }
        }
        val output = reader.submit<String> {
            process.inputStream.use { input ->
                val captured = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (releaseOnOutput) File(directory, "output-observed").writeText("observed")
                    // Continue draining even if a broken command unexpectedly emits too much.
                    val remaining = 64 * 1024 - captured.size()
                    if (remaining > 0) captured.write(buffer, 0, minOf(count, remaining))
                }
                captured.toString(Charsets.UTF_8.name())
            }
        }
        try {
            assertTrue("Fixture command timed out", process.waitFor(10, TimeUnit.SECONDS))
            return Result(process.exitValue(), output.get(2, TimeUnit.SECONDS), directory)
        } finally {
            // A broken FIFO command must not strand a child process or block the test worker.
            if (process.isAlive) {
                destroyHostDescendants(process)
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            process.inputStream.close()
            output.cancel(true)
            reader.shutdownNow()
            reader.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private fun destroyHostDescendants(process: Process) {
        // Android's compile stubs omit ProcessHandle even though these tests run on a host JDK.
        // Invoke its public interface reflectively; never use platform kill commands or su.
        runCatching {
            val handle = Process::class.java.getMethod("toHandle").invoke(process)
            val handleType = Class.forName("java.lang.ProcessHandle")
            val stream = handleType.getMethod("descendants").invoke(handle) as java.util.stream.Stream<*>
            val descendants = stream.use { it.iterator().asSequence().toList() }
            val destroy = handleType.getMethod("destroyForcibly")
            descendants.asReversed().forEach { destroy.invoke(it) }
        }
    }
}
