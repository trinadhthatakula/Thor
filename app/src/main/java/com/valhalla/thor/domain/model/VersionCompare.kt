// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * True iff [newVersionCode] is a *known* version code strictly older than [installedVersionCode].
 *
 * A non-positive [newVersionCode] means "unknown" — the analyzer could not read a usable version
 * code out of the picked file — and must never be reported as a downgrade. `0` loses every
 * comparison, so treating it as authoritative would flag an install over *any* installed app as a
 * downgrade, however new the file actually is. Same `0 = unknown` convention as
 * [CatalogEntry.versionCode].
 *
 * Version *names* are deliberately not consulted: Android sequences updates by version code alone,
 * so a file may carry a newer-looking name (`1.2.5.1` over `1.2.4.7`) and still be a real
 * downgrade. Callers should surface both codes so that verdict is explainable to the user.
 */
fun isVersionDowngrade(newVersionCode: Long, installedVersionCode: Long): Boolean =
    newVersionCode > 0L && newVersionCode < installedVersionCode
