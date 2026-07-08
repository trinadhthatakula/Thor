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

    /**
     * True iff [packageName] is signed by a key whose certificate SHA-256 is in the pinned
     * allowlist ([TrustedExtensionSigners.PINS]). This is the real trust gate — an extension
     * signed by any other key (including Thor's own app keys) is NOT trusted.
     *
     * Fail-CLOSED: any failure (missing package, null signing info, hashing error, …) returns
     * false. It never returns true unless a pinned signer is positively matched.
     */
    fun isSignatureVerified(packageName: String): Boolean = runCatching {
        val certBytes: ByteArray? =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo
                    ?.apkContentsSigners
                    ?.firstOrNull()
                    ?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                    .signatures
                    ?.firstOrNull()
                    ?.toByteArray()
            }
        certBytes != null && isPinnedSigner(certBytes.toCertSha256Hex())
    }.getOrDefault(false)

    private fun verifySignature(packageName: String): Boolean {
        // Allow all packages in debug/testing builds to simplify development.
        if (com.valhalla.thor.BuildConfig.DEBUG) return true

        return isSignatureVerified(packageName)
    }

    fun getExtensionPackageName(extension: ThorExtension): String? {
        val className = extension.javaClass.name
        // Match on the declared `thor.extension.class` metadata rather than assuming the
        // implementation class lives under the app's packageName. The code package and the
        // applicationId can differ, and a class-name prefix match would namespace the
        // extension's datastore against the wrong package.
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .firstOrNull { app ->
                app.packageName.startsWith(EXTENSION_PACKAGE_PREFIX) &&
                        app.metaData?.getString("thor.extension.class") == className
            }
            ?.packageName
    }
}

