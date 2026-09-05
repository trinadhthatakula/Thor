// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.service

import com.valhalla.thor.domain.model.THOR_JOB_CHAIN
import com.valhalla.thor.domain.model.THOR_SWEEP_CHAIN
import com.valhalla.thor.domain.model.ThorJobKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoNewWorkRequestArchitectureTest {

    @Test
    fun `only the released data compatibility boundary can enqueue WorkRequests`() {
        val allowed = setOf(
            "com/valhalla/thor/data/backup/job/ThorJobLauncher.kt",
        )
        val forbidden = listOf(
            "OneTimeWorkRequestBuilder",
            "PeriodicWorkRequestBuilder",
            "beginUniqueWork",
            "enqueueUniqueWork",
        )

        val violations = productionSources()
            .filterNot { source -> source.relativePath in allowed }
            .flatMap { source ->
                val code = source.file.readText().withoutComments()
                forbidden.mapNotNull { call ->
                    call.takeIf(code::contains)?.let { "${source.relativePath}: $it" }
                }
            }

        assertTrue(
            "feature code must not create WorkRequests; found ${violations.joinToString()}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `production cannot enqueue or reconstruct a new privilege sweep worker`() {
        val compatibilityOnly = setOf(
            "com/valhalla/thor/data/freezer/PrivilegeSweepWorker.kt",
            "com/valhalla/thor/data/freezer/SweepQueueCanceller.kt",
            "com/valhalla/thor/domain/model/ThorJob.kt",
        )
        val forbidden = listOf("PrivilegeSweepWorker", "THOR_SWEEP_CHAIN")

        val violations = productionSources()
            .filterNot { source -> source.relativePath in compatibilityOnly }
            .flatMap { source ->
                val code = source.file.readText().withoutComments()
                forbidden.mapNotNull { reference ->
                    reference.takeIf(code::contains)?.let { "${source.relativePath}: $it" }
                }
            }

        assertTrue(
            "feature code still reaches the retired sweep chain: ${violations.joinToString()}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `startup reconciliation remains non-starting`() {
        val source = projectFile("app/src/main/java/com/valhalla/thor/ThorApplication.kt")
            .readText()
            .withoutComments()

        assertTrue(source.contains("privilegeSweepReconciler.pruneRetained()"))
        assertTrue(
            Regex(
                pattern = """finally\s*\{[^}]*launchSweepBarrier\.markSwept\(\)""",
                option = RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(source)
        )
        listOf(
            "PrivilegeSweepServiceStarter",
            "DataSyncServiceStarter",
            "startForegroundService(",
            "startService(",
        ).forEach { startPath ->
            assertFalse("startup must not invoke $startPath", source.contains(startPath))
        }
    }

    @Test
    fun `released chain names and job identities remain unchanged`() {
        assertEquals("thor.job.chain", THOR_JOB_CHAIN)
        assertEquals("thor.sweep.chain", THOR_SWEEP_CHAIN)
        assertEquals(
            listOf(
                "archive-backup" to 0,
                "archive-restore" to 1,
                "app-export" to 2,
                "privilege-sweep" to 3,
            ),
            ThorJobKind.entries.map { it.id to it.ordinal },
        )
    }

    private fun productionSources(): List<ProductionSource> {
        val root = projectFile("app/src/main/java")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { file ->
                ProductionSource(
                    file = file,
                    relativePath = file.relativeTo(root).invariantSeparatorsPath,
                )
            }
            .toList()
    }

    private fun projectFile(relativePath: String): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.exists()) return candidate
            directory = directory.parentFile
        }
        error("could not locate $relativePath from ${System.getProperty("user.dir")}")
    }

    private fun String.withoutComments(): String =
        replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""//[^\r\n]*"""), "")
            .replace(Regex("\"(?:\\\\.|[^\"\\\\])*\""), "\"\"")

    private data class ProductionSource(
        val file: File,
        val relativePath: String,
    )
}
