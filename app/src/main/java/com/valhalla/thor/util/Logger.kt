package com.valhalla.thor.util

import com.valhalla.thor.BuildConfig
import org.koin.core.logger.Level

typealias Logger = com.valhalla.thor.extension.api.Logger

val Logger.koinLogLevel: Level
    get() = if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE