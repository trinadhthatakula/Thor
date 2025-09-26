package com.valhalla.thor.ui.appList

import androidx.lifecycle.ViewModel
import com.valhalla.thor.model.AppInfo

data class AppListState(
    val isLoading: Boolean = false,
    val apps: List<AppInfo> = emptyList(),
    val error: String? = null,
)

class AppListViewmodel: ViewModel(){



}