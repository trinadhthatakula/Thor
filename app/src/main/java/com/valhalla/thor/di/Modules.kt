package com.valhalla.thor.di

import com.valhalla.thor.model.AppInfoGrabber
import com.valhalla.thor.model.shizuku.ShizukuManager
import com.valhalla.thor.ui.home.HomeViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appGrabber = module {
    singleOf(::AppInfoGrabber)
}

val shizukuModule = module {
    viewModelOf(::ShizukuManager)
}

val commonModule = module {
    viewModelOf(::HomeViewModel)
}