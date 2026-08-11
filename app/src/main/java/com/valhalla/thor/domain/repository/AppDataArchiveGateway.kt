// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.ClassEntries
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.TarOutcome
import java.io.File

/**
 * Everything privileged or Android-specific that the archive use cases need, behind one port.
 *
 * Deliberately **not** added to `SystemRepository`: two test files hand-write a full implementation of
 * that interface (`FreezeAppUseCaseTest.kt:33`, `BulkFreezeWorkerTest.kt:121`), so every method added
 * there is a compile error in code that has nothing to do with backup. See deviation 7.
 *
 * Only `File`, `String` and domain types cross this boundary, so both use cases stay JVM-testable
 * against a fake.
 *
 * `internal` because [ClassEntries] is `internal` — a `public` interface cannot expose an `internal`
 * return type. Both are used only within the `:app` module, so the restriction is no real loss.
 */
internal interface AppDataArchiveGateway {

    /**
     * The Android multi-user id whose data Thor reads.
     *
     * One value, not a choice: `am get-current-user` is denied without `INTERACT_ACROSS_USERS`, so
     * this is `Process.myUserHandle().hashCode()` — see `data/source/local/ThorUser.kt`. **This is
     * not a Linux uid.** The two are different numbers with the same nickname; [appUid] is the other
     * one.
     */
    suspend fun thorUserId(): Int

    /** `Environment.getExternalStorageDirectory()`, or `""` when it cannot be resolved. */
    suspend fun externalStorageDir(): String

    /**
     * A path in Thor's **internal** cache for the shell to write and Thor to read back.
     *
     * Internal, not `externalCacheDir` (§7.1): the staged file is a plaintext tar of someone's app
     * data, and on shared storage any all-files-access app could read it. The OBB feature staged
     * externally because Thor's own uid had to *write* there; here the shell writes and Thor only
     * reads, and root can write anywhere.
     */
    suspend fun stagingFile(name: String): File

    /**
     * `am force-stop`, once per job (§7.2 step 4).
     *
     * Not per class: stopping the app four times gives it three chances to be restarted by a
     * broadcast in between.
     */
    suspend fun forceStop(packageName: String)

    /** `ls -A` the class root and run the reply through `filterBackupEntries`. */
    suspend fun listClass(packageName: String, dataClass: DataClass): ClassEntries

    /**
     * `tar` [entries] out of the class root into [out], then hand [out] to Thor's own uid.
     *
     * @param compress try `-czf`. The caller retries with `false` on [TarOutcome.Failed] and records
     *   which one worked in the member's `compression` field (§7.2 step 7c).
     */
    suspend fun tarClass(
        packageName: String,
        dataClass: DataClass,
        entries: List<String>,
        out: File,
        compress: Boolean,
    ): TarOutcome

    /**
     * The app's **Linux** uid, read live from `PackageManager`.
     *
     * Null when the package is not installed. Restore must call this *after* the install lands: a
     * reinstalled app gets a new uid, so the archive's numeric owners are always wrong (§8.2).
     */
    suspend fun appUid(packageName: String): Int?

    /**
     * SHA-256 of the app's first signing certificate, uppercase hex, or null if it cannot be read.
     *
     * Load-bearing on the restore side: without it, restoring into a same-named but differently
     * signed package is a data-exfiltration primitive.
     */
    suspend fun signerSha256(packageName: String): String?

    /**
     * Extract [tar] into `<class root>/.thorbak-staging/` (§8.3 b).
     *
     * The tar is a file Thor wrote in its own internal cache, so it is mode 600 owned by Thor's uid.
     * Root reads it without ceremony. A shell-uid channel could not — which costs nothing, because the
     * capability probe (Task 6) already refuses a channel that cannot read a private data directory,
     * and that is the same refusal.
     *
     * **Destructive-first**, and the caller has to know it: `extractCommand` opens with
     * `rm -rf '<staging>'`. That is deliberate — it stops a previous run's debris from being promoted
     * into the class root alongside this archive's contents — but it means re-entering this call after
     * an interrupted swap deletes the staged tree, which at that moment may hold data the class root
     * no longer has. See `RestoreAppArchiveUseCase`, which never re-enters it.
     *
     * @param compressed must match the member's recorded compression. `extractCommand` picks `-xzf`
     *   or `-xf` from it; guessing would fail on a plain tar or, worse, half-succeed.
     */
    suspend fun extractInto(
        packageName: String,
        dataClass: DataClass,
        tar: File,
        compressed: Boolean,
    ): Boolean

    /**
     * Replace the class root's contents with the staged extraction, then remove the staging directory
     * (§8.3 c).
     *
     * This is the destructive step. Everything before it is recoverable.
     */
    suspend fun swapStaged(packageName: String, dataClass: DataClass): Boolean

    /**
     * `chown -R <uid>:<uid>` over the class root (§8.3 d).
     *
     * @param uid the app's **live Linux** uid, read after any install. Not [thorUserId] — see its
     *   KDoc for why the two must never be swapped.
     */
    suspend fun chownClass(packageName: String, dataClass: DataClass, uid: Int): Boolean

    /** `restorecon -RF` over the class root (§8.3 e). */
    suspend fun relabelClass(packageName: String, dataClass: DataClass): Boolean
}
