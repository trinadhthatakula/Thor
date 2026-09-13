// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import androidx.compose.runtime.Immutable

/**
 * A component Thor switched off, as the ledger remembers it.
 *
 * Deliberately *not* a claim about the component's current state. The ledger records what Thor did;
 * `PackageManager` says what is true now. Where the two disagree — the user re-enabled the component
 * in Settings, the app re-enabled it itself (which any app may always do for its own components),
 * an update removed it — the Components tab reports the disagreement and offers to forget the row.
 * It never re-applies it.
 *
 * @param restoreToEnabled what `android:enabled` said when the row was written, which is what
 * "Restore" puts the component back to. Not always `true`: a component that ships disabled and was
 * disabled again by Thor must be restored to *disabled*, or Thor has invented a state the app never
 * had.
 */
@Immutable
data class ComponentOverride(
    val packageName: String,
    val className: String,
    val type: ComponentType,
    val restoreToEnabled: Boolean,
    val disabledAt: Long,
)
