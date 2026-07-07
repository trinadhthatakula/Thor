package com.valhalla.thor.data.manager

import android.content.Context
import android.net.Uri

/**
 * Reads the CorePatch master-enable flag from Strombringer's exported config provider (IPC).
 *
 * The flag now lives in the Strombringer LSPosed extension — the extension OWNS writing it; Thor
 * only READS it. This replaces Thor's former durable `corePatchEnabled` preference so the master
 * opt-in has a single source of truth in the extension that actually performs the bypass.
 *
 * Fail-safe by design: any failure — extension not installed, provider missing/unexported, IPC
 * error, or the flag unset — resolves to `false` (CorePatch OFF). A caller can therefore treat
 * `true` as "the user has explicitly opted in via the extension" and nothing else.
 *
 * Blocking binder IPC — call off the main thread.
 */
object StrombringerConfigClient {

    /** Authority of Strombringer's exported config provider. */
    private const val CONFIG_AUTHORITY = "content://com.valhalla.thor.ext.strombringer.config"

    private const val METHOD_GET = "get"
    private const val KEY_CORE_PATCH_ENABLED = "core_patch_enabled"
    private const val EXTRA_VALUE = "value"

    fun isCorePatchEnabled(context: Context): Boolean = runCatching {
        context.contentResolver.call(
            Uri.parse(CONFIG_AUTHORITY),
            METHOD_GET,
            KEY_CORE_PATCH_ENABLED,
            null,
        )?.getBoolean(EXTRA_VALUE) == true
    }.getOrDefault(false)
}
