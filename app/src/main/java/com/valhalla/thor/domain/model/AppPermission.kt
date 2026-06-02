package com.valhalla.thor.domain.model

data class AppPermission(
    val name: String,
    val label: String,
    val description: String,
    val group: String?,
    val isGranted: Boolean,
    val isRuntime: Boolean,
    val protectionLevel: Int
)
