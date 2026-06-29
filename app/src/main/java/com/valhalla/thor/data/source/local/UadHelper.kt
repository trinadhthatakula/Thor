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

    var didLoadFail = false
        private set

    private var cachedMap: Map<String, UadEntry>? = null

    val uadMap: Map<String, UadEntry>
        get() {
            var map = cachedMap
            if (map == null) {
                map = buildUadMap()
                cachedMap = map
            }
            return map
        }

    fun invalidateCache() {
        cachedMap = null
    }

    private fun buildUadMap(): Map<String, UadEntry> {
        val map = loadUadList().toMutableMap()
        try {
            extensionManager.getDebloatExtensions().forEach { extension ->
                extension.getDebloatItems().forEach { item ->
                    map[item.packageName] = UadEntry(
                        list = extension.name,
                        description = item.description,
                        removal = item.recommendation
                    )
                }
            }
        } catch (e: Exception) {
            com.valhalla.thor.util.Logger.e("UadHelper", "Failed to load debloat items from extensions", e)
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
