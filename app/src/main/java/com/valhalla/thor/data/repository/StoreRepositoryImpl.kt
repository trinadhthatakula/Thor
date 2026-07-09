package com.valhalla.thor.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.valhalla.thor.data.manager.ExtensionManager
import com.valhalla.thor.domain.model.CatalogEntry
import com.valhalla.thor.domain.model.ExtensionCatalog
import com.valhalla.thor.domain.repository.StoreRepository
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Store networking + verification. Uses the JDK's [HttpURLConnection] (no OkHttp/Ktor in the
 * project). Every APK returned by [downloadAndVerify] has passed a SHA-256 check (when pinned by
 * the catalog) AND a pinned-signer check — fail-closed at every step.
 */
@Single(binds = [StoreRepository::class])
class StoreRepositoryImpl(
    private val context: Context,
    private val extensionManager: ExtensionManager,
) : StoreRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchCatalog(): Result<List<CatalogEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = httpGet(CATALOG_URL)
            val catalog = json.decodeFromString<ExtensionCatalog>(body)
            // Be resilient to a malformed catalog: an entry with a blank `id` (optional field
            // omitted) or a duplicate `id` would otherwise collide on the LazyColumn item key AND the
            // per-entry installStatuses map, crashing the store. Drop blanks, keep first-of-duplicate.
            catalog.extensions.filter { it.id.isNotBlank() }.distinctBy { it.id }
        }.onFailure {
            if (it is CancellationException) throw it // don't swallow cancellation
            Logger.e(TAG, "fetchCatalog failed", it)
        }
    }

    override suspend fun downloadAndVerify(entry: CatalogEntry): Result<Uri> =
        withContext(Dispatchers.IO) {
            if (entry.apkUrl.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Entry '${entry.id}' has no APK to download")
                )
            }
            // The catalog is not fully trusted (that's why we pin the signer + hash), so refuse a
            // cleartext URL rather than let it downgrade the transport.
            if (!entry.apkUrl.startsWith("https://", ignoreCase = true)) {
                return@withContext Result.failure(
                    SecurityException("Refusing non-HTTPS APK URL for '${entry.id}'")
                )
            }
            // Belt-and-suspenders over the hash + pinned-signer gates: the (untrusted) catalog can only
            // steer the download at GitHub, never an arbitrary host. Redirect targets are host-checked
            // per hop in downloadHashing too.
            val apkHost = runCatching { URL(entry.apkUrl).host }.getOrNull()
            if (!isAllowedApkHost(apkHost)) {
                return@withContext Result.failure(
                    SecurityException("Refusing APK host '$apkHost' for '${entry.id}' — not an allowed release host")
                )
            }

            val dir = File(context.cacheDir, DOWNLOAD_DIR).apply { mkdirs() }
            // Bound cache growth by reaping only STALE leftovers — never a concurrent in-flight
            // download (those are seconds old). This replaces a full dir-wipe, which raced with a
            // parallel install and could delete its APK mid-flight.
            val staleCutoff = System.currentTimeMillis() - DOWNLOAD_STALE_MS
            runCatching { dir.listFiles()?.forEach { if (it.lastModified() < staleCutoff) it.delete() } }

            // Unique per-download file. createTempFile stays inside `dir` (so catalog-controlled
            // id/version can't traverse out) and never collides with a parallel download. The prefix
            // is sanitized + length-bounded; "ext_" guarantees the >=3-char minimum.
            val slug = "ext_${entry.id}-${entry.version}".replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
            var file: File? = null

            runCatching {
                file = File.createTempFile(slug, ".apk", dir)

                // 1. Stream to cache while hashing.
                val digestHex = downloadHashing(entry.apkUrl, file!!)

                // 2. SHA-256 gate (only when the catalog pins a hash).
                if (entry.sha256.isNotBlank() && !entry.sha256.equals(digestHex, ignoreCase = true)) {
                    throw SecurityException(
                        "SHA-256 mismatch for '${entry.id}': expected ${entry.sha256}, got $digestHex"
                    )
                }

                // 3. Pinned-signer gate on the APK file itself. Fail-closed, no debug bypass.
                if (!extensionManager.isApkFileSignerPinned(file!!.path)) {
                    throw SecurityException("Untrusted signer for '${entry.id}' — not a pinned key")
                }

                FileProvider.getUriForFile(context, "${context.packageName}.provider", file!!)
            }.onFailure {
                file?.delete()
                if (it is CancellationException) throw it // preserve structured-concurrency cancellation
                Logger.e(TAG, "downloadAndVerify failed for '${entry.id}'", it)
            }
        }

    /**
     * GET [urlString] and return the response body as text. Throws on any non-2xx response or IO
     * failure. Redirects are followed; connect/read timeouts are bounded.
     */
    private fun httpGet(urlString: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                throw java.io.IOException("HTTP $code fetching $urlString")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Stream the APK at [urlString] into [dest] on disk, computing its SHA-256 as bytes flow so the
     * whole file is never held in memory. Returns the digest as uppercase hex (same `%02X` + mask
     * formatting used by the signer-cert hashing) for a case-insensitive compare with the catalog.
     */
    private suspend fun downloadHashing(urlString: String, dest: File): String {
        var url = URL(urlString)
        var redirects = 0
        // Follow redirects MANUALLY so every hop — including the redirect target — is re-validated as
        // HTTPS on an allowed GitHub host. A catalog entry (or a rogue redirect) can't steer the
        // download to an arbitrary server.
        while (true) {
            if (!url.protocol.equals("https", ignoreCase = true) || !isAllowedApkHost(url.host)) {
                throw SecurityException("Refusing APK download host '${url.host}' (${url.protocol})")
            }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            try {
                connection.connect()
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw java.io.IOException("Redirect ($code) without Location for $url")
                    if (++redirects > MAX_REDIRECTS) {
                        throw java.io.IOException("Too many redirects downloading $urlString")
                    }
                    url = URL(url, location) // resolve a possibly-relative Location against this URL
                    continue // re-validate the new host at the top of the loop
                }
                if (code !in 200..299) {
                    throw java.io.IOException("HTTP $code downloading $url")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                connection.inputStream.use { input ->
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            // Cooperatively abort a cancelled download (user left the sheet) instead of
                            // streaming the whole APK to /dev/null on a background thread.
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                return digest.digest()
                    .joinToString(separator = "") { "%02X".format(it.toInt() and 0xFF) }
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * True only for the hosts GitHub actually serves verified-extension releases from: `github.com`
     * (the release-download URL CI writes into the catalog) and its asset CDN
     * (`*.githubusercontent.com`, e.g. `release-assets.githubusercontent.com`, which `github.com`
     * 302-redirects to). Anything else is refused.
     */
    private fun isAllowedApkHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "github.com" || h == "githubusercontent.com" || h.endsWith(".githubusercontent.com")
    }

    companion object {
        private const val TAG = "StoreRepository"

        /**
         * Source-of-truth catalog URL. To test against a fork/branch, change this string (raw
         * GitHub URL over HTTPS) — nothing else in the flow is URL-aware.
         */
        const val CATALOG_URL =
            "https://raw.githubusercontent.com/trinadhthatakula/Thor-Extensions/main/catalog/extensions.json"

        private const val DOWNLOAD_DIR = "ext_downloads"
        private const val DOWNLOAD_STALE_MS = 10 * 60_000L // reap leftovers older than 10 min
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 5
    }
}
