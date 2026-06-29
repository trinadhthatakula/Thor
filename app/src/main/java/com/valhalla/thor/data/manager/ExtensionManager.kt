package com.valhalla.thor.data.manager

import android.content.Context
import android.content.pm.PackageManager
import com.valhalla.thor.extension.api.DebloatExtension
import com.valhalla.thor.extension.api.ThorExtension
import dalvik.system.PathClassLoader
import org.koin.core.annotation.Single

@Single
class ExtensionManager(private val context: Context) {

    private val pm = context.packageManager
    private val EXTENSION_PACKAGE_PREFIX = "com.valhalla.thor.ext."

    /**
     * Finds and loads all valid installed extensions.
     */
    fun loadExtensions(): List<ThorExtension> {
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return installedApps.filter { app ->
            app.packageName.startsWith(EXTENSION_PACKAGE_PREFIX)
        }.mapNotNull { app ->
            // 1. Signature Verification
            if (!verifySignature(app.packageName)) {
                return@mapNotNull null
            }

            val className = app.metaData?.getString("thor.extension.class") ?: return@mapNotNull null
            try {
                // Load class dynamically using PathClassLoader
                val classLoader = PathClassLoader(app.sourceDir, context.classLoader)
                val clazz = classLoader.loadClass(className)
                val extension = clazz.getDeclaredConstructor().newInstance() as ThorExtension
                extension
            } catch (e: Exception) {
                com.valhalla.thor.util.Logger.e("ExtensionManager", "Failed to load extension $className", e)
                null
            }
        }
    }

    /**
     * Filters loaded extensions by DebloatExtension type.
     */
    fun getDebloatExtensions(): List<DebloatExtension> {
        return loadExtensions().filterIsInstance<DebloatExtension>()
    }

    fun isSignatureVerified(packageName: String): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val coreInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val extInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val coreSigning = coreInfo.signingInfo
                val extSigning = extInfo.signingInfo
                if (coreSigning != null && extSigning != null) {
                    if (coreSigning.hasMultipleSigners()) {
                        coreSigning.apkContentsSigners.contentEquals(extSigning.apkContentsSigners)
                    } else {
                        coreSigning.signingCertificateHistory.contentEquals(extSigning.signingCertificateHistory)
                    }
                } else false
            } else {
                @Suppress("DEPRECATION")
                val coreSignatures = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
                @Suppress("DEPRECATION")
                val extSignatures = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
                coreSignatures.contentEquals(extSignatures)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun verifySignature(packageName: String): Boolean {
        // Allow all packages in debug/testing builds to simplify development
        if (com.valhalla.thor.BuildConfig.DEBUG) return true

        return isSignatureVerified(packageName)
    }

    fun getExtensionPackageName(extension: ThorExtension): String? {
        val className = extension.javaClass.name
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return installedApps.firstOrNull { app ->
            app.packageName.startsWith(EXTENSION_PACKAGE_PREFIX) &&
                    className.startsWith(app.packageName)
        }?.packageName
    }
}

