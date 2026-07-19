package com.valhalla.thor.presentation.extension

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.data.manager.ExtensionManager
import com.valhalla.thor.extension.api.ThorExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

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
    val error: String? = null
)

@KoinViewModel
class ExtensionManagerViewModel(
    private val extensionManager: ExtensionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExtensionUiState())
    val uiState: StateFlow<ExtensionUiState> = _uiState.asStateFlow()

    init {
        loadExtensions()
    }

    fun loadExtensions() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
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

