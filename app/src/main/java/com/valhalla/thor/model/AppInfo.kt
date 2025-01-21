package com.valhalla.thor.model


import org.json.JSONObject

import java.io.Serializable


class AppInfo : Serializable {

    var appName: String? = null
    var packageName: String? = null
    var versionName: String? = null
    var versionCode: Int = 0
    var isSystem: Boolean = false
    var installerPackageName: String? = null
    var publicSourceDir: String? = null

    fun toJSON(): JSONObject? {
        val jsonObject = JSONObject()
        try {
            jsonObject.put("appName", appName)
            jsonObject.put("packageName", packageName)
            jsonObject.put("versionName", versionName)
            jsonObject.put("versionCode", versionCode)
            return jsonObject

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

    }



}
