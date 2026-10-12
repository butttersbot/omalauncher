package dev.omalauncher.ui

import dev.omalauncher.assist.prototypeUnit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.layout.width
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.graphics.ColorFilter
import dev.omalauncher.AndroidApp
import androidx.compose.runtime.collectAsState
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxState
import androidx.compose.ui.graphics.vector.ImageVector
import dev.omalauncher.linux.FILES_COMMAND
import dev.omalauncher.linux.LinuxApp

/** One app in the app list or its search results. */
internal sealed interface AppHit {
    /** Stable id, also the [dev.omalauncher.AppUsage] key. */
    val key: String
    val label: String

    /** What screen readers say, to tell a work app from the personal one of the same name. */
    val spoken: String get() = label

    /** Another name search matches: a work app's own name with "work" added, so "work" or "whatsapp work" finds it. */
    val alias: String? get() = null

    /** In a paused work profile: shown dimmed, and opening it turns work apps back on. */
    fun paused(runtime: Runtime) = false

    data class Linux(
        val app: LinuxApp,
    ) : AppHit {
        override val key get() = "l:" + app.id
        override val label get() = app.name
    }

    data class Android(
        val app: AndroidApp,
    ) : AppHit {
        override val key get() = "a:" + app.key
        override val label get() = app.label
        override val spoken get() = app.spoken
        override val alias get() = if (app.profile != null) "${app.label} work" else null

        override fun paused(runtime: Runtime) = runtime.androidApps.paused(app.profile)
    }
}

/** What the search field's query matched: apps best first, and whether Enter opens the first rather than asking the agent. */
internal class BarSearch(
    val hits: List<AppHit>,
    val opensApp: Boolean,
) {
    /** The result Enter acts on: an index into [hits], or -1 for the agent. */
    fun highlighted(picked: Int?) = picked?.coerceIn(-1, hits.lastIndex) ?: if (opensApp) 0 else -1
}

private fun allApps(runtime: Runtime): List<AppHit> = runtime.linuxApps.map { AppHit.Linux(it) } + runtime.androidApps.apps.map { AppHit.Android(it) }

internal fun searchApps(
    runtime: Runtime,
    query: String,
): BarSearch {
    if (query.isBlank()) return BarSearch(emptyList(), false)
    val now = System.currentTimeMillis()
    val ranked = rankMatches(allApps(runtime), query, { it.label }, boost = { (runtime.usage.score(it.key, now) * 10).toInt() }, alias = { it.alias })
    return BarSearch(ranked.map { it.first }, ranked.firstOrNull()?.let { isStrongMatch(it.second, query) } == true)
}

/** What the app cells do: open, pin to home, pin to the dock or unpin, and the Android app's info and uninstall. */
internal class AppHandlers(
    val launch: (AppHit) -> Unit,
    val pin: (AppHit) -> Unit,
    /** Whether the app is in the dock, which decides what [dock] is called in the menu. */
    val docked: (AppHit) -> Boolean,
    /** Pins the app to the dock, or unpins it if it's there. */
    val dock: (AppHit) -> Unit,
    val info: (AndroidApp) -> Unit,
    val uninstall: (AndroidApp) -> Unit,
)

/** What the long-press menu calls [AppHandlers.dock] for an app that is or isn't [docked]. */
internal fun dockActionLabel(docked: Boolean) = if (docked) "unpin from dock" else "pin to dock"

/** The long-press menu of an app cell: pin to home, pin to the dock or unpin, and for Android apps their info and uninstall. */
private fun AppHit.actions(handlers: AppHandlers): List<Pair<String, () -> Unit>> {
    val dock = dockActionLabel(handlers.docked(this)) to { handlers.dock(this) }
    return when (this) {
        is AppHit.Linux -> listOf("add to home" to { handlers.pin(this) }, dock)
        is AppHit.Android ->
            listOfNotNull(
                "add to home" to { handlers.pin(this) },
                dock,
                "app info" to { handlers.info(app) },
                if (app.system) null else "uninstall" to { handlers.uninstall(app) },
            )
    }
}

/** The app's icon and its silhouette (each null until loaded or if it has none), and a glyph for built-in apps (Files). */
private fun AppHit.icons(runtime: Runtime): Triple<ImageBitmap?, ImageBitmap?, ImageVector?> =
    when (this) {
        is AppHit.Linux -> Triple(runtime.linuxIcons[app.id], runtime.linuxMasks[app.id], if (app.exec == FILES_COMMAND) Icons.Folder else null)
        is AppHit.Android -> Triple(runtime.androidApps.icons[app.key], runtime.androidApps.masks[app.key], null)
    }

@Composable
private fun HitCell(
    runtime: Runtime,
    hit: AppHit,
    handlers: AppHandlers,
    selected: Boolean = false,
) {
    val (icon, mask, glyph) = hit.icons(runtime)
    AppCell(hit.label, icon, mask, onClick = { handlers.launch(hit) }, actions = { hit.actions(handlers) }, glyph = glyph, selected = selected, spoken = hit.spoken, paused = hit.paused(runtime))
}

/** Height of the recent apps strip; home keeps this much room for it above the dock, even while it's empty. */
val RecentStripHeight = 56.dp

/**
 * The apps opened lately, on home right above the dock, scrolling sideways; [vertical] stands it up
 * beside the grid in landscape, scrolling down. A home tile like the others: frame, fill and gaps,
 * or bare when home icons are. Can be hidden in themes.
 */
@Composable
internal fun RecentStrip(
    runtime: Runtime,
    handlers: AppHandlers,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val apps = recentApps(runtime, 20)
    // Nothing opened yet: no strip, but its room stays, so home doesn't shift when the first app shows up.
    if (apps.isEmpty()) {
        Spacer(if (vertical) modifier.width(RecentStripHeight) else modifier.height(RecentStripHeight))
        return
    }
    val bare = Pack.bareIcons
    val listState = rememberLazyListState()
    // Back on home, the strip starts at the app opened last, wherever it was scrolled to before.
    LaunchedEffect(runtime.usage.settled) { listState.scrollToItem(0) }
    Box(
        modifier
            .then(if (vertical) Modifier.fillMaxHeight().width(RecentStripHeight) else Modifier.padding(horizontal = PageGap).fillMaxWidth().height(RecentStripHeight))
            .then(if (bare) Modifier else Modifier.dockBar()),
        contentAlignment = if (vertical) Alignment.TopCenter else Alignment.CenterStart,
    ) {
        val cells: LazyListScope.() -> Unit = {
            items(apps, key = { it.key }) { hit ->
                val (icon, mask, glyph) = hit.icons(runtime)
                StripCell(hit.label, icon, mask, onClick = { handlers.launch(hit) }, actions = { hit.actions(handlers) }, glyph = glyph, spoken = hit.spoken, paused = hit.paused(runtime))
            }
        }
        if (vertical) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, content = cells)
        } else {
            LazyRow(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, content = cells)
        }
    }
}

/** An app in the recent strip or the dock, drawn like a pinned app's tile: the icon alone, or on a badge when bare. */
@Composable
internal fun StripCell(
    label: String,
    icon: ImageBitmap?,
    mask: ImageBitmap?,
    onClick: () -> Unit,
    /** Built when the menu opens: a fresh list of lambdas would keep the cell from skipping recomposition. */
    actions: () -> List<Pair<String, () -> Unit>>,
    glyph: ImageVector? = null,
    /** As in [AppHit.spoken]. */
    spoken: String = label,
    /** As in [AppHit.paused]: drawn dimmed, like stock launchers. */
    paused: Boolean = false,
    /** The icon's size inside the 48dp cell. */
    size: Dp = 40.dp,
    /** The cell's own button, in a dock of buttons: see [dockButton]. */
    chrome: Modifier = Modifier,
    /** The cell itself: what is touched. */
    cell: Dp = 48.dp,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val mono = Pack.monoIcons
    val bare = Pack.bareIcons
    Box(
        Modifier
            .size(cell)
            .then(chrome)
            .alpha(if (paused) PausedAlpha else 1f)
            .clip(Pack.shape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = if (mono) mask else icon
        // On a dock button of its own, a glyph, silhouette or letter is in the dock's ink, and the button is its badge.
        val onButton = chrome !== Modifier
        when {
            onButton && glyph != null -> Image(glyph, label, Modifier.size(size * 0.7f), colorFilter = Pack.tinted(Pack.dockInk))
            onButton && bmp != null && mono -> Image(bmp, spoken, Modifier.size(size), colorFilter = Pack.tinted(Pack.dockInk))
            onButton && bmp != null -> AppIcon(bmp, spoken, Modifier.size(size), mono = false, framed = true)
            onButton -> BasicText(Pack.placeholderText(label), style = Pack.placeholder(Pack.size.large, Pack.dockInk))
            glyph != null -> Image(glyph, label, Modifier.size(size * 0.7f), colorFilter = Pack.tinted())
            // Bare silhouettes and initials sit on a badge, as pinned apps do, so they read on any wallpaper.
            bare && (mono || bmp == null) ->
                Box(Modifier.iconBadge(size), contentAlignment = Alignment.Center) {
                    if (bmp != null) {
                        Image(bmp, spoken, Modifier.size(size * 0.75f), colorFilter = Pack.tinted())
                    } else {
                        BasicText(Pack.placeholderText(label), style = Pack.placeholder(Pack.size.body))
                    }
                }
            bmp != null -> AppIcon(bmp, spoken, Modifier.size(size * if (mono) 0.85f else 1f), mono)
            else -> BasicText(Pack.placeholderText(label), style = Pack.placeholder(Pack.size.large))
        }
        if (menuOpen) AppActions(actions(), onDismiss = { menuOpen = false })
    }
}

/** The apps opened lately, last opened first; ones not installed (or not loaded yet) are skipped. */
internal fun recentApps(
    runtime: Runtime,
    limit: Int,
): List<AppHit> {
    val byKey = allApps(runtime).associateBy { it.key }
    return runtime.usage.recent.mapNotNull { byKey[it] }.take(limit)
}

/**
 * Whether opening the app list puts the cursor in its search field: only with a physical keyboard out
 * ([keyboard] and [hardKeyboardHidden] as in [Configuration]), which types there without an on-screen
 * keyboard coming up. Otherwise the field waits to be tapped, and the list opens at full height.
 */
internal fun focusesSearchOnOpen(
    keyboard: Int,
    hardKeyboardHidden: Int,
) = keyboard == Configuration.KEYBOARD_QWERTY && hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO

/**
 * The app list, over all of home: a search field and settings at the top, the apps below. Empty query:
 * Linux and Android apps to browse. Typing: a row that asks the agent, then matches best first.
 * The field takes the keyboard only when it's tapped.
 */
@Composable
internal fun AppPanel(
    runtime: Runtime,
    state: LauncherState,
    search: BarSearch,
    /** What Enter acts on: an index into the search hits, or -1 for the agent. */
    highlighted: Int,
    handlers: AppHandlers,
    onAsk: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Cheap when nothing changed: the list is only replaced if the .desktop entries differ.
    LaunchedEffect(Unit) { runtime.refreshApps() }
    val linuxState by runtime.bootstrap.state.collectAsState()
    val query = state.query
    val listState = rememberLazyListState()
    // New results start at the top, where the best match is.
    LaunchedEffect(query.isBlank(), search) { listState.scrollToItem(0) }

    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val configuration = LocalConfiguration.current
    LaunchedEffect(Unit) {
        if (focusesSearchOnOpen(configuration.keyboard, configuration.hardKeyboardHidden)) focus.requestFocus()
    }
    // Closed: the keyboard goes with the field, and the next opening starts from the whole list.
    DisposableEffect(state) {
        onDispose {
            keyboard?.hide()
            state.query = ""
        }
    }

    /** Enter: the highlighted app, or the agent. */
    fun submit() {
        val hit = search.hits.getOrNull(highlighted)
        if (hit != null) handlers.launch(hit) else if (query.isNotBlank()) onAsk()
    }

    /** Moves the highlight down the results (away from the field) or back up; the ask row sits highest. */
    fun pick(step: Int) {
        if (query.isBlank()) return
        state.picked = (highlighted + step).coerceIn(-1, search.hits.lastIndex)
    }

    val screenHeight = with(LocalDensity.current) { configuration.screenHeightDp.dp.toPx() }
    // Pulling down from the top of the list drags the panel closed, like stock drawers.
    val pullToClose =
        remember(state, screenHeight) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // Half-closed and scrolling back up: reopen before scrolling the list.
                    if (source != NestedScrollSource.UserInput || available.y >= 0f || state.drawer.value >= 1f) return Offset.Zero
                    state.dragBy(-available.y / screenHeight)
                    return Offset(0f, available.y)
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                    state.dragBy(-available.y / screenHeight)
                    return Offset(0f, available.y)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (state.drawer.value >= 1f) return Velocity.Zero
                    state.settle(available.y)
                    return available
                }
            }
        }

    // Full width: a wide screen fits more icons in each row rather than leaving the sides empty.
    Column(modifier.fillMaxSize()) {
        val owns = Pack.ownsPage
        val u = LocalConfiguration.current.run { prototypeUnit(screenWidthDp.toFloat(), screenHeightDp.toFloat()) }
        // The search field and settings: over the list in Console, and under it where the look has the prototype's drawer.
        val searchRow: @Composable () -> Unit = {
            Row(
                Modifier.fillMaxWidth().padding(if (owns) PaddingValues(start = (20 * u).dp, end = (20 * u).dp, top = 8.dp, bottom = (16 * u).dp) else PaddingValues(top = 12.dp, start = 12.dp, end = 12.dp, bottom = 8.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchField(
                    query,
                    onChange = { state.query = it },
                    onGo = ::submit,
                    placeholder = "search apps",
                    scrap = owns,
                    modifier =
                        Modifier
                            .weight(1f)
                            .focusRequester(focus)
                            // A hardware Enter submits. Tab and the arrows move the highlight.
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when {
                                    e.key == Key.Enter -> submit()
                                    e.key == Key.Tab -> pick(if (e.isShiftPressed) -1 else 1)
                                    e.key == Key.DirectionDown -> pick(1)
                                    e.key == Key.DirectionUp -> pick(-1)
                                    else -> return@onPreviewKeyEvent false
                                }
                                true
                            },
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(Pack.shape(22.dp))
                        .background(Pack.surface)
                        .clickable(onClick = onSettings),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(Icons.Gear, "settings", Modifier.size(22.dp), colorFilter = Pack.tinted())
                    // Updates are waiting: settings › system updates lists them.
                    if ((runtime.updates.pending?.count ?: 0) > 0) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(8.dp)
                                .background(Pack.red, CircleShape),
                        )
                    }
                }
            }
        }
        if (owns) {
            // The prototype's caption over the list, with its way back down.
            Row(
                Modifier.fillMaxWidth().padding(start = (20 * u).dp, end = (20 * u).dp, top = (12 * u).dp, bottom = (4 * u).dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("all apps", color = Pack.pageMuted)
                BasicText(Pack.caption("close ↓"), Modifier.clickable { state.close() }.padding(top = 12.dp, bottom = 4.dp), style = Pack.mono(color = Pack.pageMuted))
            }
        } else {
            searchRow()
        }
        // Only as tall as its rows, so taps below a short list reach the scrim behind and close the panel.
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (owns) (14 * u).dp else 12.dp)) {
            if (query.isNotBlank()) AskRow(runtime, linuxState == LinuxState.Installed, query, highlighted == -1, onAsk)
            // Clipped, so rows scrolled up or stretched by an overscroll stop short of the search field.
            BoxWithConstraints(Modifier.weight(1f, fill = false).clipToBounds()) {
                // The prototype has four across closed and eight open: a column is 85 of its units.
                val columns = (maxWidth / if (owns) (85 * u).dp else 76.dp).toInt().coerceAtLeast(1)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().nestedScroll(pullToClose),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
                ) {
                    fun row(
                        key: String,
                        cells: List<AppHit>,
                        selected: Int = -1,
                    ) = item(key = key) {
                        Row(Modifier.fillMaxWidth()) {
                            cells.forEachIndexed { i, hit -> Box(Modifier.weight(1f)) { HitCell(runtime, hit, handlers, selected = i == selected) } }
                            repeat(columns - cells.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }

                    fun section(
                        title: String,
                        apps: List<AppHit>,
                    ) {
                        if (apps.isEmpty()) return
                        item(key = "h:$title") { SectionLabel(title, Modifier.padding(start = 8.dp), color = Pack.pageMuted) }
                        apps.chunked(columns).forEachIndexed { r, cells -> row("$title:$r", cells) }
                    }

                    if (query.isNotBlank()) {
                        search.hits.chunked(columns).forEachIndexed { r, cells -> row("q:$r", cells, highlighted - r * columns) }
                    } else {
                        if (linuxState == LinuxState.Installed) {
                            section("linux", runtime.linuxApps.map { AppHit.Linux(it) })
                        } else {
                            // Linux apps would be listed here; without Linux, one quiet offer instead.
                            item(key = "linux-offer") {
                                Row(Modifier.padding(top = 12.dp, bottom = 4.dp, start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    BasicText("linux apps", Modifier.weight(1f), style = Pack.body(color = Pack.muted))
                                    BracketAction(if (linuxState is LinuxState.Installing) "installing…" else "install linux", Pack.accent) {
                                        state.closeNow()
                                        state.linuxOpen = true
                                    }
                                }
                            }
                        }
                        section("android", runtime.androidApps.apps.map { AppHit.Android(it) })
                    }
                }
            }
        }
        if (owns) searchRow()
    }
}

/** The one line under the search field that takes what's typed to the agent: it opens the Oma chat with it, not sent. */
@Composable
private fun AskRow(
    runtime: Runtime,
    linux: Boolean,
    query: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val agent = runtime.agents.default
    val label =
        when {
            !linux -> "ask an agent · install linux"
            agent == null -> "ask an agent · install one"
            else -> "ask ${agent.bin}: " + query.trim().replace('\n', ' ')
        }
    Row(
        Modifier
            .padding(bottom = 6.dp)
            .fillMaxWidth()
            .then(if (selected) Modifier.panel() else Modifier.clip(Pack.shape()))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("⏎ ", style = Pack.body(color = if (selected) Pack.accent else Pack.muted))
        BasicText(
            label,
            style = Pack.body(color = if (selected) Pack.foreground else Pack.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SearchField(
    query: String,
    onChange: (String) -> Unit,
    onGo: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    /** Drawn as one of the look's scraps, as the prototype's search field is, instead of the plain rounded field. */
    scrap: Boolean = false,
) {
    val paper = Pack.look.pack.scrap.surface
    val style = Pack.body(Pack.size.item, if (scrap) paper.ink.toColor() else Pack.foreground)
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(Pack.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (scrap) Modifier.surface(paper, focused = false) else Modifier.clip(Pack.shape(24.dp)).background(Pack.surface))
                .padding(horizontal = 18.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box {
                if (query.isEmpty()) BasicText(placeholder, style = style.copy(color = if (scrap) Pack.scrapMuted else Pack.muted))
                inner()
            }
        },
    )
}

/**
 * One drawer entry: a 52dp icon over a label two lines tall. Linux apps without an icon get initials.
 * Long-press opens a popup of [actions] above the icon, like stock launchers.
 */
@Composable
private fun AppCell(
    label: String,
    icon: ImageBitmap?,
    /** The icon's silhouette, shown instead in the monochrome icon style. */
    mask: ImageBitmap?,
    onClick: () -> Unit,
    /** Built when the menu opens, as in [StripCell]. */
    actions: () -> List<Pair<String, () -> Unit>>,
    /** Built-in apps (Files) draw a theme-colored glyph instead of a bitmap. */
    glyph: ImageVector? = null,
    /** What Enter in the search field would open: outlined in the accent color. */
    selected: Boolean = false,
    /** As in [AppHit.spoken]. */
    spoken: String = label,
    /** As in [AppHit.paused]: drawn dimmed, like stock launchers. */
    paused: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (paused) PausedAlpha else 1f)
            .clip(Pack.shape(16.dp))
            .then(if (selected) Modifier.framed(Pack.mark, Pack.accent) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            ).padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val mono = Pack.monoIcons
        // Badges are circles when rounded, squares with Omarchy's sharp edges.
        if (glyph != null) {
            Box(
                Modifier.iconBadge(52.dp, fill = if (mono) Pack.badge else Pack.accent),
                contentAlignment = Alignment.Center,
            ) {
                Image(glyph, label, Modifier.size(26.dp), colorFilter = Pack.tinted(if (mono) Pack.tint else Pack.background))
            }
        } else if (mono && mask != null) {
            Box(Modifier.iconBadge(52.dp), contentAlignment = Alignment.Center) {
                Image(mask, spoken, Modifier.size(36.dp), colorFilter = Pack.tinted())
            }
        } else if (!mono && icon != null) {
            AppIcon(icon, spoken, Modifier.size(52.dp), mono = false)
        } else {
            Box(Modifier.iconBadge(52.dp), contentAlignment = Alignment.Center) {
                BasicText(Pack.placeholderText(label), style = Pack.placeholder(Pack.size.title))
            }
        }
        Spacer(Modifier.height(6.dp))
        BasicText(
            label,
            // The list stands on the look's paper where it has one: names are in the page's ink, as the prototype's are.
            style = Pack.body(Pack.size.small, if (Pack.ownsPage) Pack.onPage else Pack.foreground).copy(textAlign = TextAlign.Center),
            // Always two lines tall, so a row keeps its height whichever apps fill it while typing.
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (menuOpen) {
            AppActions(actions(), onDismiss = { menuOpen = false })
        }
    }
}

/** The long-press popup: sits above the cell (below it when there's no room), closes on outside tap. */
@Composable
internal fun AppActions(
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position =
        remember(gap) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                    val above = anchorBounds.top - popupContentSize.height - gap
                    val y = if (above >= 0) above else anchorBounds.bottom + gap
                    return IntOffset(x, y)
                }
            }
        }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        MenuItems(actions, onDismiss, minWidth = 160.dp, itemPadding = 12.dp, fontSize = Pack.size.item)
    }
}
