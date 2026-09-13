// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.Entity
import androidx.room.Index

/**
 * One component Thor switched off, and what it was before Thor touched it.
 *
 * **Bookkeeping only.** The PackageManager is the source of truth for what a component's state
 * actually *is*; this table only records what Thor did, so that "Restore all" has something to
 * restore to and so the user can find the changes they made across dozens of apps without
 * remembering which apps those were. Nothing re-applies these rows: there is no boot receiver, no
 * periodic sweep, and an override that the system, the app, or another tool undoes simply stops
 * being true — which the Components tab reports as drift rather than silently re-imposing.
 *
 * That choice is what keeps the feature honest. A ledger that re-applied itself would be a second,
 * invisible source of truth racing the first, and the failure mode is a component the user re-enabled
 * in Settings turning itself off again on the next boot with no visible cause.
 *
 * @param componentType a [com.valhalla.thor.domain.model.ComponentType] name. Stored rather than
 * looked up, because a row has to keep rendering after an app update removes the component it names
 * — at which point `PackageManager` can no longer say whether it was a service or a receiver, and
 * the row would otherwise have to be dropped or filed under "unknown".
 * @param restoreToEnabled the component's `android:enabled` as it stood when Thor wrote this row.
 * Restoring means putting the component back the way its developer shipped it, which for the
 * majority of components means "enabled" but for a meaningful minority — stubs, A/B experiment
 * receivers, OEM variants — means "disabled". Restoring those to *enabled* would be Thor inventing
 * a state the app never had.
 * @param userId the Android user the override was written for. Part of the key because the same
 * package in a work profile is a different installation with its own component state, and a row
 * that did not name the user would let a personal-profile restore claim to undo a work-profile
 * change.
 */
@Entity(
    tableName = "component_overrides",
    primaryKeys = ["packageName", "className", "userId"],
    indices = [Index("packageName")],
)
data class ComponentOverrideEntity(
    val packageName: String,
    val className: String,
    val userId: Int,
    val componentType: String,
    val restoreToEnabled: Boolean,
    val disabledAt: Long,
)
