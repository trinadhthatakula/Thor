package com.valhalla.thor.domain.model

enum class PrivilegeMode {
    /** No privilege available (reactive/active value only — never persisted as a preference). */
    NONE,
    ROOT,
    SHIZUKU,
    DHIZUKU
}
