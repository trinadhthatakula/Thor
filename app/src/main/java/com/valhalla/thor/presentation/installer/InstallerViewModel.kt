package com.valhalla.thor.presentation.installer

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.data.corepatch.ApkSignerSha
import com.valhalla.thor.data.manager.StrombringerConfigClient
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.model.CorePatchAuthorization
import com.valhalla.thor.domain.repository.AppAnalyzer
import com.valhalla.thor.domain.repository.InstallMode
import com.valhalla.thor.domain.repository.InstallerRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.presentation.corepatch.CorePatchConfirmState
import com.valhalla.thor.presentation.corepatch.buildCorePatchConfirmState
import com.valhalla.thor.presentation.corepatch.capabilityFor
import com.valhalla.thor.util.UiText
import com.valhalla.thor.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@KoinViewModel
class InstallerViewModel(
    private val context: Context,
    private val repository: InstallerRepository,
    private val analyzer: AppAnalyzer,
    private val eventBus: InstallerEventBus,
    private val packageManager: PackageManager,
    private val systemRepository: SystemRepository,
    private val preferenceRepository: PreferenceRepository,
) : ViewModel() {

    val installState = eventBus.events

    // Surfaced so the per-op CorePatch confirm dialog can gate its affirmative action behind a device
    // biometric when the user has the app lock on.
    val biometricLockEnabled: StateFlow<Boolean> = preferenceRepository.userPreferences
        .map { it.biometricLockEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _installMode = MutableStateFlow(InstallMode.NORMAL)
    val installMode: StateFlow<InstallMode> = _installMode.asStateFlow()

    private val _availableModes = MutableStateFlow(listOf(InstallMode.NORMAL))
    val availableModes: StateFlow<List<InstallMode>> = _availableModes.asStateFlow()

    // Non-null while the per-op CorePatch (signature-bypass) confirm dialog is shown. Cleared on
    // confirm or dismiss so a stale authorization can never linger.
    private val _corePatchConfirm = MutableStateFlow<CorePatchConfirmState?>(null)
    val corePatchConfirm: StateFlow<CorePatchConfirmState?> = _corePatchConfirm.asStateFlow()

    var currentPackageName: String? = null
        private set

    private var pendingUri: Uri? = null
    private var isUpdateOperation: Boolean = false
    private var isDowngrade: Boolean = false

    fun resetState() {
        viewModelScope.launch { eventBus.emit(InstallState.Idle) }
    }

    fun parsePackage(uri: Uri) {
        pendingUri = uri
        viewModelScope.launch {
            eventBus.emit(InstallState.Parsing)
            val result = analyzer.analyze(uri)

            result.fold(
                onSuccess = { meta ->
                    currentPackageName = meta.packageName

                    // getPackageInfo() and the privilege checks in checkPrivilegeAndModes()
                    // (isShizukuAvailable()/isDhizukuAvailable() are synchronous binder IPC)
                    // must not run on the main thread.
                    val existing = withContext(Dispatchers.IO) {
                        // Privilege detection is best-effort: an unexpected repository/
                        // binder IPC exception must not crash package parsing. On failure
                        // the available modes simply stay at their defaults (NORMAL) and
                        // parsing still proceeds to getPackageInfo so the user can install.
                        runCatching { checkPrivilegeAndModes(meta.packageName) }
                        runCatching {
                            packageManager.getPackageInfo(meta.packageName, 0)
                        }.getOrNull()
                    }

                    isUpdateOperation = existing != null
                    isDowngrade = if (existing != null) {
                        // meta.versionCode is a Long; compare against the full long version
                        // code so large version codes aren't truncated by the deprecated Int field.
                        meta.versionCode < PackageInfoCompat.getLongVersionCode(existing)
                    } else false

                    eventBus.emit(
                        InstallState.ReadyToInstall(
                            meta = meta,
                            isUpdate = isUpdateOperation,
                            isDowngrade = isDowngrade,
                            oldVersion = existing?.versionName
                        )
                    )
                },
                onFailure = {
                    eventBus.emit(InstallState.Error(UiText.StringResource(R.string.error_parse_package)))
                }
            )
        }
    }

    private suspend fun checkPrivilegeAndModes(packageName: String) {
        val modes = mutableListOf(InstallMode.NORMAL)
        if (systemRepository.isRootAvailable()) modes.add(InstallMode.ROOT)
        if (systemRepository.isShizukuAvailable()) modes.add(InstallMode.SHIZUKU)
        if (systemRepository.isDhizukuAvailable()) modes.add(InstallMode.DHIZUKU)
        
        _availableModes.value = modes
        
        // Pick best available mode
        _installMode.value = when {
            modes.contains(InstallMode.DHIZUKU) -> InstallMode.DHIZUKU
            modes.contains(InstallMode.SHIZUKU) -> InstallMode.SHIZUKU
            modes.contains(InstallMode.ROOT) -> InstallMode.ROOT
            else -> InstallMode.NORMAL
        }
    }

    fun setInstallMode(mode: InstallMode) {
        _installMode.value = mode
    }

    fun startInstallation() {
        val uri = pendingUri ?: return
        val mode = _installMode.value

        if (isDowngrade && mode == InstallMode.NORMAL) {
            viewModelScope.launch {
                eventBus.emit(InstallState.Error(UiText.StringResource(R.string.error_downgrade_privilege)))
            }
            return
        }

        viewModelScope.launch {
            // CorePatch auto-detect. Fail-safe & narrowly scoped: only on the ROOT path (the only mode
            // that honours CorePatch), only when the master gate is on, and only when the incoming APK
            // is signed differently from the already-installed one — the exact case a plain install
            // rejects with INSTALL_FAILED_UPDATE_INCOMPATIBLE. Any other install (gate off, non-root,
            // fresh install, or matching signer) falls straight through to the normal install below,
            // byte-for-byte unchanged. The `mode == ROOT` short-circuit keeps every non-root install
            // off even the cheap pref read.
            if (mode == InstallMode.ROOT && needsCorePatchBypass(uri)) {
                requestCorePatchInstall()
                return@launch
            }
            repository.installPackage(uri, mode, isDowngrade)
        }
    }

    /**
     * True when this ROOT install must be routed through the CorePatch signature-bypass confirm:
     * CorePatch is master-enabled AND the target package is already installed AND the incoming APK's
     * signer differs from the installed one. The master gate is read FIRST so a user who has not
     * opted in never pays the (blocking, disk-copying) signer-probe cost — the normal install stays
     * on its fast path. Signer reads run on IO.
     */
    private suspend fun needsCorePatchBypass(uri: Uri): Boolean {
        val pkg = currentPackageName ?: return false
        return withContext(Dispatchers.IO) {
            // Master gate is read FIRST (over IPC to Strombringer's config provider, fail-safe false)
            // so a user who has not opted in never pays the (blocking, disk-copying) signer-probe cost.
            // The blocking binder read runs on IO.
            if (!StrombringerConfigClient.isCorePatchEnabled(context)) return@withContext false
            // ofInstalled == null => not installed (fresh install): nothing to mismatch, no bypass.
            val installedSigner = ApkSignerSha.ofInstalled(context, pkg) ?: return@withContext false
            val newSigner = newApkSignerSha(uri) ?: return@withContext false
            !installedSigner.equals(newSigner, ignoreCase = true)
        }
    }

    /**
     * Trigger for the per-op CorePatch confirm dialog. Computes the installed and incoming signer
     * SHA-256, derives the capability (`sig` vs `digest`), and publishes a [CorePatchConfirmState]
     * for the UI to render. Call this instead of [startInstallation] when the user opts into a
     * signature-bypass install of the currently-parsed package.
     *
     * The incoming signer is read from a throwaway on-disk copy of the APK; a null result (e.g. a
     * multi-APK bundle that `getPackageArchiveInfo` can't parse) surfaces an error rather than
     * silently arming a bypass with no expected signer to cross-check against.
     */
    fun requestCorePatchInstall() {
        val pkg = currentPackageName ?: return
        val uri = pendingUri ?: return
        viewModelScope.launch {
            val installedSigner = withContext(Dispatchers.IO) {
                ApkSignerSha.ofInstalled(context, pkg)
            }
            val newSigner = withContext(Dispatchers.IO) { newApkSignerSha(uri) }
            if (newSigner == null) {
                eventBus.emit(InstallState.Error(UiText.StringResource(R.string.error_core_patch_signer)))
                return@launch
            }
            _corePatchConfirm.value = buildCorePatchConfirmState(
                pkg = pkg,
                installed = installedSigner,
                new = newSigner,
                capability = capabilityFor(installedSigner, newSigner),
                isDowngrade = isDowngrade,
            )
        }
    }

    fun dismissCorePatchConfirm() {
        _corePatchConfirm.value = null
    }

    /**
     * Confirm handler for the per-op dialog: arms a single-shot [CorePatchAuthorization] built
     * verbatim from [state] (so the armed package is byte-identical to what the user saw) and runs
     * the install on the ROOT path — the only [InstallMode] that honours CorePatch. [disablePlayProtect]
     * is the user's toggle from the dialog.
     */
    fun confirmCorePatchInstall(state: CorePatchConfirmState, disablePlayProtect: Boolean) {
        val uri = pendingUri ?: return
        _corePatchConfirm.value = null
        viewModelScope.launch {
            repository.installPackage(
                uri = uri,
                mode = InstallMode.ROOT,
                canDowngrade = state.isDowngrade,
                corePatch = CorePatchAuthorization(
                    pkg = state.pkg,
                    capability = state.capability,
                    expectedNewSignerSha256 = state.newSignerSha256,
                    disablePlayProtect = disablePlayProtect,
                    downgrade = state.isDowngrade,
                ),
            )
        }
    }

    /**
     * Best-effort SHA-256 of the incoming APK's signer. Copies the content [uri] to a private cache
     * file (content URIs have no readable path) and delegates to [ApkSignerSha.ofApk]. Returns null
     * for anything `getPackageArchiveInfo` can't read (e.g. an XAPK/.apks bundle). Blocking — call
     * off the main thread.
     */
    private fun newApkSignerSha(uri: Uri): String? = runCatching {
        val tempApk = File(context.cacheDir, "corepatch_probe_${UUID.randomUUID()}.apk")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempApk).use { output -> input.copyTo(output) }
            } ?: return null
            ApkSignerSha.ofApk(context, tempApk.absolutePath)
        } finally {
            tempApk.delete()
        }
    }.getOrNull()
}
