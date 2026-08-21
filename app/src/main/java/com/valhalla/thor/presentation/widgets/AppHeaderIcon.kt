// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.valhalla.asgard.expressivePress
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.presentation.utils.AppIconModel

/** How much wider than the icon [AppHeaderIcon]'s glow is, by default. */
internal const val APP_HEADER_ICON_GLOW_RATIO = 1.45f

// The three animated scale bumps. They are additive and can all be at maximum at once: a long press
// is recognised while the finger is still down, so `press` is at 1 when `flare` snaps to 1, and
// `breath` is free-running and answers to nothing.
private const val GLOW_BREATH_SCALE = 0.02f
private const val GLOW_PRESS_SCALE = 0.04f
private const val GLOW_FLARE_SCALE = 0.05f

/**
 * The transparent margin [AppHeaderIcon] reserves on every side of a [size]-dp icon for its glow.
 *
 * Callers subtract this from the spacing they want around the icon. The widget measures
 * `size * `[APP_HEADER_ICON_GLOW_RATIO], so a caller that leaves its usual gap on top of that leaves
 * about twice the gap it intended.
 */
internal fun appHeaderIconGlowInset(size: Dp): Dp = size * (APP_HEADER_ICON_GLOW_RATIO - 1f) / 2f

/**
 * How far past its own bounds [AppHeaderIcon]'s glow reaches at the peak of a press-and-hold.
 *
 * [appHeaderIconGlowInset] reserves room for the glow at rest — scale 1.0 and no more. The press and
 * flare take it to about 1.11x, and nothing inside the widget clips, so that last 11 % draws outside
 * it. It is the outermost ring of a smoothstep falloff, which has arrived at zero alpha by then, so
 * a clip there costs nothing visible; a caller that wants the peak intact anyway can reserve this.
 */
internal fun appHeaderIconGlowOvershoot(size: Dp): Dp =
    size * APP_HEADER_ICON_GLOW_RATIO *
        (GLOW_BREATH_SCALE + GLOW_PRESS_SCALE + GLOW_FLARE_SCALE) / 2f

/**
 * The app icon as it appears at the top of both app-info surfaces — and the two shortcuts on it.
 *
 * Tap opens the app, long-press opens its system settings page. Both are shortcuts to actions the
 * row below already offers, which is the point: the row is user-customisable and anything in it can
 * be hidden, so users who hide Open and Settings to shorten the row keep both within reach. Nothing
 * is *only* here.
 *
 * **[onOpen] and [onOpenSettings] must be the very lambdas the row's `onLaunch` and
 * `onSystemSettings` get.** Not a copy that does the same thing — the same instances. "Open" is not
 * `startActivity`: a frozen or suspended app has to be restored first (`MainViewModel` turns
 * [com.valhalla.thor.domain.model.AppClickAction.Launch] into an unfreeze/unsuspend and *then* a
 * launch), and a shortcut that skipped that would silently do nothing on exactly the apps a user of
 * this app is most likely to tap. Sharing the lambda makes the two paths identical by construction
 * rather than by inspection.
 *
 * **The glow is the discoverability story.** An icon that is suddenly tappable, with no ripple until
 * it is touched, is a gesture nobody finds. [AmbientGlow] behind it breathes slowly at rest to say
 * "this is alive", brightens and swells under a finger, and flares once when a long press is
 * recognised — that last one because a long press hands off to an external activity, so the frame or
 * two before it appears is the only feedback Thor still owns. Every animated value is read inside a
 * `graphicsLayer` block, so none of it recomposes the header.
 *
 * Sizing is passed in rather than fixed: the sheet's header draws this at 100 dp and the details
 * screen's at 72 dp. Everything else about it is shared, so the two cannot drift.
 *
 * @param imageModifier applied to the icon image itself, for the details screen's shared-element
 *   transition. On the container it would animate the glow and the ripple with it.
 * @param glowDiameter defaults to [APP_HEADER_ICON_GLOW_RATIO] x [size] — a halo hugging the icon,
 *   not a wash behind the header. Twice [size] was the first attempt and it was wrong: at 100 dp
 *   that is a 220 dp patch reaching 60 dp past the icon, which on the sheet lands under the app
 *   title and reads as a discoloured background rather than as something around the icon.
 *
 * The difference between [glowDiameter] and [size] is *reserved* around the icon rather than left to
 * overflow, because both surfaces sit inside something that clips: the sheet's scrolling column and
 * the details card's rounded background. Overflowing, the glow was sliced off flat along the icon's
 * own top edge on the sheet — a bright straight line where a halo should be, and only above, since
 * the bottom half had a spacer to bleed into. The widget therefore measures [glowDiameter], not
 * [size]: [appHeaderIconGlowInset] of transparent margin on every side.
 *
 * **Callers have to spend that margin, not add to it.** A caller that keeps the spacing it used
 * before the glow existed puts the next thing along twice as far away as it means to, and on a
 * horizontal layout takes the same amount off whatever shares the row. Both call sites subtract
 * [appHeaderIconGlowInset] from the gap they want; see either one. The reserved margin covers the
 * glow at rest — the press and flare scale it up to [appHeaderIconGlowOvershoot] past the widget's
 * bounds, which is the *outermost* ring of a smoothstep falloff and so has essentially no alpha
 * left, but a caller with a clip right there can pay for it as the sheet's header does.
 */
@Composable
internal fun AppHeaderIcon(
    appInfo: AppInfo,
    onOpen: () -> Unit,
    onOpenSettings: () -> Unit,
    size: Dp,
    cornerRadius: Dp,
    contentPadding: Dp,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    glowDiameter: Dp = size * APP_HEADER_ICON_GLOW_RATIO,
    imageModifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // The resting breath. Slow and shallow on purpose: this is a hint sitting under the user's eyes
    // for as long as the sheet is open, not a call to action.
    val transition = rememberInfiniteTransition(label = "appHeaderIconGlow")
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "appHeaderIconGlowBreath"
    )

    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "appHeaderIconGlowPress"
    )

    // Counter rather than a Boolean: two long presses in a row have to replay the flare, and a
    // Boolean that is already true is not a change. snapTo, then decay — a long press is recognised
    // at the end of the hold, so there is nothing left to ramp up to.
    var longPressCount by remember { mutableIntStateOf(0) }
    val flare = remember { Animatable(0f) }
    LaunchedEffect(longPressCount) {
        if (longPressCount == 0) return@LaunchedEffect
        flare.snapTo(1f)
        flare.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 700, easing = LinearOutSlowInEasing)
        )
    }

    // The room the glow needs on every side. Padding rather than a larger Box so the icon stays
    // centred in it and the glow, matching the *padded* bounds, stays centred on the icon.
    val glowInset = ((glowDiameter - size) / 2).coerceAtLeast(0.dp)

    // One alpha cannot serve both schemes. Dark mode puts a pale green on near-black, where a few
    // percent already reads as light; light mode puts a *dark* green on a near-white surface, where
    // the same figure is a grey smudge — measured at 11/255 against the sheet, which is nothing. So
    // light mode gets a much stronger alpha and `primaryContainer`, the saturated mid-green, rather
    // than `primary`, which at these alphas tints towards shade rather than towards colour.
    //
    // Luminance of `surface`, not `isSystemInDarkTheme`: `ThorTheme` also has an AMOLED variant and
    // takes `darkTheme` as a parameter the caller can override, so the scheme actually in hand is
    // the only reliable answer to what this glow is being drawn over.
    val scheme = MaterialTheme.colorScheme
    val surfaceLuminance = scheme.surface.luminance()
    val onLightScheme = surfaceLuminance > 0.5f
    val glowColor = when {
        !onLightScheme -> scheme.primary
        // Thor's own light scheme is unusual: its `primaryContainer` is a *dark* green (#4c662b,
        // luminance 0.11 against a 0.95 surface), which is why it is the better choice above. Every
        // dynamic light scheme puts the ordinary M3 tone there instead — accent1-90, a near-white
        // pastel — and `useDynamicColor` is a user preference (`HomeActivity`), so that scheme is
        // reachable. At 0.40 alpha a pastel over a near-white sheet is an 8/255 delta: the
        // affordance simply is not there. So take `primaryContainer` only when it is actually dark
        // enough to register, and fall back to `primary`, which is the 40-tone in every M3 light
        // scheme and so always is.
        surfaceLuminance - scheme.primaryContainer.luminance() > 0.35f -> scheme.primaryContainer
        else -> scheme.primary
    }
    val restAlpha = if (onLightScheme) 0.40f else 0.16f
    val breathAlpha = if (onLightScheme) 0.12f else 0.08f
    val pressAlpha = if (onLightScheme) 0.28f else 0.22f
    val flareAlpha = if (onLightScheme) 0.32f else 0.30f

    Box(modifier = modifier.padding(glowInset), contentAlignment = Alignment.Center) {
        // matchParentSize so the glow never enters the parent's measurement: it is bigger than the
        // icon, and a glow that pushed the header's layout around would move the title. The inset
        // above is what keeps it inside this widget's own bounds regardless.
        AmbientGlow(
            modifier = Modifier.matchParentSize(),
            diameter = glowDiameter,
            // No blur. A blur is what put a square edge on this: it smears the halo out to the
            // node's rectangular bounds, and the default edge treatment then clips it there, so the
            // glow ended in four straight lines a few pixels short of where the gradient had already
            // faded to nothing. The gradient's own smoothstep falloff is the softness, which also
            // means this renders identically on API 28-30, where `Modifier.blur` does nothing at all.
            blurRadius = 0.dp,
            color = glowColor,
            // The icon container is opaque and covers the middle of its own halo, so without a core
            // the only visible part of the gradient is the tail that has already faded to nearly
            // nothing. 0.45 puts the falloff just outside the icon's edge.
            coreFraction = 0.45f,
            // Concentrated rather than spread: a tighter halo needs a little more alpha to read at
            // all. Punch comes from alpha, not size — the scale deltas are small deliberately. The
            // reserved inset covers scale 1.0 exactly, so every bump above it draws outside the
            // widget ([appHeaderIconGlowOvershoot]); keeping the peak at 1.11x keeps what escapes to
            // the zero-alpha tail of the falloff, where a clip is invisible. Reserving for the peak
            // instead would cost 16 dp of permanent layout to serve 300 ms of animation.
            alpha = {
                restAlpha + breathAlpha * breath + pressAlpha * press + flareAlpha * flare.value
            },
            scale = {
                1f + GLOW_BREATH_SCALE * breath + GLOW_PRESS_SCALE * press +
                    GLOW_FLARE_SCALE * flare.value
            }
        )

        Box(
            modifier = Modifier
                .size(size)
                // Squish first, then clip, then the click, then the background — everything visible
                // inside the squish's `graphicsLayer`, or it shrinks under something that stays put.
                // The app list's rows clip *outside* it, which is subtly wrong on a shape this
                // round: `expressivePress` is a scale layer (`Motion.kt`), and a clip above it keeps
                // its corner arcs at the unscaled radius while the fill shrinks under them, so a
                // pressed 32 dp corner is cut by a stationary r=32 arc and gains a visible kink
                // where the arc meets the straight edge. Inside the layer the whole silhouette
                // scales together and the press is a squish rather than a change of shape.
                .expressivePress(interactionSource)
                .clip(RoundedCornerShape(cornerRadius))
                .combinedClickable(
                    interactionSource = interactionSource,
                    role = Role.Button,
                    // The row's own labels for these two actions, reused verbatim: already
                    // translated everywhere, and TalkBack then names the shortcut and the action
                    // identically. No haptics here — CombinedClickableNode performs the long-press
                    // haptic itself, and a second one is a double buzz.
                    onClickLabel = stringResource(R.string.action_open),
                    onLongClickLabel = stringResource(R.string.settings),
                    onLongClick = {
                        longPressCount++
                        onOpenSettings()
                    },
                    onClick = onOpen
                )
                .background(containerColor)
                .padding(contentPadding),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = AppIconModel(appInfo.packageName),
                // The app's name, where this image used to be decorative. It is the only child of a
                // clickable node now, so with a null description TalkBack reaches a button it can
                // only call "unlabelled".
                contentDescription = appInfo.appName ?: appInfo.packageName,
                modifier = Modifier
                    .fillMaxSize()
                    .then(imageModifier)
            )
        }
    }
}
