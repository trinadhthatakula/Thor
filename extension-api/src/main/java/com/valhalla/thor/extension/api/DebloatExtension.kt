package com.valhalla.thor.extension.api

interface DebloatExtension : ThorExtension {
    val targetManufacturer: String
    fun getDebloatItems(): List<ExtensionDebloatItem>
}

data class ExtensionDebloatItem(
    val packageName: String,
    val recommendation: String, // "recommended", "advanced", "expert", "unsafe"
    val description: String
)
