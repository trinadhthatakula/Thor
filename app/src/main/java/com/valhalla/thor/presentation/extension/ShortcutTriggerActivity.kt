package com.valhalla.thor.presentation.extension

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.valhalla.thor.util.Logger

class ShortcutTriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        if (uri == null || uri.scheme != "thor" || uri.host != "extension" || uri.path != "/trigger") {
            finish()
            return
        }

        val extensionClass = uri.getQueryParameter("class")
        val triggerId = uri.getQueryParameter("triggerId")

        if (extensionClass.isNullOrEmpty() || triggerId.isNullOrEmpty()) {
            finish()
            return
        }

        Logger.d("ShortcutTriggerActivity", "Received shortcut deep-link for: $extensionClass, triggerId: $triggerId")

        // Forward to ExtensionTriggerReceiver via secure broadcast
        val forwardIntent = Intent("com.valhalla.thor.action.TRIGGER_EXTENSION").apply {
            setPackage(packageName)
            putExtra("extension_class", extensionClass)
            putExtra("trigger_id", triggerId)
        }
        sendBroadcast(forwardIntent, "com.valhalla.thor.permission.TRIGGER_EXTENSION")

        // Call finish() synchronously to comply with Theme.NoDisplay requirements
        finish()
    }
}
