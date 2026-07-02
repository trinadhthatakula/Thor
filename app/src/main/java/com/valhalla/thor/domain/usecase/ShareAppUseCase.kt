package com.valhalla.thor.domain.usecase

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.domain.model.AppInfo
import org.koin.core.annotation.Factory

@Factory
class ShareAppUseCase(
    private val context: Context,
    private val bundleBuilder: AppBundleBuilder
) {
    suspend operator fun invoke(appInfo: AppInfo): Result<Uri> =
        bundleBuilder.build(appInfo).map { file ->
            FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", file)
        }
}
