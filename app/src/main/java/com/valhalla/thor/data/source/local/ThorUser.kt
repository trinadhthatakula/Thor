// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import android.os.Process

/**
 * The Android user Thor itself runs as — the one every per-package command has to name.
 *
 * Read in-process from [Process.myUserHandle]: no shell, no permission, no binder. Both privilege
 * helpers resolve the user through this one symbol so that a freeze rung and its fallback cannot
 * name different users; a rung that disables for user 10 followed by a fallback that uninstalls for
 * user 0 leaves the verify confirming a state nobody set. `SUSPEND_USER_ID` in `RootSystemGateway`
 * makes the same point from the other direction.
 *
 * Deliberately **not** `am get-current-user`, which is what the two helpers used to shell out for.
 * That reports the *foreground* user — a different number from this one on any work-profile device
 * — and it is a command Dhizuku's device-owner identity is not permitted to run at all.
 *
 * It lives here, in the package both privilege helpers sit under, rather than in either of them:
 * neither Shizuku nor Dhizuku owns this question, and a value used by both should not make one
 * privilege mode's package depend on the other's. `userIdOf` in `data/gateway/AndroidUserIds.kt`
 * answers the neighbouring question — which user some *other* uid belongs to — and stays with the
 * gateways that ask it.
 */
internal val thorUserId: Int get() = Process.myUserHandle().hashCode()
