// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import com.valhalla.bypass.Bypass
import com.valhalla.thor.data.ACTION_INSTALL_STATUS
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.receivers.InstallReceiver
import com.valhalla.thor.data.source.local.SessionApk
import com.valhalla.thor.data.source.local.installViaSessionCommand
import com.valhalla.thor.data.source.local.privileged.InstallerHandle
import com.valhalla.thor.data.source.local.privileged.PrivilegedInstallerTransport
import com.valhalla.thor.data.source.local.privileged.PrivilegedPackageInstallers
import com.valhalla.thor.data.source.local.privileged.sessionInstallerPackageName
import com.valhalla.thor.data.source.local.privileged.transportFor
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.data.source.local.shizuku.ShizukuReflector
import com.valhalla.thor.data.source.local.shizuku.Shizuku as ShizukuHelper
import com.valhalla.thor.data.source.local.dhizuku.DhizukuHelper
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.model.ObbPlacement
import com.valhalla.thor.domain.model.StagedPackage
import com.valhalla.thor.domain.repository.InstallMode
import com.valhalla.thor.domain.repository.InstallerRepository
import com.valhalla.thor.util.UiText
import com.valhalla.thor.R
import com.valhalla.thor.util.Logger
import com.valhalla.superuser.utils.escapeForShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import kotlinx.coroutines.flow.first
import com.valhalla.thor.domain.repository.PreferenceRepository
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

@Single(binds = [InstallerRepository::class])
class InstallerRepositoryImpl(
    private val context: Context,
    private val eventBus: InstallerEventBus,
    private val rootGateway: RootSystemGateway,
    private val shizukuReflector: ShizukuReflector,
    private val preferenceRepository: PreferenceRepository,
    // The only part of the install path that writes outside app storage; see ObbInstaller.
    private val obbInstaller: ObbInstaller,
    // Carries the session writes, the APK extraction and the hashing.
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
    // Only installWithExternal() uses this: handing the URI to the system's installer chooser is a
    // UI hand-off, so it stays on main. Note this is plain Main, not Main.immediate.
    @Named("main") private val mainDispatcher: CoroutineDispatcher
) : InstallerRepository {

    // The in-process installer. Its sessions are created by Thor's own uid, so the platform's
    // openSession() is the correct opener here — which is why the unprivileged case has to be asked
    // for by name rather than arrived at by leaving an argument off.
    private val defaultInstaller =
        InstallerHandle.unprivileged(context.packageManager.packageInstaller)

    /**
     * Given an on-disk copy of the installer input, return the ordered list of APK
     * base names to install from a genuine bundle (XAPK/.apks/.apkm), or null when
     * it is a monolithic APK that must be streamed whole.
     *
     * Reads the bundle with [BundleZip] (ZipFile / central directory) — NOT
     * ZipInputStream, which cannot handle APKPure's STORED-with-data-descriptor
     * entries. A monolithic APK carries its own top-level AndroidManifest.xml and no
     * bundle signal (GH#207); for real bundles we prefer the manifest.json split
     * list (unioned with any present-but-unlisted splits so a stale manifest never
     * drops one) and otherwise order the .apk entries base-first (GH#159).
     *
     * Goes through [resolveBundlePlan] rather than [resolveBundleInstallSet] directly, which is
     * the same call AppAnalyzerImpl reads its identity candidates out of. The two selections have
     * to come from one function or they are free to disagree — and they did.
     */
    private fun resolveInstallSetFromFile(bundleFile: File, displayName: String?): List<String>? {
        // Single ZipFile pass for entry names + both sidecar files.
        val contents = try {
            BundleZip.read(bundleFile, setOf("manifest.json", "info.json"))
        } catch (_: Exception) {
            return null // not a readable zip → treat as a monolithic APK
        }
        if (isMonolithicApk(contents.entryNames, displayName)) return null

        val manifest = contents.bytes["manifest.json"]?.let { parseXapkManifest(String(it)) }
        val apkmInfo = contents.bytes["info.json"]?.let { parseApkmInfo(String(it)) }
        val packageHint = manifest?.packageName?.takeIf { it.isNotBlank() }
            ?: apkmInfo?.packageName?.takeIf { it.isNotBlank() }

        return resolveBundlePlan(
            contents.entryNames,
            manifest?.splitApkFiles(),
            manifest?.baseApkFile(),
            packageHint
        ).installSet.ifEmpty { null }
    }

    override suspend fun installPackage(
        staged: StagedPackage,
        uri: Uri,
        mode: InstallMode,
        canDowngrade: Boolean,
        grantAllPermissions: Boolean?,
    ) =
        withContext(ioDispatcher) {
            try {
                // Refuse before installing, not after. An archive whose game data cannot be placed
                // would otherwise leave an installed game that starts and immediately fails — the
                // same broken outcome #164 reports, arrived at from the other direction.
                //
                // Null for everything that is not an XAPK carrying a readable manifest.json, which
                // is what keeps a plain APK, an .apks and an .apkm on exactly the path they were on
                // before: one extra read of the central directory and no shell command at all.
                val packageName = resolvePackageNameForObb(staged.file)
                if (packageName != null) {
                    obbInstaller.refusalReason(staged.file, packageName)?.let { reason ->
                        eventBus.emit(InstallState.Error(UiText.DynamicString(reason)))
                        return@withContext
                    }
                }

                // Read *before* installing, because for an update the answer changes and nothing
                // afterwards can reconstruct it. See [awaitInstalled].
                val stampBefore = packageName?.let { installStamp(it) }

                when (mode) {
                    InstallMode.ROOT -> {
                        installWithRoot(staged, canDowngrade, grantAllPermissions)
                    }

                    InstallMode.SHIZUKU -> {
                        // 1. Try Shell command first
                        val shellSuccess = try {
                            installWithShizuku(staged, canDowngrade, grantAllPermissions)
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            // A refusal is a verdict about the archive, not a failure of this rung.
                            // Every rung below reads the same staged bytes and reaches it again,
                            // after re-writing however many gigabytes it took to get there — so it
                            // goes straight out to the sheet with its own message. The four catches
                            // that make up the two ladders all do this; ROOT and NORMAL have no
                            // fallback and already propagate.
                            if (e is InstallRefusedException) throw e
                            Logger.e("InstallerRepo", "Shizuku shell install failed with exception, trying reflection", e)
                            false
                        }

                        if (!shellSuccess) {
                            Logger.d("InstallerRepo", "Shizuku shell install failed. Trying reflection fallback...")
                            // 2. Try Reflection
                            val privilegedInstaller = try {
                                privilegedInstallerHandle(InstallMode.SHIZUKU)
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                Logger.e("InstallerRepo", "Failed to get Shizuku privileged installer: ${e.message}")
                                null
                            }

                            var reflectionSuccess = false
                            if (privilegedInstaller != null) {
                                try {
                                    performPackageInstallerInstall(
                                        staged,
                                        privilegedInstaller,
                                        canDowngrade,
                                        emitErrors = false
                                    )
                                    reflectionSuccess = true
                                } catch (e: Throwable) {
                                    if (e is CancellationException) throw e
                                    if (e is InstallRefusedException) throw e
                                    Logger.e("InstallerRepo", "Shizuku reflection install failed: ${e.message}")
                                }
                            }

                            if (!reflectionSuccess) {
                                Logger.d("InstallerRepo", "Shizuku reflection install failed. Falling back to normal installer...")
                                // 3. Fallback to Normal
                                performPackageInstallerInstall(
                                    staged,
                                    defaultInstaller,
                                    canDowngrade,
                                    emitErrors = true
                                )
                            }
                        }
                    }

                    InstallMode.DHIZUKU -> {
                        // 1. Try Shell command first
                        val shellSuccess = try {
                            installWithDhizuku(staged, canDowngrade, grantAllPermissions)
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            if (e is InstallRefusedException) throw e
                            Logger.e("InstallerRepo", "Dhizuku shell install failed with exception, trying reflection", e)
                            false
                        }

                        if (!shellSuccess) {
                            Logger.d("InstallerRepo", "Dhizuku shell install failed. Trying reflection fallback...")
                            // 2. Try Reflection
                            val privilegedInstaller = try {
                                privilegedInstallerHandle(InstallMode.DHIZUKU)
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                Logger.e("InstallerRepo", "Failed to get Dhizuku privileged installer: ${e.message}")
                                null
                            }

                            var reflectionSuccess = false
                            if (privilegedInstaller != null) {
                                try {
                                    performPackageInstallerInstall(
                                        staged,
                                        privilegedInstaller,
                                        canDowngrade,
                                        emitErrors = false
                                    )
                                    reflectionSuccess = true
                                } catch (e: Throwable) {
                                    if (e is CancellationException) throw e
                                    if (e is InstallRefusedException) throw e
                                    Logger.e("InstallerRepo", "Dhizuku reflection install failed: ${e.message}")
                                }
                            }

                            if (!reflectionSuccess) {
                                Logger.d("InstallerRepo", "Dhizuku reflection install failed. Falling back to normal installer...")
                                // 3. Fallback to Normal
                                performPackageInstallerInstall(
                                    staged,
                                    defaultInstaller,
                                    canDowngrade,
                                    emitErrors = true
                                )
                            }
                        }
                    }

                    InstallMode.NORMAL -> {
                        performPackageInstallerInstall(
                            staged,
                            defaultInstaller,
                            canDowngrade,
                            emitErrors = true
                        )
                    }

                    InstallMode.EXTERNAL -> {
                        // The only mode that still needs the URI: we install nothing here, we
                        // hand the job to whichever installer the user picks, and it does its
                        // own read behind its own confirmation.
                        installWithExternal(uri)
                    }
                }

                // The install rungs emit InstallState.Success themselves and do not reliably throw
                // on failure, so "did it install?" is answered by asking the package manager rather
                // than by the absence of an exception.
                //
                // EXTERNAL is excluded because nothing has been installed yet on that path: the
                // chooser has only just been handed the URI, so the package manager there answers
                // about whatever copy was already on the device, and placing game data for a version
                // the user has not confirmed yet would hand it to an install that is entitled to
                // wipe Android/obb/<pkg> when it runs.
                //
                // The carriesExpansions() gate comes first so that an archive with no game data pays
                // one central-directory read and nothing else — in particular, never the wait below.
                if (mode != InstallMode.EXTERNAL && packageName != null &&
                    obbInstaller.carriesExpansions(staged.file, packageName)
                ) {
                    val name = staged.displayName ?: packageName
                    when (awaitInstalled(packageName, stampBefore)) {
                        InstallWait.INSTALLED ->
                            when (val placement = obbInstaller.place(staged.file, packageName)) {
                                is ObbPlacement.Failed -> eventBus.emit(
                                    InstallState.Error(
                                        UiText.DynamicString(
                                            "$name installed, but its game data could not be " +
                                                "placed: ${placement.reason}"
                                        )
                                    )
                                )

                                is ObbPlacement.Placed, ObbPlacement.NotNeeded -> Unit
                            }

                        // Silent on purpose. The install itself failed — a declined confirmation
                        // dialog is the common way — and InstallReceiver has already put the real
                        // reason on the bus. A second error about game data would bury the cause
                        // under one of its consequences.
                        InstallWait.FAILED -> Unit

                        InstallWait.UNCONFIRMED -> eventBus.emit(
                            InstallState.Error(
                                UiText.DynamicString(
                                    "Thor could not confirm $name finished installing, so its game " +
                                        "data was not placed. Install it again to place the game data."
                                )
                            )
                        )
                    }
                }
            } catch (e: Throwable) {
                // Throwable, matching the per-mode catches above: a bounded read still leaves
                // OutOfMemoryError reachable through the platform parser, and an Error escaping
                // to viewModelScope kills the process instead of failing the install.
                if (e is CancellationException) throw e
                eventBus.emit(InstallState.Error(UiText.DynamicString(e.message ?: "Unknown error during installation")))
            }
        }

    /**
     * The package an archive installs, from its own manifest — null when it cannot be read.
     *
     * Deliberately manifest-only, and therefore null for a plain APK, an `.apks` and an `.apkm`:
     * OBB is an XAPK-only convention, so anything without a readable `manifest.json` at the archive
     * root has no expansions by definition and must not pay for the question being asked.
     *
     * `isUsablePackageName` here as well as inside `ObbInstaller` — this name is read out of an
     * untrusted archive and is the *only* input that decides which directory the placement shell
     * creates.
     */
    private fun resolvePackageNameForObb(bundle: File): String? = try {
        BundleZip.read(bundle, setOf("manifest.json")).bytes["manifest.json"]
            ?.let { parseXapkManifest(it.decodeToString()) }
            ?.packageName
            ?.takeIf { isUsablePackageName(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * `lastUpdateTime` for [packageName], or null when it is not installed.
     *
     * The platform stamps this on every successful install of an existing package, which makes a
     * *change* in it the one locally observable proof that this install finished. Package presence
     * cannot do that job: for an update it is already true before `commit()` has done anything.
     */
    private fun installStamp(packageName: String): Long? = try {
        context.packageManager.getPackageInfo(packageName, 0).lastUpdateTime
    } catch (_: Exception) {
        null
    }

    /** How [awaitInstalled] ended. Three outcomes because "not installed" has two very different causes. */
    private enum class InstallWait { INSTALLED, FAILED, UNCONFIRMED }

    /**
     * Wait until this install has actually landed, it has failed, or we give up.
     *
     * Only the `pm`-based rungs finish synchronously. `performPackageInstallerInstall` ends at
     * `session.commit()`, which returns before the platform has installed anything — the outcome
     * arrives later as a broadcast to `InstallReceiver`. Reading "not installed yet" as "no install,
     * so no game data to place" would drop the OBB silently, which is exactly the bug this feature
     * exists to fix. It is reachable today: Shizuku's shell rung failing falls through to a session.
     *
     * Two things this must get right, and a presence check gets neither:
     *
     *  - **An update is already "installed" before it starts.** [stampBefore] is compared, not
     *    presence, so the wait ends when the copy on disk changed rather than when a copy exists.
     *    Placing expansions against a session still in flight is the hazard `ObbInstaller` describes,
     *    and the update is the common case for a game.
     *  - **A failure never arrives as a package.** A declined confirmation dialog or a rejected
     *    session means the stamp never moves, so polling alone spins out the whole timeout and then
     *    reports "could not confirm" on top of the real error `InstallReceiver` already delivered.
     *    [InstallerEventBus.latest] answers that in one read per tick. A *previous* install's error
     *    cannot be misread as this one's: every session path emits `Installing(1.0f)` before this
     *    gate, and `InstallerViewModel` emits `Parsing` before that, so both overwrite the bus.
     *
     * The wait engages only when needed and so costs nothing on the synchronous rungs, which have
     * already moved the stamp by the time they return. It cannot substitute for real completion
     * plumbing, so the timeout ends in a stated failure rather than in silence.
     */
    private suspend fun awaitInstalled(packageName: String, stampBefore: Long?): InstallWait {
        fun landed() = installStamp(packageName)?.let { it != stampBefore } == true

        if (landed()) return InstallWait.INSTALLED
        val settled = withTimeoutOrNull(OBB_INSTALL_WAIT_MS) {
            while (!landed()) {
                if (eventBus.latest is InstallState.Error) return@withTimeoutOrNull InstallWait.FAILED
                delay(OBB_INSTALL_POLL_MS)
            }
            InstallWait.INSTALLED
        }
        return settled ?: InstallWait.UNCONFIRMED
    }

    /**
     * The privileged session installer for [mode], or `null` for a mode that has no such thing.
     *
     * One function for both privilege modes, deliberately. There used to be two — and the Dhizuku
     * one fetched its `IPackageInstaller` through `ShizukuPackageInstallerUtils`, so **the Dhizuku
     * rung transacted over `ShizukuBinderWrapper`**, which on a Dhizuku-only device wraps a service
     * that is not installed. Its own comment claimed to be "using Dhizuku's binder wrapper". Two
     * copies is how they drifted; [transportFor] answering from the mode is how they stop.
     *
     * The returned [InstallerHandle] carries its own session opener, because
     * `PackageInstaller.openSession` does **not** wrap the session binder it gets back — see
     * [PrivilegedPackageInstallers.openSession] for what that costs.
     */
    private fun privilegedInstallerHandle(mode: InstallMode): InstallerHandle? {
        val transport = transportFor(mode) ?: run {
            // Unreachable today — both call sites pass a literal privileged mode. Logged rather than
            // returned silently so that a mode added later shows up here instead of quietly skipping
            // the session rung and landing the user in the confirmation dialog.
            Logger.e("InstallerRepo", "No privileged installer transport for $mode")
            return null
        }

        // Read only on the transport it means something for. The Dhizuku rung must not touch
        // rikka.shizuku at all — reaching for Shizuku on the Dhizuku path is the exact defect this
        // function exists to fix, and on a Dhizuku-only device getUid() has no binder to ask.
        // -1 is the "could not read it" sentinel; sessionInstallerPackageName treats it the same as
        // a root Shizuku, i.e. it names Thor rather than shell.
        val shizukuUid = if (transport == PrivilegedInstallerTransport.SHIZUKU) {
            try {
                rikka.shizuku.Shizuku.getUid()
            } catch (_: Throwable) {
                -1
            }
        } else {
            -1
        }

        // The mirror image, scoped the same way: a Dhizuku transact is re-issued from the device
        // owner's process, so `mAppOps.checkPackage(callingUid, installerPackageName)` in
        // `createSessionInternal` refuses any name that uid does not own — including Thor's.
        // getOwnerPackageName() throws until the owner component has been received; `null` then means
        // "no better name available" and sessionInstallerPackageName falls back to Thor's, which is
        // where this rung already was.
        val dhizukuOwnerPackageName = if (transport == PrivilegedInstallerTransport.DHIZUKU) {
            try {
                com.rosan.dhizuku.api.Dhizuku.getOwnerPackageName()
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

        return PrivilegedPackageInstallers.handleFor(
            transport = transport,
            installerPackageName = sessionInstallerPackageName(
                transport = transport,
                shizukuUid = shizukuUid,
                thorPackageName = context.packageName,
                dhizukuOwnerPackageName = dhizukuOwnerPackageName,
            ),
            // Thor's own user, whatever uid the transport holds. Which uid Shizuku runs as is a
            // different question from which user the session installs for, and the old
            // `if (isRoot) … else 0` answered the first one — a work-profile install landed in the
            // primary user instead.
            userId = thorUserId,
        )
    }

    private suspend fun installWithExternal(uri: Uri) {
        withContext(mainDispatcher) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                val chooser = Intent.createChooser(intent, "Install with...")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)

                // We consider this a success in terms of handing off the job
                eventBus.emit(InstallState.Success)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                eventBus.emit(InstallState.Error(UiText.DynamicString("Could not open external installer: ${e.message}")))
            }
        }
    }

    /**
     * Lay the APK(s) [staged] contains out in [tempDir] for a `pm`-based install.
     *
     * Reads only the staged copy — the URI is never re-opened, so what gets installed is what
     * the sheet described (see [StagedPackage]). The staged file itself is left alone: its owner
     * deletes it, and a failed install may be retried off it.
     *
     * Every returned [ExtractedApk] carries the SHA-256 of the bytes THIS call wrote, taken in
     * flight. tempDir is shared storage on the Shizuku and Dhizuku rungs, so hashing the files
     * afterwards — the shape this replaces — measured whatever was in them by then, which on API
     * 28-29 is not necessarily what we put there.
     */
    private fun stageInstallSet(staged: StagedPackage, tempDir: File): List<ExtractedApk>? {
        return try {
            tempDir.mkdirs()
            val bundleFile = staged.file
            val installSet = resolveInstallSetFromFile(bundleFile, staged.displayName)
            if (installSet == null) {
                // Monolithic APK: copy the staged file as-is (named base.apk). A copy, not a
                // rename: the staged file has to survive for a retry, and on the Shizuku/Dhizuku
                // paths tempDir is on a different filesystem anyway.
                //
                // The budget cannot fire here — the source is Thor's own staged file, which
                // analyze() already bounded on the way in — but it is spelled out rather than
                // assumed, because that bound lives in another class and this one would keep
                // copying either way if it ever moved.
                val tempApk = File(tempDir, "base.apk")
                val digest = MessageDigest.getInstance("SHA-256")
                val copied = bundleFile.inputStream().use { input ->
                    tempApk.outputStream().use { output ->
                        input.copyAtMostTo(output, MAX_EXTRACTED_TOTAL_BYTES, digest)
                    }
                } ?: throw InstallRefusedException(
                    "The selected file is larger than " +
                        "${MAX_EXTRACTED_TOTAL_BYTES / (1024 * 1024)} MB; refusing to install it."
                )
                Logger.d("InstallerRepo", "Staged $copied bytes as a monolithic base.apk")
                listOf(ExtractedApk(tempApk, digest.digest().toLowercaseHex()))
            } else {
                // Genuine bundle: extract exactly the resolved split set via ZipFile. No
                // `.ifEmpty { null }` any more — extractEntries refuses a set it cannot deliver
                // whole rather than returning what it managed, so "empty" no longer means
                // "partially fine", it is unreachable. Reading emptiness as a staging failure was
                // what turned a truncated set into the caller's generic error and, on the
                // privileged ladders, into the next rung.
                val wanted = installSet.mapTo(HashSet()) { it.substringAfterLast('/') }
                BundleZip.extractEntries(bundleFile, wanted, tempDir)
            }
        } catch (e: InstallRefusedException) {
            // Not a staging failure, a verdict on the archive. Returning null here would turn it
            // into the caller's generic "Failed to extract or copy installation files" — or worse,
            // into `false`, which sends the ladder down to the next rung to re-read the same bytes.
            throw e
        } catch (e: Exception) {
            Logger.e("InstallerRepo", "Failed to stage the install set", e)
            null
        }
    }

    /**
     * [stageInstallSet], with [tempDir] removed if it throws.
     *
     * The three ladder rungs call this before entering the `try`/`finally` that owns [tempDir], so
     * a throw from staging escapes past the only `deleteRecursively` on the path and strands the
     * directory. It leaks no bytes — the extractor deletes what it wrote before refusing — but it
     * leaks one empty directory per refused archive, and this branch makes staging refuse in
     * strictly more cases than before, so a pre-existing trickle becomes a faster one. Two of the
     * three rungs stage into `externalCacheDir`, where the residue is visible to the user in a file
     * manager rather than hidden in app-private storage.
     *
     * Cleaning up here rather than moving the call inside the rung's `try` is deliberate: that
     * `try` ends in `catch (e: Exception)`, which would fold [InstallRefusedException] into a
     * generic "install error" message *and* stop it propagating — and propagation is what makes
     * the ladder halt instead of dropping to a rung that would read the same refused bytes.
     * Rethrowing the original preserves that, and [CancellationException] with it.
     */
    private fun stageInstallSetCleaningUpOnFailure(
        staged: StagedPackage,
        tempDir: File,
    ): List<ExtractedApk>? = try {
        stageInstallSet(staged, tempDir)
    } catch (e: Throwable) {
        tempDir.deleteRecursively()
        throw e
    }

    private suspend fun installWithRoot(
        staged: StagedPackage,
        canDowngrade: Boolean,
        grantAllPermissions: Boolean?,
    ) {
        eventBus.emit(InstallState.Installing(0f))

        val tempDir = File(context.cacheDir, "install_root_${UUID.randomUUID()}")
        val tempFiles = stageInstallSetCleaningUpOnFailure(staged, tempDir)

        if (tempFiles.isNullOrEmpty()) {
            eventBus.emit(InstallState.Error(UiText.DynamicString("Failed to extract or copy installation files")))
            tempDir.deleteRecursively()
            return
        }

        eventBus.emit(InstallState.Installing(0.5f))

        try {
            // No integrity guard on this rung, and none needed: tempDir is context.cacheDir, which
            // is app-private on every API level, so there is no window for another app to swap a
            // file between the write and `pm`'s read. The Shizuku/Dhizuku rungs below are guarded
            // because they have to stage into shared storage; the session paths read the staged
            // file directly and never expose it at all. Those are all four write paths.
            val apkPaths = tempFiles.map { it.file.absolutePath }
            // The gateway resolves a null against the saved setting; this rung has no reason to
            // resolve it first, and doing so would put a second copy of that rule in the app.
            val result = if (apkPaths.size == 1) {
                rootGateway.installApp(apkPaths[0], canDowngrade, grantAllPermissions)
            } else {
                rootGateway.installMultipleApks(apkPaths, canDowngrade, grantAllPermissions)
            }

            if (result.isSuccess) {
                eventBus.emit(InstallState.Installing(1.0f))
                eventBus.emit(InstallState.Success)
            } else {
                eventBus.emit(
                    InstallState.Error(UiText.DynamicString(result.exceptionOrNull()?.message ?: "Root install failed"))
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            eventBus.emit(InstallState.Error(UiText.DynamicString("Root install error: ${e.message}")))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private suspend fun installWithShizuku(
        staged: StagedPackage,
        canDowngrade: Boolean,
        grantAllPermissions: Boolean?,
    ): Boolean {
        eventBus.emit(InstallState.Installing(0f))

        // Shared storage, because the *shell* has to be able to read these files: uid 2000 cannot
        // open anything under Thor's own /data/data, and it is exempt from the Android 11
        // Android/data restriction — MediaProvider's FUSE daemon waives that check for any
        // `uid < AID_APP_START` (10000), which is an AOSP implementation detail rather than a
        // documented guarantee, but it is what the shell rung has always relied on. So shared
        // storage is the one place both sides can reach. integrityGuardedInstall re-hashes them
        // there to close the exposure. See the digest map below.
        //
        // What used to be written here — that piping the bytes in "is not available here", because
        // newProcess feeds the command itself down stdin — was wrong, and it is why this rung named
        // an absolute path to `pm` for its entire existence. The pipeline in
        // `cat <apk> | pm install-write … -` is internal to the shell and has nothing to do with
        // what that shell was started with; Odin's root channel is fed on stdin in exactly the same
        // way and has streamed installs successfully since GH#159.
        val baseDir = context.externalCacheDir ?: context.cacheDir
        val tempDir = File(baseDir, "install_shizuku_${UUID.randomUUID()}")
        val tempFiles = stageInstallSetCleaningUpOnFailure(staged, tempDir)

        if (tempFiles.isNullOrEmpty()) {
            tempDir.deleteRecursively()
            return false
        }

        eventBus.emit(InstallState.Installing(0.5f))

        val installerArg = preferenceRepository.getInstallerArg()
        // The caller's answer for this one install if it gave one — the portable installer's
        // checkbox — and the saved setting otherwise. Not `grantAllPermissions == true`: a missing
        // answer means "nobody was asked", which is not the same as "the user said no", and
        // collapsing the two would override anyone who had turned the setting on.
        val grantAll = grantAllPermissions
            ?: preferenceRepository.shouldGrantAllPermissionsOnInstall()

        return try {
            // The shell rung, and normally the one that decides the outcome: the PackageInstaller
            // session rung is only reached when this returns false. Both rungs now name the same
            // user, which is the whole point — privilegedInstallerHandle() creates its session for
            // thorUserId, so a shell rung that installed somewhere else meant one operation landing
            // in two different places depending on which rung happened to succeed.
            //
            // Bare `pm install` was not "install for the shell's user" and was not "install for
            // user 0" either: makeInstallParams leaves params.userId at USER_ALL when no --user is
            // parsed, and the session is then created with USER_SYSTEM plus INSTALL_ALL_USERS, so
            // every install here landed on *every* user of the device and exited 0. From a work
            // profile that pushes an APK into the personal profile nobody asked to install it into.
            //
            // A session, streaming the bytes in, rather than `pm install <path>`: the shell can open
            // these files but system_server — which is what actually opens a path argument, via
            // ShellCommand.openFileForSystem — may not, and `pm install-multiple` is not a verb any
            // Android has ever implemented, so every split set failed here unconditionally.
            // installViaSessionCommand carries the full argument.
            //
            // The digests came out of the copy itself, not out of a read-back of these paths. By
            // the time this line runs, `eventBus.emit` and a DataStore read have both suspended —
            // tens of milliseconds during which a co-installed app holding WRITE_EXTERNAL_STORAGE
            // could have replaced base.apk. Hashing here would have hashed its file and then
            // confirmed it against itself.
            //
            // Both builders escape the paths themselves, so raw paths go to both. Note what
            // streaming does to the guard: `sha256sum <path>` and `cat <path>` need the same one
            // permission, so the guard no longer adds a way for the install to fail that the install
            // did not already have.
            val digests = tempFiles.map { it.file.absolutePath to it.sha256 }
            val command = installViaSessionCommand(
                apks = tempFiles.map {
                    SessionApk(
                        path = it.file.absolutePath,
                        sizeBytes = it.file.length(),
                        name = it.file.name,
                    )
                },
                userId = thorUserId,
                canDowngrade = canDowngrade,
                grantAllPermissions = grantAll,
                installerArg = installerArg,
            )
            val result = ShizukuHelper.execute(integrityGuardedInstall(digests, command))

            if (result.first == 0) {
                eventBus.emit(InstallState.Installing(1.0f))
                eventBus.emit(InstallState.Success)
                true
            } else {
                Logger.e("InstallerRepo", "Shizuku shell install failed: ${result.second}")
                false
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e("InstallerRepo", "Shizuku shell install failed with exception: ${e.message}", e)
            false
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private suspend fun installWithDhizuku(
        staged: StagedPackage,
        canDowngrade: Boolean,
        grantAllPermissions: Boolean?,
    ): Boolean {
        eventBus.emit(InstallState.Installing(0f))

        // Shared storage for the same reason as the Shizuku path, and guarded the same way — but
        // with a caveat that does not apply there. DhizukuAPI.newProcess runs the shell inside the
        // device-owner *app*, at an ordinary app uid, which is precisely the uid class that cannot
        // read another app's Android/data from API 30 on; Shizuku's uid 2000 is the exemption, and
        // Dhizuku has no equivalent. So this rung is expected to work on API 28-29 and to fail on
        // anything newer no matter how the bytes are moved, and the session rung
        // (privilegedInstallerHandle) is the one that has to carry modern Android: there Thor's own
        // process supplies the bytes and the shell is not involved at all. That rung had its own
        // defect until now — it ran on the Shizuku binder wrapper, so on a Dhizuku-only device
        // there was nothing left to carry modern Android with.
        val baseDir = context.externalCacheDir ?: context.cacheDir
        val tempDir = File(baseDir, "install_dhizuku_${UUID.randomUUID()}")
        val tempFiles = stageInstallSetCleaningUpOnFailure(staged, tempDir)

        if (tempFiles.isNullOrEmpty()) {
            tempDir.deleteRecursively()
            return false
        }

        eventBus.emit(InstallState.Installing(0.5f))

        val installerArg = preferenceRepository.getInstallerArg()
        // Same resolution rule as installWithShizuku above, and the same reason for it.
        val grantAll = grantAllPermissions
            ?: preferenceRepository.shouldGrantAllPermissionsOnInstall()

        return try {
            // Same rung, same seed, same fix as installWithShizuku above — and the same pairing
            // with the session rung, which privilegedInstallerHandle() creates for thorUserId.
            // Dhizuku's identity does not soften the trap: `pm` runs inside the device-owner app
            // via DhizukuAPI.newProcess, but the missing --user is parsed by PackageManagerService,
            // not by whoever invoked it, so the bare form installed for every user here too.
            // A streaming session for the same two reasons as the Shizuku rung: system_server, not
            // this shell, is what opens a path argument, and `pm install-multiple` does not exist.
            // The second reason bites here even on API 28-29, where the read succeeds — every split
            // set failed on an unknown verb regardless of permissions.
            //
            // Digests from the copy, for the same reason as the Shizuku rung above.
            val digests = tempFiles.map { it.file.absolutePath to it.sha256 }
            val command = installViaSessionCommand(
                apks = tempFiles.map {
                    SessionApk(
                        path = it.file.absolutePath,
                        sizeBytes = it.file.length(),
                        name = it.file.name,
                    )
                },
                userId = thorUserId,
                canDowngrade = canDowngrade,
                grantAllPermissions = grantAll,
                installerArg = installerArg,
            )
            val result = DhizukuHelper.execute(integrityGuardedInstall(digests, command))

            if (result.first == 0) {
                eventBus.emit(InstallState.Installing(1.0f))
                eventBus.emit(InstallState.Success)
                true
            } else {
                Logger.e("InstallerRepo", "Dhizuku shell install failed: ${result.second}")
                false
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e("InstallerRepo", "Dhizuku shell install failed with exception: ${e.message}", e)
            false
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @SuppressLint("RequestInstallPackagesPolicy")
    private suspend fun performPackageInstallerInstall(
        staged: StagedPackage,
        // An [InstallerHandle] rather than a bare PackageInstaller: on a privileged installer the
        // platform's own openSession() hands back a session whose binder is unwrapped, so every
        // write on it transacts as Thor and is refused. The handle carries the correct opener.
        installer: InstallerHandle,
        canDowngrade: Boolean,
        emitErrors: Boolean = true
    ) {
        // Written before the first entry is copied, once the install set is known; the staged
        // file's own length is the right answer for a monolithic APK and a decent lower bound
        // for a bundle until then.
        var totalBytes = staged.file.length()
        var bytesProcessed = 0L
        var lastProgressEmitted = 0

        eventBus.emit(InstallState.Parsing)

        // No install-time permission grant here, and deliberately wired to neither
        // `UserPreferences.grantAllPermissionsOnInstall` nor the portable installer's per-install
        // checkbox, which is the same answer arriving by a different route. `SessionParams` exposes
        // no public way to ask for one — the shell's `-g` is
        // `INSTALL_GRANT_ALL_REQUESTED_PERMISSIONS`, a bit in the hidden `installFlags` field — and
        // a session created with a flag the caller is not allowed to set fails outright rather than
        // degrading, so reaching for it would turn a convenience toggle into an install that stops
        // working. This rung has never granted anything and is not the rung GH#445 was about; it is
        // the *fallback*, reached only when the shell rung above returns false. Consequence worth
        // knowing: with the box ticked, a package that lands here comes up ungranted anyway.
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )

        if (canDowngrade) {
            try {
                // Use reflection via Bypass as it might be unresolved in some SDK configurations
                Bypass.invoke<Any?>(params::class.java, params, "setRequestDowngrade", true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.w(
                    "InstallerRepo",
                    "Failed to setRequestDowngrade via reflection, proceeding without downgrade flag: ${e.message}"
                )
            }
        }

        val sessionId = try {
            installer.createSession(params)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (emitErrors) {
                eventBus.emit(InstallState.Error(UiText.DynamicString("Failed to create session: ${e.message}")))
                return
            } else throw e
        }

        val session = try {
            installer.openSession(sessionId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // createSession() succeeded but the session could not be opened — abandon it
            // so the failed session isn't leaked in PackageInstaller (both paths below).
            try {
                installer.abandonSession(sessionId)
            } catch (_: Exception) {
            }
            if (emitErrors) {
                eventBus.emit(InstallState.Error(UiText.DynamicString("Failed to open session: ${e.message}")))
                return
            } else throw e
        }

        // Whole-percent progress ticks are handed off (non-blocking) through this
        // conflated channel and emitted by a child of the install coroutine below, so a
        // late tick can never land after a terminal state and cancelling the install
        // cancels the emission too.
        val progressChannel = Channel<Float>(Channel.CONFLATED)

        // Helper to track progress across different streams
        fun getTrackedStream(baseStream: InputStream): InputStream {
            return object : InputStream() {
                override fun read(): Int {
                    val b = baseStream.read()
                    if (b != -1) updateProgress(1)
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val read = baseStream.read(b, off, len)
                    if (read != -1) updateProgress(read.toLong())
                    return read
                }

                override fun close() {
                    baseStream.close()
                }

                private fun updateProgress(readBytes: Long) {
                    bytesProcessed += readBytes
                    if (totalBytes > 0) {
                        val currentProgress =
                            ((bytesProcessed.toDouble() / totalBytes) * 100).toInt()
                        if (currentProgress > lastProgressEmitted) {
                            lastProgressEmitted = currentProgress
                            // Non-blocking hand-off; conflated so only the latest tick
                            // survives if the drainer is momentarily behind. Clamped because
                            // totalBytes is derived from sizes the archive declares: an entry
                            // that says 10 bytes and streams a gigabyte put fractions in the
                            // millions on the bus, and the write is bounded now but the lie
                            // still is not.
                            progressChannel.trySend(
                                (bytesProcessed.toFloat() / totalBytes).coerceIn(0f, 1f)
                            )
                        }
                    }
                }
            }
        }

        try {
            // The staged copy IS the input, already on disk — no second read of the URI, and no
            // second copy either. ZipFile (central directory) reads it; ZipInputStream cannot
            // handle APKPure's STORED-with-data-descriptor entries and derails on the first one.
            val bundleFile = staged.file
            coroutineScope {
                // Drain progress ticks on a child coroutine so emissions are bound to the
                // install job (cancellation stops them) and can never outlive the write phase.
                // Closing the channel ends the drain loop; coroutineScope then awaits this child
                // before returning, so the final tick is flushed before we continue. No explicit
                // join(): it is redundant here, and a suspending join() in a finally could mask
                // the real failure (e.g. an IOException from openWrite) under cancellation.
                launch {
                    for (fraction in progressChannel) {
                        eventBus.emit(InstallState.Installing(fraction))
                    }
                }
                try {
                    // Genuine bundle: write each resolved split into the session, read via
                    // ZipFile so STORED-with-data-descriptor entries stream correctly.
                    val installSet = resolveInstallSetFromFile(bundleFile, staged.displayName)
                    if (installSet != null) {
                        val wanted =
                            installSet.mapTo(HashSet()) { it.substringAfterLast('/').lowercase() }
                        ZipFile(bundleFile).use { zf ->
                            // OrRefuse, not the bare selector: an install set that does not
                            // survive selection is a verdict about the archive, not a licence to
                            // install something else. An empty selection used to fall through to
                            // the monolithic branch below and stream the outer container as
                            // base.apk — a file that by construction is not the one the sheet's
                            // identity was read from.
                            val toWrite =
                                selectEntriesToWriteOrRefuse(zf.entries().asSequence(), wanted)
                            // Now that the set is known, progress can be measured against what
                            // actually gets written rather than the archive's compressed length —
                            // capped, because this sum is the archive's own claim about itself.
                            val declared = toWrite.sumOf { if (it.size >= 0) it.size else 0L }
                            if (declared > 0) {
                                totalBytes = declared.coerceAtMost(MAX_EXTRACTED_TOTAL_BYTES)
                            }
                            writeEntriesWithinBudget(
                                zip = zf,
                                entries = toWrite,
                                budget = MAX_EXTRACTED_TOTAL_BYTES,
                                openSink = { name, length -> session.openWrite(name, 0, length) },
                                trackProgress = { inner -> getTrackedStream(inner) },
                                afterEntry = { out -> session.fsync(out) }
                            )
                        }
                    } else {
                        // Monolithic APK (or not a readable bundle): stream the staged file
                        // whole as base.apk.
                        //
                        // Gated on the ABSENCE of an install set, not on "nothing got written".
                        // `installSet == null` is resolveInstallSetFromFile's own monolithic
                        // verdict — the very condition AppAnalyzerImpl checks (plan.installSet
                        // .isEmpty()) before it lets the whole file identify itself, so this is
                        // the only state in which the file's own manifest describes the bytes
                        // `pm` ends up with.
                        Logger.d("thor", "Treating stream as monolithic base.apk")
                        // The budget cannot bite here — the source is Thor's own staged copy,
                        // bounded by analyze() — but it is applied anyway rather than assumed,
                        // so this path does not depend on an invariant held in another class.
                        val length = bundleFile.length().takeIf { it in 1..MAX_EXTRACTED_TOTAL_BYTES }
                            ?: -1L
                        var copied: Long? = null
                        session.openWrite("base.apk", 0, length).use { out ->
                            bundleFile.inputStream().use { inner ->
                                copied = getTrackedStream(inner)
                                    .copyAtMostTo(out, MAX_EXTRACTED_TOTAL_BYTES)
                            }
                            if (copied != null) session.fsync(out)
                        }
                        if (copied == null) {
                            throw InstallRefusedException(
                                "The selected file is larger than " +
                                    "${MAX_EXTRACTED_TOTAL_BYTES / (1024 * 1024)} MB; " +
                                    "refusing to install it."
                            )
                        }
                    }
                } finally {
                    progressChannel.close()
                }
            }

            eventBus.emit(InstallState.Installing(1.0f))

            val intent = Intent(context, InstallReceiver::class.java).apply {
                action = ACTION_INSTALL_STATUS
                setPackage(context.packageName)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                flags
            )

            session.commit(pendingIntent.intentSender)
            session.close()

        } catch (e: Throwable) {
            // Abandon FIRST, and on every throwable — including CancellationException, which is
            // the common case: the user swipes the sheet away mid-copy and this coroutine is
            // cancelled. Rethrowing before the abandon left the session neither committed nor
            // abandoned, and PackageInstaller caps sessions per app, so a few dozen abandoned
            // previews used to block every later install until they aged out. The failure path
            // where openSession() throws already got this right; this one didn't.
            runCatching { session.abandon() }
            if (e is CancellationException) throw e
            Logger.e("thorInstaller", "Install failed", e)
            if (emitErrors) {
                eventBus.emit(InstallState.Error(UiText.DynamicString(e.message ?: "Unknown installation error")))
            } else throw e
        }
    }
}

/** Exit code the integrity guard uses; distinct from anything `pm` itself returns. */
internal const val INTEGRITY_CHECK_EXIT_CODE = 90

/**
 * How long to wait for an asynchronously committed install to appear before giving up on placing an
 * archive's game data.
 *
 * Long enough for a privileged session on a slow device and for a user tapping through the system
 * installer's confirmation; short enough that an install which failed does not leave the sheet
 * waiting indefinitely for a package that is never coming.
 */
private const val OBB_INSTALL_WAIT_MS = 90_000L

/** Poll interval for the above. Cheap: a `getPackageInfo` on a name, no IPC storm. */
private const val OBB_INSTALL_POLL_MS = 250L

/**
 * The entries of a bundle that get written into an install session: the first entry per wanted
 * base name (case-insensitive), in archive order.
 *
 * Names come from an untrusted archive and become the session's file names, so anything that is
 * not a plain leaf name is dropped rather than handed to `openWrite`, which would answer it with
 * an IllegalArgumentException from inside the platform.
 *
 * Dropping is not the same as tolerating. The caller compares this list against the set it asked
 * for and refuses the install if anything is missing, so a drop costs the archive the install, not
 * the entry — anything else would let the sheet describe a file the session never received. The
 * case is unreachable in practice because `resolveBundleInstallSet` filters those names out of the
 * install set first; this is the second lock, and it is the one that also catches a wanted name
 * that simply is not in the archive.
 */
internal fun selectEntriesToWrite(
    entries: Sequence<ZipEntry>,
    wantedLowercaseBaseNames: Set<String>
): List<ZipEntry> {
    val seen = HashSet<String>()
    return entries.filter { entry ->
        if (entry.isDirectory) return@filter false
        val base = entry.name.substringAfterLast('/')
        if (!isSafeEntryFileName(base)) return@filter false
        val key = base.lowercase()
        key in wantedLowercaseBaseNames && seen.add(key)
    }.toList()
}

/**
 * [selectEntriesToWrite], refusing the archive unless it yields every wanted name.
 *
 * This is the session path's half of the rule `BundleZip.extractEntries` enforces for the
 * privileged rungs, and it exists for the same reason: a set that came back short was read as
 * success. There, `.ifEmpty { null }` turned a truncated bundle into an install; here, an empty
 * selection fell into `performPackageInstallerInstall`'s monolithic branch, which streamed the
 * *container archive* as `base.apk` — not merely a smaller install, but a file the confirmation
 * sheet never described. Both shapes are the same mistake: treating "fewer entries than asked for"
 * as a quantity when it is a verdict.
 *
 * Reachable two ways, neither of which should occur once `resolveBundleInstallSet` has filtered the
 * names: an entry whose leaf `openWrite` could not take, and a wanted name that is not in the
 * archive at all. Both are refusals, so this stays correct without depending on that filter.
 */
internal fun selectEntriesToWriteOrRefuse(
    entries: Sequence<ZipEntry>,
    wantedLowercaseBaseNames: Set<String>
): List<ZipEntry> {
    val selected = selectEntriesToWrite(entries, wantedLowercaseBaseNames)
    val got = selected.mapTo(HashSet()) { it.name.substringAfterLast('/').lowercase() }
    if (got != wantedLowercaseBaseNames) {
        throw InstallRefusedException(
            "this archive does not usably contain " +
                (wantedLowercaseBaseNames - got).sorted().joinToString(", ") +
                "; refusing to install a partial set."
        )
    }
    return selected
}

/**
 * Copy [entries] out of [zip] into the sinks [openSink] hands back, refusing the whole install as
 * soon as the set passes [budget]. Returns the total number of bytes written.
 *
 * The budget lives here as well as in `BundleZip.extractEntries` because this is the path
 * `extractEntries` never sees. `performPackageInstallerInstall` is InstallMode.NORMAL — every user
 * with no root and no Shizuku — and it is also the last rung of both privileged ladders, so it is
 * where most installs land; it opens the archive itself and copied each entry with an unbounded
 * `copyTo`. The name check (`selectEntriesToWrite`) had been applied to it and the size check had
 * not, which is the classic shape: validate the entry list, then extract in a second pass that
 * validates nothing. The session's staging directory is `/data/app/vmdl<id>.tmp` — the same data
 * partition the budget was added to protect, filled the same way.
 *
 * [budget] is spent across the set, not per entry, because a bundle installs as a set: what matters
 * is what the whole thing expands to. An exhausted budget throws rather than committing what fitted.
 *
 * The length reaching [openSink] is clamped to what is still allowed to be written. It comes from
 * the archive's central directory, which is a claim by whoever built the archive; `openWrite`
 * preallocates against it, so an entry declaring 2 TB would fail the install on an allocation Thor
 * already knows it will never honour, and -1 ("unknown") is the honest input in that case. An
 * under-declaration is left alone — it costs nothing now that the copy itself is bounded.
 *
 * @param trackProgress wraps each source stream (the caller's byte counter); identity by default.
 * @param afterEntry runs on a sink that received all its bytes, before it closes (`session.fsync`).
 */
internal fun writeEntriesWithinBudget(
    zip: ZipFile,
    entries: List<ZipEntry>,
    budget: Long,
    openSink: (name: String, declaredLength: Long) -> OutputStream,
    trackProgress: (InputStream) -> InputStream = { it },
    afterEntry: (OutputStream) -> Unit = {}
): Long {
    var remaining = budget
    for (entry in entries) {
        val base = entry.name.substringAfterLast('/')
        val declared = entry.size.takeIf { it in 0..remaining } ?: -1L
        var copied: Long? = null
        openSink(base, declared).use { out ->
            zip.getInputStream(entry).use { inner ->
                copied = trackProgress(inner).copyAtMostTo(out, remaining)
            }
            if (copied != null) afterEntry(out)
        }
        val written = copied ?: throw InstallRefusedException(
            "\"$base\" expands past the ${budget / (1024 * 1024)} MB install budget; " +
                "refusing to install this archive."
        )
        remaining -= written
    }
    return budget - remaining
}

/**
 * Prefix a privileged install command with a check that each staged APK still hashes to what it did
 * when we wrote it, aborting with [INTEGRITY_CHECK_EXIT_CODE] if not.
 *
 * The Shizuku/Dhizuku rungs have to stage into shared storage (see installWithShizuku), where on
 * API 28-29 — minSdk is 28, and Android/data was not sandboxed until 11 — any app holding
 * WRITE_EXTERNAL_STORAGE can watch the directory with a FileObserver and swap base.apk before
 * `pm` reads it. The session would then install the attacker's package, silently — and, if the user
 * has turned `grantAllPermissionsOnInstall` on, with every runtime permission already granted —
 * while the sheet showed the app the user actually picked. Note which half of that this guard is
 * for: turning the grant off narrows the blast radius, it does not close the swap, so the check
 * still has to run on every install regardless of what the toggle says.
 *
 * Running the check inside the same shell invocation is the point: doing it from Thor's process
 * would put a binder round trip and a process spawn between the check and the read. A window
 * remains — `sha256sum` finishes, then the bytes are read — but it is microseconds of the same
 * script rather than the whole staging-to-install span.
 *
 * Now that both callers pass [installViaSessionCommand], the guard's read and the install's read are
 * the same read: `sha256sum <path>` and `cat <path>` are both performed by this shell, on this path,
 * needing exactly one permission between them. So the guard can no longer fail on a path the install
 * would have managed — it costs a hash, not a rung. That was not true of `pm install <path>`, where
 * the two reads had different readers and therefore different failure conditions.
 *
 * Two things this KDoc used to claim, and should not be believed:
 *  - that the guard is scoped to the window it describes. It is applied on every API level, though
 *    `BundleZip` states outright that from API 30 the staging directory is sandboxed and the race is
 *    gone.
 *  - that failing closed "costs nothing" because the next rung is the privileged session.
 *    `installPackage` does not stop there: past the session rung it continues to the *unprivileged*
 *    installer, which is the system confirmation dialog. Failing closed costs the silent install.
 *
 * Both are real, both are narrower than the bug this file was last edited for, and neither is fixed
 * here.
 *
 * What makes that true is where the expected hash comes from. It is computed during the copy that
 * writes the file (see [ExtractedApk]), not from reading the file back afterwards: a read-back
 * happens after the write has already suspended twice — an eventBus emit and a DataStore read — so
 * it measured whatever was on disk by then, and an attacker who had already swapped the file simply
 * got their own bytes hashed and then confirmed. The guard covered the microseconds and missed the
 * span it was written for.
 *
 * The whole script is wrapped in `( … )` so the abort ends the *subshell*. Today both callers hand
 * this to `ShizukuHelper.execute` / `DhizukuHelper.execute`, which spawn a fresh `sh` per command,
 * so a bare `exit` would only end a process that was about to end anyway. That is a property of the
 * current callers, not of this function, and it is exactly the assumption that broke
 * `obbProbeCommand`: routed through the root gateway instead, the same shape kills Odin's single
 * long-lived `su` session mid-script — libsu never appends its end marker, the real exit code is
 * lost, and the *next* unrelated privileged command fails too. A script that ends its own subshell
 * is safe on every transport, so the transport stops being something a caller has to know.
 * `RootSystemGateway.installViaSession` is the same wrap for the same reason.
 *
 * @param digests staged absolute path to its expected lowercase SHA-256 hex.
 */
internal fun integrityGuardedInstall(
    digests: List<Pair<String, String>>,
    installCommand: String
): String {
    // Fail closed rather than quietly returning a bare `pm install`: an empty list means the
    // caller lost track of what it staged, and the throw lands in that caller's catch, which
    // gives up this rung for the privileged-session path instead of installing unverified bytes.
    require(digests.isNotEmpty()) { "refusing to build an unguarded privileged install" }

    val sb = StringBuilder()
    sb.append("(\n")
    for ((path, expected) in digests) {
        sb.append("H=$(sha256sum ").append(path.escapeForShell())
            .append(" 2>/dev/null | cut -d' ' -f1)\n")
        sb.append("if [ \"\$H\" != \"").append(expected).append("\" ]; then ")
            .append("echo 'staged APK failed its integrity check' 1>&2; ")
            .append("exit ").append(INTEGRITY_CHECK_EXIT_CODE).append("; fi\n")
    }
    sb.append(installCommand).append("\n")
    // The subshell's status is the install's status, so the caller's `result.first == 0` still reads
    // `pm`'s exit code and a failed hash still surfaces as INTEGRITY_CHECK_EXIT_CODE. Nothing may be
    // appended after this line.
    sb.append(")\n")
    return sb.toString()
}