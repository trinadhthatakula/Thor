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
    /** Package names of extensions already installed on-device. */
    val installedPackageNames: Set<String> = emptySet(),
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
                runCatching { extensionManager.getInstalledExtensionPackageNames() }
                    .getOrDefault(emptyList())
                    .toSet()
            }
            storeRepository.fetchCatalog().fold(
                onSuccess = { entries ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            entries = entries,
                            error = null,
                            installedPackageNames = installed,
                        )
                    }
                },
                onFailure = { t ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = t.message ?: "Failed to load catalog",
                            installedPackageNames = installed,
                        )
                    }
                }
            )
        }
    }

    /** True when [entry] matches an already-installed extension package. */
    fun isInstalled(entry: CatalogEntry): Boolean {
        val installed = _uiState.value.installedPackageNames
        return installed.any { pkg ->
            pkg == entry.id ||
                pkg == "com.valhalla.thor.ext.${entry.id}" ||
                pkg.endsWith(".${entry.id}")
        }
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
