package com.valhalla.thor.domain.model

import com.valhalla.thor.R
import kotlinx.serialization.Serializable

@Serializable
enum class SortBy {
    NAME,
    //SIZE,
    INSTALL_DATE,
    LAST_UPDATED,
    VERSION_CODE,
    VERSION_NAME,
    TARGET_SDK_VERSION,
    MIN_SDK_VERSION;

    fun asGeneralName(): String = when (this) {
        NAME -> "Name"
        //SIZE -> "Size"
        INSTALL_DATE -> "Install Date"
        LAST_UPDATED -> "Last Updated"
        VERSION_CODE -> "Version Code"
        VERSION_NAME -> "Version Name"
        TARGET_SDK_VERSION -> "Target SDK Version"
        MIN_SDK_VERSION -> "Min SDK Version"
    }
}

fun SortBy.isDateBased(): Boolean = this == SortBy.INSTALL_DATE || this == SortBy.LAST_UPDATED

fun SortBy.isVersionBased(): Boolean = this == SortBy.VERSION_CODE || this == SortBy.VERSION_NAME

fun SortBy.isSdkBased(): Boolean = this == SortBy.TARGET_SDK_VERSION || this == SortBy.MIN_SDK_VERSION

fun SortBy.isNameBased(): Boolean = this == SortBy.NAME

@Serializable
enum class SortOrder {
    ASCENDING,
    DESCENDING;

    fun asGeneralName(): String = when (this) {
        ASCENDING -> "Ascending"
        DESCENDING -> "Descending"
    }

    fun icon() = when (this) {
        ASCENDING -> R.drawable.arrow_upward
        DESCENDING -> R.drawable.arrow_downward
    }

    fun flip(): SortOrder = when (this) {
        ASCENDING -> DESCENDING
        DESCENDING -> ASCENDING
    }

    fun angle(): Float = when (this) {
        ASCENDING -> 0f
        DESCENDING -> 180f
    }

}