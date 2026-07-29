// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

/**
 * `UserHandle.PER_USER_RANGE`, which is `@hide`. Every Android user gets a contiguous block of this
 * many uids, so integer-dividing a uid by it yields the user the uid belongs to.
 */
private const val PER_USER_RANGE = 100_000

/**
 * The Android user id owning [uid] — the arithmetic behind the `@hide` `UserHandle.getUserId()`, and
 * what shell tooling uses for the same mapping.
 *
 * Shared by all three gateways rather than repeated in each: they must agree on which user a package
 * lives in, or the same `pm grant` would land on a different user depending on which privilege mode
 * happened to be active. One copy is also one place to correct if the platform ever moves the range.
 */
internal fun userIdOf(uid: Int): Int = uid / PER_USER_RANGE
