// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import androidx.core.content.edit
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.source.local.UadHelper
import com.valhalla.thor.data.source.local.room.AppDao
import com.valhalla.thor.data.source.local.room.AppEntity
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.ComponentDetail
import com.valhalla.thor.domain.model.ComponentSnapshot
import com.valhalla.thor.domain.model.DetailedAppInfo
import com.valhalla.thor.domain.model.PermissionDetail
import com.valhalla.thor.domain.model.ScanVerdict
import com.valhalla.thor.domain.model.prunableWatchlistRows
import com.valhalla.thor.domain.model.scanVerdict
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.InstalledAppsPermissionGate
import com.valhalla.thor.util.AppScanRevision
import com.valhalla.thor.util.LocaleRevision
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.io.File

@Single(binds = [AppRepository::class])
class AppRepositoryImpl(
    private val context: Context,
    private val appDao: AppDao,
    private val uadHelper: UadHelper,
    private val installedAppsPermission: InstalledAppsPermissionGate,
    // Only for the watchlist prune below. The scan is the one place that knows whether a package's
    // absence is real, and the Freezer's rows are the only other thing keyed on package name that
    // outlives the package — the same class of stale artifact as the cached icon PNGs this already
    // deletes.
    private val freezerRepository: FreezerRepository,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) : AppRepository {

    private val pm = context.packageManager

    /**
     * Drop Freezer watchlist rows for packages this scan proves are gone.
     *
     * **Here, and not in `FreezerViewModel`, because this is the only place that holds the
     * verdict.** The Freezer screen sees a `List<AppInfo>`, and on a retained scan that list is the
     * union of the scan and the rows Thor refused to prune — so from there a genuinely uninstalled
     * package and a package hidden by a truncated scan look identical. [prunableWatchlistRows] holds
     * the rule; nothing is decided here, the same division [scanVerdict] already uses above.
     *
     * Silent, and one `Logger` line. There is no UI surface that renders a row with no referent, so
     * a dialog would be asking permission to tidy something the user cannot see, and naming the
     * packages would list apps they can no longer act on.
     *
     * Failure is swallowed on purpose. This is housekeeping hanging off a scan whose actual job is
     * to emit an app list; a Room error here must not take the emission down with it, and the next
     * trusted scan tries again.
     *
     * [watchlistBeforeScan] is passed in rather than read here, and that ordering is the whole of
     * the rule — see [watchlistSnapshot].
     */
    private suspend fun pruneWatchlist(
        watchlistBeforeScan: Set<String>?,
        scannedPackageNames: Set<String>,
        verdict: ScanVerdict
    ) {
        // Null means the snapshot itself failed to read. Pruning against a watchlist we could not
        // see is the one thing worse than not pruning: the empty set makes every row look absent.
        if (watchlistBeforeScan == null) return
        try {
            val stale = prunableWatchlistRows(
                watchlist = watchlistBeforeScan,
                scannedPackageNames = scannedPackageNames,
                verdict = verdict
            )
            if (stale.isEmpty()) return
            freezerRepository.removeAll(stale)
            Logger.d(
                "AppRepository",
                "pruned ${stale.size} freezer row(s) with no installed package"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("AppRepository", "pruning the freezer watchlist failed", e)
        }
    }

    /**
     * The watchlist as it stood **before** the package scan this prune will judge it against.
     *
     * The order is the point. Read afterwards, a row added while the scan was running names a
     * package the scan never had a chance to see, so it lands in the stale set and is deleted —
     * and because adding to the Freezer freezes immediately, that leaves a frozen app with no
     * Freezer row: precisely the unreachable state this whole prune exists to clean up. It needs a
     * newer scan to have surfaced a fresh install while this older one is still mid-flight, which
     * is narrow, but the fix is an ordering, not a lock.
     *
     * Rows added after this point are simply not candidates for this scan. The next one considers
     * them, by which time it has seen their packages — so the error this ordering can make is
     * always "pruned nothing", never "pruned something live".
     *
     * Returns null rather than an empty set on failure, and the two must not be conflated: an
     * empty watchlist prunes nothing, while an *unread* watchlist would prune everything if it
     * were treated as empty and compared the other way round. The read is guarded here rather than
     * inside the scan's own `try`, whose catch swallows and loops — a Room throw there would skip
     * the `producer.send` below and drop that scan's emission entirely, turning a housekeeping
     * failure into a blank app list.
     */
    private suspend fun watchlistSnapshot(): Set<String>? =
        try {
            freezerRepository.getAllPackageNames().toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("AppRepository", "reading the freezer watchlist failed", e)
            null
        }

    /**
     * What the cached app labels depend on, as one comparable value.
     *
     * **Two** locales, and they can now differ. Thor's own strings follow the *app* language —
     * `AppLocale`'s configuration override below API 33, the platform's per-app locale above it —
     * while a third-party app's label is loaded from *that app's* resources and follows the
     * **system** language. Comparing only the first misses a device-language change made while an
     * in-app override is active, which is precisely when every cached label in the database is
     * wrong and nothing else would notice; comparing only the second was what this did before the
     * app context could disagree with the system at all.
     *
     * Over-firing is the safe direction: a miss leaves a stale label on every row until some
     * unrelated package event happens to force a re-map, while a spurious refresh costs one rescan
     * immediately after a language change the user just made and was already waiting on.
     */
    private fun localeCacheKey(): String {
        val app = context.resources.configuration.locales
            .takeIf { !it.isEmpty }?.get(0)?.toString().orEmpty()
        val system = Resources.getSystem().configuration.locales
            .takeIf { !it.isEmpty }?.get(0)?.toString().orEmpty()
        return "$app|$system"
    }

    /**
     * The locale key the [AppEntity] rows in Room were actually mapped under, or `null` if no scan
     * has ever recorded one.
     *
     * A one-key `SharedPreferences` file, the same shape [com.valhalla.thor.util.AppLocale] uses for
     * its mirror and for the same reason: it has to be readable before the first scan, without a
     * suspension point and without a schema migration. It describes the *cache*, not the user, which
     * is why it does not live in `PreferenceRepository` next to things a backup should carry — a
     * restored device's Room rows are its own, and a restored key describing someone else's rows
     * would be worse than no key at all.
     */
    private val labelCacheState by lazy {
        context.getSharedPreferences(LABEL_CACHE_PREFS, Context.MODE_PRIVATE)
    }

    private fun cachedLabelLocale(): String? = labelCacheState.getString(KEY_LABEL_LOCALE, null)

    private fun recordLabelLocale(key: String) {
        labelCacheState.edit { putString(KEY_LABEL_LOCALE, key) }
    }

    /**
     * RUTHLESS OPTIMIZATION V2:
     * We debounce the TRIGGER to prevent heavy package scanning during batch operations.
     */
    override fun getAllApps(): Flow<List<AppInfo>> = callbackFlow {
        val producer = this

        // A conflated channel acts as a signal buffer.
        // If 50 broadcasts come in, we only keep the latest "refresh needed" flag.
        val triggerChannel = Channel<Unit>(Channel.CONFLATED)

        // Read here, synchronously, and *before* the worker below exists — not down at the watcher
        // that consumes it. The worker runs on a multi-threaded dispatcher, so it can be inside
        // getInstalledPackages() while this block is still registering receivers; a scan request
        // raised in that window has to survive until the watcher subscribes, and only a baseline
        // taken before the scan started can tell "arrived while we were scanning" from "already
        // folded into the scan we are about to run". See [AppScanRevision.requestsAfter].
        val scanRevisionAtStart = AppScanRevision.snapshot()

        // The Worker: Consumes triggers, waits for quiet, then fetches ONCE.
        val worker = launch(ioDispatcher) {
            // Initial load from cache and baseline for comparison
            val cachedMap = try {
                val entities = appDao.getAllApps()
                if (entities.isNotEmpty()) {
                    // Deliberately un-enriched. `AppEntity` stores no bloat fields, so this first
                    // frame carries a null recommendation and description for every app, and the
                    // list's UAD tier badge draws nothing until the rescan below lands. Copying
                    // `uadHelper.uadMap` in here would fix the pop-in and drag the ~1.6 MB JSON
                    // parse, under its lock, onto the one path that exists to get pixels up fast —
                    // the path that was cleared of exactly that after an ANR (see the note at the
                    // per-rescan read further down, and `UadHelper.uadMap`). A badge that arrives a
                    // beat late is recoverable; a stall before the first frame is not.
                    producer.send(entities.map { it.toDomain() })
                }
                entities.associateBy { it.packageName }.toMutableMap()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) e.printStackTrace()
                mutableMapOf()
            }

            // Seeded from what the cached rows were mapped under, NOT from the locale in force now.
            // Reading localeCacheKey() here would compare the current key against itself on the
            // first scan, so `forceRefresh` could never be true on the one scan that most needs it:
            // a language changed while this process was dead, or before this collection started,
            // leaves every row in the previous language and no package event to force a re-map.
            // `null` on a first run differs from every key, so that scan re-maps — which is free,
            // because it has no cache to reuse anyway.
            var lastLocale = cachedLabelLocale()

            // How many scans in a row scanVerdict() has refused to prune against. Deliberately
            // declared out here, not inside the trigger loop: the whole point of the tolerance is
            // that a shrinkage has to reproduce across *independent* scans before it is believed,
            // and a counter reset on every trigger would be stuck at zero and never let the cache
            // shrink again. Local to this collection rather than to the class, because a retained
            // cache self-heals on the next good scan — there is no degraded state worth persisting.
            var consecutiveSuspectScans = 0

            // Signal the worker to refresh
            triggerChannel.send(Unit)

            for (signal in triggerChannel) {
                // Drain any extra signals that arrived while we were waiting
                while (triggerChannel.tryReceive().isSuccess) {
                    // Do nothing, just consume them so we don't loop immediately again
                }

                // Now Perform the Heavy Fetch ONE time
                try {
                    val currentLocale = localeCacheKey()
                    val forceRefresh = currentLocale != lastLocale

                    // Before the scan, not after it. See [watchlistSnapshot] — a row added
                    // between the two reads names a package the scan could not have seen.
                    val watchlistBeforeScan = watchlistSnapshot()

                    val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()
                    var installedPackages =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags))
                        } else {
                            pm.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES)
                        }

                    // On Chinese OEMs (HyperOS, MIUI, ColorOS), MATCH_UNINSTALLED_PACKAGES may trigger
                    // OEM package-visibility filters and collapse to only the calling app. Fall back to standard flags.
                    //
                    // The fallback recovers the *list*, never the *authority to prune against it*:
                    // without MATCH_UNINSTALLED_PACKAGES the query cannot see a package whose per-user
                    // FLAG_INSTALLED bit is clear, so an uninstall-frozen app reads as gone when it is
                    // merely invisible. Believing that would delete its Room row, its cached icon PNG
                    // and — the one that cannot be repaired by a later scan — its Freezer watchlist row,
                    // which is what makes it thawable at all. Hence the flag, read at the verdict below.
                    var usedVisibilityFallback = false
                    if (installedPackages.size <= 1) {
                        val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
                        } else {
                            pm.getInstalledPackages(0)
                        }
                        if (fallback.size > installedPackages.size) {
                            installedPackages = fallback
                            usedVisibilityFallback = true
                        }
                    }

                    val initialCachedCount = cachedMap.size
                    val currentList = ArrayList<AppInfo>(installedPackages.size)
                    val toUpdate = mutableListOf<AppEntity>()

                    // Read the (cache-backed) UAD map + load-fail flag ONCE per rescan.
                    // Reading uadHelper.uadMap per-package rebuilt the ~1.6MB list under
                    // its lock whenever a bulk freeze/unfreeze invalidated the cache
                    // mid-loop, stalling the main-thread receiver (ANR) and this rescan.
                    val uadMap = uadHelper.uadMap
                    val uadLoadFailed = uadHelper.didLoadFail

                    for (packInfo in installedPackages) {
                        // The loop below is plain blocking work, so cancelling this flow cannot
                        // interrupt it on its own. Without this check a torn-down collector's scan
                        // keeps running to completion and overlaps the scan started by whoever
                        // replaced it (e.g. two pull-to-refreshes in a row). One volatile read per
                        // package buys prompt teardown.
                        ensureActive()

                        val appInfo = packInfo.applicationInfo ?: continue
                        val packageName = packInfo.packageName

                        val cachedEntry = cachedMap[packageName]
                        val isSuspended =
                            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0

                        val isInstalled = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0
                        val isEnabled = appInfo.enabled && isInstalled

                        if (!forceRefresh &&
                            cachedEntry != null &&
                            // Also a cache miss when the cached code disagrees with the platform's.
                            // Rows written before versionCode widened to Long hold a truncated
                            // value, and lastUpdateTime alone never invalidates them: installing
                            // *Thor* does not change the *target* package's timestamp. Comparing
                            // the value we already have in hand repairs those rows on the first
                            // scan, for one Long compare per package and a re-map of only the
                            // packages that are actually wrong.
                            cachedEntry.versionCode == packInfo.longVersionCode &&
                            cachedEntry.lastUpdateTime == packInfo.lastUpdateTime &&
                            cachedEntry.enabled == isEnabled &&
                            cachedEntry.isSuspended == isSuspended
                        ) {
                            val domain = cachedEntry.toDomain()
                            val bloat = uadMap[domain.packageName]
                            currentList.add(domain.copy(
                                bloatRecommendation = bloat?.removal,
                                bloatDescription = bloat?.description,
                                isInstalled = isInstalled,
                                isUadLoadFailed = uadLoadFailed
                            ))
                        } else {
                            val mapped =
                                mapToAppInfo(packInfo, appInfo, pm, isLightweight = true)
                            val bloat = uadMap[mapped.packageName]
                            val mappedWithBloat = mapped.copy(
                                bloatRecommendation = bloat?.removal,
                                bloatDescription = bloat?.description,
                                isUadLoadFailed = uadLoadFailed
                            )
                            currentList.add(mappedWithBloat)
                            val entity = AppEntity.fromDomain(mapped)
                            toUpdate.add(entity)
                            cachedMap[packageName] = entity
                        }
                    }

                    // Handle uninstalled apps: Cleanup cache
                    val currentPackageNames = installedPackages.map { it.packageName }.toSet()
                    val toDelete = cachedMap.keys.filter { it !in currentPackageNames }

                    // "Not in the scan" is only the same thing as "uninstalled" when the scan can
                    // be trusted. On the ROMs that gate package visibility behind the runtime
                    // GET_INSTALLED_APPS permission, backgrounding Thor makes getInstalledPackages()
                    // return a near-empty list, and pruning against that wipes the Room rows *and*
                    // the cached icon PNGs for almost every app the user has. scanVerdict() holds
                    // the rules; nothing is decided here.
                    //
                    // A scan that needed the weaker-flags fallback skips the rules entirely: none of
                    // them can see that MATCH_UNINSTALLED_PACKAGES was dropped, and every one of them
                    // would happily Accept the 300 packages such a scan *does* return. See
                    // [scanVerdictFor].
                    val verdict = scanVerdictFor(
                        usedVisibilityFallback = usedVisibilityFallback,
                        scannedPackageNames = currentPackageNames,
                        cachedCount = initialCachedCount,
                        consecutiveSuspectScans = consecutiveSuspectScans,
                        permission = installedAppsPermission.state()
                    )

                    // Accept resets the tolerance, an ordinary Retain spends one of it, and a
                    // VisibilityFallback Retain does neither — [nextSuspectScanCount] holds the
                    // reasoning. Read before the `when` below so both branches see the same rule.
                    consecutiveSuspectScans = nextSuspectScanCount(consecutiveSuspectScans, verdict)

                    // The cached rows this scan did not see and was not allowed to delete. Mapped
                    // through the same AppEntity.toDomain() the initial cache emission above uses,
                    // so a retained row reaches the UI exactly as it did a moment earlier.
                    var syncCacheSucceeded = true
                    val retained = when (verdict) {
                        ScanVerdict.Accept -> {
                            if (toUpdate.isNotEmpty() || toDelete.isNotEmpty()) {
                                try {
                                    appDao.syncCache(toUpdate, toDelete)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    syncCacheSucceeded = false
                                    Logger.e("AppRepository", "syncCache failed during accepted scan", e)
                                }
                                if (syncCacheSucceeded) {
                                    toDelete.forEach { pkgName ->
                                        cachedMap.remove(pkgName)
                                        try {
                                            File(context.filesDir, "app_icons/$pkgName.png").delete()
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                            pruneWatchlist(watchlistBeforeScan, currentPackageNames, verdict)
                            emptyList<AppInfo>()
                        }

                        is ScanVerdict.Retain -> {
                            Logger.w(
                                "AppRepository",
                                "not pruning against this scan (${verdict.reason}): saw " +
                                        "${currentPackageNames.size} package(s) against " +
                                        "$initialCachedCount cached, keeping ${toDelete.size} row(s)"
                            )
                            // Updates still land — they describe packages the scan *did* see, so
                            // they are no less trustworthy than on an accepted scan. Only the
                            // deletions are withheld, and the icon files with them: a retained Room
                            // row is repaired by the next good scan, a deleted PNG is not.
                            if (toUpdate.isNotEmpty()) {
                                try {
                                    appDao.syncCache(toUpdate, emptyList())
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    syncCacheSucceeded = false
                                    Logger.e("AppRepository", "syncCache failed during retained scan", e)
                                }
                            }
                            toDelete.mapNotNull { cachedMap[it]?.toDomain() }
                        }
                    }

                    // Emit a single complete snapshot of all installed apps. On a retained scan
                    // that snapshot is the union, never the scan alone: emitting what a truncated
                    // scan saw is the blank list this whole guard exists to prevent, and emitting
                    // nothing would strand isLoading forever on a fresh collection.
                    producer.send(currentList + retained)

                    // Only now, and only on a scan that was trusted enough to prune against and whose
                    // cache synchronization succeeded — [shouldRecordLabelLocale] holds the rule.
                    // Moving the key up next to the `forceRefresh` read would let a scan that was
                    // cancelled mid-loop, or one that ran while package visibility was truncated,
                    // record a language for rows it never re-mapped.
                    if (shouldRecordLabelLocale(forceRefresh, verdict, syncCacheSucceeded)) {
                        lastLocale = currentLocale
                        recordLabelLocale(currentLocale)
                    }

                } catch (e: CancellationException) {
                    // Kotlin's CancellationException is an Exception, so the broad catch below
                    // would otherwise swallow it, log it in debug, and loop straight back round to
                    // the channel — defeating ensureActive() above and the awaitClose teardown.
                    throw e
                } catch (e: Exception) {
                    Logger.e("AppRepository", "getAllApps scan failed", e)
                }
            }
        }

        // A language change is not a package event, so without this nothing in the flow above would
        // ever pump the channel for one and the locale key could only be consulted when some
        // unrelated package broadcast happened to arrive — on a device that installs nothing, never.
        // This covers the *app* half of the key; the system half is ACTION_LOCALE_CHANGED below,
        // which is a separate signal because it fires in cases this one cannot see (see
        // [LocaleRevision]).
        val localeWatcher = launch {
            LocaleRevision.changes.collect { triggerChannel.trySend(Unit) }
        }

        // Process-wide scan requests (e.g. self-permission auto-grant, privilege changes).
        //
        // Filtered against the baseline captured at the top rather than with drop(1): a bump that
        // predates this collection is already covered by the initial trigger the worker sends
        // itself, so acting on it too would buy a second full scan every time — but a bump raised
        // *after* that baseline must get through however late this subscribes, because the scan it
        // is racing may have read the package list before the grant landed. drop(1) cannot tell
        // those two apart; the baseline can. See the AppScanRevision class KDoc.
        val scanWatcher = launch {
            AppScanRevision.requestsAfter(scanRevisionAtStart)
                .collect { triggerChannel.trySend(Unit) }
        }

        // Receiver for Package-specific changes (requires "package" data scheme)
        val packageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                uadHelper.invalidateCache()
                triggerChannel.trySend(Unit)
            }
        }

        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }

        // Receiver for General Package changes (No data scheme)
        val generalReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                triggerChannel.trySend(Unit)
            }
        }

        val generalFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGES_SUSPENDED)
            addAction(Intent.ACTION_PACKAGES_UNSUSPENDED)
            // The *system* half of the locale key, and the only signal that carries it. A
            // third-party app's label is loaded from that app's resources and so follows the device
            // language, which can move while Thor's own language does not: under an in-app override
            // the application configuration never changes, so LocaleRevision stays silent and every
            // cached label is quietly a language behind.
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }

        context.registerReceiver(packageReceiver, packageFilter)
        context.registerReceiver(generalReceiver, generalFilter)

        awaitClose {
            context.unregisterReceiver(packageReceiver)
            context.unregisterReceiver(generalReceiver)
            localeWatcher.cancel()
            scanWatcher.cancel()
            worker.cancel()
        }
    }.flowOn(ioDispatcher)

    override suspend fun getAppDetails(packageName: String): AppInfo? =
        withContext(ioDispatcher) {
            try {
                val flags = (PackageManager.MATCH_UNINSTALLED_PACKAGES).toLong()
                val packInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags))
                } else {
                    pm.getPackageInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                }
                val appInfo = packInfo.applicationInfo ?: return@withContext null

                val mapped = mapToAppInfo(packInfo, appInfo, pm, isLightweight = false)
                val bloat = uadHelper.uadMap[packageName]
                mapped.copy(
                    bloatRecommendation = bloat?.removal,
                    bloatDescription = bloat?.description,
                    isUadLoadFailed = uadHelper.didLoadFail
                )
            } catch (e: Exception) {
                if (BuildConfig.DEBUG)
                    e.printStackTrace()
                null
            }
        }

    @Suppress("DEPRECATION")
    override suspend fun getDetailedAppInfo(packageName: String): DetailedAppInfo? =
        withContext(ioDispatcher) {
            try {
                val appInfo = (getAppDetails(packageName) ?: return@withContext null)
                    // Carry the persisted total install size (computed lazily on Size
                    // sort) so the App Info sheet can show it; null until first computed.
                    .copy(installSize = appDao.getApp(packageName)?.installSize)

                val flags = (PackageManager.GET_ACTIVITIES or
                        PackageManager.GET_SERVICES or
                        PackageManager.GET_RECEIVERS or
                        PackageManager.GET_PROVIDERS or
                        PackageManager.GET_PERMISSIONS or
                        PackageManager.GET_CONFIGURATIONS or
                        PackageManager.MATCH_UNINSTALLED_PACKAGES or
                        PackageManager.MATCH_DISABLED_COMPONENTS or
                        PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS or
                        PackageManager.GET_SIGNING_CERTIFICATES).toLong()

                val packInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags))
                } else {
                    pm.getPackageInfo(packageName, flags.toInt())
                }

                val components = componentSnapshotOf(packageName, packInfo)
                val reqFeatures = packInfo.reqFeatures?.map {
                    if (it.name != null) {
                        it.name
                    } else {
                        val glEs = it.glEsVersion
                        if (!glEs.isNullOrEmpty()) {
                            "GlEsVersion: $glEs"
                        } else {
                            val major = it.reqGlEsVersion shr 16
                            val minor = it.reqGlEsVersion and 0xFFFF
                            "GlEsVersion: $major.$minor"
                        }
                    }
                } ?: emptyList()

                val requestedPermissions = packInfo.requestedPermissions ?: emptyArray()
                val permissions = requestedPermissions.map { permName ->
                    val isGranted = pm.checkPermission(
                        permName,
                        packageName
                    ) == PackageManager.PERMISSION_GRANTED
                    var label: String? = null
                    var description: String? = null
                    val protection = try {
                        val permInfo = pm.getPermissionInfo(permName, 0)
                        label = permInfo.loadLabel(pm).toString()
                        description = permInfo.loadDescription(pm)?.toString()
                        val base = permInfo.protectionLevel and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE
                        when (base) {
                            android.content.pm.PermissionInfo.PROTECTION_NORMAL -> "Normal"
                            android.content.pm.PermissionInfo.PROTECTION_DANGEROUS -> "Dangerous"
                            android.content.pm.PermissionInfo.PROTECTION_SIGNATURE -> "Signature"
                            android.content.pm.PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM -> "Signature/System"
                            else -> "Unknown ($base)"
                        }
                    } catch (_: Exception) {
                        "Unknown"
                    }
                    PermissionDetail(
                        name = permName,
                        isGranted = isGranted,
                        protectionLevel = protection,
                        label = label,
                        description = description
                    )
                }

                val hasWakelockPermission =
                    requestedPermissions.contains(android.Manifest.permission.WAKE_LOCK)

                val nativeLibDir = packInfo.applicationInfo?.nativeLibraryDir
                val nativeLibs = if (nativeLibDir != null) {
                    val dir = File(nativeLibDir)
                    if (dir.exists() && dir.isDirectory) {
                        dir.listFiles()?.map { it.name } ?: emptyList()
                    } else emptyList()
                } else emptyList()

                val signatureSha256 = try {
                    val signatures = packInfo.signingInfo?.signingCertificateHistory
                    if (!signatures.isNullOrEmpty()) {
                        val cert = signatures[0].toByteArray()
                        val md = java.security.MessageDigest.getInstance("SHA-256")
                        val digest = md.digest(cert)
                        digest.joinToString(":") { "%02X".format(it) }
                    } else null
                } catch (_: Exception) {
                    null
                }

                DetailedAppInfo(
                    appInfo = appInfo,
                    components = components,
                    permissions = permissions,
                    nativeLibs = nativeLibs,
                    reqFeatures = reqFeatures,
                    hasWakelockPermission = hasWakelockPermission,
                    signatureSha256 = signatureSha256
                )
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) e.printStackTrace()
                null
            }
        }

    override suspend fun getComponentDetails(packageName: String): ComponentSnapshot? =
        withContext(ioDispatcher) {
            try {
                val flags = COMPONENT_QUERY_FLAGS
                val packInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, flags.toInt())
                }
                componentSnapshotOf(packageName, packInfo)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) e.printStackTrace()
                null
            }
        }

    /**
     * Map one already-fetched [PackageInfo] onto the four [ComponentDetail] lists.
     *
     * The caller must have fetched with [COMPONENT_QUERY_FLAGS] — specifically with
     * `MATCH_DISABLED_COMPONENTS` and `MATCH_DISABLED_UNTIL_USED_COMPONENTS`, without which
     * `PackageManager` silently omits every component that is currently switched off, which are
     * precisely the ones this screen exists to show and switch back on.
     *
     * The effective state comes from [PackageManager.getComponentEnabledSetting] rather than from a
     * second, flagless `getPackageInfo` differenced against the first. Both would answer "is this
     * component live", but the diff answers it wrongly in two situations that matter here:
     *  - **A frozen app.** A package-level disable makes `PackageUserState.isMatch` reject *every*
     *    component, so the flagless query comes back empty and the diff marks all several hundred
     *    rows DISABLED. `getComponentEnabledSetting` is per-component and untouched by the package
     *    state, so a frozen app still shows which of its components the user had individually
     *    turned off.
     *  - **An explicit `ENABLED` on a component the manifest already enables.** Indistinguishable
     *    from `DEFAULT` by any diff, but it is a real stored override, and it is the one this
     *    screen must offer "Reset to default" for.
     *
     * It costs one binder call per component. That is the same shape as the permission loop above
     * (two calls per requested permission) and it runs on [ioDispatcher]; a `getComponentEnabledSetting`
     * that throws for a component the parser produced but PMS does not recognise degrades that one
     * row to its manifest default rather than failing the screen.
     */
    private fun componentSnapshotOf(
        packageName: String,
        packInfo: android.content.pm.PackageInfo,
    ): ComponentSnapshot {
        // PackageManager can hand back the same component twice; the tab used to compensate by
        // putting the list index in the LazyColumn key, which made every row's identity change as
        // soon as a filter was typed.
        fun <T : android.content.pm.ComponentInfo> List<T>?.mapComponents(
            permissionOf: (T) -> String?,
        ): List<ComponentDetail> = this.orEmpty()
            .distinctBy { it.name }
            .map { info ->
                val manifestDefaultEnabled = info.enabled
                val setting = try {
                    pm.getComponentEnabledSetting(ComponentName(packageName, info.name))
                } catch (_: Exception) {
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                }
                ComponentDetail(
                    className = info.name,
                    exported = info.exported,
                    enabled = when (setting) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifestDefaultEnabled
                        else -> false
                    },
                    manifestDefaultEnabled = manifestDefaultEnabled,
                    hasExplicitState = setting != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    permission = permissionOf(info),
                )
            }

        return ComponentSnapshot(
            activities = packInfo.activities?.toList().mapComponents { it.permission },
            services = packInfo.services?.toList().mapComponents { it.permission },
            receivers = packInfo.receivers?.toList().mapComponents { it.permission },
            // A provider has no single entry permission: `android:permission` is shorthand for
            // setting both halves, and either half alone is enough to keep a caller out.
            providers = packInfo.providers?.toList().mapComponents {
                it.readPermission ?: it.writePermission
            },
        )
    }

    override suspend fun getApkDetails(apkPath: String): AppInfo? = withContext(ioDispatcher) {
        val flags = PackageManager.GET_PERMISSIONS
        val packInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apkPath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkPath, flags)
        } ?: return@withContext null

        val appInfo = packInfo.applicationInfo?.apply {
            sourceDir = apkPath
            publicSourceDir = apkPath
        } ?: return@withContext null

        mapToAppInfo(packInfo, appInfo, pm, isLightweight = false).copy(
            appName = pm.getApplicationLabel(appInfo).toString()
        )
    }

    override suspend fun updateInstallSizes(sizes: Map<String, Long>) {
        appDao.updateInstallSizes(sizes)
    }

    private companion object {
        /**
         * Not in the Auto Backup allowlist, deliberately — it describes rows that live in a
         * database Auto Backup does not carry either, and restoring one without the other would
         * assert a language for labels that were never scanned on this device.
         */
        const val LABEL_CACHE_PREFS = "app_label_cache"
        const val KEY_LABEL_LOCALE = "label_locale_key"

        /**
         * The flags a component query needs to see components that are switched **off**.
         *
         * Without `MATCH_DISABLED_COMPONENTS` and `MATCH_DISABLED_UNTIL_USED_COMPONENTS`,
         * `PackageManager` omits exactly the rows the Components tab exists to switch back on, and
         * omits them silently — the list simply comes back shorter. `MATCH_UNINSTALLED_PACKAGES`
         * keeps the query working for a package that is installed for another user but frozen or
         * archived for this one.
         */
        val COMPONENT_QUERY_FLAGS: Long = (
                PackageManager.GET_ACTIVITIES or
                        PackageManager.GET_SERVICES or
                        PackageManager.GET_RECEIVERS or
                        PackageManager.GET_PROVIDERS or
                        PackageManager.MATCH_UNINSTALLED_PACKAGES or
                        PackageManager.MATCH_DISABLED_COMPONENTS or
                        PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                ).toLong()
    }
}
