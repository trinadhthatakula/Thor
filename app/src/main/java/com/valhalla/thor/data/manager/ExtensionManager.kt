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

    /**
     * A verified, already-instantiated extension bound to the exact APK it was loaded from. The
     * cache entry is only reused while BOTH the installed [versionCode] and the on-disk [sourceDir]
     * still match the package manager — either changing (update, reinstall, downgrade) invalidates
     * the entry so a stale instance is never returned, and dropping the entry releases its
     * [classLoader] (and the class metadata it pins) for GC.
     */
    private class CachedExtension(
        val versionCode: Long,
        val sourceDir: String?,
        val classLoader: ClassLoader,
        val extension: ThorExtension,
    )

    private val cacheLock = Any()

    /** Guarded by [cacheLock]: packageName -> its currently-loaded [CachedExtension]. */
    private val extensionCache = HashMap<String, CachedExtension>()

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
        const val LEGACY_EXTENSION_PACKAGE = "com.valhalla.thor.ext.strombringer"
    }

    /**
     * Finds and returns all valid installed extensions, reusing cached instances where possible.
     *
     * Enumeration is cheap: a lightweight [PackageManager.getInstalledPackages] scan with `0` flags
     * is filtered to the extension prefix, then GET_META_DATA is fetched individually only for those
     * few matching packages. A single GET_META_DATA scan over ALL installed apps would marshal every
     * app's metadata across the binder in one transaction and can throw TransactionTooLargeException
     * (or lag) on devices with many apps. Each extension is only class-loaded and instantiated once:
     * subsequent calls reuse the cached instance while both its installed versionCode and APK path
     * are unchanged. An updated, reinstalled, or removed extension invalidates its cache entry (so
     * users never get a stale instance after an update), and stale entries' [PathClassLoader]s are
     * dropped so their class metadata can be reclaimed instead of accumulating on every screen resume.
     */
    fun loadExtensions(): List<ThorExtension> {
        // Enumerate cheaply (0 flags), filter to extensions, then fetch GET_META_DATA only for those
        // few packages — a GET_META_DATA scan over ALL apps can exceed the binder buffer
        // (TransactionTooLargeException). getInstalledPackages can return null on some ROMs; guard it.
        val installedExtensions = (pm.getInstalledPackages(0) ?: emptyList())
            .filter { it.packageName.startsWith(EXTENSION_PACKAGE_PREFIX) }
            .mapNotNull { pkg ->
                runCatching { pm.getPackageInfo(pkg.packageName, PackageManager.GET_META_DATA) }.getOrNull()
            }

        return synchronized(cacheLock) {
            // Drop cached entries (and their ClassLoaders) for extensions no longer installed.
            val installedNames = installedExtensions.mapTo(HashSet()) { it.packageName }
            extensionCache.keys.retainAll(installedNames)

            installedExtensions.mapNotNull { pkgInfo ->
                val packageName = pkgInfo.packageName
                val versionCode = pkgInfo.longVersionCode
                val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null
                // sourceDir can be null; PathClassLoader(null, ...) would NPE, so skip this one.
                val sourceDir = appInfo.sourceDir ?: return@mapNotNull null

                // Fast path: a previously loaded instance is still valid iff both the installed
                // versionCode AND the on-disk APK path match what's currently installed.
                extensionCache[packageName]?.let { cached ->
                    if (cached.versionCode == versionCode && cached.sourceDir == sourceDir) {
                        return@mapNotNull cached.extension
                    }
                }

                // (Re)load: drop any stale entry first so a failed reload can't leave a stale
                // instance behind, then verify signature and instantiate from a fresh ClassLoader.
                extensionCache.remove(packageName)
                if (!verifySignature(packageName)) return@mapNotNull null
                val className =
                    appInfo.metaData?.getString("thor.extension.class") ?: return@mapNotNull null
                try {
                    val classLoader = PathClassLoader(sourceDir, context.classLoader)
                    val clazz = classLoader.loadClass(className)
                    val extension = clazz.getDeclaredConstructor().newInstance() as ThorExtension
                    extensionCache[packageName] =
                        CachedExtension(versionCode, sourceDir, classLoader, extension)
                    extension
                } catch (e: Exception) {
                    com.valhalla.thor.util.Logger.e("ExtensionManager", "Failed to load extension $className", e)
                    null
                }
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
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
                ?.apkContentsSigners
                ?.firstOrNull()
                ?.toByteArray()
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
        // getPackageArchiveInfo(GET_SIGNING_CERTIFICATES) has a known bug on some API 28+
        // devices where signingInfo comes back null for an APK FILE; fall back to the
        // deprecated GET_SIGNATURES so a valid, pinned extension isn't spuriously rejected.
        val signingInfo =
            pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo
        val certBytes: ByteArray? =
            if (signingInfo != null) {
                signingInfo.apkContentsSigners?.firstOrNull()?.toByteArray()
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
        (pm.getInstalledPackages(0) ?: emptyList())
            .filter { it.packageName.startsWith(EXTENSION_PACKAGE_PREFIX) }
            .associate { it.packageName to it.longVersionCode }

    /**
     * True iff [packageName] is installed, via a single-package [PackageManager.getPackageInfo]
     * lookup — NOT a full [PackageManager.getInstalledPackages] enumeration. Used to cheaply probe
     * for the deprecated [LEGACY_EXTENSION_PACKAGE] without marshalling every installed package
     * across the binder just to test for one. No signature/trust check is implied by this call.
     */
    fun isPackageInstalled(packageName: String): Boolean =
        runCatching { pm.getPackageInfo(packageName, 0) }.isSuccess

    fun getExtensionPackageName(extension: ThorExtension): String? {
        // Fast path: the extension was just returned by loadExtensions(), so its packageName is in
        // the cache — a reverse lookup avoids a full getInstalledApplications() PM scan PER extension
        // (an N+1 across the list that risked TransactionTooLargeException / UI lag on big devices).
        synchronized(cacheLock) {
            extensionCache.entries.firstOrNull { it.value.extension === extension }?.let { return it.key }
        }
        val className = extension.javaClass.name
        // Fallback (a not-yet-cached instance): match on the declared `thor.extension.class`
        // metadata rather than assuming the implementation class lives under the app's packageName.
        // The code package and the applicationId can differ, and a class-name prefix match would
        // namespace the extension's datastore against the wrong package.
        return (pm.getInstalledApplications(PackageManager.GET_META_DATA) ?: emptyList())
            .firstOrNull { app ->
                app.packageName.startsWith(EXTENSION_PACKAGE_PREFIX) &&
                        app.metaData?.getString("thor.extension.class") == className
            }
            ?.packageName
    }

    /**
     * Returns the installed version name (e.g. "1.00.4") of [packageName] from the package manager.
     * Falls back to "1.0.0" on failure.
     */
    fun getExtensionVersionName(packageName: String): String {
        return runCatching {
            pm.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
    }
}

