package com.valhalla.thor.extension.api

import android.util.Log

object Logger {
    var isDebug: Boolean = false

    fun d(tag: String, message: String) {
        if (isDebug) {
            Log.d(tag, message)
        }
    }

    fun i(tag: String, message: String) {
        if (isDebug) {
            Log.i(tag, message)
        }
    }

    fun w(tag: String, message: String) {
        if (isDebug) {
            Log.w(tag, message)
        }
    }

    fun v(tag: String, message: String) {
        if (isDebug) {
            Log.v(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (isDebug) {
            Log.e(tag, message, throwable)
        }
    }
}
