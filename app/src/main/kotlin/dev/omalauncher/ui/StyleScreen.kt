package dev.omalauncher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.linux.TerminalFont
import dev.omalauncher.linux.TerminalText
import dev.omalauncher.theme.OmarchyTheme
import dev.omalauncher.theme.ThemeDirs
import dev.omalauncher.theme.ThemeDownload
import dev.omalauncher.theme.ThemeStore
import dev.omalauncher.theme.pack.ConsolePack
import dev.omalauncher.theme.pack.CornerStyle
import dev.omalauncher.theme.pack.IconTreatment
import dev.omalauncher.theme.pack.PageKind
import dev.omalauncher.theme.pack.ThemePack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The look screen: which look the launcher is drawn from, first and largest; then only what still changes
 * something under that look (see [styleChoices]); and, with Linux, the terminal's font and text size.
 * Omarchy's themes and their backgrounds are Console's alone, and are offered only while Console is the look.
 */
@Composable
fun StyleScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
    /** Opens the community themes over this screen. */
    onBrowse: () -> Unit,
) {
    val store = runtime.themes
    val scope = rememberCoroutineScope()
    RescanThemes(store)
    var installOpen by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<OmarchyTheme?>(null) }
    val linux by runtime.bootstrap.state.collectAsState()
    OverlayPanel("look", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LookPicker(store, Modifier.padding(top = 4.dp))
            // The look being drawn, which is Console when the picked one couldn't be used.
            val choices = styleChoices(ActivePack.pack)
            if (StyleChoice.Theme in choices) {
                ConsoleOnly()
                ThemeSection(
                    store,
                    onBrowse = onBrowse,
                    onInstall = {
                        store.clearInstallError()
                        installOpen = true
                    },
                    onRemove = { removing = it },
                )
            }
            if (StyleChoice.Background in choices) store.current?.let { BackgroundSection(store, it) }
            // These don't depend on a theme, so they're offered before themes are downloaded too.
            Heading("home screen")
            if (StyleChoice.Icons in choices) {
                StyleOption("icons", listOf("monochrome", "colored"), if (LauncherStyle.monochromeIcons) 0 else 1) { store.setMonochromeIcons(it == 0) }
            }
            if (StyleChoice.HomeApps in choices) {
                StyleOption("home apps", listOf("boxed", "icon only"), if (LauncherStyle.bareAppIcons) 1 else 0) { store.setBareAppIcons(it == 1) }
            }
            StyleOption("lock screen", listOf("as it is", "look's paper"), if (LauncherStyle.lockFollowsLook) 1 else 0) { store.setLockFollowsLook(it == 1) }
            if (StyleChoice.RecentApps in choices) {
                StyleOption("recent apps", listOf("shown", "hidden"), if (LauncherStyle.recentApps) 0 else 1) { store.setRecentApps(it == 0) }
            }
            if (StyleChoice.Corners in choices) {
                StyleOption("corners", Corners.entries.map { it.label }, LauncherStyle.corners.ordinal) { store.setCorners(Corners.entries[it]) }
            }
            if (linux == LinuxState.Installed) TerminalSection(runtime.terminalFont)
        }
        if (installOpen) {
            TextDialog(
                "install theme",
                "",
                "install",
                hint = "A theme's GitHub repo. It replaces an installed theme of the same name.",
                onDismiss = { installOpen = false },
            ) { url ->
                installOpen = false
                scope.launch { store.install(url) }
            }
        }
        removing?.let { theme ->
            val builtIn = theme.dirs.size > 1
            ConfirmDialog(
                "remove ${theme.title.lowercase()}?",
                if (builtIn) "Your changes to it go; Omarchy's version stays." else "It's deleted from the phone and from Linux.",
                "remove",
                onDismiss = { removing = null },
            ) {
                removing = null
                scope.launch { store.remove(theme) }
            }
        }
    }
}

/** One thing the look screen can offer under the looks themselves. */
internal enum class StyleChoice {
    /** An Omarchy theme: Console's colors. */
    Theme,

    /** The theme's background: Console's wallpaper. */
    Background,

    /** App icons as silhouettes or in their own colors. */
    Icons,

    /** Home's apps framed or bare. */
    HomeApps,

    /** The strip of recent apps over the dock. */
    RecentApps,

    /** How frames round their corners. */
    Corners,
}

/**
 * What is worth offering while the launcher is drawn from [pack]: only what would change something there.
 * A look decides much of this itself. Omarchy's themes and backgrounds color and paper Console alone; a look
 * that treats icons its own way ignores the monochrome switch; one that draws its own page has the
 * prototype's dock and no recent apps strip; and one with a corner style of its own ignores the user's.
 * Framed or bare apps on home is the user's choice in every look.
 */
internal fun styleChoices(pack: ThemePack): Set<StyleChoice> =
    buildSet {
        if (pack.id == ConsolePack.ID) {
            add(StyleChoice.Theme)
            add(StyleChoice.Background)
        }
        if (pack.icons.treatment == IconTreatment.User) add(StyleChoice.Icons)
        add(StyleChoice.HomeApps)
        if (pack.page.kind == PageKind.System) add(StyleChoice.RecentApps)
        if (pack.shape.corners == CornerStyle.User) add(StyleChoice.Corners)
    }

/** Says plainly, over the theme and its background, that they are Console's and no other look's. */
@Composable
private fun ConsoleOnly() {
    Heading("for console only")
    BasicText(
        "Omarchy's themes color Console and give it its wallpaper. Every other look brings its own colors and paper.",
        style = Pack.body(Pack.size.small, Pack.muted),
    )
}

/** The installed themes in a row, starting at the current one; browse has Omarchy's whole catalog. */
@Composable
private fun ThemeSection(
    store: ThemeStore,
    onBrowse: () -> Unit,
    onInstall: () -> Unit,
    onRemove: (OmarchyTheme) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val current = store.current
    Heading("theme") {
        BracketAction("browse", Palette.accent, onBrowse)
        BracketAction("install", Palette.accent, onInstall)
    }
    InstallStatus(store)
    if (!store.complete) {
        DownloadPrompt(store.download, partial = store.themes.isNotEmpty()) { scope.launch { store.ensureRelease() } }
        Spacer(Modifier.height(8.dp))
    }
    if (store.themes.isNotEmpty()) {
        val start = store.themes.indexOfFirst { it.name == current?.name }.coerceAtLeast(0)
        LazyRow(
            // Scrolls under the panel's padding to its frame, starting aligned with the heading.
            Modifier.bleed(PanelPadding),
            state = rememberLazyListState(start),
            contentPadding = PaddingValues(horizontal = PanelPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(store.themes, key = { it.name }) { theme ->
                ThemeCard(theme, selected = theme.name == current?.name, Modifier.width(160.dp)) { scope.launch { store.apply(theme) } }
            }
        }
    }
    if (current?.own != null) {
        val source = remember(current) { ThemeDirs.sourceOf(current) }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                source?.label ?: "your own theme",
                Modifier.weight(1f),
                style = Mono.copy(color = Palette.muted),
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (source != null) BracketAction("update", Palette.accent) { scope.launch { store.update(current) } }
            BracketAction("remove", Palette.red) { onRemove(current) }
        }
    }
}

/** [theme]'s backgrounds, one of the user's own images, and handing it all to Android. */
@Composable
private fun BackgroundSection(
    store: ThemeStore,
    theme: OmarchyTheme,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Keyed on the background too, so an image just added shows up.
    val backgrounds = remember(theme, store.background) { theme.backgrounds() }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { store.addBackground(uri) }
    }
    Heading("background")
    LazyRow(Modifier.bleed(PanelPadding), contentPadding = PaddingValues(horizontal = PanelPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(backgrounds, key = { it.path }) { file ->
            Thumbnail(
                file,
                selected = file == store.background,
                modifier = Modifier.width(120.dp).aspectRatio(16f / 10f),
                onClick = { scope.launch { store.setBackground(file) } },
            )
        }
        // Omarchy's Install > Style > Background, with Android's image picker instead of a folder.
        item(key = "add") {
            Box(
                Modifier
                    .width(120.dp)
                    .aspectRatio(16f / 10f)
                    .frame(2.dp, Palette.border, Palette.surface, radius = 12.dp)
                    .clickable { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText("[ + image ]", style = Mono.copy(color = Palette.accent))
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            // Some phones (Android Go) have no live wallpaper picker.
            .clickable(enabled = !store.liveWallpaperActive) { runCatching { context.startActivity(store.liveWallpaperIntent()) } }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (store.liveWallpaperActive) {
            // SystemUI defers live-wallpaper color changes until the screen turns off.
            BasicText(
                "✓ android wallpaper & system colors follow the theme (system colors update after the screen turns off)",
                style = Mono.copy(color = Palette.green),
            )
        } else {
            BasicText("[ use as android wallpaper + system colors ]", style = Mono.copy(color = Palette.accent))
        }
    }
}

/** The terminals' font and the size they open at, on top of Android's font size; see [TerminalFont]. */
@Composable
private fun TerminalSection(font: TerminalFont) {
    LaunchedEffect(Unit) { font.refreshFamilies() }
    Heading("terminal")
    FontOption(font.families, font.family, font::select)
    BasicText(
        "terminals opened from now on · more with pacman, like ttf-cascadia-code",
        Modifier.padding(start = 104.dp, bottom = 4.dp),
        style = Pack.body(Pack.size.small, Pack.muted),
    )
    StyleOption("text", TerminalText.entries.map { it.label }, font.text.ordinal) { font.select(TerminalText.entries[it]) }
    // In portrait, where columns run short; landscape has plenty.
    val metrics = LocalContext.current.resources.displayMetrics
    val columns = font.columns(minOf(metrics.widthPixels, metrics.heightPixels))
    BasicText(
        "${TerminalFont.format(font.size).removeSuffix(".0")} pt · $columns columns in portrait",
        Modifier.padding(start = 104.dp, top = 2.dp),
        style = Pack.body(Pack.size.small, Pack.muted),
    )
}

/** A section's title, set apart from the muted labels of the options under it by the accent color, as the weather screen sets its sections apart; [actions] go at the far end. */
@Composable
private fun Heading(
    text: String,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(Pack.caption(text), Modifier.weight(1f), style = Pack.mono(color = Pack.accent), softWrap = false)
        actions()
    }
}

/** Font names run long: each is a chip of its own, and they wrap rather than scroll out of sight. */
@Composable
private fun FontOption(
    families: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        BasicText("font", Modifier.widthIn(min = 104.dp).padding(top = 8.dp, end = 8.dp), style = Pack.body(color = Pack.muted), softWrap = false)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (family in families) {
                val active = family == selected
                BasicText(
                    family,
                    Modifier
                        .framed(Pack.hair, Pack.border, if (active) Pack.accent else Pack.clear, radius = 12.dp)
                        .clickable { onSelect(family) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    style = Pack.body(color = if (active) Pack.background else Pack.foreground),
                    softWrap = false,
                )
            }
        }
    }
}

/** A labeled segmented choice: the picked option fills with the accent color. */
@Composable
private fun StyleOption(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, Modifier.widthIn(min = 104.dp).padding(end = 8.dp), style = Pack.body(color = Pack.muted), softWrap = false)
        // At a large Android font size the options may not fit: they scroll rather than wrap.
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.framed(Pack.hair, Pack.border, radius = 12.dp)) {
                options.forEachIndexed { i, option ->
                    val active = i == selected
                    BasicText(
                        option,
                        Modifier
                            .background(if (active) Pack.accent else Pack.clear)
                            .clickable { onSelect(i) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        style = Pack.body(color = if (active) Pack.background else Pack.foreground),
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadPrompt(
    state: ThemeDownload,
    /** Some themes are here already (first run fetches only the picked one). */
    partial: Boolean,
    onDownload: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(
            if (partial) {
                "Omarchy's other themes are downloading from github.com/omacom/omarchy (~70 MB)."
            } else {
                "Omarchy's themes aren't installed yet. They're downloaded from github.com/omacom/omarchy (~70 MB)."
            },
            style = Mono,
        )
        when (state) {
            is ThemeDownload.Running -> BasicText("downloading… ${state.megabytes} MB", style = Mono.copy(color = Palette.accent))
            is ThemeDownload.Failed -> {
                BasicText("failed: ${state.message}", style = Mono.copy(color = Palette.red))
                BracketAction("retry", Palette.accent, onDownload)
            }
            ThemeDownload.Idle -> BracketAction("download themes", Palette.accent, onDownload)
        }
    }
}

@Composable
private fun ThemeCard(
    theme: OmarchyTheme,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val bg = theme.colors["background"].toColor()
    Column(
        modifier
            .frame(2.dp, if (selected) Palette.accent else Palette.border, bg, radius = 12.dp)
            .clickable(onClick = onClick),
    ) {
        Thumbnail(theme.preview, selected = false, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f), onClick = onClick)
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(theme.title, Modifier.weight(1f), style = Mono.copy(color = theme.colors["foreground"].toColor(), fontSize = TextSize.Small), maxLines = 1)
            for (key in listOf("accent", "red", "green", "yellow")) {
                Box(Modifier.padding(start = 2.dp).size(8.dp).background(theme.colors[key].toColor()))
            }
        }
    }
}

@Composable
private fun Thumbnail(
    file: File,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val image by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { decodeSampled(file, 480, 0)?.asImageBitmap() }
    }
    Box(
        modifier
            .frame(2.dp, if (selected) Palette.accent else Color.Transparent, Palette.surface, radius = 12.dp)
            .clickable(onClick = onClick),
    ) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

private fun String?.toColor(): Color = hexColor(this) ?: Color.Gray
