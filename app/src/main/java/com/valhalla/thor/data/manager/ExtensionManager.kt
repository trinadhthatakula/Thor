package com.valhalla.thor.data.manager

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.valhalla.thor.extension.api.DebloatExtension
import com.valhalla.thor.extension.api.ThorExtension
import dalvik.system.PathClassLoader
import org.koin.core.annotation.Single

@Single
class ExtensionManager(private val context: Context) {

    private val pm = context.packageManager
    private val EXTENSION_PACKAGE_PREFIX = "com.valhalla.thor.ext."

    companion object {
        /**
         * Action for an extension's optional configuration Activity. Extensions render config UI in
         * their OWN process (Thor starts it with this action) rather than inside Thor — that keeps
         * their Compose/Asgard/kotlin-stdlib from having to link against Thor's minified runtime.
         * The extension declares an exported activity with an intent-filter for this exact string
         * (mirrored in its manifest, since manifests can't reference a Kotlin const).
         */
        const val ACTION_CONFIGURE = "com.valhalla.thor.extension.action.CONFIGURE"

        /**
         * Theme hints Thor attaches to the CONFIGURE launch so an extension's config UI (rendered in
         * its OWN process, with no access to Thor's prefs) can match Thor's look: light/dark/system,
         * dynamic color, and AMOLED. The extension resolves SYSTEM against its own night config so
         * "follow system" stays live. All optional — extensions may ignore them.
         */
        const val EXTRA_THEME_MODE = "com.valhalla.thor.extension.extra.THEME_MODE"      // LIGHT|DARK|SYSTEM
        const val EXTRA_DYNAMIC_COLOR = "com.valhalla.thor.extension.extra.DYNAMIC_COLOR" // boolean
        const val EXTRA_AMOLED = "com.valhalla.thor.extension.extra.AMOLED"              // boolean
    }

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

    /**
     * True iff the APK FILE at [apkPath] is signed by a key whose certificate SHA-256 is in the
     * pinned allowlist ([TrustedExtensionSigners.PINS]). This is the pre-install trust gate for
     * catalog-downloaded extension APKs — the file is checked BEFORE it is ever handed to the
     * package installer.
     *
     * Unlike [verifySignature], there is NO `BuildConfig.DEBUG` bypass here: a downloaded APK must
     * ALWAYS be positively matched against a pinned signer. The debug bypass only ever applies to
     * already-installed, locally-built extensions.
     *
     * Fail-CLOSED: any failure (unreadable archive, null signing info, hashing error, …) returns
     * false. It never returns true unless a pinned signer is positively matched.
     */
    fun isApkFileSignerPinned(apkPath: String): Boolean = runCatching {
        val certBytes: ByteArray? =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                // getPackageArchiveInfo(GET_SIGNING_CERTIFICATES) has a known bug on some API 28+
                // devices where signingInfo comes back null for an APK FILE; fall back to the
                // deprecated GET_SIGNATURES so a valid, pinned extension isn't spuriously rejected.
                val signingInfo =
                    pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo
                if (signingInfo != null) {
                    signingInfo.apkContentsSigners?.firstOrNull()?.toByteArray()
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES)
                        ?.signatures
                        ?.firstOrNull()
                        ?.toByteArray()
                }
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES)
                    ?.signatures
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

    /**
     * An explicit Intent to launch [packageName]'s configuration Activity, or null if it has none
     * (or the package isn't a trusted, pinned-signer extension). Trust is enforced HERE so an
     * untrusted look-alike package can't get Thor to launch it: [verifySignature] must pass (lax
     * only in debug builds). The implicit [ACTION_CONFIGURE] intent is resolved to a concrete
     * component so the launch can't be hijacked by a racing implicit match.
     */
    fun getConfigLaunchIntent(packageName: String): Intent? {
        if (!verifySignature(packageName)) return null
        val probe = Intent(ACTION_CONFIGURE).setPackage(packageName)
        val resolved = pm.resolveActivity(probe, 0) ?: return null
        return probe.apply {
            setClassName(resolved.activityInfo.packageName, resolved.activityInfo.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** True iff [packageName] is a trusted extension that exposes a configuration Activity. */
    fun isConfigurable(packageName: String): Boolean = getConfigLaunchIntent(packageName) != null

    /**
     * Installed extension packageName -> its `longVersionCode`, for every installed extension
     * (anything under [EXTENSION_PACKAGE_PREFIX]), WITHOUT loading classes or verifying signatures —
     * a cheap scan the store uses to mark which catalog entries are installed and to compare an
     * installed copy against the catalog to offer updates. Signature trust is still enforced
     * separately at load time by [loadExtensions]. `longVersionCode` is available from API 28 (Thor's
     * minSdk), so no compat shim is needed.
     */
    fun getInstalledExtensionVersionCodes(): Map<String, Long> =
        pm.getInstalledPackages(0)
            .filter { it.packageName.startsWith(EXTENSION_PACKAGE_PREFIX) }
            .associate { it.packageName to it.longVersionCode }

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

