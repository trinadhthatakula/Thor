package com.valhalla.thor.domain.repository

import android.net.Uri
import com.valhalla.thor.domain.model.CatalogEntry

/**
 * Networking + trust boundary for the in-app Extensions store.
 *
 * Implementations fetch the remote catalog and download-then-verify extension APKs. All
 * verification (SHA-256 match + pinned signer) happens inside [downloadAndVerify] and is
 * fail-closed: the returned [Uri] is only ever produced for a file that passed every check.
 */
interface StoreRepository {

    /** Fetch and parse the remote extension catalog. */
    suspend fun fetchCatalog(): Result<List<CatalogEntry>>

    /**
     * Download [entry]'s APK to app cache, verify its SHA-256 (when the catalog pins one) and its
     * signer against the pinned allowlist, and return a FileProvider content [Uri] ready to hand to
     * the installer. Any failure (blank/invalid URL, network error, hash mismatch, untrusted
     * signer) deletes the partial file and returns [Result.failure].
     */
    suspend fun downloadAndVerify(entry: CatalogEntry): Result<Uri>
}
