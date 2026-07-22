// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

enum class PrivilegeMode {
    /** No privilege available (reactive/active value only — never persisted as a preference). */
    NONE,
    ROOT,
    SHIZUKU,
    DHIZUKU
}
