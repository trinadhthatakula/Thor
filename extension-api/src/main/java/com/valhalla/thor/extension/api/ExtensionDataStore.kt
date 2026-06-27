package com.valhalla.thor.extension.api

interface ExtensionDataStore {
    fun saveString(key: String, value: String)
    fun getString(key: String): String?
    fun deleteString(key: String)
    fun getAllKeys(): List<String>
}
