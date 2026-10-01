// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor

import android.content.Context
import android.util.Base64
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.gateway.ShizukuSystemGateway
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.gateway.SystemGateway
import com.valhalla.thor.domain.model.*
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Serializable
internal data class SettingsBridgeRequest(val operation: String, val table: String, val userId: Int, val key: String? = null, val expected: SettingValue? = null, val desired: SettingValue? = null)
@Serializable
internal data class SettingsBridgeResponse(val status: String, val entries: List<SettingEntry>)

/** Keep the process watchdog as defense in depth alongside Odin isolated-job cancellation. */
internal fun settingsEditorProcessDeadline(command: String, seconds: Int = 30): String {
    require(seconds > 0)
    return "/system/bin/toybox timeout --foreground -s KILL $seconds $command"
}

@Single
class SettingsEditorRepository(
    context: Context,
    private val root: RootSystemGateway,
    private val shizuku: ShizukuSystemGateway,
    private val preferences: PreferenceRepository,
    private val privileges: PrivilegeStateProvider,
    private val store: SettingsEditorStore,
    @Named("io") private val io: CoroutineDispatcher,
) {
    private val apk = context.applicationInfo.sourceDir
    private val json = Json { encodeDefaults = true }
    private val controller = SettingsEditorController({ thorUserId }, { store.consent.first() }, ::openSession, store.history)
    private suspend fun openSession(): SettingsEditorSession {
        val mode = settingsEditorMode(privileges.state.value, preferences.userPreferences.first().preferredPrivilegeMode) ?: error("unavailable")
        val execution = PrivilegeExecutionContext(
            commandClass = PrivilegeCommandClass("settings_editor.manage"),
            rootExecutionPolicy = RootExecutionPolicy.ISOLATED,
        )
        val gateway: SystemGateway = when (mode) {
            PrivilegeMode.ROOT -> root.also { check(it.isRootAvailable(execution)) { "unavailable" } }
            PrivilegeMode.SHIZUKU -> shizuku.also { check(it.isShizukuAvailable()) { "unavailable" } }
            else -> error("unavailable")
        }
        return object : SettingsEditorSession {
            override val provider = mode
            override suspend fun ensureWriteAllowed(view: SettingsEditorView, userId: Int, key: String) {
                store.rootExecutions.ensureWriteAllowed(SettingsRootResource(view, userId, key))
            }
            override suspend fun read(view: SettingsEditorView, userId: Int): List<SettingEntry> =
                execute(SettingsBridgeRequest("read", requireNotNull(view.table), userId)).entries
            override suspend fun properties(): List<SettingEntry> =
                execute(SettingsBridgeRequest("properties", "system", 0)).entries
            override suspend fun write(view: SettingsEditorView, userId: Int, key: String, expected: SettingValue, desired: SettingValue): SettingsBridgeResult {
                ensureWriteAllowed(view, userId, key)
                // Recheck selected mode before dispatch. Verification itself stays on this provider.
                check(settingsEditorMode(privileges.state.value, preferences.userPreferences.first().preferredPrivilegeMode) == mode) { "unavailable" }
                val reply = execute(SettingsBridgeRequest("write", requireNotNull(view.table), userId, key, expected, desired))
                return SettingsBridgeResult(reply.entries, reply.status == "conflict")
            }
            private suspend fun execute(request: SettingsBridgeRequest): SettingsBridgeResponse {
                val payload = Base64.encodeToString(json.encodeToString(request).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                // Settings Editor uses Odin's isolated cancellable job; keep this watchdog until
                // the full supported root-manager/device matrix establishes its replacement.
                // The executor awaits termination and output drain before releasing its lease.
                val command = "CLASSPATH=${quote(apk)} " + settingsEditorProcessDeadline(
                    "/system/bin/app_process /system/bin com.valhalla.thor.data.settingseditor.SettingsEditorBridge ${quote(payload)}"
                )
                val observer = if (mode == PrivilegeMode.ROOT && request.operation == "write") {
                    store.rootExecutions.observer(SettingsRootResource(
                        SettingsEditorView.entries.single { it.table == request.table },
                        request.userId,
                        requireNotNull(request.key),
                    ))
                } else null
                val (code, output) = try {
                    gateway.executeShellCommand(command, execution.copy(rootExecutionObserver = observer)).getOrThrow()
                } catch (failure: IsolatedRootExecutionException) {
                    if (!failure.outcome.cleanupConfirmed) throw SettingsExecutionUncertain(failure)
                    throw failure
                }
                check(code == 0) { "provider_error" }
                val encoded = output.orEmpty().lineSequence().filter { it.startsWith("THOR_SETTINGS:") }.singleOrNull()?.removePrefix("THOR_SETTINGS:") ?: error("provider_error")
                val reply = json.decodeFromString<SettingsBridgeResponse>(String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8))
                check(reply.status == "ok" || reply.status == "conflict") { "provider_error" }
                check(reply.entries.map { it.key }.distinct().size == reply.entries.size) { "provider_error" }
                return reply
            }
        }
    }
    suspend fun read(view: SettingsEditorView): Result<List<SettingEntry>> = guarded {
        when (view) {
            SettingsEditorView.JAVA_PROPERTIES -> { openSession(); System.getProperties().entries.map { SettingEntry(it.key.toString(), it.value.toString()) } }
            SettingsEditorView.ENVIRONMENT -> { openSession(); System.getenv().map { SettingEntry(it.key, it.value) } }
            SettingsEditorView.ANDROID_PROPERTIES -> androidProperties()
            else -> controller.read(view)
        }.sortedBy { it.key }
    }
    private suspend fun androidProperties(): List<SettingEntry> = openSession().properties()
    suspend fun change(view: SettingsEditorView, key: String, expected: SettingValue, desired: SettingValue) = guarded { controller.change(view, key, expected, desired) }
    suspend fun undo(id: String) = guarded { controller.undo(id) }
    suspend fun history() = guarded { controller.history() }
    private suspend fun <T> guarded(block: suspend () -> T): Result<T> = withContext(io) {
        try { Result.success(block()) } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
    }
    internal fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
