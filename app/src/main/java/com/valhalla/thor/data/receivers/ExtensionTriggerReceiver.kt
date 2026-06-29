package com.valhalla.thor.data.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.data.manager.ExtensionManager
import com.valhalla.thor.data.manager.ThorShellExecutor
import com.valhalla.thor.R
import com.valhalla.thor.extension.api.AutomationExtension
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ExtensionTriggerReceiver : BroadcastReceiver(), KoinComponent {

    private val extensionManager: ExtensionManager by inject()
    private val systemRepository: SystemRepository by inject()
    private val extensionDataDao: com.valhalla.thor.data.source.local.room.ExtensionDataDao by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.valhalla.thor.action.TRIGGER_EXTENSION") return

        val extensionClass = intent.getStringExtra("extension_class") ?: return
        val triggerId = intent.getStringExtra("trigger_id") ?: return

        Logger.d("ExtensionTriggerReceiver", "Received trigger for class: $extensionClass, triggerId: $triggerId")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val loadedExtensions = extensionManager.loadExtensions()
                val targetExtension = loadedExtensions.firstOrNull { it.javaClass.name == extensionClass }

                if (targetExtension != null) {
                    if (targetExtension is AutomationExtension) {
                        Logger.d("ExtensionTriggerReceiver", "Executing onTrigger for: $extensionClass")
                        val shellExecutor = ThorShellExecutor(systemRepository)
                        val pkgName = extensionManager.getExtensionPackageName(targetExtension) ?: extensionClass.substringBeforeLast(".")
                        val dataStore = com.valhalla.thor.data.manager.RoomExtensionDataStore(pkgName, extensionDataDao)
                        targetExtension.onTrigger(context, triggerId, shellExecutor, dataStore)

                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.log_trigger_executed, triggerId.substringAfter(":")),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        Logger.e("ExtensionTriggerReceiver", "Extension $extensionClass is not an AutomationExtension")
                    }
                } else {
                    Logger.e("ExtensionTriggerReceiver", "Target extension $extensionClass not found or failed signature check")
                }
            } catch (e: Exception) {
                Logger.e("ExtensionTriggerReceiver", "Error executing trigger", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
