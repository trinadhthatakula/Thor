package com.valhalla.thor.domain.model

enum class AnimationIntensity {
    LOW,
    MEDIUM,
    HIGH;

    fun label(): String = when (this) {
        LOW -> "Low"
        MEDIUM -> "Medium"
        HIGH -> "High"
    }
}
