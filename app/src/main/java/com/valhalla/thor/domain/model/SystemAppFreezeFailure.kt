// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** Stable reasons survive queue persistence without storing localized messages or shell output. */
enum class SystemAppFreezeFailureReason {
    SYSTEM_APP_DISABLE_REFUSED,
    SYSTEM_APP_DISABLE_FAILED,
    SYSTEM_APP_RESTORE_FAILED,
}

class SystemAppFreezeFailure(
    val reason: SystemAppFreezeFailureReason,
    message: String,
) : java.io.IOException(message)
