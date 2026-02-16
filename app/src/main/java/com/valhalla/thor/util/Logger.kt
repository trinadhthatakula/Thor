@file:Suppress("unused")

package com.valhalla.thor.util

import android.util.Log
import com.valhalla.thor.BuildConfig
import org.koin.core.logger.Level

object Logger {

    /**
     * Koin Log Level Configuration.
     * Usage in startKoin: androidLogger(Logger.koinLogLevel)
     */
    val koinLogLevel: Level = if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.i(tag, message)
        }
    }

    fun w(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.w(tag, message)
        }
    }

    fun v(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.v(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            Log.e(tag, message, throwable)
        }
    }
}