// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import java.util.UUID
import kotlin.time.Duration.Companion.hours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeSweepTest {

    @Test
    fun `normalization drops duplicates and sorts by natural string order`() {
        assertEquals(
            listOf("A.pkg", "a.pkg", "z.pkg"),
            normalizeSweepTargets(listOf("z.pkg", "A.pkg", "a.pkg", "z.pkg")),
        )
    }

    @Test
    fun `normalization preserves case rather than folding it`() {
        // "A.pkg" and "a.pkg" are different packages to PackageManager, so folding them would
        // silently drop one of the user's selections. The ordering is code-unit order — 'A' (65)
        // before 'a' (97) — which is what makes it locale-independent: a Turkish locale must not
        // reorder a sweep.
        val normalized = normalizeSweepTargets(listOf("a.pkg", "A.pkg"))

        assertEquals(listOf("A.pkg", "a.pkg"), normalized)
    }

    @Test
    fun `normalization is idempotent`() {
        val once = normalizeSweepTargets(listOf("z.pkg", "A.pkg", "a.pkg", "z.pkg"))

        assertEquals(once, normalizeSweepTargets(once))
    }

    @Test
    fun `normalization rejects blank package names`() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeSweepTargets(listOf("ok.pkg", " "))
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeSweepTargets(listOf("ok.pkg", ""))
        }
    }

    @Test
    fun `freeze requires a resolved freezer mode`() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeSweepSpec(
                operation = PrivilegeSweepOperation.FREEZE,
                packageNames = listOf("a.pkg"),
                freezerMode = null,
                userId = 0,
                source = PrivilegeSweepSource.FREEZER,
            )
        }

        val resolved = PrivilegeSweepSpec(
            operation = PrivilegeSweepOperation.FREEZE,
            packageNames = listOf("a.pkg"),
            freezerMode = FreezerMode.SUSPEND,
            userId = 0,
            source = PrivilegeSweepSource.FREEZER,
        )

        assertEquals(FreezerMode.SUSPEND, resolved.freezerMode)
    }

    @Test
    fun `non-freeze operations carry no freezer mode`() {
        PrivilegeSweepOperation.entries
            .filter { it != PrivilegeSweepOperation.FREEZE }
            .forEach { operation ->
                assertThrows(IllegalArgumentException::class.java) {
                    PrivilegeSweepSpec(
                        operation = operation,
                        packageNames = listOf("a.pkg"),
                        freezerMode = FreezerMode.FREEZE,
                        userId = 0,
                        source = PrivilegeSweepSource.MAIN,
                    )
                }

                val spec = PrivilegeSweepSpec(
                    operation = operation,
                    packageNames = listOf("a.pkg"),
                    freezerMode = null,
                    userId = 0,
                    source = PrivilegeSweepSource.MAIN,
                )

                assertNull(spec.freezerMode)
            }
    }

    @Test
    fun `a spec refuses targets that are not already canonical`() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeSweepSpec(
                operation = PrivilegeSweepOperation.CLEAR_CACHE,
                packageNames = listOf("z.pkg", "a.pkg"),
                freezerMode = null,
                userId = 0,
                source = PrivilegeSweepSource.APP_LIST,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeSweepSpec(
                operation = PrivilegeSweepOperation.CLEAR_CACHE,
                packageNames = listOf("a.pkg", "a.pkg"),
                freezerMode = null,
                userId = 0,
                source = PrivilegeSweepSource.APP_LIST,
            )
        }
    }

    @Test
    fun `force-stop and uninstall cannot be represented as a sweep operation`() {
        // Both are excluded on purpose: WorkManager may replay a persisted request, and neither is
        // replay-safe (a killed process can restart; an uninstall can commit before Thor
        // checkpoints it). Widening this enum is a product decision, not a refactor.
        assertEquals(
            listOf("FREEZE", "UNFREEZE", "CLEAR_CACHE", "REINSTALL"),
            PrivilegeSweepOperation.entries.map { it.name },
        )
        listOf("FORCE_STOP", "KILL", "UNINSTALL").forEach { name ->
            assertTrue(
                "$name must not be a sweep operation",
                PrivilegeSweepOperation.entries.none { it.name == name },
            )
        }
        // The deferred operations do exist in the lease vocabulary — this is an admission rule for
        // durable work, not a claim that Thor cannot force-stop or uninstall at all.
        listOf("FORCE_STOP", "UNINSTALL").forEach { name ->
            assertTrue(PackageOperationOwner.entries.any { it.name == name })
        }
    }

    @Test
    fun `a status reports every count the presentation layer renders`() {
        val status = PrivilegeSweepStatus(
            requestId = UUID.randomUUID(),
            workId = UUID.randomUUID(),
            operation = PrivilegeSweepOperation.CLEAR_CACHE,
            source = PrivilegeSweepSource.APP_LIST,
            phase = PrivilegeSweepPhase.PARTIAL,
            total = 5,
            succeeded = 2,
            failed = 1,
            busy = 1,
            unresolved = 1,
            rootLaneDegraded = true,
        )

        assertEquals(
            status.total,
            status.succeeded + status.failed + status.busy + status.unresolved
        )
        assertTrue(status.rootLaneDegraded)
    }

    @Test
    fun `launch results distinguish acceptance from every rejection reason`() {
        val requestId = UUID.randomUUID()
        val workId = UUID.randomUUID()
        val accepted = PrivilegeSweepLaunchResult.Accepted(requestId, workId, coalesced = true)

        assertEquals(requestId, accepted.requestId)
        assertEquals(workId, accepted.workId)
        assertTrue(accepted.coalesced)

        val rejections = listOf(
            PrivilegeSweepLaunchRejection.NotificationsRequired,
            PrivilegeSweepLaunchRejection.NoPrivilege,
            PrivilegeSweepLaunchRejection.NoTargets,
            PrivilegeSweepLaunchRejection.EnqueueFailed("rejected"),
        )

        assertEquals(rejections.size, rejections.distinct().size)
        rejections.forEach { reason ->
            assertEquals(reason, PrivilegeSweepLaunchResult.Rejected(reason).reason)
        }
    }

    @Test
    fun `retention window and worker input key are pinned`() {
        assertEquals(24.hours, SWEEP_RESULT_RETENTION)
        assertEquals("sweep_request_id", SWEEP_REQUEST_ID_KEY)
    }
}
