package com.valhalla.thor.data.source.local

import android.content.Context
import com.valhalla.thor.data.manager.ExtensionManager
import org.json.JSONObject
import org.koin.core.annotation.Single

data class UadEntry(
    val list: String,
    val description: String,
    val removal: String
)

@Single
class UadHelper(
    private val context: Context,
    private val extensionManager: ExtensionManager
) {

    @Volatile
    var didLoadFail = false
        private set

    private val lock = Any()

    @Volatile
    private var cachedMap: Map<String, UadEntry>? = null

    val uadMap: Map<String, UadEntry>
        get() {
            cachedMap?.let { return it }
            return synchronized(lock) {
                cachedMap ?: buildUadMap().also { cachedMap = it }
            }
        }

    fun invalidateCache() {
        // Lock-free by design: this is called from a main-thread BroadcastReceiver on
        // every package change. Taking `lock` here would block the main thread behind
        // an in-progress buildUadMap() (a ~1.6MB JSON parse), which — during a bulk
        // freeze/unfreeze that floods PACKAGE_CHANGED broadcasts — caused an ANR.
        // A `null` write to the @Volatile field is atomic and visible; the getter still
        // holds `lock` to de-duplicate concurrent rebuilds. Worst case, an invalidate
        // that races a concurrent rebuild is absorbed by that (fresh) rebuild — a
        // harmless one-generation staleness for a static recommendation list.
        cachedMap = null
    }

    private fun buildUadMap(): Map<String, UadEntry> {
        val map = loadUadList().toMutableMap()
        // Isolate per-extension failures so one bad provider doesn't drop the rest.
        extensionManager.getDebloatExtensions().forEach { extension ->
            try {
                extension.getDebloatItems().forEach { item ->
                    map[item.packageName] = UadEntry(
                        list = extension.name,
                        description = item.description,
                        removal = item.recommendation
                    )
                }
            } catch (e: Exception) {
                com.valhalla.thor.util.Logger.e("UadHelper", "Failed to load debloat items from extension ${extension.name}", e)
            }
        }
        return map
    }

    private fun loadUadList(): Map<String, UadEntry> {
        val map = HashMap<String, UadEntry>()
        try {
            val jsonString = context.assets.open("uad_lists.json").bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)
            for (key in jsonObject.keys()) {
                val valueObj = jsonObject.getJSONObject(key)
                val list = valueObj.optString("list", "")
                val description = valueObj.optString("description", "")
                val removal = valueObj.optString("removal", "")
                map[key] = UadEntry(list, description, removal)
            }
            didLoadFail = false
        } catch (e: Exception) {
            com.valhalla.thor.util.Logger.e("UadHelper", "Failed to load uad_lists.json", e)
            didLoadFail = true
        }
        return map
    }
}
