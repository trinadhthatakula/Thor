package com.valhalla.thor.model

import kotlinx.serialization.Serializable

sealed interface FilterType {
    data object Source : FilterType
    data object State : FilterType {
        val types = listOf(
            "All","Active","Frozen"//, "Suspended", "Compressed"
        )
    };//active, frozen, suspended, compressed etc.


}

val filterTypes = listOf(
    FilterType.State,
    FilterType.Source
)

fun FilterType.asGeneralName() = when (this) {
    FilterType.State -> "Active State"
    FilterType.Source -> "Installation Source"
}




