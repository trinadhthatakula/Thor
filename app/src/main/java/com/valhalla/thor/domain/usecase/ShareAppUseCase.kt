package com.valhalla.thor.domain.usecase

import android.net.Uri
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.repository.AppBundleFileStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named

@Factory
class ShareAppUseCase(
    private val bundleBuilder: AppBundleBuilder,
    private val fileStore: AppBundleFileStore,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) {
    suspend operator fun invoke(appInfo: AppInfo): Result<Uri> = withContext(ioDispatcher) {
        bundleBuilder.build(appInfo).mapCatching { file ->
            // The FileProvider content-uri bulk lives in the data layer; the use case keeps only
            // a thin android.net.Uri residual for its return type (MainViewModel's stable contract).
            Uri.parse(fileStore.shareUri(file))
        }
    }
}
