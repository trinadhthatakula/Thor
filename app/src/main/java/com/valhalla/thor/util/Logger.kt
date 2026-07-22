// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.util

import com.valhalla.thor.BuildConfig
import org.koin.core.logger.Level

typealias Logger = com.valhalla.thor.extension.api.Logger

val Logger.koinLogLevel: Level
    get() = if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE