package dev.omalauncher.theme

import dev.omalauncher.theme.pack.FONT_WEIGHTS
import dev.omalauncher.theme.pack.fontHasVariations
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.omalauncher.LauncherData
import dev.omalauncher.linux.Proot
import dev.omalauncher.theme.pack.AssetPackSource
import dev.omalauncher.theme.pack.AssetType
import dev.omalauncher.theme.pack.ConsolePack
import dev.omalauncher.theme.pack.PackOption
import dev.omalauncher.theme.pack.PackPick
import dev.omalauncher.theme.pack.PackRegistry
import dev.omalauncher.theme.pack.PackSource
import dev.omalauncher.theme.pack.readCapped
import dev.omalauncher.theme.pack.PageKind
import dev.omalauncher.ui.ActivePack
import dev.omalauncher.ui.Corners
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.PackWallpapers
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.decodeForScreen
import dev.omalauncher.upgrade.appStamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CopyOnWriteArraySet

private const val TAG = "themes"

/** A theme's colors bundled with the app, for picking a theme before the full themes are downloaded. */
class BundledTheme(
    val name: String,
    val colors: Map<String, String>,
) {
    val title get() = titleOf(name)
}

/** Installing a theme from its repo; see [ThemeStore.install]. */
sealed interface ThemeInstall {
    data object Idle : ThemeInstall

    data class Running(
        val name: String,
        val megabytes: Int,
    ) : ThemeInstall

    data class Failed(
        val message: String,
    ) : ThemeInstall
}

sealed interface ThemeDownload {
    data object Idle : ThemeDownload

    data class Running(
        val megabytes: Int,
    ) : ThemeDownload

    data class Failed(
        val message: String,
    ) : ThemeDownload
}

/**
 * Omarchy themes, in the launcher's own data (see [LauncherData]); the guest
 * sees them where Omarchy keeps them (~/.local/share/omarchy/themes), and the
 * user's own in ~/.config/omarchy/themes (see [ThemeDirs]).
 * Applying a theme recolors the launcher and swaps the wallpaper and, when
 * Linux is installed, writes the guest's themed configs.
 */
class ThemeStore(
    private val context: Context,
    private val proot: Proot,
    /** Whether Linux is installed; without it, nothing is written into the guest. */
    private val isLinux: () -> Boolean,
    /** Called when the guest configs depending on the theme changed; see [managed]. */
    private val onConfigChanged: () -> Unit,
) {
    private val release = OmarchyRelease(LauncherData.omarchyDir(context))
    private val dirs = themeDirs(context)
    private val guest = GuestThemeConfig(proot, release.dir)
    private val prefs = prefs(context)

    var themes by mutableStateOf<List<OmarchyTheme>>(emptyList())
        private set
    var current by mutableStateOf<OmarchyTheme?>(null)
        private set
    var background by mutableStateOf<File?>(null)
        private set
    var wallpaper by mutableStateOf<ImageBitmap?>(null)
        private set
    var download by mutableStateOf<ThemeDownload>(ThemeDownload.Idle)
        private set

    /** Every Omarchy theme's colors, from the app's assets (see scripts/fetch-theme-colors.sh). Read off the main thread. */
    val bundled: List<BundledTheme> by lazy {
        context.assets.list("themes").orEmpty().sorted().map { file ->
            val text = context.assets.open("themes/$file").bufferedReader().use { it.readText() }
            BundledTheme(file.removeSuffix(".toml"), ThemeColors.resolve(ThemeColors.parse(text)))
        }
    }

    /**
     * The themes to pick from: [bundled], with the colors of the user's
     * override where there is one, then the user's own themes.
     */
    val choices: List<BundledTheme> by derivedStateOf {
        val own = themes.filter { it.own != null }.associateBy { it.name }
        bundled.map { b -> own[b.name]?.let { BundledTheme(it.name, it.colors) } ?: b } +
            own.values.filter { t -> bundled.none { it.name == t.name } }.map { BundledTheme(it.name, it.colors) }
    }

    /** The theme picked, applied or not; its full version is applied once downloaded. */
    var picked by mutableStateOf(prefs.getString(THEME, null) ?: DEFAULT_THEME)
        private set

    /** Recolors the launcher right away and remembers the choice until the theme itself is installed. */
    fun pick(theme: BundledTheme) {
        picked = theme.name
        Palette.apply(theme.colors)
        prefs.edit().putString(THEME, theme.name).remove(BACKGROUND).apply()
    }

    /** Applies the picked theme once the themes are on disk: wallpaper, launcher colors and guest configs. */
    suspend fun applyPicked() {
        themes.firstOrNull { it.name == picked }?.let { apply(it) }
    }

    /**
     * The launcher's style, and the picked theme's bundled colors until the
     * themes are loaded (or downloaded, on a fresh install). Reads one small
     * asset, so it's fine before the first frame.
     */
    fun loadStyle() {
        // A user's own theme has no bundled colors.
        runCatching { ThemeColors.resolve(ThemeColors.parse(context.assets.open("themes/$picked.toml").bufferedReader().use { it.readText() })) }
            .recoverCatching { checkNotNull(dirs.load(picked)).colors }
            .onSuccess { Palette.apply(it) }
        LauncherStyle.monochromeIcons = prefs.getBoolean(MONO_ICONS, false)
        LauncherStyle.bareAppIcons = prefs.getBoolean(BARE_APP_ICONS, false)
        LauncherStyle.recentApps = prefs.getBoolean(RECENT_APPS, true)
        LauncherStyle.lockFollowsLook = prefs.getBoolean(LOCK_FOLLOWS, false)
        LauncherStyle.corners = Corners.of(prefs.getString(CORNERS, null))
        usePack(PackRegistry.choose(prefs.getString(PACK, DEFAULT_PACK), ::packSource))
    }

    /** The theme packs shipped in the app's assets, by folder name. Console is built in and isn't one of them. */
    private val packIds: List<String> by lazy { runCatching { context.assets.list(PACKS).orEmpty().toList() }.getOrDefault(emptyList()) }

    /** The looks there are, by id, Console first. */
    val lookIds: List<String> get() = listOf(ConsolePack.ID) + packIds.filter { it != ConsolePack.ID }

    /** The id of the look that is picked. */
    val pickedPack: String get() = prefs.getString(PACK, DEFAULT_PACK) ?: DEFAULT_PACK

    /** Where the pack [id] is read from, or null when there is none. Only assets so far. */
    private fun packSource(id: String): PackSource? = if (id != ConsolePack.ID && id in packIds) AssetPackSource(context.assets, "$PACKS/$id") else null

    /** Tells agents on every machine which look is drawn (LookReport): the guest's ~/.cache/soma/look.json. */
    private fun reportLook(pick: PackPick) {
        scope.launch(Dispatchers.IO) {
            val look = if (pick.isConsole) ConsolePack.ID else pick.id
            val toml = runCatching { context.assets.open("$PACKS/$look/pack.toml").use { it.readBytes() } }.getOrNull()
            val name = pick.load?.takeIf { !pick.isConsole }?.pack?.name ?: look
            @Suppress("DEPRECATION")
            val build = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong() }.getOrDefault(0L)
            LookReport.write(context.filesDir, LookReport.json(look, name, toml, build, System.currentTimeMillis()))
        }
    }

    /** The looks to pick from, Console first, each with what loading it came to. Reads every pack's manifest. */
    suspend fun packOptions(): List<PackOption> = withContext(Dispatchers.IO) { PackRegistry.options(packIds, ::packSource) }

    /**
     * Draws the launcher from the pack [id] and remembers it. The Omarchy theme stays picked: Console
     * shows it, and any other pack draws in its own colors until Console is picked again. A pack that
     * can't be loaded leaves Console drawing, and the picker says why.
     */
    fun pickPack(id: String) {
        prefs.edit().putString(PACK, id).apply()
        usePack(PackRegistry.choose(id, ::packSource))
        // At once in the new look's colors; again, with its own typefaces, once those are read.
        LookWidgets.refresh(context)
    }

    private fun usePack(pick: PackPick) {
        ActivePack.choose(pick)
        reportLook(pick)
        val load = pick.load?.takeIf { !pick.isConsole }
        // Console (also what draws when a pack can't be loaded) draws in its own fonts from the store (assets/fonts).
        val console = load == null
        val source = (if (console) AssetPackSource(context.assets, "fonts") else packSource(pick.id)) ?: return
        val id = if (console) ConsolePack.ID else pick.id
        // Fonts and wallpapers arrive after the first frame; until then each font is its system family and the page its paper color.
        scope.launch(Dispatchers.IO) {
            fun fonts(): Map<String, FontFamily> {
                if (console) {
                    // Every voice is JetBrains Mono; the body has Schibsted Grotesk behind it, and Console's own
                    // monospace (Palette.mono, most of its text) is drawn in that, the fuller stack.
                    val mono = packFont(source, id, ConsolePack.FONT) ?: return emptyMap()
                    val body = packFont(source, id, ConsolePack.FONT, listOf(ConsolePack.BODY_FALLBACK)) ?: mono
                    return mapOf("display:" to mono, "hand:" to mono, "mono:" to mono, "body:" to body)
                }
                val type = load!!.pack.type
                return listOf(type.display, type.body, type.hand, type.mono)
                    .map { it.file }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .mapNotNull { path -> packFont(source, id, path)?.let { path to it } }
                    .toMap()
            }
            val fonts = fonts()
            val wallpapers = load?.pack?.page?.let { page -> PackWallpapers(packImage(source, page.wallpaperCover), packImage(source, page.wallpaperInner)) }
            withContext(Dispatchers.Main) {
                // Another pack may have been picked meanwhile.
                if (ActivePack.pick === pick) {
                    ActivePack.fonts = fonts
                    dev.omalauncher.ui.PlatformFonts.consoleMono = if (console) fonts["body:"] else null
                    if (wallpapers != null) {
                        ActivePack.wallpapers = wallpapers
                        syncLockPaper()
                    }
                    LookWidgets.refresh(context)
                }
            }
            // Emoji and CJK from Soma Canvas, once per device (Boss, 2026-10-29): then the voices again, with them behind.
            if (fetchStoreFonts()) {
                val again = fonts()
                withContext(Dispatchers.Main) {
                    if (ActivePack.pick === pick) {
                        ActivePack.fonts = again
                        if (console) dev.omalauncher.ui.PlatformFonts.consoleMono = again["body:"]
                        LookWidgets.refresh(context)
                    }
                }
            }
        }
    }

    private val storePins by lazy { runCatching { StoreFonts.pins(context.assets.open(StoreFonts.PINS).bufferedReader().use { it.readText() }) }.getOrNull() }
    private val storeDir get() = File(context.filesDir, StoreFonts.DIR)
    private val storeFetch = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Fetches the pinned emoji/CJK faces this device doesn't keep, once per process; whether any arrived. */
    private fun fetchStoreFonts(): Boolean {
        val fetched = storePins?.second.orEmpty()
        if (fetched.all { StoreFonts.cached(storeDir, it) != null } || !storeFetch.compareAndSet(false, true)) return false
        val origin = canvasOrigin() ?: return false
        return StoreFonts.fetchMissing(storeDir, origin, fetched).isNotEmpty()
    }

    /** Soma Canvas's address, as the agents' CLI has it (~/.config/soma-canvas/env in Linux). */
    private fun canvasOrigin(): String? = runCatching {
        val value = File(proot.home, ".config/soma-canvas/env").readLines().firstOrNull { it.trim().removePrefix("export ").startsWith("CANVAS_URL=") }
            ?.trim()?.removePrefix("export ")?.substringAfter('=')?.trim()?.trim { it.code == 34 || it.code == 39 }
        dev.omalauncher.oma.CanvasReceipt.origin(value)
    }.getOrNull()

    /** What every voice falls back to, in the store's order: the bundled symbols font, then the emoji and CJK kept here. */
    private fun storeFallbacks(): List<android.graphics.fonts.FontFamily> {
        val (bundled, fetched) = storePins ?: return emptyList()
        fun family(font: () -> android.graphics.fonts.Font) =
            runCatching { android.graphics.fonts.FontFamily.Builder(font()).build() }.onFailure { Log.w(TAG, "store font", it) }.getOrNull()
        return bundled.mapNotNull { pin -> pin.asset?.let { asset -> family { android.graphics.fonts.Font.Builder(context.assets, asset).build() } } } +
            fetched.mapNotNull { pin -> StoreFonts.cached(storeDir, pin)?.let { file -> family { android.graphics.fonts.Font.Builder(file).build() } } }
    }

    /** A pack's font file as a font family, read no further than a font's size limit; null if it can't be used. */
    private fun packFont(
        source: PackSource,
        id: String,
        path: String,
        /** The pack's own faces after this one, before the store's (Console's body). */
        then: List<String> = emptyList(),
    ): FontFamily? =
        runCatching {
            val bytes = readCapped(source, path, AssetType.Font.maxBytes) ?: return null
            // Typeface reads a file, so the font is copied out of the pack.
            val file = File(File(context.cacheDir, "pack-fonts/$id").apply { mkdirs() }, path.replace('/', '_'))
            file.writeBytes(bytes)
            // A variable font gives every weight its own drawing; a single face is what it is, and Android thickens it for bold.
            val variable = fontHasVariations(bytes)
            val own = if (variable) {
                val fonts = FONT_WEIGHTS.map { w -> android.graphics.fonts.Font.Builder(file).setWeight(w).setFontVariationSettings("'wght' $w").build() }
                android.graphics.fonts.FontFamily.Builder(fonts.first()).apply { fonts.drop(1).forEach(::addFont) }.build()
            } else {
                android.graphics.fonts.FontFamily.Builder(android.graphics.fonts.Font.Builder(file).build()).build()
            }
            // Then the store's fonts, as every voice's stack ends (symbols, emoji, CJK); past them, the device's own.
            val after = then.mapNotNull { next ->
                runCatching {
                    val bytes2 = readCapped(source, next, AssetType.Font.maxBytes) ?: return@runCatching null
                    val file2 = File(File(context.cacheDir, "pack-fonts/$id").apply { mkdirs() }, next.replace('/', '_')).apply { writeBytes(bytes2) }
                    android.graphics.fonts.FontFamily.Builder(android.graphics.fonts.Font.Builder(file2).build()).build()
                }.getOrNull()
            }
            val fallbacks = after + storeFallbacks()
            fun typeface(weight: Int) = Typeface.CustomFallbackBuilder(own).apply { fallbacks.forEach { addCustomFallback(it) } }
                .setStyle(android.graphics.fonts.FontStyle(weight, android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT)).build()
            // One typeface per weight, each with the whole stack behind it: Compose picks the weight asked for (a single
            // fallback typeface drew every weight at 400; lane C, 2b09d000: bold mono and hand were lost). A single face
            // is one weight that Compose thickens for bold, as before.
            if (variable) FontFamily(FONT_WEIGHTS.map { w -> StackFont(typeface(w), FontWeight(w)) })
            else FontFamily(StackFont(typeface(400), FontWeight.Normal))
        }.onFailure { Log.w(TAG, "pack $id font $path", it) }.getOrNull()

    /** A pack's wallpaper, read no further than an image's size limit and decoded no larger than the screen needs. */
    private fun packImage(
        source: PackSource,
        path: String,
    ): ImageBitmap? =
        runCatching {
            val bytes = readCapped(source, path, AssetType.Image.maxBytes) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val screen = context.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= screen) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.onFailure { Log.w(TAG, "pack wallpaper $path", it) }.getOrNull()

    fun setMonochromeIcons(on: Boolean) {
        LauncherStyle.monochromeIcons = on
        prefs.edit().putBoolean(MONO_ICONS, on).apply()
    }

    /**
     * Whether the phone's lock and home wallpapers follow the look. Turned on, the display in use gets the look's
     * paper now. Turned off, they keep what they have: the wallpapers they had before are not ours to put back.
     */
    fun setLockFollowsLook(on: Boolean) {
        LauncherStyle.lockFollowsLook = on
        prefs.edit().putBoolean(LOCK_FOLLOWS, on).remove(LOCK_DONE).apply()
        syncLockPaper()
    }

    /**
     * Gives the lock screen of the display in use the look's paper, if it is to follow the look and doesn't
     * have it yet. Called when a look is picked and whenever home shows, which is when the other display of a
     * fold first comes by. Main thread; the drawing and the setting are done off it.
     */
    fun syncLockPaper() {
        val pick = ActivePack.pick
        val pack = ActivePack.pack
        val page = pack.page
        val bounds = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        val key = LockPaper.key(pick.id, bounds.width(), bounds.height())
        // The display in use is noted every time, before anything is decided: one already in the look's paper was
        // otherwise never noted, and a fold's other display stayed unknown (seen on the Fold, 2026-10-12).
        val here = "${bounds.width()}x${bounds.height()}"
        val sizes = prefs.getStringSet(LOCK_SIZES, emptySet()).orEmpty().toSet()
        if (here !in sizes) prefs.edit().putStringSet(LOCK_SIZES, sizes + here).apply()
        val done = prefs.getStringSet(LOCK_DONE, emptySet()).orEmpty().filter { it.startsWith("${pick.id}|") }.toSet()
        if (!LockPaper.wanted(LauncherStyle.lockFollowsLook, page.kind, done, key) || key in lockSetting) return
        // A look whose paper is its own image waits for that image; it comes back here once it is read.
        val wallpapers = ActivePack.wallpapers
        if (page.kind == PageKind.Wallpaper && wallpapers.cover == null && wallpapers.inner == null) return
        lockSetting += key
        val density = context.resources.displayMetrics.density
        // A fold's other display, by the size it was last seen at: it is given the paper now too, so its lock screen
        // has the new look the next time the phone is folded or opened, not one unlock late.
        val other = LockPaper.otherDisplay(sizes + here, bounds.width(), bounds.height())
        scope.launch(Dispatchers.IO) {
            val both = WallpaperManager.FLAG_LOCK or WallpaperManager.FLAG_SYSTEM
            if (other != null) {
                // Asked for by Samsung's own mark for a display. Whether it is honored from here can't be told, so that
                // display is not counted as done: it is set again, the sure way, when home next shows on it.
                runCatching {
                    val paper = LockPaper.render(pack, wallpapers, other.width, other.height, density)
                    WallpaperManager.getInstance(context).setBitmap(paper, null, true, both or other.mark)
                    paper.recycle()
                }.onFailure { Log.w(TAG, "lock paper for the other display", it) }
            }
            val set =
                runCatching {
                    val paper = LockPaper.render(pack, wallpapers, bounds.width(), bounds.height(), density)
                    // Home's too: it shows behind recents, and the system's color palette is taken from it. Set last,
                    // so this display is right even if the request for the other one landed here.
                    WallpaperManager.getInstance(context).setBitmap(paper, null, true, both)
                    paper.recycle()
                }.onFailure { Log.w(TAG, "lock paper $key", it) }.isSuccess
            withContext(Dispatchers.Main) {
                lockSetting -= key
                if (set) prefs.edit().putStringSet(LOCK_DONE, done + key).apply()
            }
        }
    }

    /** The displays whose lock paper is being set now, so one isn't set twice at once. Main thread. */
    private val lockSetting = HashSet<String>()

    fun setBareAppIcons(on: Boolean) {
        LauncherStyle.bareAppIcons = on
        prefs.edit().putBoolean(BARE_APP_ICONS, on).apply()
    }

    fun setRecentApps(on: Boolean) {
        LauncherStyle.recentApps = on
        prefs.edit().putBoolean(RECENT_APPS, on).apply()
    }

    fun setCorners(corners: Corners) {
        LauncherStyle.corners = corners
        prefs.edit().putString(CORNERS, corners.id).apply()
    }

    /** Whether our live wallpaper is the Android wallpaper; the launcher then shows it instead of drawing its own. */
    var liveWallpaperActive by mutableStateOf(false)
        private set

    suspend fun refreshLiveWallpaper() {
        liveWallpaperActive = WallpaperManager.getInstance(context).wallpaperInfo?.packageName == context.packageName
        if (!liveWallpaperActive && wallpaper == null) wallpaper = decodeWallpaper(background)
    }

    private suspend fun decodeWallpaper(file: File?) = file?.let { withContext(Dispatchers.IO) { decodeForScreen(it, context)?.asImageBitmap() } }

    /** Opens Android's live wallpaper preview with our wallpaper preselected; apps can't set one silently. */
    fun liveWallpaperIntent(): Intent =
        Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, OmarchyWallpaperService::class.java))

    /** Whether the Omarchy files on disk are the release this app pins. */
    fun hasCurrentRelease() = release.isCurrent()

    /** Whether [load] has finished; until then it may still replace the wallpaper. */
    var loaded by mutableStateOf(false)
        private set

    /** Restores the saved theme; guest configs are already on disk from when it was applied. */
    suspend fun load() {
        try {
            restoreSaved()
        } finally {
            loaded = true
        }
    }

    private suspend fun restoreSaved() {
        themes = withContext(Dispatchers.IO) { scan() }
        val theme = themes.firstOrNull { it.name == prefs.getString(THEME, null) } ?: return
        current = theme
        Palette.apply(theme.colors)
        // Templates and how we render them may have changed with an app update.
        if (isLinux() && prefs.getString(RENDERED_BY, null) != appStamp(context)) {
            withContext(Dispatchers.IO) { guest.render(theme) }
            prefs.edit().putString(RENDERED_BY, appStamp(context)).apply()
        }
        val bg = theme.backgrounds().let { list -> list.firstOrNull { it.name == prefs.getString(BACKGROUND, null) } ?: list.firstOrNull() }
        // On reload a different background may replace one already showing; it stays up until the new one is decoded.
        val stale = wallpaper != null && bg != background
        background = bg
        // Decodes the wallpaper on first load, unless ours is the live one.
        refreshLiveWallpaper()
        if (stale && !liveWallpaperActive) wallpaper = decodeWallpaper(bg)
    }

    /** After a new release was downloaded: its themes, templates and configs. */
    suspend fun reload() {
        prefs.edit().remove(RENDERED_BY).apply()
        load()
        onConfigChanged()
    }

    /** Picks up themes the user added or removed in Linux; the theme menus call it when they open. */
    suspend fun rescan() {
        themes = withContext(Dispatchers.IO) { scan() }
    }

    private fun scan(): List<OmarchyTheme> = dirs.scan { name, e -> Log.w(TAG, "bad theme $name", e) }

    /** One download at a time; a second caller waits for the first one's result. */
    private val downloading = Mutex()

    /** Whether every theme is on disk; first run fetches only the picked one (see [fetchPicked]). */
    var complete by mutableStateOf(release.isCurrent())
        private set

    /** Each theme's first background at [OMARCHY_REF], bundled by scripts/fetch-theme-colors.sh. */
    private val firstBackgrounds: Map<String, String> by lazy {
        runCatching { context.assets.open("theme-backgrounds.txt").bufferedReader().use { it.readLines() } }
            .getOrDefault(emptyList())
            .mapNotNull { line -> line.split(' ', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Single-theme fetches by theme name; each resolves to why it failed, or null. Main thread only. */
    private val prefetches = HashMap<String, Deferred<String?>>()

    /**
     * Starts fetching [name]'s colors and first wallpaper (see [fetchPicked]),
     * as soon as it's selected, so start usually finds them on disk. A failed
     * fetch is tried again on the next call.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun prefetch(name: String): Deferred<String?> {
        prefetches[name]?.let { if (!it.isCompleted || it.getCompleted() == null) return it }
        return scope.async(Dispatchers.IO) {
            if (complete || dirs.hasColors(name)) return@async null
            try {
                release.downloadTheme(name, firstBackgrounds[name])
                null
            } catch (e: Exception) {
                Log.e(TAG, "theme $name failed", e)
                e.message ?: e.toString()
            }
        }.also { prefetches[name] = it }
    }

    /**
     * First run: the picked theme's colors and first wallpaper alone, a few
     * MB, so the home screen opens fully themed; [ensureRelease] fetches the
     * rest in the background. Returns why it failed, or null once applied.
     */
    suspend fun fetchPicked(): String? {
        prefetch(picked).await()?.let { return it }
        downloading.withLock {
            themes = withContext(Dispatchers.IO) { scan() }
            applyPicked()
        }
        return null
    }

    /**
     * Downloads the pinned Omarchy release if it's missing or outdated (an
     * app update can move [OMARCHY_REF]), then applies the picked theme, or
     * reloads the current one from the new files. Returns whether the themes are current.
     */
    suspend fun ensureRelease(): Boolean =
        downloading.withLock {
            if (hasCurrentRelease()) return@withLock true
            // A single-theme fetch writing into the directory the release replaces could fail the swap.
            prefetches.values.toList().forEach { it.join() }
            if (!downloadThemes()) return@withLock false
            if (current == null) applyPicked() else reload()
            true
        }

    /** Fetches the pinned Omarchy release; see [OmarchyRelease]. Returns whether it worked. */
    private suspend fun downloadThemes(): Boolean =
        withContext(Dispatchers.IO) {
            download = ThemeDownload.Running(0)
            try {
                release.download { download = ThemeDownload.Running(it) }
                themes = scan()
                complete = true
                download = ThemeDownload.Idle
                true
            } catch (e: Exception) {
                Log.e(TAG, "download failed", e)
                download = ThemeDownload.Failed(e.message ?: e.toString())
                false
            }
        }

    /** The community themes on omarchy.org/themes, to [install] from. */
    val catalog = ThemeCatalog(context.cacheDir)

    var installing by mutableStateOf<ThemeInstall>(ThemeInstall.Idle)
        private set

    /**
     * Like omarchy-theme-install: fetches the theme in the GitHub repo at
     * [url] into the user's themes, replacing one of the same name, and
     * applies it. What it may hand to Linux is limited; see [ThemeDirs.isFromRepo].
     */
    suspend fun install(url: String) {
        if (installing is ThemeInstall.Running) return
        val repo = ThemeRepo.parse(url)
        if (repo == null) {
            installing = ThemeInstall.Failed("not a GitHub repo: ${url.trim()}")
            return
        }
        val name = repo.themeName
        installing = ThemeInstall.Running(name, 0)
        val staging = File(dirs.user.parentFile, ".theme-install")
        val done =
            withContext(Dispatchers.IO) {
                runCatching {
                    LauncherData.deleteTree(staging)
                    unpackTarball(repo.tarball, staging, { installing = ThemeInstall.Running(name, it) }, maxMegabytes = 200) { path ->
                        // What the launcher and Linux use: the top-level files and the backgrounds.
                        val depth = path.count { it == '/' }
                        (depth == 0 || depth == 1 && path.startsWith("backgrounds/")) && path != "unlock.png"
                    }
                    checkNotNull(ThemeDirs.colorsOf(staging)) { "it has no colors.toml or alacritty.toml" }
                    File(staging, ThemeDirs.SOURCE_MARKER).writeText(repo.url + "\n")
                    val target = File(dirs.user, name)
                    dirs.user.mkdirs()
                    LauncherData.deleteTree(target)
                    check(staging.renameTo(target)) { "can't move the theme into place" }
                }.onFailure { runCatching { LauncherData.deleteTree(staging) } }
            }
        done.exceptionOrNull()?.let { e ->
            Log.e(TAG, "installing $url failed", e)
            installing = ThemeInstall.Failed(if (e is FileNotFoundException) "${repo.label} isn't on GitHub" else e.message ?: e.toString())
            return
        }
        rescan()
        themes.firstOrNull { it.name == name }?.let { apply(it) }
        installing = ThemeInstall.Idle
    }

    /** Fetches [theme] again from where [install] got it. */
    suspend fun update(theme: OmarchyTheme) {
        ThemeDirs.sourceOf(theme)?.let { install(it.url) }
    }

    fun clearInstallError() {
        if (installing is ThemeInstall.Failed) installing = ThemeInstall.Idle
    }

    /**
     * Like omarchy-theme-remove: deletes the user's directory of [theme].
     * If it was applied, its built-in version takes over, or the default theme.
     */
    suspend fun remove(theme: OmarchyTheme) {
        val own = theme.own ?: return
        withContext(Dispatchers.IO) { LauncherData.deleteTree(own) }
        rescan()
        if (current?.name != theme.name) return
        val next = themes.firstOrNull { it.name == theme.name } ?: themes.firstOrNull { it.name == DEFAULT_THEME } ?: themes.firstOrNull()
        if (next != null) {
            apply(next)
        } else {
            current = null
            bundled.firstOrNull { it.name == DEFAULT_THEME }?.let(::pick)
        }
    }

    suspend fun apply(
        theme: OmarchyTheme,
        bg: File? = theme.backgrounds().firstOrNull(),
    ) {
        current = theme
        picked = theme.name
        Palette.apply(theme.colors)
        prefs.edit().putString(THEME, theme.name).apply()
        setBackground(bg)
        if (!isLinux()) return
        withContext(Dispatchers.IO) {
            // Called from screen scopes, where a failed write (storage full) would crash the launcher.
            runCatching {
                guest.render(theme)
                prefs.edit().putString(RENDERED_BY, appStamp(context)).apply()
                onConfigChanged()
                guest.recolorTerminals(theme)
            }.onFailure { Log.e(TAG, "theme ${theme.name} not written to the guest", it) }
        }
    }

    /**
     * Writes the current theme into a freshly installed guest: what [apply]
     * skipped while Linux wasn't there. The caller syncs [managed] afterwards.
     */
    suspend fun renderGuest() {
        val theme = current ?: return
        withContext(Dispatchers.IO) {
            guest.render(theme)
            background?.let(guest::linkBackground)
        }
        prefs.edit().putString(RENDERED_BY, appStamp(context)).apply()
    }

    suspend fun setBackground(file: File?) {
        background = file
        prefs.edit().putString(BACKGROUND, file?.name).apply()
        // With the live wallpaper active, the launcher is transparent and needs no copy of its own.
        wallpaper = if (liveWallpaperActive) null else decodeWallpaper(file)
        if (file != null && isLinux()) withContext(Dispatchers.IO) { guest.linkBackground(file) }
        changeListeners.forEach { it() }
    }

    /**
     * Like omarchy-theme-bg-install, then picking it: copies an image into the
     * current theme's ~/.config/omarchy/backgrounds/<theme> and shows it.
     * Formats Linux can't read (HEIC, GIF) are re-encoded as JPEG.
     */
    suspend fun addBackground(uri: Uri) {
        val theme = current ?: return
        val file = withContext(Dispatchers.IO) {
            val dir = File(dirs.backgrounds, theme.name)
            val type = context.contentResolver.getType(uri)
            val ext = when (type) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val out = File(dir, "image-${System.currentTimeMillis()}.$ext")
            runCatching {
                dir.mkdirs()
                val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
                if (type in listOf("image/png", "image/webp", "image/jpeg")) {
                    input.use { i -> out.outputStream().use { i.copyTo(it) } }
                } else {
                    val bmp = input.use(BitmapFactory::decodeStream) ?: error("can't decode $type")
                    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                }
                out
            }.onFailure {
                out.delete()
                Log.e(TAG, "background not added", it)
            }.getOrNull()
        } ?: return
        setBackground(file)
    }

    /**
     * Guest configs for Omarchy's apps, in the current theme's colors. Before
     * [load] ran, the saved theme's: leaving them out would count as removing them.
     */
    fun managed() = guest.managed(current ?: savedTheme())

    private fun savedTheme(): OmarchyTheme? {
        val name = prefs.getString(THEME, null) ?: return null
        return runCatching { dirs.load(name) }.getOrNull()
    }

    companion object {
        /** The Omarchy commit themes and configs come from. Changing it updates them on every install. */
        const val OMARCHY_REF = "8b4eae66da2938ba9559f103b18dbf85cdf28a70"

        private const val THEME = "theme"
        private const val BACKGROUND = "background"
        private const val MONO_ICONS = "mono_icons"
        private const val BARE_APP_ICONS = "bare_app_icons"
        private const val RECENT_APPS = "recent_apps"
        private const val LOCK_FOLLOWS = "lock_follows_look"
        // Renamed when what is set changed (the home wallpaper too; then scraps on the paper, and off again), so it is set again once.
        private const val LOCK_DONE = "lock_paper_done_3"
        private const val LOCK_SIZES = "lock_paper_sizes"
        private const val CORNERS = "corners"
        private const val PACK = "pack"

        /** The look until one is picked: the prototype's own (design/oma-scrapbook-prototype.html opens in Riso). Console stays in the picker. */
        private const val DEFAULT_PACK = "riso"

        /** The assets folder each shipped theme pack has a folder in. */
        private const val PACKS = "packs"
        private const val RENDERED_BY = "rendered_by"
        private const val DEFAULT_THEME = "tokyo-night"

        /** Called on the main thread after the theme or background changes (the live wallpaper listens). */
        val changeListeners = CopyOnWriteArraySet<() -> Unit>()

        private fun prefs(context: Context) = context.getSharedPreferences("themes", Context.MODE_PRIVATE)

        private fun themeDirs(context: Context) =
            ThemeDirs(
                File(LauncherData.omarchyDir(context), "themes"),
                LauncherData.userThemesDir(context),
                LauncherData.userBackgroundsDir(context),
            )

        /**
         * The saved theme colors and background, read straight from disk. The wallpaper
         * service can run without the launcher (e.g. after a reboot), so this needs no store.
         */
        fun savedSelection(context: Context): Pair<Map<String, String>, File?>? {
            val prefs = prefs(context)
            val name = prefs.getString(THEME, null) ?: return null
            val theme = runCatching { themeDirs(context).load(name) }.getOrNull() ?: return null
            val bg = prefs.getString(BACKGROUND, null)?.let { saved -> theme.backgrounds().firstOrNull { it.name == saved } }
            return theme.colors to bg
        }
    }
}

/**
 * A ready typeface (a voice's face with the store's fallbacks behind it, Typeface.CustomFallbackBuilder) as a Compose
 * font of one weight, so a FontFamily of them picks by weight and still falls back per character.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private class StackFont(
    val typeface: Typeface,
    override val weight: FontWeight,
) : androidx.compose.ui.text.font.AndroidFont(
        androidx.compose.ui.text.font.FontLoadingStrategy.Blocking,
        Loader,
        androidx.compose.ui.text.font.FontVariation.Settings(),
    ) {
    override val style = androidx.compose.ui.text.font.FontStyle.Normal

    private object Loader : androidx.compose.ui.text.font.AndroidFont.TypefaceLoader {
        override fun loadBlocking(context: Context, font: androidx.compose.ui.text.font.AndroidFont): Typeface = (font as StackFont).typeface

        override suspend fun awaitLoad(context: Context, font: androidx.compose.ui.text.font.AndroidFont): Typeface = (font as StackFont).typeface
    }
}
