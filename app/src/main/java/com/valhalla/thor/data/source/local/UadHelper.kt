// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

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

/**
 * One consistent read of [UadHelper] — the recommendation map plus whether the list behind it
 * failed to load — taken together so a caller cannot mix a fresh map with a stale flag.
 *
 * That mix is not hypothetical. [UadHelper.didLoadFail] is assigned only inside the lazy load
 * that the `uadMap` getter triggers, so on a cold instance the flag reads `false` until the map
 * has been touched at least once. Code that consults the flag first therefore concludes "the
 * list loaded fine" no matter what — and the safety rule it guards ("no UAD data means treat
 * every system app as blocked") fails *open*, which is the exact direction it must never fail.
 * [UadHelper.snapshot] reads the map first; taking the pair through this type is what stops the
 * order being re-derived, and re-derived wrongly, at each call site.
 */
class UadSnapshot internal constructor(
    private val entries: Map<String, UadEntry>,
    val loadFailed: Boolean,
) {
    /** UAD's removal recommendation for [packageName] ("Unsafe", "Expert", …), or null. */
    fun recommendationFor(packageName: String): String? = entries[packageName]?.removal

    companion object {
        /**
         * A snapshot that classifies nothing, for paths where the tier is irrelevant by design
         * — unfreezing, and the plain state read behind `AppFreezeStateReader.stateOf`.
         *
         * Deliberately NOT a default parameter value anywhere: on a freeze path this would
         * silently disable the block, so every use has to be written out and justified.
         */
        val UNFILTERED = UadSnapshot(emptyMap(), loadFailed = false)
    }
}

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

    /**
     * The map and [didLoadFail] as one value — see [UadSnapshot] for why they must travel
     * together.
     *
     * The `uadMap` read has to come first: it is what forces the lazy load that assigns
     * [didLoadFail], so reading the flag first would observe the pre-load default. Cheap after
     * the first call (the map is cached), so a per-run snapshot is fine.
     */
    fun snapshot(): UadSnapshot {
        val entries = uadMap
        return UadSnapshot(entries, didLoadFail)
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
