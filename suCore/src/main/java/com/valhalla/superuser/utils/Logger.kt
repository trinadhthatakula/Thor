@file:Suppress("unused")

package com.valhalla.superuser.utils

import com.valhalla.superuser.BuildConfig
import android.util.Log

object Logger {

    fun d(tag: String?, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag?:"", message)
        }
    }

    fun i(tag: String?, message: String) {
        if (BuildConfig.DEBUG) {
            Log.i(tag?:"", message)
        }
    }

    fun w(tag: String?, message: String) {
        if (BuildConfig.DEBUG) {
            Log.w(tag?:"", message)
        }
    }

    fun v(tag: String?, message: String) {
        if (BuildConfig.DEBUG) {
            Log.v(tag?:"", message)
        }
    }

    fun e(tag: String?, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            Log.e(tag?:"", message, throwable)
        }
    }
}