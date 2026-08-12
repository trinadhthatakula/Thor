// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * A record that a restore was in flight.
 *
 * @param appLabel carried rather than looked up later: by the time this is read, the app may be in
 *   whatever state the interruption left it, and a label resolved then could be blank.
 */
data class ArchiveBreadcrumb(
    val packageName: String,
    val appLabel: String,
    val startedAt: Long,
)

/**
 * §8.5. Written before the destructive phase, deleted on success.
 *
 * A breadcrumb surviving to the next launch means Thor can say *"the restore of X was interrupted and
 * its data may be incomplete"* instead of letting the user discover it when the app crashes.
 *
 * **It is a notice, not a resume token, and it deliberately records no phase.** An interrupted restore
 * is not resumable in this design: `AppDataCommands.extractCommand` opens with `rm -rf '<staging>'`, so
 * re-entering the extract deletes a staged tree that may at that moment hold data the class root no
 * longer has; and promoting a staged tree without re-entering the extract is worse still, because
 * nothing records *which archive* that tree came from — restore A dies mid-swap, the user then restores
 * B, and an automatic "resume at the swap" would put A's data in place and report B. That is exactly
 * the mixing `extractCommand`'s leading `rm -rf` exists to prevent. So the only safe recovery is the
 * one §8.6 describes: tell the user, and let them start a restore from the top.
 */
interface ArchiveBreadcrumbStore {

    /**
     * @return false when the notice could not be recorded — a full or unwritable `filesDir`.
     *
     * It returns a value rather than swallowing the failure because the caller cannot make the write
     * succeed but *can* stop being silent about it: a destructive phase that runs with no breadcrumb
     * behind it is precisely the silence §8.5 exists to prevent, and the user is owed the warning
     * that an interruption from here on will not be reported.
     */
    suspend fun write(packageName: String, appLabel: String): Boolean

    /** Null when no restore is recorded as in flight. */
    suspend fun read(): ArchiveBreadcrumb?

    /**
     * [read], re-run whenever this store's own [write] or [clear] changes the answer. Emits once on
     * collection, so it is a drop-in for a one-shot read.
     *
     * It exists because two surfaces show this notice at once. `ArchiveRestore` is registered as a
     * detail pane, so on an expanded window the restore screen and the Settings section are composed
     * together: acknowledging the notice on one has to take it off the other, and a screen that reads
     * once keeps reporting a breadcrumb that has been deleted.
     *
     * **In-process only.** The trigger is a call on this instance, not a file watch — which is enough
     * because one process writes breadcrumbs and Koin binds one instance of this within it, workers
     * included: WorkManager declares no `android:process`, so a worker shares it. (Thor is not a
     * single-process app — `ThorRootService` runs in `:root` — but nothing there touches this file.) An
     * implementation that has no way to change underneath itself may return a single-element flow.
     *
     * A consumer outside the screen running the restore wants `ObserveInterruptedRestoreUseCase`, not
     * this: the breadcrumb is written at the *start* of the destructive phase, so a live observation of
     * it reports a running restore as one that did not finish.
     */
    fun observe(): Flow<ArchiveBreadcrumb?>

    /** Idempotent: called on every success path and again from the launch sweep. */
    suspend fun clear()
}
