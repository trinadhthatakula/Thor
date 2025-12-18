package com.valhalla.thor.presentation.common

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Handles the messy boilerplate of listening to Shizuku's binder and requesting permissions.
 *
 * Usage in Activity:
 * 1. Initialize: val shizukuHandler = ShizukuPermissionHandler(...)
 * 2. onCreate: shizukuHandler.register()
 * 3. onDestroy: shizukuHandler.unregister()
 * 4. onResume: shizukuHandler.checkAndRequestPermission(code)
 */
class ShizukuPermissionHandler(
    private val onPermissionGranted: () -> Unit = {},
    private val onBinderDead: () -> Unit = {}
) {

    /**
     * Listener for when Shizuku service starts/binds.
     * We immediately check permission when this happens.
     */
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (checkPermission()) {
            onPermissionGranted()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        onBinderDead()
    }

    /**
     * Callback for the permission dialog result.
     */
    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                onPermissionGranted()
            }
        }

    fun register() {
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
    }

    fun unregister() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
    }

    /**
     * Checks if we have permission. If not, requests it.
     * Returns true if already granted.
     */
    fun checkAndRequestPermission(requestCode: Int): Boolean {
        // If binder isn't ready, we can't do anything yet.
        // The binderReceivedListener will handle it when it wakes up.
        if (!Shizuku.pingBinder()) {
            return false
        }

        if (checkPermission()) {
            onPermissionGranted()
            return true
        }

        // Logic to request permission
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                // Ideally, show a UI explanation here before requesting.
                // For now, we proceed to request.
            }
            Shizuku.requestPermission(requestCode)
        } catch (e: Exception) {
            // Shizuku server might be acting up or version mismatch
            return false
        }
        return false
    }

    private fun checkPermission(): Boolean {
        return try {
            if (Shizuku.pingBinder()) {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
        } catch (e: Throwable) {
            false
        }
    }
}