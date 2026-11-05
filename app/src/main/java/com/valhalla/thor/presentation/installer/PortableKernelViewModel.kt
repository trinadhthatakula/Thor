// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.presentation.installer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.data.kernel.KernelFlashController
import com.valhalla.thor.domain.model.PortablePackage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

/** Owns staging across rotation; a new process consults the durable controller before importing. */
@KoinViewModel
class PortableKernelViewModel(private val controller: KernelFlashController) : ViewModel() {
    private var input: PortablePackage.Kernel? = null
    private val mutableImported = MutableStateFlow<Boolean?>(null)
    val imported = mutableImported.asStateFlow()

    fun prepare(packageInput: PortablePackage.Kernel) {
        if (input != null) return
        input = packageInput
        viewModelScope.launch {
            mutableImported.value = controller.selectShared(
                packageInput.staged.file, packageInput.staged.displayName, packageInput.sha256,
            )
        }
    }
}
