package com.valhalla.thor.presentation.extension

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.valhalla.superuser.ktx.ShellRepository
import com.valhalla.thor.data.manager.ExtensionManager
import com.valhalla.thor.data.manager.ThorShellExecutor
import com.valhalla.thor.extension.api.AutomationExtension
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ShortcutTriggerActivity : Activity(), KoinComponent {

    private val extensionManager: ExtensionManager by inject()
    private val shellRepository: ShellRepository by inject()

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

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val loadedExtensions = extensionManager.loadExtensions()
                val targetExtension = loadedExtensions.firstOrNull { it.javaClass.name == extensionClass }

                if (targetExtension != null && targetExtension is AutomationExtension) {
                    val shellExecutor = ThorShellExecutor(shellRepository)
                    targetExtension.onTrigger(this@ShortcutTriggerActivity, triggerId, shellExecutor)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@ShortcutTriggerActivity,
                            "Trigger executed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@ShortcutTriggerActivity,
                            "Failed to load extension trigger",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Logger.e("ShortcutTriggerActivity", "Error executing shortcut trigger", e)
            } finally {
                finish()
            }
        }
    }
}
