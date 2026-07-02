package com.valhalla.thor.domain.model

/** Where an export should be written. */
sealed interface ExportTargetChoice {
    /** Public Downloads/Thor (MediaStore / legacy). */
    data object Downloads : ExportTargetChoice
    /** A user-picked SAF tree (persisted URI string). */
    data class Custom(val treeUri: String) : ExportTargetChoice
}

/**
 * @param clearSavedDir true when a saved-but-invalid dir should be cleared from prefs.
 */
data class ExportTargetResolution(
    val choice: ExportTargetChoice,
    val clearSavedDir: Boolean
)

/**
 * Pure resolver: use the saved SAF dir only if it is still valid; otherwise fall
 * back to Downloads (and signal a clear when a stale dir was saved).
 */
fun resolveExportTarget(savedUri: String?, isSavedValid: Boolean): ExportTargetResolution =
    when {
        savedUri != null && isSavedValid ->
            ExportTargetResolution(ExportTargetChoice.Custom(savedUri), clearSavedDir = false)
        savedUri != null ->
            ExportTargetResolution(ExportTargetChoice.Downloads, clearSavedDir = true)
        else ->
            ExportTargetResolution(ExportTargetChoice.Downloads, clearSavedDir = false)
    }
