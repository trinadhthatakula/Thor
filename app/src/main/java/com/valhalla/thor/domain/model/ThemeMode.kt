package com.valhalla.thor.domain.model

enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM;

    fun label(): String = when (this) {
        LIGHT -> "Light"
        DARK -> "Dark"
        SYSTEM -> "System"
    }
}
