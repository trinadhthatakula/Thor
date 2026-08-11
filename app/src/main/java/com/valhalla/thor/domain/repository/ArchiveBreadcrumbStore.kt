// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

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

    suspend fun write(packageName: String, appLabel: String)

    /** Null when no restore is recorded as in flight. */
    suspend fun read(): ArchiveBreadcrumb?

    /** Idempotent: called on every success path and again from the launch sweep. */
    suspend fun clear()
}
