package com.valhalla.thor.domain.usecase

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.domain.model.AppInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named

@Factory
class ShareAppUseCase(
    private val context: Context,
    private val bundleBuilder: AppBundleBuilder,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) {
    suspend operator fun invoke(appInfo: AppInfo): Result<Uri> = withContext(ioDispatcher) {
        bundleBuilder.build(appInfo).mapCatching { file ->
            FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", file)
        }
    }
}
