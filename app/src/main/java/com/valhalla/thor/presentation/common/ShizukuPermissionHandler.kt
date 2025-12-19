package com.valhalla.thor.presentation.common

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Handles the messy boilerplate of listening to Shizuku's binder and requesting permissions.
 */
class ShizukuPermissionHandler(
    private val onPermissionGranted: () -> Unit = {},
    private val onPermissionDenied: () -> Unit = {},
    private val onBinderDead: () -> Unit = {}
) {

    private var isRequestInProgress = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (checkPermission()) {
            onPermissionGranted()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        onBinderDead()
    }

    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            isRequestInProgress = false // Reset flag
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                onPermissionGranted()
            } else {
                onPermissionDenied()
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
        if (!Shizuku.pingBinder()) {
            return false
        }

        if (checkPermission()) {
            onPermissionGranted()
            return true
        }

        // Prevent spamming requests if one is already pending
        if (isRequestInProgress) return false

        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                // Ideally show UI rationale here.
            }
            isRequestInProgress = true // Set flag
            Shizuku.requestPermission(requestCode)
        } catch (_: Exception) {
            isRequestInProgress = false
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