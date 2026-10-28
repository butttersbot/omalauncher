package dev.omalauncher.ui

import android.content.Context
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.omalauncher.herd.Herd
import dev.omalauncher.herd.HerdSheep
import dev.omalauncher.herd.SheepFrame
import dev.omalauncher.herd.giveHerd
import dev.omalauncher.herd.herdPage
import dev.omalauncher.herd.herdUnreachable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How often the sheep asks while it sits on home, and while its panel is open. */
private const val HERD_EVERY_MS = 15_000L
private const val HERD_OPEN_EVERY_MS = 4_000L

/**
 * The herd's sheep in home's corner: the same page the Mac's desktop pet draws (assets/herd/pet.html, the one source since
 * 2026-10-28: tools/herd/publish.sh copies it to ClaudeSync's herd project and Omarchy), fed what the reporters say. A tap opens its panel of every agent on
 * every machine; back closes it. It asks only while home has the window.
 */
@Composable
fun HerdPet(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val page = remember { arrayOfNulls<WebView>(1) }
    val focused = LocalWindowInfo.current.isWindowFocused
    val screen = LocalConfiguration.current
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("herd", Context.MODE_PRIVATE) }
    // Where he dragged it from home's corner, in pixels (Boss, 2026-10-15: "I need to be able to drag that thing around").
    var dx by remember { mutableFloatStateOf(prefs.getFloat("home_dx", 0f)) }
    var dy by remember { mutableFloatStateOf(prefs.getFloat("home_dy", 0f)) }

    DisposableEffect(Unit) { onDispose { HerdSheep.onHome = null } }
    // Back ends choosing several first (pet.html herd.back), then closes the panel.
    BackHandler(enabled = open) { page[0]?.evaluateJavascript("herd.back ? herd.back() : herd.tap()", null) }

    LaunchedEffect(focused, loaded, open) {
        while (focused && loaded) {
            val json = withContext(Dispatchers.IO) { Herd.status() }
            page[0]?.let { giveHerd(it, "update", json ?: herdUnreachable()) }
            delay(if (open) HERD_OPEN_EVERY_MS else HERD_EVERY_MS)
        }
    }

    // How far from home's corner the sheep may be carried and still be all on the screen, in pixels.
    val d = context.resources.displayMetrics.density
    val roomX = maxOf(0f, (screen.screenWidthDp - 150) * d)
    val roomY = maxOf(0f, (screen.screenHeightDp - 150 - 120) * d)
    dx = dx.coerceIn(-roomX, 0f)
    dy = dy.coerceIn(-roomY, 0f)

    // Closed it is just the sheep; open it is the panel above it, as wide as a phone's screen allows.
    val width = if (open) minOf(400, screen.screenWidthDp).dp else 150.dp
    val height = if (open) minOf(680, screen.screenHeightDp * 8 / 10).dp else 150.dp
    AndroidView(
        factory = { ctx ->
            SheepFrame(ctx, onDrag = { x, y ->
                // Open, the panel stands in home's corner and isn't carried; closed, the sheep stays on the screen.
                if (!open) {
                    dx = (dx + x).coerceIn(-roomX, 0f)
                    dy = (dy + y).coerceIn(-roomY, 0f)
                }
            }, onDrop = { prefs.edit().putFloat("home_dx", dx).putFloat("home_dy", dy).apply() }).apply {
                val web = herdPage(ctx, onOpen = { open = it }, onLoaded = { loaded = true })
                addView(web, android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                page[0] = web
            }
        },
        onRelease = {
            page[0]?.destroy()
            page[0] = null
        },
        modifier =
            modifier
                .offset { if (open) IntOffset.Zero else IntOffset(dx.toInt(), dy.toInt()) }
                .size(width, height)
                .onGloballyPositioned { HerdSheep.onHome = it.boundsInWindow() },
    )
}

