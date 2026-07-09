package com.valhalla.thor.data.launcher

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import com.valhalla.thor.R
import com.valhalla.thor.data.receivers.FreezerShortcutPinnedReceiver
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single

/** Owns all launcher-shortcut plumbing for the Freezer feature. */
@Single
class FreezerShortcutManager(
    private val context: Context,
    private val freezerRepository: FreezerRepository,
    private val manageAppUseCase: ManageAppUseCase,
    private val preferenceRepository: PreferenceRepository,
) {
    // App-scoped: bulk work must survive the (finishing) trampoline activity.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Solid launcher-tile backgrounds for the bulk action shortcuts (shared with the in-app preview).
    private val freezeShortcutBg = FreezerShortcutContract.FREEZE_TILE_COLOR
    private val unfreezeShortcutBg = FreezerShortcutContract.UNFREEZE_TILE_COLOR

    private companion object {
        const val LAUNCH_ACTIVITY = "com.valhalla.thor.presentation.launcher.FreezerLaunchActivity"
    }

    fun isPinSupported(): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Ask the launcher to pin a home-screen shortcut for an app. The icon follows the app's state
     *  (grey while frozen, full colour while enabled). Runs off the caller's thread — the icon
     *  decode is heavy — so any surface (dialog, details, freezer) can call this directly. */
    fun pinAppShortcut(packageName: String, label: String) {
        scope.launch { pinAppShortcutSuspend(packageName, label) }
    }

    /** Suspending pin so bulk callers can pin sequentially instead of spawning N concurrent bitmap
     *  decodes + binder pin requests (which risks OOM / overwhelming the shortcut service). */
    suspend fun pinAppShortcutSuspend(packageName: String, label: String) {
        val shortcut = buildAppShortcut(packageName, label)
        // A shortcut id previously greyed by disableShortcuts stays disabled on re-pin unless we
        // re-enable it — otherwise a re-frozen app comes back greyed/uninteractive.
        ShortcutManagerCompat.enableShortcuts(context, listOf(shortcut))
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, pinnedCallback(label).intentSender)
    }

    /** Update an already-pinned per-app shortcut so its icon reflects the app's current state.
     *  No-op if no such shortcut exists. Call after any freeze/unfreeze of the package. */
    fun refreshAppShortcut(packageName: String) {
        scope.launch { updateShortcutIcon(packageName) }
    }

    /** Ask the launcher to pin a Freeze-all / Unfreeze-all action shortcut. */
    fun pinBulkShortcut(action: String) {
        val shortcut = bulkShortcut(action)
        val label = shortcut.shortLabel.toString()
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, pinnedCallback(label).intentSender)
    }

    /** Publish (or remove) the Freeze-all + Unfreeze-all long-press dynamic shortcuts. */
    fun syncDynamicShortcuts(enabled: Boolean) {
        // Binder IPC — called from Main (cold-start + Settings); keep it off the caller's thread.
        scope.launch {
            if (enabled) {
                ShortcutManagerCompat.setDynamicShortcuts(
                    context,
                    listOf(
                        bulkShortcut(FreezerShortcutContract.ACTION_FREEZE_ALL),
                        bulkShortcut(FreezerShortcutContract.ACTION_UNFREEZE_ALL),
                    )
                )
            } else {
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            }
        }
    }

    /** Grey out a per-app shortcut (the ceiling — pinned icons can't be silently removed). */
    fun disableAppShortcut(packageName: String) {
        ShortcutManagerCompat.disableShortcuts(
            context,
            listOf(FreezerShortcutContract.appShortcutId(packageName)),
            context.getString(R.string.shortcut_no_longer_frozen)
        )
    }

    /** Bulk freeze/unfreeze every package in the freezer, off the finishing activity. */
    fun runBulk(disable: Boolean) {
        scope.launch {
            val pinnedIds = pinnedShortcutIds()
            val updated = mutableListOf<ShortcutInfoCompat>()
            // Freeze must honor the user's Freezer mode: Suspend mode suspends, Freeze mode disables.
            val useSuspend = disable &&
                    preferenceRepository.userPreferences.first().freezerMode == FreezerMode.SUSPEND
            freezerRepository.getAllPackageNames().forEach { pkg ->
                when {
                    disable && useSuspend -> manageAppUseCase.setAppSuspended(pkg, true)
                    disable -> manageAppUseCase.setAppDisabled(pkg, true)
                    // Unfreeze must restore suspended apps too, not just re-enable disabled ones.
                    else -> manageAppUseCase.forceUnfreeze(pkg)
                }
                // Only apps that actually have a pinned shortcut need a state-following icon refresh;
                // accumulate them and push ONE updateShortcuts IPC instead of N.
                if (FreezerShortcutContract.appShortcutId(pkg) in pinnedIds) {
                    appLabel(pkg)?.let { updated.add(buildAppShortcut(pkg, it)) }
                }
            }
            if (updated.isNotEmpty()) {
                ShortcutManagerCompat.updateShortcuts(context, updated)
            }
        }
    }

    private fun pinnedShortcutIds(): Set<String> =
        ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .mapTo(HashSet()) { it.id }

    private fun bulkShortcut(action: String): ShortcutInfoCompat {
        val spec = when (action) {
            FreezerShortcutContract.ACTION_FREEZE_ALL -> BulkSpec(
                FreezerShortcutContract.SHORTCUT_FREEZE_ALL,
                R.string.freeze_all_apps,
                R.drawable.frozen,
                freezeShortcutBg
            )
            FreezerShortcutContract.ACTION_UNFREEZE_ALL -> BulkSpec(
                FreezerShortcutContract.SHORTCUT_UNFREEZE_ALL,
                R.string.unfreeze_all_apps,
                R.drawable.unfreeze,
                unfreezeShortcutBg
            )
            else -> error("Unsupported bulk shortcut action: $action")
        }
        val label = context.getString(spec.labelRes)
        return ShortcutInfoCompat.Builder(context, spec.id)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(bulkIcon(spec.iconRes, spec.background))
            .setIntent(trampolineIntent(action))
            .build()
    }

    private data class BulkSpec(val id: String, val labelRes: Int, val iconRes: Int, val background: Int)

    // A launcher-visible adaptive icon: the white-tinted glyph centred on a solid colour tile. The raw
    // frozen/unfreeze vectors are white-on-transparent (meant to be tinted by the host), so passing them
    // to createWithResource renders an invisible/white blob on the launcher — hence this composed bitmap.
    private fun bulkIcon(iconRes: Int, backgroundColor: Int): IconCompat {
        val size = 216
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backgroundColor)
        try {
            ContextCompat.getDrawable(context, iconRes)?.mutate()?.apply {
                setTint(Color.WHITE)
                val inset = size / 4 // glyph fills the centre ~50%, within the adaptive safe zone
                setBounds(inset, inset, size - inset, size - inset)
                draw(canvas)
            }
        } catch (e: Exception) {
            // Fall back to a solid coloured tile rather than a broken/blank icon.
            Logger.e("FreezerShortcut", "bulk icon glyph load failed", e)
        }
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    // Fires (broadcast) ONLY when the launcher actually pins the shortcut — Android provides no
    // failure/cancel callback, so this confirms success and can't detect a user cancel.
    private fun pinnedCallback(label: String): PendingIntent {
        val intent = Intent(context, FreezerShortcutPinnedReceiver::class.java)
            .putExtra(FreezerShortcutPinnedReceiver.EXTRA_LABEL, label)
        return PendingIntent.getBroadcast(
            context,
            label.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // Explicit-component intent → our (non-exported) trampoline, targeted by string class name so
    // this class doesn't compile-depend on FreezerLaunchActivity. Shortcuts require an action.
    private fun trampolineIntent(action: String): Intent =
        Intent().apply {
            setClassName(context, LAUNCH_ACTIVITY)
            this.action = Intent.ACTION_VIEW
            // Start the trampoline in its own task (it also declares an empty taskAffinity), so tapping
            // a shortcut never brings Thor's existing task to the foreground.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(FreezerShortcutContract.EXTRA_ACTION, action)
        }

    private fun buildAppShortcut(packageName: String, label: String): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, FreezerShortcutContract.appShortcutId(packageName))
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(appIcon(packageName, grayscale = isFrozen(packageName)))
            .setIntent(
                trampolineIntent(FreezerShortcutContract.ACTION_LAUNCH)
                    .putExtra(FreezerShortcutContract.EXTRA_PACKAGE, packageName)
            )
            .build()

    // Rebuild + push the current-state icon for a package's pinned shortcut (no-op if absent).
    private fun updateShortcutIcon(packageName: String) {
        val label = appLabel(packageName) ?: return
        ShortcutManagerCompat.updateShortcuts(context, listOf(buildAppShortcut(packageName, label)))
    }

    // "Frozen" == the app is disabled OR (in Suspend mode) suspended-but-enabled, so the shortcut
    // icon greys out in both cases. MATCH_DISABLED_COMPONENTS so we can still read a disabled app;
    // FLAG_SUSPENDED (API 24+, matches the rest of the app) catches the suspended case.
    private fun isFrozen(packageName: String): Boolean = try {
        val info = context.packageManager
            .getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
        !info.enabled || (info.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
    } catch (e: Exception) {
        false
    }

    private fun appLabel(packageName: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(
            pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
        ).toString()
    } catch (e: Exception) {
        null
    }

    // The app's own icon, optionally desaturated to grey (used while the app is frozen).
    private fun appIcon(packageName: String, grayscale: Boolean): IconCompat {
        return try {
            val src = context.packageManager.getApplicationIcon(packageName).toBitmap()
            val out = if (grayscale) {
                Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also { gray ->
                    Canvas(gray).drawBitmap(
                        src, 0f, 0f,
                        Paint().apply {
                            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                        }
                    )
                }
            } else {
                src
            }
            IconCompat.createWithBitmap(out)
        } catch (e: Exception) {
            Logger.e("FreezerShortcut", "icon load failed for $packageName", e)
            IconCompat.createWithResource(context, R.drawable.frozen)
        }
    }
}
