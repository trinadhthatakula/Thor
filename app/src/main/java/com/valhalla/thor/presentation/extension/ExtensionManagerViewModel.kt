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
    val isConfigurable: Boolean
)

data class ExtensionUiState(
    val isLoading: Boolean = true,
    val extensions: List<ExtensionUiItem> = emptyList()
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
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = extensionManager.loadExtensions()
            val mapped = loaded.map { ext ->
                val packageName = extensionManager.getExtensionPackageName(ext) 
                    ?: ext.javaClass.name.substringBeforeLast('.')
                val isVerified = extensionManager.isSignatureVerified(packageName)
                ExtensionUiItem(
                    extension = ext,
                    packageName = packageName,
                    isVerified = isVerified,
                    isConfigurable = extensionManager.isConfigurable(packageName)
                )
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    extensions = mapped
                )
            }
        }
    }
}

