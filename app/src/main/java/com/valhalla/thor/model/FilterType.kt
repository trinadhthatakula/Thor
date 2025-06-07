package com.valhalla.thor.model

import com.valhalla.thor.model.FilterType.SOURCE
import com.valhalla.thor.model.FilterType.STATE

enum class FilterType {
    SOURCE,
    STATE//active, frozen, suspended, compressed etc.
}

fun FilterType.asGeneralName() = when (this) {
    STATE -> "Active State"
    SOURCE -> "Installation Source"
}


