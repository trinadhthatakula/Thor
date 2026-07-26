// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * True iff [newVersionCode] is a *known* version code strictly older than [installedVersionCode].
 *
 * A null [newVersionCode] means "unknown" — the analyzer could not read a version code out of the
 * picked file at all — and must never be reported as a downgrade, because an unknown would
 * otherwise have to be represented by some number, and any number we picked would lose against
 * something. Unknown is deliberately *not* encoded as `0`: `0` is a legal Android version code (the
 * platform parser defaults a manifest with no `android:versionCode` to zero) and Android compares
 * it numerically like any other, so installing a genuine `0` over a positive code IS a downgrade
 * and has to be reported as one.
 *
 * Version *names* are deliberately not consulted: Android sequences updates by version code alone,
 * so a file may carry a newer-looking name (`1.2.5.1` over `1.2.4.7`) and still be a real
 * downgrade. Callers should surface both codes so that verdict is explainable to the user.
 */
fun isVersionDowngrade(newVersionCode: Long?, installedVersionCode: Long): Boolean =
    newVersionCode != null && newVersionCode < installedVersionCode
