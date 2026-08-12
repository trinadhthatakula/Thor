// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.domain.model.ArchiveBundleInfo
import com.valhalla.thor.domain.model.ArchiveCompression
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveKdf
import com.valhalla.thor.domain.model.ArchiveMember
import com.valhalla.thor.domain.model.ClassEntries
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.ObbPlacement
import com.valhalla.thor.domain.model.TarOutcome
import com.valhalla.thor.domain.model.THORBAK_BUNDLE_ENTRY
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.domain.repository.AppArchiveInstaller
import com.valhalla.thor.domain.repository.AppDataArchiveGateway
import com.valhalla.thor.domain.repository.ArchiveBreadcrumb
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import com.valhalla.thor.domain.repository.ArchiveInstallOutcome
import com.valhalla.thor.domain.repository.ArchiveSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import javax.crypto.SecretKey

class RestoreAppArchiveUseCaseTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val cipher = AppArchiveCipher()
    private val salt = ByteArray(16) { it.toByte() }
    private val key: SecretKey = cipher.deriveKey("pass".toCharArray(), salt, iterations = 4)

    /** Every gateway call, in order, as `"<verb>:<class>"` — the assertion surface for §8.3's sequence. */
    private val calls = mutableListOf<String>()

    private class FakeSource(private val entries: Map<String, ByteArray>) : ArchiveSource {
        override val displayName = "com.example.app-100.thorbak"
        override fun entryNames() = entries.keys.toList()
        override fun openEntry(name: String): InputStream? = entries[name]?.let(::ByteArrayInputStream)
        override fun close() = Unit
    }

    /** One encrypted member plus the stats the header must record for it. */
    private fun member(dataClass: DataClass, body: String = "tar bytes for ${dataClass.id}"): Pair<ArchiveMember, ByteArray> {
        val nonce = cipher.newNonce()
        val out = ByteArrayOutputStream()
        val name = dataClass.memberName(compressed = true)
        val stats = cipher.encryptMember(name, ByteArrayInputStream(body.toByteArray()), out, key, nonce)
        return ArchiveMember(
            dataClass = dataClass.id,
            fileName = name,
            nonce = Base64.getEncoder().encodeToString(nonce),
            plainBytes = stats.plainBytes,
            chunkCount = stats.chunkCount,
            compression = ArchiveCompression.GZIP.id,
        ) to out.toByteArray()
    }

    private fun archive(
        classes: List<DataClass>,
        withBundle: Boolean = true,
    ): Pair<ArchiveHeader, FakeSource> {
        val built = classes.map(::member)
        val entries = built.associate { (m, bytes) -> m.fileName to bytes }.toMutableMap()
        if (withBundle) entries[THORBAK_BUNDLE_ENTRY] = "xapk bytes".toByteArray()
        val header = ArchiveHeader(
            createdAt = 1_000L,
            thorVersionCode = 1950,
            packageName = "com.example.app",
            versionCode = 100L,
            userId = 0,
            signerSha256 = SIGNER,
            appBundle = if (withBundle) ArchiveBundleInfo(bytes = 10L, obbCapture = "present", obbCount = 2) else null,
            kdf = ArchiveKdf(iterations = 4, salt = Base64.getEncoder().encodeToString(salt)),
            verifier = Base64.getEncoder().encodeToString(cipher.verifier(key)),
            members = built.map { it.first },
        )
        return header to FakeSource(entries)
    }

    private inner class FakeGateway(
        private val failOn: String? = null,
        private val uid: Int? = 10123,
        private val signer: String? = SIGNER,
        /** Runs inside [stagingFile], before the file is handed back. The cancellation seam. */
        private val onStagingFile: () -> Unit = {},
        /**
         * The staging name to hand back as a **directory**. Opening one for write throws
         * `FileNotFoundException`, which is how a JVM test reaches the ENOSPC path: an
         * `IOException` out of the decrypt is not an `ArchiveIntegrityException` and nothing else
         * catches it.
         */
        private val unwritableStaging: String? = null,
    ) : AppDataArchiveGateway {
        val stagedFiles = mutableListOf<File>()

        /**
         * Every previously handed-out staging file that still had bytes in it when the *next* one was
         * asked for.
         *
         * This is what makes "peak disk is one class" testable rather than merely asserted at the end:
         * a `finally` folded up into `invoke` still deletes every tar eventually, so an end-of-run
         * check alone stays green under exactly the edit the invariant exists to forbid.
         */
        val liveAtHandout = mutableListOf<String>()

        override suspend fun thorUserId() = 0
        override suspend fun externalStorageDir() = "/storage/emulated/0"
        override suspend fun stagingFile(name: String): File {
            liveAtHandout += stagedFiles.filter { it.exists() && it.length() > 0L }.map(File::getName)
            onStagingFile()
            val handedOut = "staged-${stagedFiles.size}-$name"
            val file =
                if (name == unwritableStaging) temp.newFolder(handedOut) else temp.newFile(handedOut)
            return file.also(stagedFiles::add)
        }

        override suspend fun forceStop(packageName: String) { calls += "force-stop" }
        override suspend fun listClass(packageName: String, dataClass: DataClass) =
            ClassEntries(kept = emptyList(), skipped = emptyList(), rootAbsent = true)

        override suspend fun tarClass(
            packageName: String,
            dataClass: DataClass,
            entries: List<String>,
            out: File,
            compress: Boolean,
        ) = TarOutcome.Succeeded

        override suspend fun appUid(packageName: String) = uid
        override suspend fun signerSha256(packageName: String) = signer

        override suspend fun extractInto(packageName: String, dataClass: DataClass, tar: File, compressed: Boolean) =
            record("extract", dataClass)

        override suspend fun swapStaged(packageName: String, dataClass: DataClass) = record("swap", dataClass)
        override suspend fun chownClass(packageName: String, dataClass: DataClass, uid: Int) = record("chown", dataClass)
        override suspend fun relabelClass(packageName: String, dataClass: DataClass) = record("relabel", dataClass)

        private fun record(verb: String, dataClass: DataClass): Boolean {
            val call = "$verb:${dataClass.id}"
            calls += call
            return call != failOn
        }
    }

    private class FakeInstaller(
        private val outcome: ArchiveInstallOutcome = ArchiveInstallOutcome.Installed,
        private val placement: ObbPlacement = ObbPlacement.Placed(2),
        private val calls: MutableList<String>,
    ) : AppArchiveInstaller {
        override suspend fun installBundle(bundle: File, packageName: String): ArchiveInstallOutcome {
            calls += "install"
            return outcome
        }

        override suspend fun placeBundleObb(
            bundle: File,
            packageName: String,
            onFile: (String, Int, Int) -> Unit,
        ): ObbPlacement {
            calls += "obb"
            return placement
        }
    }

    /**
     * @param writes false stands in for a full or unwritable `filesDir`.
     * @param calls the *shared* log, when a test needs this store's writes interleaved with the
     *   installer's and the gateway's. [history] on its own cannot answer "before or after the
     *   install", because both orders leave the same entry in it.
     */
    private class RecordingBreadcrumbs(
        private val writes: Boolean = true,
        private val calls: MutableList<String>? = null,
    ) : ArchiveBreadcrumbStore {
        var current: ArchiveBreadcrumb? = null
        val history = mutableListOf<String>()

        override suspend fun write(packageName: String, appLabel: String): Boolean {
            history += "write"
            calls?.plusAssign("breadcrumb")
            if (!writes) return false
            current = ArchiveBreadcrumb(packageName, appLabel, startedAt = 1L)
            return true
        }

        override suspend fun read(): ArchiveBreadcrumb? = current

        // Unused by the use case, which writes and clears but never watches. Re-reads rather than
        // replaying, so it stays truthful if a later test does watch it.
        override fun observe(): Flow<ArchiveBreadcrumb?> = flow { emit(read()) }

        override suspend fun clear() {
            current = null
            history += "clear"
        }
    }

    private fun useCase(
        gateway: AppDataArchiveGateway,
        installer: AppArchiveInstaller,
        breadcrumbs: ArchiveBreadcrumbStore,
    ) = RestoreAppArchiveUseCase(gateway, installer, breadcrumbs, cipher)

    @Test
    fun `each internal class is extracted, swapped, chowned and relabelled in that order`() = runTest {
        val (header, source) = archive(listOf(DataClass.CE, DataClass.DE))
        val crumbs = RecordingBreadcrumbs()

        val outcome = useCase(FakeGateway(), FakeInstaller(calls = calls), crumbs)(
            source = source,
            header = header,
            key = key,
            classes = listOf(DataClass.CE, DataClass.DE),
            installFirst = false,
            restoreObb = false,
        )

        assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Completed)
        assertEquals(
            listOf(
                "force-stop",
                "extract:ce", "swap:ce", "chown:ce", "relabel:ce",
                "extract:de", "swap:de", "chown:de", "relabel:de",
                "force-stop",
            ),
            calls,
        )
    }

    @Test
    fun `external classes are neither chowned nor relabelled`() = runTest {
        // FUSE synthesizes ownership from the caller, so `chown` there changes nothing and
        // `restorecon` has no label to apply. Issuing them anyway would produce two failed commands
        // per class and a restore reported as partial when it was complete.
        val (header, source) = archive(listOf(DataClass.EXTERNAL_DATA, DataClass.EXTERNAL_MEDIA))

        val outcome = useCase(FakeGateway(), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            source, header, key,
            listOf(DataClass.EXTERNAL_DATA, DataClass.EXTERNAL_MEDIA),
            installFirst = false,
            restoreObb = false,
        )

        // Asserted positively first: an absence alone is satisfied by a restore that failed before
        // the loop and issued no commands at all, which would leave this green over a regression.
        assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Completed)
        assertTrue(calls.toString(), calls.contains("extract:${DataClass.EXTERNAL_DATA.id}"))
        assertTrue(calls.toString(), calls.contains("swap:${DataClass.EXTERNAL_MEDIA.id}"))
        assertFalse(calls.toString(), calls.any { it.startsWith("chown") || it.startsWith("relabel") })
    }

    @Test
    fun `the app is force-stopped before the first destructive call and once more at the end`() = runTest {
        // Twice, not per class: §8.3 steps 2 and 5. The second one is there because a broadcast can
        // wake the app mid-restore, and an app running on top of half-replaced data writes over it.
        val (header, source) = archive(listOf(DataClass.CE))

        useCase(FakeGateway(), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = false,
        )

        assertEquals(2, calls.count { it == "force-stop" })
        assertEquals("force-stop", calls.first())
        assertEquals("force-stop", calls.last())
    }

    @Test
    fun `the breadcrumb is written before the first destructive call and cleared on success`() = runTest {
        val (header, source) = archive(listOf(DataClass.CE))
        val crumbs = RecordingBreadcrumbs()

        useCase(FakeGateway(), FakeInstaller(calls = calls), crumbs)(
            source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = false,
        )

        assertEquals(listOf("write", "clear"), crumbs.history)
        assertNull(crumbs.current)
    }

    @Test
    fun `a failure leaves the breadcrumb in place`() = runTest {
        // This is the whole point of §8.5. Clearing on failure converts "the restore of X was
        // interrupted and its data may be incomplete" into silence, and the user finds out when the
        // app crashes.
        val (header, source) = archive(listOf(DataClass.CE))
        val crumbs = RecordingBreadcrumbs()

        val outcome = useCase(FakeGateway(failOn = "swap:ce"), FakeInstaller(calls = calls), crumbs)(
            source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = false,
        )

        assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Failed)
        assertNotNull(crumbs.current)
        assertFalse(crumbs.history.toString(), crumbs.history.contains("clear"))
    }

    @Test
    fun `a member that fails integrity leaves its class root untouched`() = runTest {
        // The ordering that matters most: decrypt fully, *then* extract, *then* swap. A corrupt
        // archive discovered after the swap has already deleted the data it was replacing.
        val (header, source) = archive(listOf(DataClass.CE))
        val corrupted = FakeSource(
            source.entryNames().associateWith { name ->
                source.openEntry(name)!!.readBytes().also { if (name.endsWith(".enc")) it[it.size - 1]++ }
            }
        )

        val outcome = useCase(FakeGateway(), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            corrupted, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = false,
        )

        assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Failed)
        assertFalse(calls.toString(), calls.any { it.startsWith("extract") || it.startsWith("swap") })
    }

    @Test
    fun `a member the container does not hold is a failure, not a silent skip`() = runTest {
        val (header, _) = archive(listOf(DataClass.CE))
        val empty = FakeSource(emptyMap())

        val outcome = useCase(FakeGateway(), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            empty, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = false,
        )

        val reason = (outcome as ArchiveRestoreOutcome.Failed).reason
        assertTrue(reason, reason.contains(DataClass.CE.memberName(compressed = true)))
    }

    @Test
    fun `install-first installs before any data call`() = runTest {
        val (header, source) = archive(listOf(DataClass.CE))

        val outcome = useCase(FakeGateway(), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            source, header, key, listOf(DataClass.CE), installFirst = true, restoreObb = false,
        )

        // "install came first" is also true of a run that installed and then failed, so the data
        // calls are asserted too — otherwise the ordering is pinned over an empty tail.
        assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Completed)
        assertEquals("install", calls.first())
        assertTrue(calls.toString(), calls.contains("swap:${DataClass.CE.id}"))
    }

    @Test
    fun `an install that does not land writes no data and leaves no breadcrumb`() = runTest {
        // Nothing was destroyed, so a breadcrumb saying otherwise would make the user go looking for
        // damage that is not there.
        val (header, source) = archive(listOf(DataClass.CE))
        val crumbs = RecordingBreadcrumbs()

        val outcome = useCase(
            FakeGateway(),
            FakeInstaller(outcome = ArchiveInstallOutcome.Unconfirmed, calls = calls),
            crumbs,
        )(source, header, key, listOf(DataClass.CE), installFirst = true, restoreObb = false)

        assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Failed)
        assertEquals(listOf("install"), calls)
        assertNull(crumbs.current)
    }

    @Test
    fun `an install-first restore records that it started before it installs anything`() = runTest {
        // §8.5's window, and the reason this is an ordering assertion rather than a presence one:
        // both orders leave the same breadcrumb behind on a restore that completes. The case that
        // separates them is the one no test can run — the process is killed between the install
        // returning and the marker being written — and on that path the user is left holding a
        // freshly installed app with no data in it while nothing anywhere says a restore was
        // interrupted. The marker has to be on disk before the irreversible step, not after it.
        val (header, source) = archive(listOf(DataClass.CE))
        val crumbs = RecordingBreadcrumbs(calls = calls)

        useCase(FakeGateway(), FakeInstaller(calls = calls), crumbs)(
            source, header, key, listOf(DataClass.CE), installFirst = true, restoreObb = false,
        )

        assertTrue(
            "the breadcrumb must be written before the install, not after it: $calls",
            calls.indexOf("breadcrumb") in 0..<calls.indexOf("install"),
        )
    }

    @Test
    fun `a restore that is not install-first still writes the breadcrumb exactly once`() = runTest {
        // The other half of making the write idempotent. The install branch is skipped entirely here,
        // so the marker goes down at the first class swap — and it must go down once, because a
        // second write would restamp `startedAt` and make an interrupted restore look newer than it is.
        val (header, source) = archive(listOf(DataClass.CE, DataClass.DE))
        val crumbs = RecordingBreadcrumbs()

        useCase(FakeGateway(), FakeInstaller(calls = calls), crumbs)(
            source, header, key, listOf(DataClass.CE, DataClass.DE),
            installFirst = false, restoreObb = false,
        )

        assertEquals(listOf("write", "clear"), crumbs.history)
    }

    @Test
    fun `an install that outright failed leaves no breadcrumb either`() = runTest {
        // The marker now goes down *before* the install, so every pre-data failure has to take it
        // back off. Without that, a refused install would announce an interrupted restore on the next
        // launch over a device in exactly the state it started in.
        val (header, source) = archive(listOf(DataClass.CE))
        val crumbs = RecordingBreadcrumbs()

        val outcome = useCase(
            FakeGateway(),
            FakeInstaller(outcome = ArchiveInstallOutcome.Failed("no room on device"), calls = calls),
            crumbs,
        )(source, header, key, listOf(DataClass.CE), installFirst = true, restoreObb = false)

        assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Failed)
        assertNull(crumbs.current)
        assertEquals(listOf("write", "clear"), crumbs.history)
    }

    @Test
    fun `a signer that does not match leaves no breadcrumb`() = runTest {
        // Same rule, the other pre-data exit: an install landed, but no data was ever written and the
        // job returns a reason the user is shown. §8.5 answers for restores that never returned.
        val (header, source) = archive(listOf(DataClass.CE))
        val crumbs = RecordingBreadcrumbs()

        val outcome = useCase(
            FakeGateway(signer = "00".repeat(32)),
            FakeInstaller(calls = calls),
            crumbs,
        )(source, header, key, listOf(DataClass.CE), installFirst = true, restoreObb = false)

        assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Failed)
        assertNull(crumbs.current)
    }

    @Test
    fun `a restore that never marked itself does not clear another app's breadcrumb`() = runTest {
        // The store holds one breadcrumb for the whole device. This restore fails before it reaches
        // its own irreversible step, so it has nothing to take back — and an unguarded clear here
        // would delete the record of a *different* app's genuinely interrupted restore, silencing the
        // §8.5 notice for an app this run never touched.
        val (header, source) = archive(listOf(DataClass.CE))
        val other = ArchiveBreadcrumb("com.example.other", "Other", startedAt = 1L)
        val crumbs = RecordingBreadcrumbs().apply { current = other }

        val outcome = useCase(FakeGateway(uid = null), FakeInstaller(calls = calls), crumbs)(
            source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = false,
        )

        assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Failed)
        assertEquals(other, crumbs.current)
    }

    @Test
    fun `an unconfirmed install is not reported as a failed one`() = runTest {
        // `session.commit()` is fire-and-forget, so an install Thor could not confirm may well have
        // succeeded. The reason must say that and not "the app could not be installed", or the user
        // is told a false thing about a package that may be sitting on their device.
        val (header, source) = archive(listOf(DataClass.CE))

        val outcome = useCase(
            FakeGateway(),
            FakeInstaller(outcome = ArchiveInstallOutcome.Unconfirmed, calls = calls),
            RecordingBreadcrumbs(),
        )(source, header, key, listOf(DataClass.CE), installFirst = true, restoreObb = false, appLabel = "Example")

        val reason = (outcome as ArchiveRestoreOutcome.Failed).reason
        assertTrue(reason, reason.contains("could not confirm"))
        assertTrue(reason, reason.contains("Example"))
    }

    @Test
    fun `a failed install reports the platform's own words`() = runTest {
        // The reason on `Failed` is the message the install path put on the bus. Replacing it with a
        // flat sentence throws away the only actionable thing the user will ever see.
        val (header, source) = archive(listOf(DataClass.CE))

        val outcome = useCase(
            FakeGateway(),
            FakeInstaller(outcome = ArchiveInstallOutcome.Failed("INSTALL_FAILED_VERSION_DOWNGRADE"), calls = calls),
            RecordingBreadcrumbs(),
        )(source, header, key, listOf(DataClass.CE), installFirst = true, restoreObb = false)

        assertEquals(
            "INSTALL_FAILED_VERSION_DOWNGRADE",
            (outcome as ArchiveRestoreOutcome.Failed).reason,
        )
    }

    @Test
    fun `an install that landed without its game data still restores the data, and warns`() = runTest {
        // The fourth outcome, added after this task's brief was written. The package is installed and
        // current — only its expansions are missing — so stopping here would leave the user with an
        // installed, empty app and no path forward. The warning has to survive, because a game whose
        // expansions are missing starts and then crashes.
        val (header, source) = archive(listOf(DataClass.CE))
        val crumbs = RecordingBreadcrumbs()

        val outcome = useCase(
            FakeGateway(),
            FakeInstaller(
                outcome = ArchiveInstallOutcome.InstalledWithoutGameData("no room for the expansions"),
                calls = calls,
            ),
            crumbs,
        )(source, header, key, listOf(DataClass.CE), installFirst = true, restoreObb = false)

        val completed = outcome as ArchiveRestoreOutcome.Completed
        assertEquals(listOf(DataClass.CE), completed.classesRestored)
        assertTrue(
            completed.warnings.toString(),
            completed.warnings.any { it.contains("no room for the expansions") },
        )
        assertTrue(calls.toString(), calls.contains("swap:ce"))
        assertNull(crumbs.current)
    }

    @Test
    fun `a signer mismatch after the install stops the restore`() = runTest {
        // The gate (Task 11) cannot check an absent app's signer, so this is the only place that check
        // can happen for an install-first restore. Without it, "app not installed" is a hole straight
        // through the one refusal §8.1 allows no override for.
        val (header, source) = archive(listOf(DataClass.CE))

        val outcome = useCase(
            FakeGateway(signer = "CD".repeat(32)),
            FakeInstaller(calls = calls),
            RecordingBreadcrumbs(),
        )(source, header, key, listOf(DataClass.CE), installFirst = true, restoreObb = false)

        assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Failed)
        assertFalse(calls.toString(), calls.any { it.startsWith("swap") })
    }

    @Test
    fun `an unreadable signer after the install stops the restore`() = runTest {
        // Null is "the question could not be answered", never "it matches". Reading it as a pass is
        // the same defect `installLanded` refuses on the stamp.
        val (header, source) = archive(listOf(DataClass.CE))

        val outcome = useCase(
            FakeGateway(signer = null),
            FakeInstaller(calls = calls),
            RecordingBreadcrumbs(),
        )(source, header, key, listOf(DataClass.CE), installFirst = true, restoreObb = false)

        assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Failed)
        assertEquals(listOf("install"), calls)
    }

    @Test
    fun `the signer is not checked when the app was already installed`() = runTest {
        // Task 11's gate already ran that comparison against the live app. Repeating it here would be
        // harmless; *only* doing it here would not be, which is why the install-first case has its own
        // test above.
        val (header, source) = archive(listOf(DataClass.CE))

        val outcome = useCase(
            FakeGateway(signer = "CD".repeat(32)),
            FakeInstaller(calls = calls),
            RecordingBreadcrumbs(),
        )(source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = false)

        assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Completed)
    }

    @Test
    fun `an unreadable uid stops the restore before anything is force-stopped`() = runTest {
        val (header, source) = archive(listOf(DataClass.CE))

        val outcome = useCase(FakeGateway(uid = null), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = false,
        )

        assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Failed)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `the staged tar of one class is gone before the next is decrypted`() = runTest {
        // Peak disk is one class, same invariant the backup side holds. A `finally` folded up into the
        // outer `try` breaks this and only this.
        val gateway = FakeGateway()
        val (header, source) = archive(listOf(DataClass.CE, DataClass.DE, DataClass.EXTERNAL_DATA))

        useCase(gateway, FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            source, header, key,
            listOf(DataClass.CE, DataClass.DE, DataClass.EXTERNAL_DATA),
            installFirst = false,
            restoreObb = false,
        )

        // Checked at each hand-out, not only at the end: an end-of-run check alone survives moving
        // the deletion out of the loop, which is exactly the edit this pins.
        assertEquals(emptyList<String>(), gateway.liveAtHandout)
        assertEquals(
            emptyList<File>(),
            gateway.stagedFiles.filter { it.exists() && it.length() > 0L },
        )
    }

    @Test
    fun `a failed restore leaves no staged bundle behind`() = runTest {
        // The bundle is the app's whole download. Leaking one copy of it into Thor's internal cache
        // per failed restore is the same defect the per-class `finally` exists to prevent, one level
        // up.
        val gateway = FakeGateway()
        val (header, source) = archive(listOf(DataClass.CE))

        useCase(
            gateway,
            FakeInstaller(outcome = ArchiveInstallOutcome.Failed("nope"), calls = calls),
            RecordingBreadcrumbs(),
        )(source, header, key, listOf(DataClass.CE), installFirst = true, restoreObb = false)

        assertEquals(
            emptyList<File>(),
            gateway.stagedFiles.filter { it.exists() && it.length() > 0L },
        )
    }

    @Test
    fun `an archive whose bundle entry is missing fails before anything is destroyed`() = runTest {
        // The header's `appBundle` is a claim; the container is the authority. A header that promises
        // a bundle the zip does not hold must not reach the installer with an empty file.
        val (header, _) = archive(listOf(DataClass.CE))
        val noBundle = FakeSource(
            header.members.associate { it.fileName to ByteArray(0) }
        )

        val outcome = useCase(FakeGateway(), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            noBundle, header, key, listOf(DataClass.CE), installFirst = true, restoreObb = false,
        )

        assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Failed)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `a container that throws while opening the bundle fails as an outcome, not as a throw`() =
        runTest {
            // A truncated `.thorbak` makes `openEntry` throw rather than answer null. The decrypt's
            // catch was widened for exactly this reason; the bundle path needs the same, or the worker
            // gets a raw `IOException` where the contract promises an `ArchiveRestoreOutcome`.
            val (header, source) = archive(listOf(DataClass.CE))
            val truncated = object : ArchiveSource by source {
                override fun openEntry(name: String): InputStream? =
                    if (name == THORBAK_BUNDLE_ENTRY) throw IOException("unexpected end of stream")
                    else source.openEntry(name)
            }

            val outcome = useCase(FakeGateway(), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
                truncated, header, key, listOf(DataClass.CE), installFirst = true, restoreObb = false,
            )

            assertTrue(outcome.toString(), outcome is ArchiveRestoreOutcome.Failed)
            assertEquals(emptyList<String>(), calls)
        }

    @Test
    fun `OBB is placed for an already-installed app when asked`() = runTest {
        val (header, source) = archive(listOf(DataClass.CE))

        val outcome = useCase(FakeGateway(), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = true,
        )

        assertTrue(calls.toString(), calls.contains("obb"))
        assertEquals(ObbPlacement.Placed(2), (outcome as ArchiveRestoreOutcome.Completed).obb)
    }

    @Test
    fun `OBB is not placed twice after an install`() = runTest {
        // The install path places the bundle's expansions itself. Doing it again would re-copy every
        // gigabyte for no change.
        val (header, source) = archive(listOf(DataClass.CE))

        useCase(FakeGateway(), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            source, header, key, listOf(DataClass.CE), installFirst = true, restoreObb = true,
        )

        assertFalse(calls.toString(), calls.contains("obb"))
    }

    @Test
    fun `a failed OBB placement is a warning, not a failed restore`() = runTest {
        // The data landed. Reporting the whole restore as failed would send the user to try it again,
        // which destroys and rewrites the data that is already correct.
        val (header, source) = archive(listOf(DataClass.CE))

        val outcome = useCase(
            FakeGateway(),
            FakeInstaller(placement = ObbPlacement.Failed("no space"), calls = calls),
            RecordingBreadcrumbs(),
        )(source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = true)

        val completed = outcome as ArchiveRestoreOutcome.Completed
        assertTrue(completed.warnings.toString(), completed.warnings.any { it.contains("no space") })
    }

    @Test
    fun `the OBB branch still force-stops and clears the breadcrumb`() = runTest {
        // Its own return path, so §8.3 steps 5 and 6 have to be repeated on it. They were not, once.
        val (header, source) = archive(listOf(DataClass.CE))
        val crumbs = RecordingBreadcrumbs()

        useCase(FakeGateway(), FakeInstaller(calls = calls), crumbs)(
            source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = true,
        )

        assertEquals(2, calls.count { it == "force-stop" })
        assertEquals("force-stop", calls.last())
        assertNull(crumbs.current)
    }

    // "A failure reports the classes that did land" lives with the four guard fixtures at the foot
    // of this file, as `a swap that fails names the class whose data may be gone` — one fixture, and
    // both halves of what that failure has to report, rather than two tests over the same setup.

    @Test
    fun `a class the archive does not hold is a failure that keeps the breadcrumb`() = runTest {
        // Reached only when a caller asks for a class the header never recorded. It happens after the
        // breadcrumb is written, so it must take the same exit as any other mid-run failure.
        val (header, source) = archive(listOf(DataClass.CE))
        val crumbs = RecordingBreadcrumbs()

        val outcome = useCase(FakeGateway(), FakeInstaller(calls = calls), crumbs)(
            source, header, key, listOf(DataClass.CE, DataClass.DE), installFirst = false, restoreObb = false,
        )

        val failed = outcome as ArchiveRestoreOutcome.Failed
        assertTrue(failed.reason, failed.reason.contains(DataClass.DE.id))
        assertEquals(listOf(DataClass.CE), failed.classesRestored)
        assertNotNull(crumbs.current)
    }

    @Test
    fun `a cancellation between the decrypt and the destructive call stops the restore`() = runTest {
        // The decrypt is the one long stretch of this use case with no suspension point in it, so a
        // cancellation arriving during it is not observed by anything downstream on its own — a
        // `withContext` onto the dispatcher the caller is already on resumes undispatched and does not
        // check. Without the explicit checkpoint, a cancelled restore goes on to replace the class
        // root anyway.
        val (header, source) = archive(listOf(DataClass.CE))
        val job = Job()
        val gateway = FakeGateway(onStagingFile = { job.cancel() })
        val crumbs = RecordingBreadcrumbs()

        val thrown = runCatching {
            withContext(job) {
                useCase(gateway, FakeInstaller(calls = calls), crumbs)(
                    source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = false,
                )
            }
        }.exceptionOrNull()

        assertTrue(thrown.toString(), thrown is CancellationException)
        assertFalse(calls.toString(), calls.any { it.startsWith("extract") || it.startsWith("swap") })
        // An interrupted restore is exactly what §8.5's breadcrumb is for; a cancellation must not
        // clear it.
        assertNotNull(crumbs.current)
    }

    @Test
    fun `progress reports an unknown total until the first class lands`() = runTest {
        // The byte counter only moves when a *whole class* lands, so before the first one does,
        // nothing has been measured: that is `total = 0` — an indeterminate bar — not a literal 0 %,
        // which through a multi-gigabyte decrypt is indistinguishable from a stalled job. Asserted on
        // `total` rather than on `percent`, because a *genuine* integer zero later (a 1 KB class out
        // of 10 GB) is a known zero and is allowed.
        val (header, source) = archive(listOf(DataClass.CE, DataClass.DE))
        val seen = mutableListOf<ThorJobProgress>()

        useCase(FakeGateway(), FakeInstaller(calls = calls), RecordingBreadcrumbs())(
            source, header, key, listOf(DataClass.CE, DataClass.DE),
            installFirst = false, restoreObb = false,
            onProgress = { seen += it },
        )

        assertTrue(seen.isNotEmpty())
        assertEquals(0L, seen.first().total)
        // And it stops being unknown once a class has landed — `total = 0` forever would be the same
        // defect from the other side. Pinned to a RESTORING emission on purpose: the FINISHING one at
        // the end always carries the full total, so an `any {}` over every stage would pass even if
        // the bar stayed indeterminate for the whole restore.
        assertTrue(
            seen.toString(),
            seen.drop(1).any { it.stage == ThorJobStage.RESTORING && it.total > 0L },
        )
    }

    @Test
    fun `a cache that cannot be written fails with the classes that did land`() = runTest {
        // Running out of room in Thor's internal cache is *the* expected failure for a multi-gigabyte
        // restore, and an `IOException` is not an `ArchiveIntegrityException`. Letting it escape as a
        // throw takes `classesRestored` with it — and "CE is already replaced" is the whole difference
        // between a message the user can act on and one they cannot.
        val (header, source) = archive(listOf(DataClass.CE, DataClass.DE))
        val crumbs = RecordingBreadcrumbs()

        val outcome = useCase(
            FakeGateway(unwritableStaging = "restore-${DataClass.DE.id}.tar"),
            FakeInstaller(calls = calls),
            crumbs,
        )(
            source, header, key,
            listOf(DataClass.CE, DataClass.DE),
            installFirst = false,
            restoreObb = false,
        )

        val failed = outcome as ArchiveRestoreOutcome.Failed
        assertEquals(listOf(DataClass.CE), failed.classesRestored)
        assertTrue(failed.reason, failed.reason.contains(DataClass.DE.id))
        // CE was replaced before DE's decrypt died, so this is an interrupted restore.
        assertNotNull(crumbs.current)
    }

    @Test
    fun `a data-only archive asked for game data restores the data and warns`() = runTest {
        // "Restore game data" left on over a backup that holds no `.xapk` is not a failure to place —
        // there is nothing to place. Failing here wrote no data at all and told the user the bundle
        // "could not be read" about an archive that never claimed to have one.
        val (header, source) = archive(listOf(DataClass.CE), withBundle = false)
        val crumbs = RecordingBreadcrumbs()

        val outcome = useCase(FakeGateway(), FakeInstaller(calls = calls), crumbs)(
            source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = true,
        )

        val completed = outcome as ArchiveRestoreOutcome.Completed
        assertEquals(listOf(DataClass.CE), completed.classesRestored)
        assertTrue(completed.warnings.toString(), completed.warnings.any { it.contains("no game data") })
        assertFalse(calls.toString(), calls.contains("obb"))
        assertNull(completed.obb)
        assertNull(crumbs.current)
    }

    @Test
    fun `a breadcrumb that could not be written is a warning, not a silent restore`() = runTest {
        // The restore still runs — refusing it would mean a flag file's failure costs the user their
        // data restore — but §8.5's notice is now unavailable, and saying so is the only way the user
        // learns that an interruption from here on would have gone unreported.
        val (header, source) = archive(listOf(DataClass.CE))

        val outcome = useCase(
            FakeGateway(),
            FakeInstaller(calls = calls),
            RecordingBreadcrumbs(writes = false),
        )(source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = false)

        val completed = outcome as ArchiveRestoreOutcome.Completed
        assertEquals(listOf(DataClass.CE), completed.classesRestored)
        assertTrue(
            completed.warnings.toString(),
            completed.warnings.any { it.contains("interrupted") },
        )
    }

    // --- the four gateway guards after the decrypt ----------------------------------------------
    // One fixture each. Three of them had none, and deleting all three left the suite green — which
    // is how a guard whose failure message contradicts the outcome it produces survives a review.

    @Test
    fun `an extraction that fails replaced nothing`() = runTest {
        // Extraction unpacks into the class root's staging directory, never over the live data, so
        // this failure is the last one that leaves the class exactly as the user left it. The swap
        // must not be reached, and nothing may be reported as at risk.
        val (header, source) = archive(listOf(DataClass.CE))

        val outcome = useCase(
            FakeGateway(failOn = "extract:ce"), FakeInstaller(calls = calls), RecordingBreadcrumbs()
        )(source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = false)

        val failed = outcome as ArchiveRestoreOutcome.Failed
        assertTrue(failed.reason, failed.reason.contains("unpacked"))
        assertEquals(emptyList<DataClass>(), failed.classesRestored)
        assertNull(failed.classPossiblyCleared)
        assertFalse(calls.toString(), calls.any { it.startsWith("swap") })
    }

    @Test
    fun `a swap that fails names the class whose data may be gone`() = runTest {
        // The worst outcome this use case can produce, and the one the result type could not
        // describe: `swapStagedEntriesCommand` deletes the class root's entries and then moves the
        // staged ones in, so a non-zero exit spans "nothing was touched" and "the delete ran and the
        // move did not". Thor cannot tell which and cannot undo either — §8.3 has no undo rung — so
        // the one thing left is to name the class instead of reporting a plain failure over data
        // that may no longer exist.
        val (header, source) = archive(listOf(DataClass.CE, DataClass.DE))

        val outcome = useCase(
            FakeGateway(failOn = "swap:de"), FakeInstaller(calls = calls), RecordingBreadcrumbs()
        )(source, header, key, listOf(DataClass.CE, DataClass.DE), installFirst = false, restoreObb = false)

        val failed = outcome as ArchiveRestoreOutcome.Failed
        assertEquals(DataClass.DE, failed.classPossiblyCleared)
        // Not in `classesRestored`: it did not land. Both facts are needed, which is why they are
        // two fields — CE is replaced and DE may be neither replaced nor intact.
        assertEquals(listOf(DataClass.CE), failed.classesRestored)
        // The reason carries it too, because the reason is the part every consumer already shows.
        assertTrue(failed.reason, failed.reason.contains("cannot tell"))
    }

    @Test
    fun `a chown that fails still counts the class as restored`() = runTest {
        // `chownClass` runs *after* the swap: the class root already holds the archive's copy. The
        // KDoc on `classesRestored` says it lists the classes that did land, and excluding this one
        // contradicted that — leaving the user told a restore failed with no hint that their old
        // data is already gone from that class.
        val (header, source) = archive(listOf(DataClass.CE))

        val outcome = useCase(
            FakeGateway(failOn = "chown:ce"), FakeInstaller(calls = calls), RecordingBreadcrumbs()
        )(source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = false)

        val failed = outcome as ArchiveRestoreOutcome.Failed
        assertTrue(failed.reason, failed.reason.contains("ownership"))
        assertEquals(listOf(DataClass.CE), failed.classesRestored)
        // Replaced, not "possibly cleared": the swap succeeded and the data is in place.
        assertNull(failed.classPossiblyCleared)
        assertFalse(calls.toString(), calls.contains("relabel:ce"))
    }

    @Test
    fun `a relabel that fails still counts the class as restored`() = runTest {
        // Same contract as the chown above, and the more likely of the two on a real device: a tree
        // with the right owner and the wrong SELinux context is the classic "restore said it worked
        // and the app crashes on launch".
        val (header, source) = archive(listOf(DataClass.CE))

        val outcome = useCase(
            FakeGateway(failOn = "relabel:ce"), FakeInstaller(calls = calls), RecordingBreadcrumbs()
        )(source, header, key, listOf(DataClass.CE), installFirst = false, restoreObb = false)

        val failed = outcome as ArchiveRestoreOutcome.Failed
        assertTrue(failed.reason, failed.reason.contains("security labels"))
        assertEquals(listOf(DataClass.CE), failed.classesRestored)
        assertNull(failed.classPossiblyCleared)
    }

    @Test
    fun `an install-first restore says so when it places game data the user left out`() = runTest {
        // `installBundle` writes the bundle's OBB as part of the install; there is no install-first
        // path that honours "leave the game data out". The flag used to be dropped in silence behind
        // a checkbox the screen went on offering.
        val (header, source) = archive(listOf(DataClass.CE))

        val outcome = useCase(
            FakeGateway(), FakeInstaller(calls = calls), RecordingBreadcrumbs()
        )(source, header, key, listOf(DataClass.CE), installFirst = true, restoreObb = false)

        val completed = outcome as ArchiveRestoreOutcome.Completed
        assertTrue(
            completed.warnings.toString(),
            completed.warnings.any { it.contains("game data") },
        )
        // Placed by the install, not by the OBB branch, which install-first never enters.
        assertFalse(calls.toString(), calls.contains("obb"))
        assertNull(completed.obb)
    }

    @Test
    fun `an install-first restore over an archive with no game data says nothing about it`() =
        runTest {
            // The warning is about a flag that could not be honoured. An archive whose bundle holds
            // no `.obb` had nothing to leave out, so there is nothing to explain — a warning here
            // would be noise on the ordinary case.
            val (header, source) = archive(listOf(DataClass.CE))
            val noObb = header.copy(appBundle = header.appBundle!!.copy(obbCapture = "none", obbCount = 0))

            val outcome = useCase(
                FakeGateway(), FakeInstaller(calls = calls), RecordingBreadcrumbs()
            )(source, noObb, key, listOf(DataClass.CE), installFirst = true, restoreObb = false)

            val completed = outcome as ArchiveRestoreOutcome.Completed
            assertEquals(emptyList<String>(), completed.warnings)
        }

    private companion object {
        const val SIGNER = "AB"
    }
}
