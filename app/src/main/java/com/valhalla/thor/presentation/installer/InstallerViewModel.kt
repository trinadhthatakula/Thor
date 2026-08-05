// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.installer

import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.model.AnalyzedPackage
import com.valhalla.thor.domain.model.isVersionDowngrade
import com.valhalla.thor.domain.repository.AppAnalyzer
import com.valhalla.thor.domain.repository.InstallMode
import com.valhalla.thor.domain.repository.InstallerRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.util.UiText
import com.valhalla.thor.R
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Named

@KoinViewModel
class InstallerViewModel(
    private val repository: InstallerRepository,
    private val analyzer: AppAnalyzer,
    private val eventBus: InstallerEventBus,
    private val packageManager: PackageManager,
    private val systemRepository: SystemRepository,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    val installState = eventBus.events

    private val _installMode = MutableStateFlow(InstallMode.NORMAL)
    val installMode: StateFlow<InstallMode> = _installMode.asStateFlow()

    private val _availableModes = MutableStateFlow(listOf(InstallMode.NORMAL))
    val availableModes: StateFlow<List<InstallMode>> = _availableModes.asStateFlow()

    var currentPackageName: String? = null
        private set

    private var pendingUri: Uri? = null

    // The one copy of the picked file, made by the analyzer and installed as-is. This ViewModel
    // owns its lifetime: the analyzer stages it and never deletes it again, so every way out of
    // this screen — a new pick, or teardown — has to discard it or a full-size copy of the file
    // is stranded in the cache.
    private var analyzed: AnalyzedPackage? = null

    // The in-flight analyze(), so a superseding pick can cancel it. Without this the discard in
    // parsePackage() only reclaims a copy that has already been *handed over* — a parse still
    // running owns a copy this class cannot see, and would assign it to `analyzed` after the
    // discard had already run.
    private var analysisJob: Job? = null

    private var isUpdateOperation: Boolean = false
    private var isDowngrade: Boolean = false

    // True when there IS something installed but we could not read a version code out of the
    // picked file, so we can neither prove nor rule out a downgrade. Distinct from isDowngrade:
    // that one is a verdict (it drives the warning and the NORMAL-mode veto) and must only be
    // true when we can prove one. This one only ever widens the install *permission* — see
    // startInstallation().
    private var versionCodeUnknown: Boolean = false

    // True once THIS ViewModel has driven a parse/install on the shared @Single bus. Only an
    // owning VM may reset the bus in onCleared(), so tearing down a non-owning installer screen
    // (e.g. one that merely observed the bus) can't clobber a terminal Success / ReadyToInstall
    // that a different, still-alive InstallerViewModel is displaying.
    private var ownsInstall = false

    fun resetState() {
        viewModelScope.launch { eventBus.emit(InstallState.Idle) }
    }

    override fun onCleared() {
        // The event bus is app-scoped (@Single) and holds replay = 1. Without a reset it would
        // retain the last emitted state past this ViewModel's lifetime — e.g. a ReadyToInstall
        // carrying a decoded Bitmap, or a terminal Success — leaking it for the rest of the
        // process and replaying stale state onto a future installer screen. Only reset when THIS
        // VM owns the flow, so we don't wipe a state another live InstallerViewModel (sharing this
        // @Single bus) is still showing. The viewModelScope is already cancelled here, so use the
        // synchronous reset() rather than resetState().
        if (ownsInstall) eventBus.reset()
        // Covers the cancel path this class exists to serve: the user swipes the installer sheet
        // away, the Activity finishes, and nothing else would ever delete the staged copy.
        analyzer.discard(analyzed)
        analyzed = null
    }

    fun parsePackage(uri: Uri) {
        pendingUri = uri
        ownsInstall = true
        // A second pick replaces the first; the first's copy has no further use. Two things can
        // hold that copy, and both have to be released here.
        //
        // The *finished* parse hands its copy over in `analyzed` — discard reclaims it.
        //
        // The *still-running* parse does not: `analyzed` is assigned only when analyze() returns,
        // so a discard that lands mid-parse finds null and reclaims nothing, and the older parse
        // then completes and overwrites the newer one's `analyzed` — stranding a full-size copy
        // in the cache until the hourly sweep. Cancelling is what releases that one: analyze()
        // deletes its own staged file when it observes cancellation (AppAnalyzerImpl `!isActive`),
        // and a cancelled coroutine never reaches the `analyzed = analysis` assignment below.
        analysisJob?.cancel()
        analyzer.discard(analyzed)
        analyzed = null
        analysisJob = viewModelScope.launch {
            eventBus.emit(InstallState.Parsing)
            val result = analyzer.analyze(uri)

            result.fold(
                onSuccess = { analysis ->
                    analyzed = analysis
                    val meta = analysis.metadata
                    currentPackageName = meta.packageName

                    // getPackageInfo() and the privilege checks in checkPrivilegeAndModes()
                    // (isShizukuAvailable()/isDhizukuAvailable() are synchronous binder IPC)
                    // must not run on the main thread.
                    val existing = withContext(ioDispatcher) {
                        // Privilege detection is best-effort: an unexpected repository/
                        // binder IPC exception must not crash package parsing. On failure
                        // the available modes simply stay at their defaults (NORMAL) and
                        // parsing still proceeds to getPackageInfo so the user can install.
                        runCatching { checkPrivilegeAndModes(meta.packageName) }
                        runCatching {
                            packageManager.getPackageInfo(meta.packageName, 0)
                        }.getOrNull()
                    }

                    val installedVersionCode =
                        existing?.let { PackageInfoCompat.getLongVersionCode(it) }

                    isUpdateOperation = existing != null
                    // isVersionDowngrade itself gates on a KNOWN version code: the analyzer yields
                    // null when it could not read one out of the file, and any number we
                    // substituted would lose against every installed app.
                    isDowngrade = installedVersionCode != null &&
                        isVersionDowngrade(meta.versionCode, installedVersionCode)
                    versionCodeUnknown = installedVersionCode != null && meta.versionCode == null

                    eventBus.emit(
                        InstallState.ReadyToInstall(
                            meta = meta,
                            isUpdate = isUpdateOperation,
                            isDowngrade = isDowngrade,
                            oldVersion = existing?.versionName,
                            oldVersionCode = installedVersionCode
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
        // No staged copy means no analysis succeeded, and installing would mean reading the URI
        // a second time — the very thing the staging exists to prevent. There is nothing to
        // install here: the sheet only offers Install from ReadyToInstall.
        val staged = analyzed?.staged ?: return
        ownsInstall = true
        val mode = _installMode.value

        if (isDowngrade && mode == InstallMode.NORMAL) {
            viewModelScope.launch {
                eventBus.emit(InstallState.Error(UiText.StringResource(R.string.error_downgrade_privilege)))
            }
            return
        }

        // The downgrade *permission* is deliberately wider than the downgrade *verdict*. It ends up
        // as `pm install -d` / setRequestDowngrade(true), which is permissive-only — a no-op when
        // the install turns out not to be a downgrade — so it is also the right flag when we could
        // not read a version code at all: withholding it would let the OS reject the install with
        // an opaque INSTALL_FAILED_VERSION_DOWNGRADE the user cannot override. Widened only on a
        // privileged path; in NORMAL mode the flag needs a privilege we do not have, so it would
        // buy nothing and merely put a reflective setRequestDowngrade call on the unprivileged
        // happy path for the first time.
        val allowDowngrade = isDowngrade || (versionCodeUnknown && mode != InstallMode.NORMAL)

        viewModelScope.launch {
            repository.installPackage(staged, uri, mode, allowDowngrade)
        }
    }
}
