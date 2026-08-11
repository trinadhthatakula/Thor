// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.domain.model.ARCHIVE_SPACE_MARGIN_BYTES
import com.valhalla.thor.domain.model.ArchiveBackupOutcome
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveBundleInfo
import com.valhalla.thor.domain.model.ArchiveCompression
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveKdf
import com.valhalla.thor.domain.model.ArchiveMember
import com.valhalla.thor.domain.model.ArchiveSkip
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.KDF_ITERATIONS
import com.valhalla.thor.domain.model.TarOutcome
import com.valhalla.thor.domain.model.THORBAK_BUNDLE_ENTRY
import com.valhalla.thor.domain.model.THORBAK_HEADER_ENTRY
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.domain.model.thorbakFileName
import com.valhalla.thor.domain.repository.AppArchiveStore
import com.valhalla.thor.domain.repository.AppDataArchiveGateway
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.util.Logger
import java.io.File
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory

private const val TAG = "BackupAppArchive"

/**
 * §7.2, as one function.
 *
 * The invariant that shapes the whole body: **one zip stream, held open across every class.** Members
 * are appended to it and each staged tar is deleted as soon as it has been encrypted into it, so peak
 * disk is the largest single class rather than the sum of all four. Staging every tar first and then
 * zipping would be shorter and would need four times the space.
 *
 * Nothing here touches WorkManager. The worker owns the job lifecycle; this owns the sequence, which is
 * why it is testable at all.
 *
 * **Note:** `AppArchiveCipher` is in `data.backup` and this use case is in `domain.usecase`. That
 * import crosses the layering boundary deliberately — the plan chose this over an abstraction that would
 * make `AppArchiveCipher` untestable via JCE in the domain tests. The reviewer is aware.
 */
@Factory
internal class BackupAppArchiveUseCase(
    private val gateway: AppDataArchiveGateway,
    private val archiveStore: AppArchiveStore,
    private val cipher: AppArchiveCipher,
    /** §7.4 only: the pre-flight space check needs a size before it stages a class. */
    private val probe: AppDataProbe,
) {

    /**
     * @param key derived by the caller from the passphrase and [ArchiveBackupRequest.salt], handed over
     *   in memory. This function never sees a passphrase.
     * @param onProgress called on the calling coroutine. The worker forwards it to `JobRegistry`.
     * @param bundle an already-built `.xapk` to embed, or null. Built by the caller because
     *   `AppBundleBuilder` needs an `AppInfo`, which is the worker's to resolve.
     * @param bundleObbCapture `ObbProbe`'s tri-state name for what the bundle holds, and
     *   [bundleObbCount] how many `.obb` files it carries. Recorded verbatim so restore never implies
     *   game data it does not have.
     * @param usableStagingBytes §7.4. Free bytes on the staging volume, **measured by the caller** —
     *   the same division of labour as [com.valhalla.thor.domain.usecase.BackupAppsUseCase], where the
     *   number comes from `data` (which has a `Context`) and the rule lives here. `0` means "could not
     *   be measured", and the rule fails open on it, which is why `0` is also the default: a caller
     *   that does not measure gets today's behaviour rather than a refusal.
     * @param appLabel what every [ThorJobProgress] this run emits is labelled with — and therefore the
     *   only thing the notification's content text ever says. Mirrors [RestoreAppArchiveUseCase]'s
     *   parameter of the same name, and for the same reason: resolving an `AppInfo` needs a
     *   `PackageManager`, which is the worker's to have, not this function's. Defaults to the package
     *   name so a caller that has no label still labels every tick with something stable — the
     *   internal names of the thing being captured (`bundle.name`, `dataClass.id`) are progress
     *   detail, not a caption for a user.
     */
    suspend operator fun invoke(
        request: ArchiveBackupRequest,
        key: SecretKey,
        bundle: File? = null,
        bundleObbCapture: String = "none",
        bundleObbCount: Int = 0,
        versionCode: Long = 0L,
        versionName: String? = null,
        usableStagingBytes: Long = 0L,
        appLabel: String = request.packageName,
        onProgress: (ThorJobProgress) -> Unit = {},
    ): ArchiveBackupOutcome {
        val fileName = thorbakFileName(request.packageName, versionCode)
        val destination = archiveStore.openArchive(fileName) ?: return ArchiveBackupOutcome.NoDestination

        // Read before anything is written. Without a signer the archive cannot carry the check that
        // stops a restore into a same-named, differently-signed package, and an archive missing that
        // field is one a later Thor would have to either refuse or trust.
        val signer = gateway.signerSha256(request.packageName)
        if (signer == null) {
            withContext(NonCancellable) { destination.discard() }
            return ArchiveBackupOutcome.Failed("the app's signing certificate could not be read")
        }

        val members = mutableListOf<ArchiveMember>()
        val skipped = mutableListOf<ArchiveSkip>()
        val warnings = mutableListOf<String>()
        var published = false

        try {
            onProgress(ThorJobProgress(ThorJobStage.PREPARING, appLabel))
            // §7.2 step 4: once, before the first class. Not per class.
            gateway.forceStop(request.packageName)

            // Level 0 for the streamed entries: the members are ciphertext and the bundle is already
            // compressed, so deflate would spend CPU to occasionally grow the file. STORED is not an
            // option for them — it demands the CRC before `putNextEntry`, which is unknowable for a
            // stream being generated. The header, built in memory, is STORED; see below.
            val zip = ZipOutputStream(destination.output).apply { setLevel(Deflater.NO_COMPRESSION) }

            if (bundle != null) {
                // [appLabel], not `bundle.name`: this label is the notification's whole content text,
                // and a user watching their game back up should not read a generated `.xapk` file name.
                onProgress(ThorJobProgress(ThorJobStage.CAPTURING, appLabel))
                zip.putNextEntry(ZipEntry(THORBAK_BUNDLE_ENTRY))
                bundle.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }

            // Iterated in DataClass order, not the request's set order, so two runs over the same
            // selection produce members in the same order.
            val selected = DataClass.entries.filter { it in request.classes }
            // total = selected.size + 1 so we never claim 100 % before the last class is done:
            // the final slot is consumed by FINISHING (which emits with total = 0 = indeterminate).
            val progressTotal = (selected.size + 1).toLong()
            for ((index, dataClass) in selected.withIndex()) {
                onProgress(
                    ThorJobProgress(
                        stage = ThorJobStage.CAPTURING,
                        // [appLabel], not `dataClass.id`: `internal_data` is an on-disk class name,
                        // not something to show a user. Which class is in flight is carried by
                        // `completed`/`total`, and the stage says what is happening.
                        label = appLabel,
                        // completed carries the 1-based class index — not bytes. The field is
                        // `completed` (not `completedBytes`) precisely because this use case carries
                        // class indices here while restore callers carry byte counts.
                        completed = (index + 1).toLong(),
                        total = progressTotal,
                    )
                )

                // §7.4, per class rather than per run: peak disk is one class, so one class that will
                // not fit is not a reason to abandon the three that would. Recorded as a warning and
                // skipped, exactly as an unreadable root is — never a silent omission.
                val refusal = spaceRefusal(request.packageName, dataClass, usableStagingBytes)
                if (refusal != null) {
                    warnings += "${dataClass.id}: $refusal"
                    continue
                }

                val listing = gateway.listClass(request.packageName, dataClass)
                skipped += listing.skipped
                if (listing.rootAbsent) {
                    warnings += "${dataClass.id}: the directory could not be read or does not exist"
                    continue
                }
                // §7.2 step 7a: an empty class root produces no member at all.
                if (listing.kept.isEmpty()) continue

                val member = captureClass(request, dataClass, listing.kept, key, zip, warnings)
                    ?: continue
                members += member
            }

            // §6: an install-only archive (bundle present, no app data) is a valid outcome — exactly
            // the case for devices that have no privileged data access. Only refuse when there is
            // truly nothing in the container (no bundle and no data classes).
            if (members.isEmpty() && bundle == null) {
                return ArchiveBackupOutcome.Failed("no data could be captured for ${request.packageName}")
            }

            onProgress(ThorJobProgress(ThorJobStage.FINISHING, appLabel))
            val header = ArchiveHeader(
                createdAt = System.currentTimeMillis(),
                thorVersionCode = BuildConfig.VERSION_CODE,
                packageName = request.packageName,
                versionCode = versionCode,
                versionName = versionName,
                userId = gateway.thorUserId(),
                signerSha256 = signer,
                appBundle = bundle?.let {
                    ArchiveBundleInfo(
                        bytes = it.length(),
                        obbCapture = bundleObbCapture,
                        obbCount = bundleObbCount,
                    )
                },
                kdf = ArchiveKdf(
                    iterations = KDF_ITERATIONS,
                    salt = Base64.getEncoder().encodeToString(request.salt),
                ),
                verifier = Base64.getEncoder().encodeToString(cipher.verifier(key)),
                members = members,
                skippedEntries = skipped,
                warnings = warnings,
            )

            // The header is the **last** entry, because it names every member's nonce and chunk count
            // and those are only known once the member is written. It is also the one entry that is
            // STORED rather than level-0 deflated: it is built in memory, so `size` and `crc` — which
            // `ZipOutputStream` demands *before* `putNextEntry` for a STORED entry, and which no
            // streamed member can supply — are both known here. `setMethod` on the entry overrides the
            // stream's level for this entry alone.
            val headerBytes = header.encode().encodeToByteArray()
            zip.putNextEntry(
                ZipEntry(THORBAK_HEADER_ENTRY).apply {
                    method = ZipEntry.STORED
                    size = headerBytes.size.toLong()
                    compressedSize = headerBytes.size.toLong()
                    crc = CRC32().apply { update(headerBytes) }.value
                }
            )
            zip.write(headerBytes)
            zip.closeEntry()
            // `finish()`, never `close()`. `close()` would close `destination.output` underneath the
            // destination, which owns that stream and closes it inside `publish()`. `finish()` writes
            // the central directory — without it the container has no index and unzips as empty.
            zip.finish()

            published = destination.publish()
            return if (published) {
                ArchiveBackupOutcome.Completed(
                    fileName = fileName,
                    header = header,
                    destinationLabel = archiveStore.currentTargetLabel(),
                )
            } else {
                ArchiveBackupOutcome.Failed("the archive could not be moved to its final name")
            }
        } catch (e: CancellationException) {
            // Rethrow so the coroutine machinery sees the cancellation. The finally below discards
            // the partial destination before the coroutine unwinds further.
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "backup of ${request.packageName} failed", e)
            return ArchiveBackupOutcome.Failed(e.message ?: "the backup failed")
        } finally {
            // NonCancellable: `discard()` is a suspend call and may itself call `withContext`. On a
            // cancelled coroutine any suspension point throws immediately, so wrapping is required.
            // Precedent: `AppDataArchiveGatewayImpl.kt:218`. A partial `.thorbak` that looks like a
            // real archive is worse than no archive — this cleanup must complete regardless.
            if (!published) withContext(NonCancellable) { destination.discard() }
        }
    }

    /**
     * Stage one class as a tar, encrypt it into [zip], delete the tar.
     *
     * @return the member to record, or null when the class could not be captured. A failure here is
     *   per-class: an unreadable `Android/media` must not lose the CE data already in the container.
     */
    private suspend fun captureClass(
        request: ArchiveBackupRequest,
        dataClass: DataClass,
        entries: List<String>,
        key: SecretKey,
        zip: ZipOutputStream,
        warnings: MutableList<String>,
    ): ArchiveMember? {
        // Named by class, so a crashed job leaves one findable file per class rather than a temp name.
        val staged = gateway.stagingFile("${request.packageName}-${dataClass.id}.tar")
        try {
            var compressed = true
            var outcome = gateway.tarClass(request.packageName, dataClass, entries, staged, compress = true)
            if (outcome is TarOutcome.Failed) {
                // §7.2 step 7c: some toybox builds have no gzip. Retry without it and record which one
                // worked, so the reader does not try to gunzip a plain tar.
                compressed = false
                outcome = gateway.tarClass(request.packageName, dataClass, entries, staged, compress = false)
            }
            when (outcome) {
                is TarOutcome.Failed -> {
                    warnings += "${dataClass.id}: ${outcome.reason}"
                    return null
                }

                is TarOutcome.SucceededWithWarning -> warnings += "${dataClass.id}: ${outcome.warning}"
                TarOutcome.Succeeded -> Unit
            }

            val memberName = dataClass.memberName(compressed)
            val nonce = cipher.newNonce()
            zip.putNextEntry(ZipEntry(memberName))
            val stats = staged.inputStream().use { input ->
                cipher.encryptMember(memberName, input, zip, key, nonce)
            }
            zip.closeEntry()

            return ArchiveMember(
                dataClass = dataClass.id,
                fileName = memberName,
                nonce = Base64.getEncoder().encodeToString(nonce),
                plainBytes = stats.plainBytes,
                chunkCount = stats.chunkCount,
                compression = if (compressed) ArchiveCompression.GZIP.id else ArchiveCompression.NONE.id,
            )
        } finally {
            // §7.2 step 7e — the line that keeps peak disk at one class. Deleting it in `finally` means
            // a cancellation mid-encrypt does not leave a plaintext tar of app data in Thor's cache.
            staged.delete()
        }
    }

    /**
     * §7.4. Why this class cannot be staged, or null to go ahead.
     *
     * Follows `BackupAppsUseCase.checkStagingSpace`'s rule verbatim, including the part that looks like
     * a bug and is not: `usableStagingBytes > 0 &&` means an unmeasurable partition **fails open**.
     * Refusing on a number we could not read would block working devices, and the real safety net is
     * downstream — `tar` exits nonzero when it runs out of room and the staged file is deleted on any
     * failure.
     *
     * A [DataClassSize.Undetermined] size also fails open, for the same reason: `du` not answering is
     * not evidence that the class is too big.
     */
    private suspend fun spaceRefusal(
        packageName: String,
        dataClass: DataClass,
        usableStagingBytes: Long,
    ): String? {
        if (usableStagingBytes <= 0L) return null
        val size = probe.measureDataClass(packageName, dataClass)
        if (size !is DataClassSize.Known) return null
        val required = size.bytes + ARCHIVE_SPACE_MARGIN_BYTES
        return if (usableStagingBytes < required) {
            "needs about ${required / (1024 * 1024)} MB free to stage and only " +
                "${usableStagingBytes / (1024 * 1024)} MB is available"
        } else {
            null
        }
    }
}
