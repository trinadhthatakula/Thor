package com.valhalla.thor.model

import kotlinx.serialization.Serializable

@Serializable
data class AttestationStatus(
    val entries : List<KeyEntry>
)

@Serializable
data class KeyEntry(
    val cert: String,
    val status: String,
    val reason: String
)
