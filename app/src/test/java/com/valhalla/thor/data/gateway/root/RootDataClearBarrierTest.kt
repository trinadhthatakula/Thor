// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import android.app.Application
import android.content.ContextWrapper
import android.util.AtomicFile
import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator
import com.valhalla.thor.domain.repository.RetainedOperationLease
import com.valhalla.thor.domain.repository.retainOperationLeases
import java.io.File
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class RootDataClearBarrierTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `first use initializes private journal and a pending record survives recreation`() = runTest {
        val first = barrier()
        assertFalse(first.anyPending())
        val record = first.begin(PACKAGE, USER)

        assertEquals(RootDataClearPhase.PREPARED, record.phase)
        assertEquals(BOOT, record.bootId)
        assertEquals(record.id, UUID.fromString(record.id).toString())
        assertEquals(record, barrier().pending(PACKAGE, USER))
        assertTrue(barrier().anyPending())
        assertNull(barrier().pending(PACKAGE, USER + 1))
        assertTrue(journalFile().isFile)
        assertTrue(markerFile().isFile)
    }

    @Test
    fun `directory left by interrupted initialization can admit and persist a new record`() = runTest {
        assertTrue(requireNotNull(journalFile().parentFile).mkdirs())

        assertFalse(barrier().anyPending())
        assertTrue(journalFile().isFile)
        assertTrue(markerFile().isFile)
        val record = barrier().begin(PACKAGE, USER)
        assertEquals(record, barrier().pending(PACKAGE, USER))
    }

    @Test
    fun `empty version one journal resumes initialization without replacing its state`() = runTest {
        assertTrue(requireNotNull(journalFile().parentFile).mkdirs())
        val empty = """{ "version": 1, "records": [] }"""
        journalFile().writeText(empty)

        assertEquals(PackageLeaseResult.Acquired("admitted"), barrier().withGlobalLease { "admitted" })
        assertEquals(empty, journalFile().readText())
        assertTrue(markerFile().isFile)
        assertFalse(barrier().anyPending())
    }

    @Test
    fun `empty atomic backup can finish initialization`() = runTest {
        assertTrue(requireNotNull(journalFile().parentFile).mkdirs())
        val empty = """{"version":1,"records":[]}"""
        File(journalFile().path + ".bak").writeText(empty)

        assertFalse(barrier().anyPending())
        assertEquals(empty, journalFile().readText())
        assertTrue(markerFile().isFile)
    }

    @Test
    fun `markerless nonempty state and backup fail closed before old boot retirement`() = runTest {
        barrier().markBinder(barrier().begin(PACKAGE, USER))
        val pending = journalFile().readText()
        assertTrue(markerFile().delete())

        expectUnavailable { barrier(OTHER_BOOT).anyPending() }
        assertEquals(pending, journalFile().readText())
        assertFalse(markerFile().exists())

        File(journalFile().path + ".bak").writeText(pending)
        journalFile().writeText("""{"version":1,"records":[]}""")
        expectUnavailable { barrier(OTHER_BOOT).begin("com.example.other", USER) }
        assertEquals(pending, AtomicFile(journalFile()).readFully().toString(Charsets.UTF_8))
        assertFalse(markerFile().exists())
    }

    @Test
    fun `markerless malformed and unsupported empty state cannot authorize initialization`() = runTest {
        assertTrue(requireNotNull(journalFile().parentFile).mkdirs())
        for (invalid in listOf(
            "{corrupt",
            "{}",
            """{"version":2,"records":[]}""",
            """{"version":1,"records":null}""",
            """{"version":1,"records":[],"unexpected":true}""",
            " ".repeat(262_145),
        )) {
            journalFile().writeText(invalid)
            expectUnavailable { barrier().anyPending() }
            assertEquals(invalid, journalFile().readText())
            assertFalse(markerFile().exists())
        }
        assertTrue(journalFile().delete())
        assertTrue(journalFile().mkdir())
        expectUnavailable { barrier().anyPending() }
        assertFalse(markerFile().exists())
    }

    @Test
    fun `uncommitted state can initialize only while the marker is absent`() = runTest {
        assertTrue(requireNotNull(journalFile().parentFile).mkdirs())
        val uncommitted = File(journalFile().path + ".new")
        uncommitted.writeText("{interrupted")

        assertFalse(barrier().anyPending())
        assertTrue(markerFile().isFile)
        assertTrue(journalFile().delete())
        uncommitted.writeText("""{"version":1,"records":[]}""")
        expectUnavailable { barrier().anyPending() }
        assertFalse(journalFile().exists())
    }

    @Test
    fun `an invalid existing marker never resumes initialization`() = runTest {
        assertFalse(barrier().anyPending())
        val empty = journalFile().readText()
        markerFile().writeText("invalid")

        expectUnavailable { barrier().anyPending() }
        assertEquals("invalid", markerFile().readText())
        assertEquals(empty, journalFile().readText())
    }

    @Test
    fun `duplicate begin refuses exact package user while distinct resources remain independent`() = runTest {
        val first = barrier().begin(PACKAGE, USER)
        val otherUser = barrier().begin(PACKAGE, USER + 1)
        val otherPackage = barrier().begin("com.example.other", USER)
        val failure = try {
            barrier().begin(PACKAGE, USER)
            null
        } catch (pending: RootDataClearAlreadyPending) {
            pending
        }

        assertEquals(first, failure?.record)
        assertTrue(barrier().finish(otherUser))
        assertEquals(first, barrier().pending(PACKAGE, USER))
        assertEquals(otherPackage, barrier().pending(otherPackage.packageName, USER))
    }

    @Test
    fun `phase is durable before binder and only the exact current record can finish`() = runTest {
        val prepared = barrier().begin(PACKAGE, USER)
        val binder = barrier().markBinder(prepared)

        assertEquals(prepared.id, binder.id)
        assertEquals(RootDataClearPhase.BINDER, binder.phase)
        assertEquals(binder, barrier().pending(PACKAGE, USER))
        assertFalse(barrier().finish(prepared))
        assertFalse(barrier().finish(binder.copy(id = UUID.randomUUID().toString())))
        assertFalse(barrier().finish(binder.copy(packageName = "com.example.other")))
        assertFalse(barrier().finish(binder.copy(userId = USER + 1)))
        assertFalse(barrier().finish(binder.copy(bootId = OTHER_BOOT)))
        assertTrue(barrier().finish(binder))
        assertFalse(barrier().anyPending())

        val replacement = barrier().begin(PACKAGE, USER)
        assertNotEquals(binder.id, replacement.id)
        assertFalse(barrier().finish(binder))
        assertEquals(replacement, barrier().pending(PACKAGE, USER))
    }

    @Test
    fun `mark binder cannot replace a changed or already advanced record`() = runTest {
        val prepared = barrier().begin(PACKAGE, USER)
        val binder = barrier().markBinder(prepared)

        expectUnavailable { barrier().markBinder(prepared) }
        expectUnavailable { barrier().markBinder(binder) }
        assertEquals(binder, barrier().pending(PACKAGE, USER))
    }

    @Test
    fun `same unknown malformed and unreadable current boot retain the barrier`() = runTest {
        val record = barrier().begin(PACKAGE, USER)
        for (currentBoot in listOf(BOOT, null, "not-a-kernel-uuid")) {
            assertEquals(record, barrier(currentBoot).pending(PACKAGE, USER))
        }
        val unreadable = barrier().apply { bootIdProvider = { throw SecurityException("unreadable") } }
        assertEquals(record, unreadable.pending(PACKAGE, USER))
    }

    @Test
    fun `a different known boot retires old work but cannot retire unknown boot identity`() = runTest {
        val known = barrier().begin(PACKAGE, USER)
        val unknown = barrier(null).begin("com.example.unknown", USER)
        assertNull(unknown.bootId)

        val afterBoot = barrier(OTHER_BOOT)
        assertNull(afterBoot.pending(PACKAGE, USER))
        assertFalse(afterBoot.finish(known))
        assertEquals(unknown, afterBoot.pending(unknown.packageName, USER))
        assertEquals(unknown, barrier(OTHER_BOOT).pending(unknown.packageName, USER))
    }

    @Test
    fun `corrupt journal never becomes an empty result or permits another begin`() = runTest {
        barrier().begin(PACKAGE, USER)
        journalFile().writeText("{corrupt")

        expectUnavailable { barrier().pending(PACKAGE, USER) }
        expectUnavailable { barrier().anyPending() }
        expectUnavailable { barrier().begin("com.example.other", USER) }
        assertEquals("{corrupt", journalFile().readText())
    }

    @Test
    fun `missing initialized journal directory or nonempty journal marker fails closed`() = runTest {
        val initial = barrier().begin(PACKAGE, USER)
        val bytes = journalFile().readBytes()
        assertTrue(journalFile().delete())
        expectUnavailable { barrier().pending(PACKAGE, USER) }

        journalFile().writeBytes(bytes)
        assertEquals(initial, barrier().pending(PACKAGE, USER))
        assertTrue(requireNotNull(journalFile().parentFile).deleteRecursively())
        assertTrue(markerFile().isFile)
        expectUnavailable { barrier().anyPending() }

        assertTrue(requireNotNull(journalFile().parentFile).mkdirs())
        journalFile().writeBytes(bytes)
        assertTrue(markerFile().delete())
        expectUnavailable { barrier().begin("com.example.other", USER) }
    }

    @Test
    fun `unsupported metadata duplicate identities and oversized journals fail closed`() = runTest {
        val record = barrier().begin(PACKAGE, USER)
        val valid = journalFile().readText()
        val invalid = listOf(
            "{}",
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace(record.id, "invalid-id"),
            valid.replace(BOOT, "unknown-boot"),
            valid.replace("\"PREPARED\"", "\"FUTURE_PHASE\""),
            valid.replace("\"userId\":10", "\"userId\":-1"),
            valid.replace("\"records\":[", "\"records\":[" + valid.substringAfter("\"records\":[").substringBeforeLast("]}") + ","),
            " ".repeat(262_145),
        )
        for (text in invalid) {
            journalFile().writeText(text)
            expectUnavailable { barrier().pending(PACKAGE, USER) }
        }
    }

    @Test
    fun `separate instances share path ownership and cannot clobber simultaneous records`() = runTest {
        val records = (0 until 20).map { index ->
            async { barrier().begin("com.example.target$index", USER) }
        }.awaitAll()

        for (record in records) {
            assertEquals(record, barrier().pending(record.packageName, USER))
        }
        records.map { record -> async { barrier().finish(record) } }.awaitAll().forEach(::assertTrue)
        assertFalse(barrier().anyPending())
    }

    @Test
    fun `a journal that cannot be read cannot authorize a phase change`() = runTest {
        val record = barrier().begin(PACKAGE, USER)
        assertTrue(journalFile().delete())
        assertTrue(journalFile().mkdir())

        expectUnavailable { barrier().markBinder(record) }
        expectUnavailable { barrier().finish(record) }
    }

    @Test
    fun `pending or corrupt clear records block global operations before their callback`() = runTest {
        barrier().begin(PACKAGE, USER)
        var entered = false
        assertEquals(
            PackageLeaseResult.Busy(PackageOperationOwner.CLEAR_DATA),
            barrier().withGlobalLease { entered = true },
        )
        assertFalse(entered)

        journalFile().writeText("{corrupt")
        assertEquals(
            PackageLeaseResult.Busy(PackageOperationOwner.CLEAR_DATA),
            barrier().withGlobalLease { entered = true },
        )
        assertFalse(entered)
    }

    @Test
    fun `global operation excludes a new clear until its whole block finishes across instances`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val global = async {
            barrier().withGlobalLease {
                entered.complete(Unit)
                release.await()
                "global completed"
            }
        }
        entered.await()
        val newClear = async { barrier().begin(PACKAGE, USER) }
        try {
            assertNull(barrier().pending(PACKAGE, USER))
            assertFalse(newClear.isCompleted)
        } finally {
            release.complete(Unit)
        }

        assertEquals(PackageLeaseResult.Acquired("global completed"), global.await())
        val record = newClear.await()
        assertEquals(record, barrier().pending(PACKAGE, USER))
        assertEquals(
            PackageLeaseResult.Busy(PackageOperationOwner.CLEAR_DATA),
            barrier().withGlobalLease { error("The new record must block the next global operation") },
        )
    }

    @Test
    fun `cancelled global operation releases admission without inventing a clear record`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val global = async {
            barrier().withGlobalLease {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        global.cancelAndJoin()

        assertFalse(barrier().anyPending())
        val record = barrier().begin(PACKAGE, USER)
        assertEquals(record, barrier().pending(PACKAGE, USER))
    }

    @Test
    fun `cancelled global caller retains admission until its accepted work releases`() = runTest {
        val entered = CompletableDeferred<RetainedOperationLease>()
        val global = async {
            barrier().withGlobalLease {
                entered.complete(retainOperationLeases())
                awaitCancellation()
            }
        }
        val retained = entered.await()
        global.cancelAndJoin()
        // With an IO context and undispatched start, begin reaches the admission mutex before
        // async returns; a released mutex would let the whole begin complete synchronously.
        val newClear = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            barrier().begin(PACKAGE, USER)
        }
        assertNull(barrier().pending(PACKAGE, USER))
        assertFalse(newClear.isCompleted)

        retained.release()
        val record = newClear.await()
        assertEquals(record, barrier().pending(PACKAGE, USER))
        retained.release()
        assertEquals(record, barrier().pending(PACKAGE, USER))
    }

    @Test
    fun `nested archive package and unknown target global ownership are both retained`() = runTest {
        val coordinator = DefaultPackageOperationCoordinator()
        lateinit var retained: RetainedOperationLease
        coordinator.withPackageLease(PACKAGE, PackageOperationOwner.ARCHIVE_RESTORE, Duration.ZERO) {
            barrier().withGlobalLease { retained = retainOperationLeases() }
        }
        assertEquals(
            PackageLeaseResult.Busy(PackageOperationOwner.ARCHIVE_RESTORE),
            coordinator.withPackageLease(PACKAGE, PackageOperationOwner.UNINSTALL, Duration.ZERO) {
                error("The borrowed archive package claim must remain retained")
            },
        )
        val newClear = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            barrier().begin("com.example.other", USER)
        }
        assertFalse(barrier().anyPending())
        assertFalse(newClear.isCompleted)

        retained.release()
        val record = newClear.await()
        assertEquals(record, barrier().pending(record.packageName, USER))
        assertEquals(
            PackageLeaseResult.Acquired("released"),
            coordinator.withPackageLease(PACKAGE, PackageOperationOwner.UNINSTALL, Duration.ZERO) {
                "released"
            },
        )
    }

    private fun barrier(boot: String? = BOOT): RootDataClearBarrier = RootDataClearBarrier(
        object : ContextWrapper(null) {
            override fun getNoBackupFilesDir(): File = temporary.root
        },
    ).apply { bootIdProvider = { boot } }

    private fun journalFile() = File(temporary.root, "root_data_clear_recovery/pending.json")
    private fun markerFile() = File(temporary.root, "root_data_clear_recovery_initialized")

    private suspend fun expectUnavailable(block: suspend () -> Any?) {
        val failure = try {
            block()
            null
        } catch (unavailable: RootDataClearJournalUnavailable) {
            unavailable
        }
        assertTrue("Uncertain persistence must fail closed", failure is RootDataClearJournalUnavailable)
    }

    private companion object {
        const val PACKAGE = "com.example.target"
        const val USER = 10
        const val BOOT = "6eec9e4e-17b2-4f07-b9a7-1ca54aa9e099"
        const val OTHER_BOOT = "b7a933c9-46b5-405e-bff1-bf65ea73ae16"
    }
}
