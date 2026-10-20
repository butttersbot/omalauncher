package dev.omalauncher

import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.phone.HomeDoor
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.applySystemBars
import dev.omalauncher.ui.statusBarOnLight
import dev.omalauncher.ui.LauncherRoot
import dev.omalauncher.ui.LauncherState
import dev.omalauncher.ui.openOmaChat
import dev.omalauncher.widgets.layout.Hinge
import dev.omalauncher.widgets.market.Share

/** The home screen: widgets, pinned apps and the drawer; Linux windows open as their own tasks. */
class MainActivity : ComponentActivity() {
    private val runtime get() = (application as App).runtime
    private val state = LauncherState()

    /** Home as the agent's bridge reaches it, inside this process: which page shows, and a page asked for by name. */
    private val door =
        object : HomeDoor.Home {
            override val showing get() = state.currentPage

            override fun turn() {
                val page = HomeDoor.take() ?: return
                // The pager goes by position; unfolded, to the pair that page is in.
                runtime.widgets.notebook.position(page).takeIf { it >= 0 }?.let { state.requestedPage = it }
            }
        }

    /**
     * Soma's own screens other than home that are showing (the Oma chat, an approval, ...). The chat's window lets
     * home show behind it, so home stays started under it; while any of these shows, the handle's swipe is "go home",
     * as from an app (Boss, 2026-10-20: "if I have an Oma widget up in full screen, it should be like I have an app up").
     */
    private val ownScreens = mutableSetOf<android.app.Activity>()
    private val screensWatch =
        object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                if (activity !== this@MainActivity) ownScreens += activity
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                ownScreens -= activity
            }

            override fun onActivityDestroyed(activity: android.app.Activity) {
                ownScreens -= activity
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) {}

            override fun onActivityResumed(activity: android.app.Activity) {}

            override fun onActivityPaused(activity: android.app.Activity) {}

            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) {}
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        application.registerActivityLifecycleCallbacks(screensWatch)
        applySystemBars(this, Pack.look.barsOnLight(drawer = false, panel = false))
        runtime.start()
        HomeDoor.enter(door)
        // A page asked for while there was no home screen to turn.
        door.turn()
        setContent {
            LauncherRoot(runtime, state)
            // The bars' clock and icons follow what is behind them: the look's page, its app list once that is most of
            // the way up, or a full-screen panel. Read as state, so picking another look over home turns them at once.
            val barsOnLight by remember {
                derivedStateOf {
                    Pack.look.barsOnLight(drawer = state.drawer.value > 0.5f, panel = state.panelOpen || runtime.widgets.openScreen != null)
                }
            }
            // Over a manga page's hero the status bar's are light, until the app list or a panel covers it.
            val statusOnLight by remember {
                derivedStateOf {
                    statusBarOnLight(barsOnLight, state.heroUnderBar, drawer = state.drawer.value > 0.5f, panel = state.panelOpen || runtime.widgets.openScreen != null)
                }
            }
            LaunchedEffect(barsOnLight, statusOnLight) { applySystemBars(this@MainActivity, barsOnLight, statusOnLight) }
        }
        handleDebugExec(intent)
        handleSharedWidget(intent)
        watchHinge()
        // Asked on arriving at the home screen, not over the welcome screen of a fresh install,
        // and once nothing else is in front: not over the notification permission dialog that
        // opting into Linux there brings up.
        lifecycleScope.launch {
            snapshotFlow { runtime.launcherReady }.first { it }
            withResumed { requestHomeRole() }
        }
    }

    /**
     * Follows the hinge of a foldable, which home needs to tell a half-opened device from a flat one. Jetpack
     * reports it only while this window lies across it: not on the cover screen, and not on what doesn't fold.
     */
    private fun watchHinge() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity).collect { info ->
                    val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                    state.hinge =
                        when (fold?.state) {
                            FoldingFeature.State.HALF_OPENED -> Hinge.HalfOpened
                            FoldingFeature.State.FLAT -> Hinge.Flat
                            else -> null
                        }
                }
            }
        }
    }

    /** Asks once to become the default home app; Android shows its own chooser. */
    private fun requestHomeRole() {
        val roles = getSystemService(RoleManager::class.java)
        if (!roles.isRoleAvailable(RoleManager.ROLE_HOME) || roles.isRoleHeld(RoleManager.ROLE_HOME)) return
        val prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        if (prefs.getBoolean("home_role_asked", false)) return
        prefs.edit().putBoolean("home_role_asked", true).apply()
        @Suppress("DEPRECATION")
        startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), 1)
    }

    /**
     * Debug builds: `adb shell am start -n dev.omalauncher/.MainActivity --es key KEY --es exec foot`,
     * or `--es theme kanagawa` to apply a theme (scripts/collage-captures.sh).
     */
    private val debugExecSwitch = DEBUG_EXEC_SWITCH

    private fun handleDebugExec(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        // Any app can send this activity an intent, so a guest command from one is taken only while the owner has
        // left a key in the switch file in the app's own files, which only adb's run-as can write or read, and the
        // intent brings the same key: off unless he turns it on, and then open only to who he gave the key.
        val key = runCatching { java.io.File(filesDir, debugExecSwitch).readText() }.getOrNull()
        if (debugExecAllowed(key, intent.getStringExtra("key"))) intent.getStringExtra("exec")?.let(runtime::launch)
        intent.getStringExtra("theme")?.let { name ->
            lifecycleScope.launch {
                snapshotFlow { runtime.themes.loaded }.first { it }
                runtime.themes.themes.firstOrNull { it.name == name }?.let { runtime.themes.apply(it) }
                    ?: Log.w("debug", "no theme $name")
            }
        }
    }

    /**
     * A widget link (see [Share]): from the camera, a browser, or the store page's "open in Omalauncher" button,
     * which can't keep the link's fragment and passes the data as the `widget` extra instead.
     */
    private fun handleSharedWidget(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val data = intent.data
        val link =
            intent.getStringExtra("widget")
                ?: data?.takeIf { it.host == Share.HOST && it.path.orEmpty().startsWith(Share.PATH) }?.toString()
                ?: return
        state.shared = link
    }

    override fun onResume() {
        super.onResume()
        // Whether the Mac's Whisper answers, known before the next dictation rather than learned during it.
        dev.omalauncher.voice.WhisperMac.check(this)
        runtime.wm.focus(null)
        removeStaleWindowTasks()
        // Made the default home app (or no longer it) while away: only then does Android show us pinned shortcuts.
        runtime.androidApps.checkShortcutAccess()
        // The user may have set or replaced the wallpaper while we were in the background.
        lifecycleScope.launch { runtime.themes.refreshLiveWallpaper() }
        // Folded or unfolded since: the display now in use may not have the look's paper on its lock screen yet.
        runtime.themes.syncLockPaper()
        // Coming home is when phone control is next wanted: put back if something other than him switched it off.
        dev.omalauncher.phone.PhoneControlHeal.look(this)
        dev.omalauncher.phone.AdbWifiHeal.look(this)
    }

    /** Drops Recents cards whose Linux window no longer exists (app exited or process restarted). */
    private fun removeStaleWindowTasks() {
        for (task in getSystemService(ActivityManager::class.java).appTasks) {
            val base = runCatching { task.taskInfo?.baseIntent }.getOrNull() ?: continue
            // By its data rather than its class: windows start through aliases too.
            if (base.data?.let { it.scheme == "omarchy" && it.host == "window" } != true) continue
            // A window that just closed removes its own task meanwhile; finishing it again throws.
            if (runtime.wm.windowByHandle(base.data?.lastPathSegment) == null) runCatching { task.finishAndRemoveTask() }
        }
    }

    /** Pressing Home while already home closes the menu, like other launchers. */
    override fun onNewIntent(intent: Intent) {
        // Home asked for again while home already showed with nothing over it, as the gesture handle's swipe up asks for
        // it: the app list opens (Boss, 2026-10-20: the handle's swipe opens the drawer, only on home). Android pauses a
        // shown activity before it hands it a new intent, so one that was showing is STARTED here; one coming back from
        // an app was stopped, and comes home as before.
        val swipedOnHome = intent.hasCategory(Intent.CATEGORY_HOME) && lifecycle.currentState == Lifecycle.State.STARTED &&
            ownScreens.isEmpty() && state.bareHome && runtime.widgets.openScreen == null
        super.onNewIntent(intent)
        if (swipedOnHome) {
            state.open()
            handleDebugExec(intent)
            handleSharedWidget(intent)
            return
        }
        state.close()
        state.closeScreens()
        runtime.widgets.openScreen = null
        state.editing = null
        handleDebugExec(intent)
        handleSharedWidget(intent)
    }

    /** Launcher shortcuts (Super+key), then typing into a terminal tile that has the keyboard. Back stays the launcher's. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.isMetaPressed && event.action == KeyEvent.ACTION_DOWN && shortcut(event.keyCode)) return true
        if (event.keyCode != KeyEvent.KEYCODE_BACK && runtime.terminals.active != null && runtime.keyboard.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** Widgets only refresh while the home screen is visible. */
    override fun onStart() {
        super.onStart()
        runtime.widgets.homeStarted()
        runtime.screenStarted()
    }

    override fun onStop() {
        super.onStop()
        state.closeNow()
        // An app was just opened from home: it moves to the front of the strip now, out of sight.
        runtime.usage.settle()
        runtime.widgets.homeStopped()
        runtime.screenStopped()
    }

    override fun onDestroy() {
        application.unregisterActivityLifecycleCallbacks(screensWatch)
        HomeDoor.leave(door)
        super.onDestroy()
    }

    private fun shortcut(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> runtime.terminal(null, "Terminal")
            KeyEvent.KEYCODE_SPACE -> state.toggle()
            KeyEvent.KEYCODE_A -> openOmaChat(this)
            // The page at that position in the notebook; unfolded, the pair it is in.
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> state.requestedPage = keyCode - KeyEvent.KEYCODE_1
            else -> return false
        }
        return true
    }
}

/** The switch file of the debug door, in the app's own files. */
const val DEBUG_EXEC_SWITCH = "allow-debug-exec"

/**
 * The debug door without the activity: `adb shell am broadcast -n <package>/dev.omalauncher.DebugExecReceiver
 * --es key KEY --es exec COMMAND` runs a guest command as the intent to [MainActivity] does, behind the same key,
 * and brings nothing to the front, so a screen being looked at stays as it is. Debug builds only.
 */
class DebugExecReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(
        context: android.content.Context,
        intent: Intent,
    ) {
        if (!BuildConfig.DEBUG) return
        val key = runCatching { java.io.File(context.filesDir, DEBUG_EXEC_SWITCH).readText() }.getOrNull()
        if (debugExecAllowed(key, intent.getStringExtra("key"))) intent.getStringExtra("exec")?.let((context.applicationContext as App).runtime::launch)
    }
}

/** The fewest characters a key for the debug door may have: a guess is not worth making. */
const val DEBUG_EXEC_KEY_LENGTH = 32

/**
 * Whether a guest command from an intent is run: the switch file holds a [kept] key of at least
 * [DEBUG_EXEC_KEY_LENGTH] characters and the intent [offered] the same one. No file, an empty file (how the
 * switch used to be turned on) or a short key keeps the door shut. Compared without stopping at the first
 * character that differs.
 */
fun debugExecAllowed(
    kept: String?,
    offered: String?,
): Boolean {
    val key = kept?.trim().orEmpty()
    if (key.length < DEBUG_EXEC_KEY_LENGTH || offered == null) return false
    return java.security.MessageDigest.isEqual(key.toByteArray(), offered.toByteArray())
}
