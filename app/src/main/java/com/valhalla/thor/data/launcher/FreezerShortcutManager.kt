package com.valhalla.thor.data.launcher

import android.content.Context
import android.content.Intent
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
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single

/** Owns all launcher-shortcut plumbing for the Freezer feature. */
@Single
class FreezerShortcutManager(
    private val context: Context,
    private val freezerRepository: FreezerRepository,
    private val manageAppUseCase: ManageAppUseCase,
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

    /** Ask the launcher to pin a home-screen shortcut for a frozen app (grayscale icon). */
    fun pinAppShortcut(packageName: String, label: String) {
        val shortcut = ShortcutInfoCompat.Builder(context, FreezerShortcutContract.appShortcutId(packageName))
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(grayscaleIcon(packageName))
            .setIntent(
                trampolineIntent(FreezerShortcutContract.ACTION_LAUNCH)
                    .putExtra(FreezerShortcutContract.EXTRA_PACKAGE, packageName)
            )
            .build()
        // A shortcut id previously greyed by disableShortcuts stays disabled on re-pin unless we
        // re-enable it — otherwise a re-frozen app comes back greyed/uninteractive.
        ShortcutManagerCompat.enableShortcuts(context, listOf(shortcut))
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    /** Ask the launcher to pin a Freeze-all / Unfreeze-all action shortcut. */
    fun pinBulkShortcut(action: String) {
        ShortcutManagerCompat.requestPinShortcut(context, bulkShortcut(action), null)
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
            freezerRepository.getAllPackageNames().forEach { pkg ->
                manageAppUseCase.setAppDisabled(pkg, disable)
            }
        }
    }

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

    // Saturation-0 grayscale of the app's own icon (loadable for a disabled-but-installed app).
    private fun grayscaleIcon(packageName: String): IconCompat {
        return try {
            val src = context.packageManager.getApplicationIcon(packageName).toBitmap()
            val gray = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            Canvas(gray).drawBitmap(
                src, 0f, 0f,
                Paint().apply {
                    colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                }
            )
            IconCompat.createWithBitmap(gray)
        } catch (e: Exception) {
            Logger.e("FreezerShortcut", "icon load failed for $packageName", e)
            IconCompat.createWithResource(context, R.drawable.frozen)
        }
    }
}
