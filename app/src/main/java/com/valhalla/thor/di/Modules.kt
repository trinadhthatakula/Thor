package com.valhalla.thor.di

import android.content.pm.PackageManager
import com.valhalla.superuser.repository.RealShellRepository
import com.valhalla.superuser.repository.ShellRepository
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.gateway.ShizukuSystemGateway
import com.valhalla.thor.data.repository.AppAnalyzerImpl
import com.valhalla.thor.data.repository.AppRepositoryImpl
import com.valhalla.thor.data.repository.InstallerRepositoryImpl
import com.valhalla.thor.data.repository.SystemRepositoryImpl
import com.valhalla.thor.data.source.local.shizuku.ShizukuReflector
import com.valhalla.thor.data.util.ApksMetadataGenerator
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.repository.AppAnalyzer
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.InstallerRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.GetAppDetailsUseCase
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.domain.usecase.ShareAppUseCase
import com.valhalla.thor.presentation.appList.AppListViewModel
import com.valhalla.thor.presentation.freezer.FreezerViewModel
import com.valhalla.thor.presentation.home.HomeViewModel
import com.valhalla.thor.presentation.main.MainViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module
import kotlin.jvm.java

val commonModule = module {
    single<PackageManager> { androidContext().packageManager }
    singleOf(::ApksMetadataGenerator)
    single<AppRepository> { AppRepositoryImpl(androidContext()) }
    factory { GetInstalledAppsUseCase(get()) }
    factory { GetAppDetailsUseCase(get()) }
    factory { ManageAppUseCase(get()) }
    factoryOf(::ShareAppUseCase)
}

val installerModule = module {
    // 1. The Singleton Event Bus (Critical for Receiver <-> VM comms)
    singleOf(::InstallerEventBus)
    single<InstallerRepository> {
        InstallerRepositoryImpl(
            context = androidContext(),
            eventBus = get()
        )
    }
    single<PackageManager>{
        androidContext().packageManager
    }
    single<AppAnalyzer> { AppAnalyzerImpl(androidContext()) }
}

val presentationModule = module {
    viewModelOf(::MainViewModel)
    viewModelOf(::HomeViewModel)
    viewModelOf(::AppListViewModel)
    viewModelOf(::FreezerViewModel)
}

val coreModule = module {
    singleOf(::RealShellRepository).bind<ShellRepository>()
    singleOf(::ShizukuReflector)
    // Singletons for the Gateways
    single { RootSystemGateway(get()) }
    single { ShizukuSystemGateway(get()) }
    // The Repository interacts with the Gateways
    singleOf(::SystemRepositoryImpl).bind<SystemRepository>()
}