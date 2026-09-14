// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import android.content.pm.ApplicationInfo
import com.valhalla.bypass.Bypass

/**
 * Device-policy hiding leaves both `enabled` and FLAG_INSTALLED set. The hidden bit is carried in
 * ApplicationInfo returned with MATCH_UNINSTALLED_PACKAGES, including to an unprivileged reader.
 * A failed field read stays an error: treating it as visible could offer to freeze an already
 * hidden app and omit it from the unfreeze list.
 */
val ApplicationInfo.isHiddenForUser: Boolean
    get() = Bypass.getField<Int>(this, "privateFlags") and PRIVATE_FLAG_HIDDEN != 0

/** The non-suspension half of Thor's frozen state, shared by scans and live package reads. */
val ApplicationInfo.isEffectivelyEnabled: Boolean
    get() = isApplicationEffectivelyEnabled(enabled, flags, isHiddenForUser)

fun isApplicationEffectivelyEnabled(enabled: Boolean, flags: Int, hidden: Boolean): Boolean =
    enabled && (flags and ApplicationInfo.FLAG_INSTALLED) != 0 && !hidden

// android.content.pm.ApplicationInfo.PRIVATE_FLAG_HIDDEN, stable since device-policy hiding was
// introduced. This is distinct from PRIVATE_FLAG_PRIVILEGED (1 shl 3).
private const val PRIVATE_FLAG_HIDDEN = 1
