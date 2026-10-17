package dev.omalauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import dev.omalauncher.spokenLabel
import dev.omalauncher.widgets.Shortcut

/**
 * A pinned app: its icon (a silhouette in the accent color, or full color), framed
 * like a widget, or bare on the wallpaper like a stock launcher (Pack.bareIcons).
 */
@Composable
fun ShortcutTile(
    shortcut: Shortcut,
    /** From the app catalogs, as the drawer shows it: a silhouette when [Pack.monoIcons]; null for initials. */
    icon: ImageBitmap?,
    focused: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
    /** In a paused work profile: drawn dimmed, like stock launchers. */
    paused: Boolean = false,
) {
    val mono = Pack.monoIcons
    val bare = Pack.bareIcons
    val spoken = spokenLabel(shortcut.label, shortcut.profile)
    BoxWithConstraints(
        modifier
            .alpha(if (paused) PausedAlpha else 1f)
            .tileFrame(focused, shortcut.key)
            .pointerInput(shortcut.key) { detectTapGestures(onTap = { onTap() }) }
            .padding(4.dp),
    ) {
        // Label only when the tile is tall enough to fit it under the icon.
        val showLabel = maxHeight > 64.dp
        // Bare icons have no frame to fill, so they take nearly the whole cell.
        val iconSize = min(maxWidth, maxHeight - if (showLabel) 18.dp else 0.dp) * if (bare) 0.95f else 0.85f
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            val bmp = icon
            when {
                // Bare silhouettes and initials sit on a badge, as in the drawer, so they read on any wallpaper.
                bare && (mono || bmp == null) ->
                    Box(
                        Modifier.iconBadge(iconSize),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bmp != null) {
                            Image(bmp, spoken, Modifier.size(iconSize * 0.75f), colorFilter = Pack.tinted())
                        } else {
                            BasicText(Pack.placeholderText(shortcut.label), style = Pack.placeholder((iconSize.value * 0.35f).sp))
                        }
                    }
                bmp != null ->
                    AppIcon(bmp, spoken, Modifier.size(iconSize), mono, framed = !bare)
                else ->
                    // Apps without an icon get the pack's placeholder: their initials, in Console.
                    Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
                        BasicText(Pack.placeholderText(shortcut.label), style = Pack.placeholder((iconSize.value * 0.45f).sp))
                    }
            }
            if (showLabel) TileLabel(shortcut.label)
        }
    }
}

/** How opaque an app from a paused work profile is drawn. */
internal const val PausedAlpha = 0.4f

/** An app tile's frame from the pack, the scrap known by [key]; none for bare icons unless [focused]. */
internal fun Modifier.tileFrame(
    focused: Boolean,
    key: String,
) = when {
    !Pack.bareIcons -> scrap(focused, key)
    // Bare icons still show which tile is focused or being edited.
    focused -> scrapFocus()
    else -> this
}

/** The name under an app tile's icon. */
@Composable
internal fun TileLabel(text: String) {
    val bare = Pack.bareIcons
    BasicText(
        text,
        // Straight on the page, a name gets the page's plate where the pack has one.
        if (bare) Modifier.background(Pack.plate) else Modifier,
        style =
            if (bare) {
                // On the wallpaper: light text with a shadow, like stock launchers; on a pack's own page, its page ink.
                Pack.pageLabel().copy(textAlign = TextAlign.Center)
            } else {
                Pack.body(Pack.size.small, Pack.scrapInk).copy(textAlign = TextAlign.Center)
            },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
