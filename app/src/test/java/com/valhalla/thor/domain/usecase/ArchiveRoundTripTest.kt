// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.data.backup.CHUNK_PLAINTEXT_BYTES
import com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator
import com.valhalla.thor.data.repository.ZipArchiveSource
import com.valhalla.thor.domain.model.ArchiveBackupOutcome
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveRestoreDecision
import com.valhalla.thor.domain.model.ArchiveRestoreRefusal
import com.valhalla.thor.domain.model.ClassEntries
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.InstalledAppFacts
import com.valhalla.thor.domain.model.ObbPlacement
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.TarOutcome
import com.valhalla.thor.domain.model.THORBAK_HEADER_ENTRY
import com.valhalla.thor.domain.model.evaluateArchiveRestoreGate
import com.valhalla.thor.domain.repository.AppArchiveInstaller
import com.valhalla.thor.domain.repository.AppArchiveStore
import com.valhalla.thor.domain.repository.AppDataArchiveGateway
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.domain.repository.ArchiveBreadcrumb
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import com.valhalla.thor.domain.repository.ArchiveDestination
import com.valhalla.thor.domain.repository.ArchiveInstallOutcome
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * One archive, written by the real backup path and read back by the real restore path.
 *
 * **Why this file exists.** Every other test on this feature builds its own fixture: the backup tests
 * unzip what backup wrote and inspect it, and the restore tests hand-build an [ArchiveHeader] and a
 * `Map<String, ByteArray>` and restore from that. Neither side has ever consumed the other's output,
 * so any disagreement between the two halves — a header field stamped with one number and read with
 * another, a member encrypted under one AAD and decrypted under another, a class the writer skips and
 * the gate then refuses — passes both suites green and produces a backup that cannot be restored. Two
 * reviewers each found one such defect independently. This is the test that would have found both.
 *
 * **What is real here.** The format, the container, the cipher, the header, the gate, and both use
 * cases. The archive is written to a real file through a real [ZipOutputStream] and read back through
 * [ZipArchiveSource], which is a JDK `ZipFile` — so the central directory, the STORED header entry and
 * the level-0 member entries are all exercised as they ship. The key the restore half uses is derived
 * from **the passphrase and the header**, never handed across from the backup half; that is what pins
 * the salt, the verifier and the iteration count to each other.
 *
 * **What is faked, and only this.** Four things, each of which is an Android or root-shell boundary
 * that has no JVM implementation:
 *  - [AppDataArchiveGateway] — `tar`, `cp`, `chown` and `restorecon` through a privileged shell.
 *  - [AppArchiveStore] — SAF/MediaStore. Replaced by a plain file in a [TemporaryFolder], which is
 *    what makes the container a real file rather than a `ByteArrayOutputStream`.
 *  - [AppArchiveInstaller] — `PackageInstaller`.
 *  - [ArchiveBreadcrumbStore] — `filesDir`.
 *
 * The one link that cannot be closed on the JVM at all is the WorkManager wiring between them
 * (`ThorJobLauncher` → `ArchiveBackupWorker`/`ArchiveRestoreWorker`): it needs `Context`, and this
 * module has no Robolectric and no `work-testing`. That seam is covered by unit tests over the pieces
 * either side of it, and this test starts and ends immediately inside it.
 *
 * The KDF runs at the shipped [com.valhalla.thor.domain.model.KDF_ITERATIONS] on purpose. Every other
 * test on this feature passes `iterations = 4` for speed, and doing that here would defeat the point:
 * the header stamps the shipped constant unconditionally, so a key derived at any other count would
 * make the archive unopenable and no test that supplies its own count can see it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveRoundTripTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val cipher = AppArchiveCipher()
    private val openArchive = OpenArchiveUseCase(cipher, UnconfinedTestDispatcher())

    private val passphrase get() = "correct horse battery staple".toCharArray()

    /**
     * A real file, written through a `.part` and renamed on publish — the same two-step
     * `AppArchiveStoreImpl` performs, because a half-written container that already carries its final
     * name is the failure mode the two-step exists to prevent.
     */
    private class FileDestination(private val target: File) : ArchiveDestination {
        private val part = File(target.parentFile, "${target.name}.part")
        private val stream = BufferedOutputStream(FileOutputStream(part))

        override val output: OutputStream = stream

        override suspend fun publish(): Boolean {
            stream.flush()
            stream.close()
            return part.renameTo(target)
        }

        override suspend fun discard() {
            runCatching { stream.close() }
            part.delete()
        }
    }

    private inner class FileStore(private val dir: File) : AppArchiveStore {
        var file: File? = null

        override suspend fun openArchive(fileName: String): ArchiveDestination? =
            FileDestination(File(dir, fileName).also { file = it })

        override suspend fun currentTargetLabel(): String = dir.name

        override suspend fun discardOrphans(names: Set<String>): Set<String> = emptySet()
    }

    /**
     * One gateway for both halves, because on a device it *is* one gateway.
     *
     * [tarred] is what `tar` produced on the way out; [extracted] is what arrived at `extractInto` on
     * the way back in. The whole round trip reduces to those two maps being equal.
     */
    private inner class RoundTripGateway(
        private val payloads: Map<DataClass, ByteArray>,
        private val emptyRoots: Set<DataClass> = emptySet(),
    ) : AppDataArchiveGateway {
        val tarred = mutableMapOf<DataClass, ByteArray>()
        val extracted = mutableMapOf<DataClass, ByteArray>()
        val swapped = mutableListOf<DataClass>()

        override suspend fun thorUserId(): Int = 0
        override suspend fun externalStorageDir(): String = "/storage/emulated/0"
        override suspend fun stagingFile(name: String): File = temp.newFile("staging-${stage++}-$name")
        override suspend fun forceStop(packageName: String) = Unit

        override suspend fun listClass(packageName: String, dataClass: DataClass): ClassEntries = when {
            dataClass in emptyRoots -> ClassEntries(emptyList(), emptyList(), rootAbsent = false)
            dataClass in payloads -> ClassEntries(listOf("files", "databases"), emptyList(), false)
            else -> ClassEntries(emptyList(), emptyList(), rootAbsent = true)
        }

        override suspend fun tarClass(
            packageName: String,
            dataClass: DataClass,
            entries: List<String>,
            out: File,
            compress: Boolean,
        ): TarOutcome {
            val bytes = payloads.getValue(dataClass)
            out.writeBytes(bytes)
            tarred[dataClass] = bytes
            return TarOutcome.Succeeded
        }

        override suspend fun appUid(packageName: String): Int? = 10123
        override suspend fun signerSha256(packageName: String): String? = SIGNER

        override suspend fun extractInto(
            packageName: String,
            dataClass: DataClass,
            tar: File,
            compressed: Boolean,
        ): Boolean {
            extracted[dataClass] = tar.readBytes()
            return true
        }

        override suspend fun swapStaged(packageName: String, dataClass: DataClass): Boolean {
            swapped += dataClass
            return true
        }

        override suspend fun chownClass(packageName: String, dataClass: DataClass, uid: Int) = true
        override suspend fun relabelClass(packageName: String, dataClass: DataClass) = true

        private var stage = 0
    }

    private class CapturingInstaller : AppArchiveInstaller {
        var installedBytes: ByteArray? = null

        override suspend fun installBundle(
            bundle: File,
            packageName: String,
            execution: PrivilegeExecutionContext,
        ): ArchiveInstallOutcome {
            installedBytes = bundle.readBytes()
            return ArchiveInstallOutcome.Installed
        }

        override suspend fun placeBundleObb(
            bundle: File,
            packageName: String,
            onFile: (String, Int, Int) -> Unit,
        ): ObbPlacement = ObbPlacement.NotNeeded
    }

    private class NoopBreadcrumbs : ArchiveBreadcrumbStore {
        private var current: ArchiveBreadcrumb? = null
        override suspend fun write(packageName: String, appLabel: String): Boolean {
            current = ArchiveBreadcrumb(packageName, appLabel, startedAt = 1L)
            return true
        }

        override suspend fun read(): ArchiveBreadcrumb? = current
        override fun observe(): Flow<ArchiveBreadcrumb?> = flow { emit(read()) }
        override suspend fun clear() {
            current = null
        }
    }

    private class NoProbe : AppDataProbe {
        override suspend fun probePrivateDataCapability(): Boolean = true
        override suspend fun probeDataArchiveCapability(): Boolean = true
        override suspend fun measureDataClass(packageName: String, dataClass: DataClass) =
            DataClassSize.Undetermined
    }

    /**
     * Run the real backup path and return the container it wrote, plus the gateway that produced it.
     *
     * The key is derived here the way `ThorJobLauncher.startBackup` derives it — passphrase and the
     * request's salt, at the default count — and is deliberately **not** shared with the restore half.
     */
    private suspend fun writeArchive(
        payloads: Map<DataClass, ByteArray>,
        emptyRoots: Set<DataClass> = emptySet(),
        bundle: File? = null,
    ): Triple<File, RoundTripGateway, ArchiveBackupOutcome> {
        val gateway = RoundTripGateway(payloads, emptyRoots)
        val store = FileStore(temp.newFolder("out-${counter++}"))
        val request = ArchiveBackupRequest(
            packageName = PACKAGE,
            classes = payloads.keys + emptyRoots,
            includeBundle = bundle != null,
            salt = cipher.newSalt(),
        )
        val key = cipher.deriveKey(passphrase, request.salt)
        val outcome = BackupAppArchiveUseCase(
            gateway,
            store,
            cipher,
            NoProbe(),
            DefaultPackageOperationCoordinator(),
        )(
            request = request,
            key = key,
            bundle = bundle,
            versionCode = VERSION_CODE,
            versionName = "1.0",
        )
        return Triple(store.file!!, gateway, outcome)
    }

    @Test
    fun `an archive the backup path wrote is opened, unlocked, gated and restored byte for byte`() =
        runTest {
            // Three sizes on purpose: one that spans several GCM frames, one that is exactly one frame
            // — the boundary the framing's `isFinal` flag is easiest to get wrong on — and one short.
            val payloads = mapOf(
                DataClass.CE to bytes(2 * CHUNK_PLAINTEXT_BYTES + 7),
                DataClass.DE to bytes(CHUNK_PLAINTEXT_BYTES),
                DataClass.EXTERNAL_DATA to bytes(17),
            )
            val (file, writer, backup) = writeArchive(payloads)
            assertTrue(backup.toString(), backup is ArchiveBackupOutcome.Completed)

            val reader = RoundTripGateway(emptyMap())
            val restored = ZipArchiveSource(file, file.name).use { source ->
                val header = (openArchive.readHeader(source) as ArchiveHeaderOutcome.Read).header

                // Derived from the passphrase and the header alone. Nothing about the key the backup
                // half used crosses this line, so the header's salt, rounds and verifier are all under
                // test at once.
                val unlocked = openArchive.unlock(header, passphrase)
                assertTrue(unlocked.toString(), unlocked is ArchiveUnlockOutcome.Unlocked)

                // Critical 2, as an assertion: what the backup half kept must be what the gate admits.
                val decision =
                    evaluateArchiveRestoreGate(header, installed(), header.heldClasses().toSet())
                assertEquals(
                    ArchiveRestoreDecision.Allowed(installFirst = false, warnings = emptyList()),
                    decision,
                )

                RestoreAppArchiveUseCase(
                    reader,
                    CapturingInstaller(),
                    NoopBreadcrumbs(),
                    cipher,
                    DefaultPackageOperationCoordinator(),
                )(
                    source = source,
                    header = header,
                    key = (unlocked as ArchiveUnlockOutcome.Unlocked).key,
                    classes = header.heldClasses(),
                    installFirst = false,
                    restoreObb = false,
                )
            }

            assertTrue(restored.toString(), restored is ArchiveRestoreOutcome.Completed)
            assertEquals(payloads.keys, reader.extracted.keys)
            for ((dataClass, expected) in writer.tarred) {
                assertArrayEquals(dataClass.id, expected, reader.extracted[dataClass])
            }
            assertEquals(payloads.keys.toList(), reader.swapped)
        }

    @Test
    fun `only the passphrase that wrote the archive reopens it`() = runTest {
        val (file, _, _) = writeArchive(mapOf(DataClass.CE to bytes(64)))

        ZipArchiveSource(file, file.name).use { source ->
            val header = (openArchive.readHeader(source) as ArchiveHeaderOutcome.Read).header
            assertEquals(
                ArchiveUnlockOutcome.WrongPassphrase,
                openArchive.unlock(header, "not the passphrase".toCharArray()),
            )
        }
    }

    /**
     * §7.2 step 7a drops a class whose root is present and empty. §8.1 refuses a selected class with no
     * member. Those two rules are written in different files by different halves of the feature, and
     * this is the only place they meet: the archive really does omit the class, and the real gate
     * really does refuse it — while the classes that *were* kept are admitted from the same header.
     */
    @Test
    fun `the gate admits exactly the classes the backup kept`() = runTest {
        val (file, _, _) = writeArchive(
            payloads = mapOf(DataClass.CE to bytes(64)),
            emptyRoots = setOf(DataClass.DE),
        )

        ZipArchiveSource(file, file.name).use { source ->
            val header = (openArchive.readHeader(source) as ArchiveHeaderOutcome.Read).header
            assertEquals(listOf(DataClass.CE), header.heldClasses())

            assertEquals(
                ArchiveRestoreDecision.Allowed(installFirst = false, warnings = emptyList()),
                evaluateArchiveRestoreGate(header, installed(), header.heldClasses().toSet()),
            )
            assertEquals(
                ArchiveRestoreDecision.Refused(ArchiveRestoreRefusal.CLASS_NOT_IN_ARCHIVE),
                evaluateArchiveRestoreGate(header, installed(), setOf(DataClass.CE, DataClass.DE)),
            )
        }
    }

    /**
     * The app-absent lane, end to end: the gate answers `installFirst = true` off the header the
     * backup half wrote, and the bytes that reach `PackageInstaller` are the bytes the backup half was
     * handed. A `.xapk` that survives the container is the difference between a restore that reinstalls
     * an app and one that installs nothing.
     */
    @Test
    fun `an install-first round trip hands the installer the bundle it was given`() = runTest {
        val bundleBytes = bytes(CHUNK_PLAINTEXT_BYTES / 4)
        val bundle = temp.newFile("app.xapk").apply { writeBytes(bundleBytes) }
        val (file, _, backup) = writeArchive(
            payloads = mapOf(DataClass.CE to bytes(128)),
            bundle = bundle,
        )
        assertTrue(backup.toString(), backup is ArchiveBackupOutcome.Completed)

        val installer = CapturingInstaller()
        val reader = RoundTripGateway(emptyMap())
        val restored = ZipArchiveSource(file, file.name).use { source ->
            val header = (openArchive.readHeader(source) as ArchiveHeaderOutcome.Read).header

            // installed = null: the app is gone, which is the only state that reaches installFirst.
            val decision = evaluateArchiveRestoreGate(header, null, header.heldClasses().toSet())
            assertEquals(
                ArchiveRestoreDecision.Allowed(installFirst = true, warnings = emptyList()),
                decision,
            )

            val unlocked = openArchive.unlock(header, passphrase) as ArchiveUnlockOutcome.Unlocked
            RestoreAppArchiveUseCase(
                reader,
                installer,
                NoopBreadcrumbs(),
                cipher,
                DefaultPackageOperationCoordinator(),
            )(
                source = source,
                header = header,
                key = unlocked.key,
                classes = header.heldClasses(),
                installFirst = true,
                restoreObb = false,
            )
        }

        assertTrue(restored.toString(), restored is ArchiveRestoreOutcome.Completed)
        assertArrayEquals(bundleBytes, installer.installedBytes)
    }

    /**
     * One flipped byte in a member, and the live data is untouched.
     *
     * §8.3 decrypts a member whole before `extractInto` runs, so a damaged archive must fail *before*
     * anything on the device is written. Asserted here against a real container rather than a
     * hand-built fixture, because "the member decrypts" and "the member is what backup wrote" are the
     * same question only when the same code wrote it.
     */
    @Test
    fun `a tampered member fails the restore before the class root is touched`() = runTest {
        val (file, _, _) = writeArchive(mapOf(DataClass.CE to bytes(4096)))
        val member = DataClass.CE.memberName(compressed = true)
        val tampered = File(temp.root, "tampered.thorbak")
        rewrite(file, tampered, corrupt = member)

        val reader = RoundTripGateway(emptyMap())
        val restored = ZipArchiveSource(tampered, tampered.name).use { source ->
            val header = (openArchive.readHeader(source) as ArchiveHeaderOutcome.Read).header
            val unlocked = openArchive.unlock(header, passphrase) as ArchiveUnlockOutcome.Unlocked
            RestoreAppArchiveUseCase(
                reader,
                CapturingInstaller(),
                NoopBreadcrumbs(),
                cipher,
                DefaultPackageOperationCoordinator(),
            )(
                source = source,
                header = header,
                key = unlocked.key,
                classes = listOf(DataClass.CE),
                installFirst = false,
                restoreObb = false,
            )
        }

        assertTrue(restored.toString(), restored is ArchiveRestoreOutcome.Failed)
        assertTrue(reader.extracted.isEmpty())
        assertTrue(reader.swapped.isEmpty())
        // Nothing was replaced, so nothing may be reported as replaced.
        assertTrue((restored as ArchiveRestoreOutcome.Failed).classesRestored.isEmpty())
    }

    /** Deterministic, and not a repeating block — a framing bug that duplicates a chunk must show. */
    private fun bytes(size: Int) = ByteArray(size) { (it * 31 + 7).toByte() }

    private fun installed(versionCode: Long = VERSION_CODE) =
        InstalledAppFacts(signerSha256 = SIGNER, versionCode = versionCode, versionName = "1.0")

    /**
     * Copy a container entry for entry, flipping one byte in [corrupt]'s payload.
     *
     * Entry-by-entry rather than a byte patch of the file: the member entries are deflated at level 0,
     * so a byte at a known offset in the plaintext is not at a known offset in the container.
     */
    private fun rewrite(from: File, to: File, corrupt: String) {
        ZipOutputStream(to.outputStream()).use { out ->
            ZipInputStream(from.inputStream()).use { input ->
                var entry = input.nextEntry
                while (entry != null) {
                    val body = input.readBytes()
                    if (entry.name == corrupt) {
                        // Well past the frame-length prefix, so this is ciphertext and the failure is
                        // a GCM tag mismatch rather than a malformed frame.
                        body[body.size / 2] = (body[body.size / 2].toInt() xor 0x40).toByte()
                    }
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(body)
                    out.closeEntry()
                    entry = input.nextEntry
                }
            }
        }
        // The header has to survive the copy or the failure under test would be the wrong one.
        ZipArchiveSource(to, to.name).use { assertTrue(THORBAK_HEADER_ENTRY in it.entryNames()) }
    }

    private companion object {
        const val PACKAGE = "com.example.app"
        const val VERSION_CODE = 100L
        const val SIGNER = "AB"
        var counter = 0
    }
}
