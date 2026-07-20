package com.valhalla.thor.presentation.extension

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.data.manager.ExtensionManager
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.extension.api.ThorExtension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Named

data class ExtensionUiItem(
    val extension: ThorExtension,
    val packageName: String,
    val isVerified: Boolean,
    val isConfigurable: Boolean,
    val version: String
)

data class ExtensionUiState(
    val isLoading: Boolean = true,
    val extensions: List<ExtensionUiItem> = emptyList(),
    val error: String? = null,
    /**
     * True when the deprecated legacy extension package is still installed, so the UI can prompt a
     * one-time migration. Probed off the main thread here instead of via a synchronous binder IPC in
     * a Composable.
     */
    val isLegacyInstalled: Boolean = false
)

@KoinViewModel
class ExtensionManagerViewModel(
    private val extensionManager: ExtensionManager,
    private val preferenceRepository: PreferenceRepository,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExtensionUiState())
    val uiState: StateFlow<ExtensionUiState> = _uiState.asStateFlow()

    /**
     * Persist the extension-manager consent on the durable viewModelScope so the DataStore write
     * survives a configuration change / composition teardown right after the user taps Accept
     * (a composition-scoped write could be cancelled mid-edit, leaving the gate un-consented).
     */
    fun acceptExtensionConsent() {
        viewModelScope.launch { preferenceRepository.setExtensionConsentAccepted(true) }
    }

    init {
        loadExtensions()
    }

    fun loadExtensions() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(ioDispatcher) {
            // Probe whether the deprecated legacy extension package is still installed. This is a
            // single-package PackageManager lookup (binder IPC) rather than a full installed-package
            // enumeration, done here on the io dispatcher rather than synchronously during
            // composition. isLegacyInstalled is one-shot / non-flapping: once true it is never reset
            // to false within this VM instance, so a transient PM failure or a negative reload probe
            // (both default to false) can't flip true->false->true across reloads and re-trigger the
            // one-time migration dialog the user already dismissed. It never produces an error state.
            // This is a non-suspending call, so it can't surface a CancellationException from
            // coroutine cancellation.
            val legacyInstalled = runCatching {
                extensionManager.isPackageInstalled(ExtensionManager.LEGACY_EXTENSION_PACKAGE)
            }.getOrDefault(false)
            _uiState.update { it.copy(isLegacyInstalled = it.isLegacyInstalled || legacyInstalled) }
            // Loading enumerates installed apps via PackageManager (GET_META_DATA), which can throw
            // TransactionTooLargeException/DeadObjectException on large package sets or a dead binder.
            // Guard it so the Extensions screen surfaces an error + retry instead of crashing/spinning.
            runCatching {
                extensionManager.loadExtensions().map { ext ->
                    val packageName = extensionManager.getExtensionPackageName(ext)
                        ?: ext.javaClass.name.substringBeforeLast('.')
                    val isVerified = extensionManager.isSignatureVerified(packageName)
                    val version = extensionManager.getExtensionVersionName(packageName)
                    ExtensionUiItem(
                        extension = ext,
                        packageName = packageName,
                        isVerified = isVerified,
                        isConfigurable = extensionManager.isConfigurable(packageName),
                        version = version
                    )
                }
            }.onSuccess { mapped ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        extensions = mapped,
                        error = null
                    )
                }
            }.onFailure { throwable ->
                // runCatching also catches CancellationException; rethrow it so a cancelled
                // viewModelScope isn't turned into a spurious error state (structured concurrency).
                if (throwable is CancellationException) throw throwable
                // Preserve any previously loaded list; just stop the spinner and expose the error.
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = throwable.message ?: throwable.javaClass.simpleName
                    )
                }
            }
        }
    }
}

