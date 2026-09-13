// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeSweepWorkerTombstoneTest {

    @Test
    fun `released worker fqcn remains reconstructable`() {
        val source = workerSource()

        assertTrue(source.contains("package com.valhalla.thor.data.freezer"))
        assertTrue(
            Regex("""@KoinWorker\s+internal class PrivilegeSweepWorker\s*\(""")
                .containsMatchIn(source)
        )
    }

    @Test
    fun `released worker terminates without dispatching or mutating a sweep`() {
        val body = workerBody()

        assertTrue(
            "legacy worker must return one bounded terminal failure",
            Regex("""override suspend fun runJob\(\): Result\s*=\s*fail\(""")
                .containsMatchIn(body),
        )
        listOf(
            "runner.run(",
            "executionFence.tryRegister(",
            "store.",
            "executor.execute(",
            "publish(",
            "noteResult(",
            "Result.retry(",
        ).forEach { mutationOrRetry ->
            assertFalse(
                "legacy worker still reaches $mutationOrRetry",
                body.contains(mutationOrRetry),
            )
        }
    }

    private fun workerBody(): String = workerSource().substringAfter(
        "internal class PrivilegeSweepWorker(",
        missingDelimiterValue = "",
    ).also { body ->
        assertTrue("PrivilegeSweepWorker declaration was not found", body.isNotEmpty())
    }

    private fun workerSource(): String = projectFile(
        "app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepWorker.kt"
    ).readText()

    private fun projectFile(relativePath: String): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("could not locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
