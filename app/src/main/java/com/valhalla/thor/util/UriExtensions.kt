package com.valhalla.thor.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Best-effort display name (hence file extension) for this [Uri]: the provider's
 * [OpenableColumns.DISPLAY_NAME], falling back to the URI's last path segment.
 *
 * Used only as a secondary bundle signal, so a null result is acceptable.
 */
fun Uri.getDisplayName(context: Context): String? {
    val fromProvider = try {
        context.contentResolver.query(
            this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (_: Exception) {
        null
    }
    return fromProvider?.takeIf { it.isNotBlank() } ?: lastPathSegment
}
