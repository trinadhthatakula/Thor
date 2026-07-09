package com.valhalla.thor.presentation.extension

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.data.manager.ExtensionManager
import com.valhalla.thor.domain.model.CatalogEntry
import com.valhalla.thor.domain.repository.StoreRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel

/** Per-entry install lifecycle in the store list. */
sealed interface InstallStatus {
    data object Idle : InstallStatus
    data object Downloading : InstallStatus

    /** Download + verification passed; the verified APK [uri] is ready to hand to the installer. */
    data class ReadyToInstall(val uri: Uri) : InstallStatus
    data class Failed(val reason: String) : InstallStatus
}

data class BrowseUiState(
    val isLoading: Boolean = true,
    val entries: List<CatalogEntry> = emptyList(),
    val error: String? = null,
    /** Keyed by [CatalogEntry.id]. Absent == [InstallStatus.Idle]. */
    val installStatuses: Map<String, InstallStatus> = emptyMap(),
    /** Installed extension packageName -> its installed `versionCode` (drives installed/update badges). */
    val installedVersionCodes: Map<String, Long> = emptyMap(),
)

/**
 * Drives the Extensions store screen: fetches the catalog, and for a chosen entry runs
 * download → SHA-256 → pinned-signer verification via [StoreRepository]. On success it exposes a
 * verified content [Uri] the UI hands to Thor's existing installer; it never installs directly.
 */
@KoinViewModel
class ExtensionBrowseViewModel(
    private val storeRepository: StoreRepository,
    private val extensionManager: ExtensionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowseUiState())
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) {
                runCatching { extensionManager.getInstalledExtensionVersionCodes() }
                    .getOrDefault(emptyMap())
            }
            storeRepository.fetchCatalog().fold(
                onSuccess = { entries ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            entries = entries,
                            error = null,
                            installedVersionCodes = installed,
                        )
                    }
                },
                onFailure = { t ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = t.message ?: "Failed to load catalog",
                            installedVersionCodes = installed,
                        )
                    }
                }
            )
        }
    }

    /** Installed `versionCode` of the package matching [entry], or null when it isn't installed. */
    private fun installedVersionCode(entry: CatalogEntry): Long? {
        val installed = _uiState.value.installedVersionCodes
        // Match by exact package name (our catalog ids ARE full package names) or by the
        // ext-prefixed short slug. No loose endsWith(".<id>") fallback — it added nothing over these
        // two precise checks and could match an unintended package, falsely reporting installed/version.
        val pkg = installed.keys.firstOrNull { pkg ->
            pkg == entry.id || pkg == "com.valhalla.thor.ext.${entry.id}"
        }
        return pkg?.let { installed[it] }
    }

    /** True when [entry] matches an already-installed extension package. */
    fun isInstalled(entry: CatalogEntry): Boolean = installedVersionCode(entry) != null

    /**
     * True when the catalog publishes a newer [CatalogEntry.versionCode] than the installed copy.
     * A catalog versionCode of 0 (unknown) never offers an update, so an older catalog without the
     * field degrades to the plain installed/not-installed behaviour.
     */
    fun isUpdateAvailable(entry: CatalogEntry): Boolean {
        // A source-only / unpublished entry has no APK to install, so never surface a (dead) Update
        // button — it falls through to the plain "Installed" state instead.
        if (!entry.isInstallable) return false
        val installed = installedVersionCode(entry) ?: return false
        return entry.versionCode > 0L && installed < entry.versionCode
    }

    fun statusFor(entry: CatalogEntry): InstallStatus =
        _uiState.value.installStatuses[entry.id] ?: InstallStatus.Idle

    fun install(entry: CatalogEntry) {
        if (!entry.isInstallable) return
        setStatus(entry.id, InstallStatus.Downloading)
        viewModelScope.launch {
            storeRepository.downloadAndVerify(entry).fold(
                onSuccess = { uri -> setStatus(entry.id, InstallStatus.ReadyToInstall(uri)) },
                onFailure = { t ->
                    setStatus(entry.id, InstallStatus.Failed(t.message ?: "Download failed"))
                }
            )
        }
    }

    /** Reset an entry's status once the installer sheet has consumed its verified Uri. */
    fun consumeReady(entryId: String) {
        setStatus(entryId, InstallStatus.Idle)
    }

    private fun setStatus(entryId: String, status: InstallStatus) {
        _uiState.update { state ->
            state.copy(installStatuses = state.installStatuses + (entryId to status))
        }
    }
}
