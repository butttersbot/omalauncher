package dev.omalauncher.phone

import android.accessibilityservice.*
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.*
import android.os.*
import android.view.accessibility.*
import dev.omalauncher.oma.OwnScreens
import dev.omalauncher.oma.ring.OmaRing
import dev.omalauncher.oma.ring.RingState
import dev.omalauncher.oma.ring.doneMillis
import dev.omalauncher.oma.ring.ringState
import dev.omalauncher.oma.ring.ringStyle
import org.json.*
import java.io.File
import java.util.UUID

/** Android owns binding; Linux commands are gated by PhoneAccess on every request. */
class PhoneService : AccessibilityService() {
    companion object {
        @Volatile var instance: PhoneService? = null; private set
        /** How long a note waits for its page to be read: less than the bridge waits for an answer. */
        private const val NOTE_WAIT_MILLIS = 6000L
        private const val TAP_MILLIS = 80L
    }
    private var snapshot = ""
    private var signatures = emptyMap<String, String>()
    /** When this service last did something to the screen, by the clock that stops in sleep; null until it has. */
    private var acted: Long? = null
    private val main = Handler(Looper.getMainLooper())
    /** The ring over other apps: on unless the stored setting says no. */
    private val ring = RingOverlay(ServiceRingWindow(this), ::ringFacts)
    /** The agent's answer over other apps. */
    private val answer = AnswerCard(this)
    private val checkRing = Runnable { syncRing() }
    private var unwatchRing: (() -> Unit)? = null
    private val screenChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = syncRing()
    }
    override fun onServiceConnected() { PhoneAccess.load(this); instance = this; watchRing(); dev.omalauncher.assist.AgentTasks.phoneChanged(this) }
    /** A window changed, the lock screen's among them: an overlay that is up looks again at once. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { if (ring.shown || answer.shown) syncRing() }
    override fun onInterrupt() = Unit
    /** Folded, unfolded or turned. */
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); lookAgain(ring.relayout()) }
    override fun onUnbind(intent: Intent?): Boolean { closeRing(); return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; closeRing(); PhoneAccess.clearIndicator(this); dev.omalauncher.assist.AgentTasks.phoneChanged(this); super.onDestroy() }

    private fun watchRing() {
        closeRing()
        unwatchRing = OmaRing.watch { main.post(checkRing) }
        // The screen going off, or coming back at the lock: checked at once rather than at the next look.
        runCatching {
            registerReceiver(screenChanged, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            })
        }
        syncRing()
    }
    private fun closeRing() {
        unwatchRing?.invoke(); unwatchRing = null
        runCatching { unregisterReceiver(screenChanged) }
        main.removeCallbacks(checkRing)
        ring.close()
        answer.close()
    }
    /** What the overlay is decided from. While the setting is off, not allowed is the whole answer: nothing else is even looked at. */
    private fun ringFacts(): OverlayFacts {
        if (!ringOverlayAllowed(ringPrefs(this))) return OverlayFacts()
        return facts(allowed = true)
    }
    /** The facts as they are, whatever the ring's setting says: the answer card asks these. */
    private fun facts(allowed: Boolean): OverlayFacts {
        return OverlayFacts(
            allowed = allowed,
            connected = instance === this,
            paused = !PhoneAccess.active,
            locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked,
            awake = getSystemService(PowerManager::class.java).isInteractive,
            assistantUp = OmaRing.assistantUp,
            ownScreen = OwnScreens.visible,
            lit = ringState(OmaRing.signals, OmaRing.now(), doneMillis(ringStyle().done)) != RingState.Off,
        )
    }
    /** Matches the overlay to the facts, and keeps looking while it is up or could be. Main thread. */
    private fun syncRing() {
        // An answer doesn't outlast what it answered: a new task, the assistant or the lock screen takes it down.
        if (answer.shown && (!answerCardWanted(facts(allowed = false)) || OmaRing.signals.working)) answer.close()
        lookAgain(ring.sync())
    }
    /** Shows the agent's answer over whatever is in front, if it may be shown now. False: say it another way. Main thread. */
    fun showAnswer(caption: String, text: String): Boolean = answerCardWanted(facts(allowed = false)) && answer.show(caption, text)
    private fun lookAgain(soon: Boolean) {
        main.removeCallbacks(checkRing)
        if (soon) main.postDelayed(checkRing, OVERLAY_CHECK_MILLIS)
    }
    /** Phone control was paused or resumed. From any thread. */
    fun ringChanged() { main.post(checkRing) }

    private fun signature(node: AccessibilityNodeInfo): String {
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        return listOf(node.packageName, node.viewIdResourceName, node.className, node.text, node.contentDescription, bounds).joinToString("|")
    }
    /**
     * The window in front as it is now. Android keeps the nodes it has handed out and only drops them when the
     * app's own change events arrive, which come late and not for everything a Compose screen does; so what is
     * kept is thrown away first, and the app is asked again.
     */
    private fun root(): AccessibilityNodeInfo {
        if (Build.VERSION.SDK_INT >= 33) clearCache()
        return rootInActiveWindow ?: error("No readable window. Try again after the screen settles.")
    }
    /** Before Android 13 what is kept can't be thrown away, so each node is asked for again. False: it has gone. */
    private fun current(node: AccessibilityNodeInfo) = Build.VERSION.SDK_INT >= 33 || node.refresh()
    private fun resolve(path: String): AccessibilityNodeInfo {
        var node = root()
        try {
            check(current(node)) { "Screen changed. Read it again." }
            if (path != "root") for (part in path.removePrefix("root.").split('.')) {
                val next = node.getChild(part.toInt()) ?: error("Screen changed. Read it again.")
                node.recycle(); node = next
                check(current(node)) { "Screen changed. Read it again." }
            }
            return node
        } catch (e: Exception) { node.recycle(); throw e }
    }
    /** Something was done to the screen: what was read of it no longer holds, and the next read gives it time to show. */
    private fun changed() { snapshot = ""; signatures = emptyMap(); acted = SystemClock.uptimeMillis() }
    /** The screen's controls, under a new snapshot id. */
    private fun read(): JSONObject {
        val nodes = JSONArray(); val next = mutableMapOf<String, String>()
        fun visit(node: AccessibilityNodeInfo, path: String, depth: Int) {
            try {
                if (depth > 40 || nodes.length() >= 600 || !current(node)) return
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                if (node.isVisibleToUser) {
                    nodes.put(JSONObject().put("id", path).put("text", if (node.isPassword) "[password]" else node.text?.toString().orEmpty())
                        .put("description", if (node.isPassword) "" else node.contentDescription?.toString().orEmpty())
                        .put("package", node.packageName?.toString()).put("viewId", node.viewIdResourceName)
                        .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                        .put("clickable", node.isClickable).put("editable", node.isEditable).put("scrollable", node.isScrollable)
                        .put("checked", node.isChecked).put("enabled", node.isEnabled))
                    next[path] = signature(node)
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, "$path.$i", depth + 1) }
            } finally { node.recycle() }
        }
        visit(root(), "root", 0)
        snapshot = UUID.randomUUID().toString(); signatures = next
        return JSONObject().put("ok", true).put("snapshot", snapshot).put("nodes", nodes)
    }
    /** One finger along [path] for [duration]; [reply] hears once it has lifted, or that Android cancelled it. */
    private fun stroke(path: Path, duration: Long, reply: (JSONObject) -> Unit) {
        changed()
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        check(dispatchGesture(gesture, object : GestureResultCallback() {
            // The screen starts to change when the finger lifts, not when it lands.
            override fun onCompleted(gestureDescription: GestureDescription?) { changed(); reply(JSONObject().put("ok", true)) }
            override fun onCancelled(gestureDescription: GestureDescription?) { reply(JSONObject().put("ok", false).put("error", "Gesture cancelled")) }
        }, null)) { "Android rejected gesture" }
    }
    /** The notebook for the page commands. Turning a page is an explicit start of our own home activity, with nothing in the intent. */
    private fun homeBook() = object : HomeBook {
        private val notebook = (application as dev.omalauncher.App).runtime.widgets.notebook
        override val pages get() = if (notebook.opened) notebook.pages.map { it.name } else null
        override val showing get() = HomeDoor.home?.showing?.let(notebook::position)?.takeIf { it >= 0 }
        override fun pageOf(widget: String) = notebook.pageOf(widget)?.let(notebook::position)?.takeIf { it >= 0 }
        override fun placed(widget: String) = notebook.cell(widget) != null
        override fun show(index: Int) {
            HomeDoor.ask(notebook.pages[index].id)
            startActivity(Intent(this@PhoneService, dev.omalauncher.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            changed()
        }
    }
    /** Home's pages for the note and board commands: which one a note goes on, the book it is written in, and what is on a page. */
    private fun notePad() = object : NotePad {
        private val notebook = (application as dev.omalauncher.App).runtime.widgets.notebook
        private val book = dev.omalauncher.widgets.annotations.HomeAnnotations.of(this@PhoneService)
        private val until = SystemClock.elapsedRealtime() + NOTE_WAIT_MILLIS
        // Only while its task runs: a page some earlier request came from is not where a later note belongs.
        override val asked get() = dev.omalauncher.assist.AgentTasks.asked?.takeIf { it.ended == null }?.page
        override val showing get() = HomeDoor.home?.showing
        override val pages get() = if (notebook.opened) notebook.pages.map { it.id } else emptyList()
        override fun name(page: String) = if (notebook.opened) notebook.name(page) else null
        override fun leave(page: String, text: String, near: Pair<Int, Int>?, then: (dev.omalauncher.widgets.annotations.NoteLeft) -> Unit) = book.whenRead(page) {
            // The bridge has stopped waiting by then and told the agent so: nothing is written behind that answer.
            if (SystemClock.elapsedRealtime() > until) then(dev.omalauncher.widgets.annotations.NoteLeft.Refused("Home took too long to read that page. Nothing was written."))
            else then(book.leaveNote(page, text, near))
        }
        // The same tiles a note is kept clear of, and the notes as the book has them: what `board` tells is what `note` avoids.
        override fun look(page: String, then: (dev.omalauncher.widgets.annotations.PageBoard, dev.omalauncher.widgets.annotations.Annotations?) -> Unit) =
            book.whenRead(page) { read -> then(notebook.board(page), if (read) book.on(page) else null) }
    }
    /** Does [command] (click, type or scroll) to the control at [id] of the screen last read; [reply] hears how it went. */
    private fun act(command: String, id: String, input: JSONObject, reply: (JSONObject) -> Unit) {
        val node = resolve(id)
        try {
            check(node.isVisibleToUser && node.isEnabled && signatures[id] == signature(node)) { "Screen changed. Read it again." }
            val done = when (command) {
                "click" -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                "type" -> {
                    check(node.isEditable && !node.isPassword) { "Only non-password text fields can be typed into." }
                    val text = input.getString("text"); require(text.length <= 10_000) { "Text too long" }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
                }
                else -> node.performAction(if (input.optString("direction", "forward") == "backward") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            if (!done && command == "click") {
                // Not every control takes a click from here, but a finger on its middle is what its owner would do.
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                val metrics = resources.displayMetrics
                val (x, y) = PhoneTouch.middle(bounds.left, bounds.top, bounds.right, bounds.bottom, metrics.widthPixels, metrics.heightPixels)
                    ?: error("Android rejected this click and the control is not on screen to tap. Read the screen again.")
                stroke(Path().apply { moveTo(x, y) }, TAP_MILLIS) { reply(if (it.optBoolean("ok")) it.put("tapped", true) else it) }
                return
            }
            check(done) { "Android rejected this action. Read the screen again or use a gesture." }
            changed()
        } finally { node.recycle() }
        reply(JSONObject().put("ok", true))
    }
    /** The owner's stickers for the sticker commands: his library, and the book the pages' stickers are kept in. */
    private fun stickerPad() = object : StickerPad {
        private val book = dev.omalauncher.widgets.annotations.HomeAnnotations.of(this@PhoneService)
        private val shelf = dev.omalauncher.widgets.annotations.HomeStickers.of(this@PhoneService)
        // The library is a folder to list and files to look into: not on the main thread.
        override fun library(then: (dev.omalauncher.widgets.annotations.StickerLibrary?) -> Unit) {
            kotlin.concurrent.thread(name = "sticker-library") {
                val found = runCatching { shelf.look() }.getOrNull()
                main.post { then(found) }
            }
        }
        override fun stick(page: String, choice: dev.omalauncher.widgets.annotations.StickerChoice, near: Pair<Float, Float>?, then: (String?) -> Unit) = book.stick(page, choice, near, then)
        override fun change(page: String, id: String, edit: (dev.omalauncher.widgets.annotations.Sticker) -> dev.omalauncher.widgets.annotations.Sticker) = book.changeSticker(page, id, edit)
        // The book takes a note off by its id as readily as a sticker: only a sticker is asked of it here.
        override fun remove(page: String, id: String) = book.on(page).stickers.any { it.id == id } && book.remove(page, id)
    }
    /** The notebook for the page-editing commands, changed as the overview changes it. */
    private fun pagePad() = object : PagePad {
        private val runtime = (application as dev.omalauncher.App).runtime
        private val notebook = runtime.widgets.notebook
        override fun add() = runtime.widgets.addPage()
        override fun taken(name: String, except: String?) = notebook.nameTaken(name, except)
        override fun rename(page: String, name: String) = notebook.renamePage(page, name)
        // An empty page has nothing to move: its still goes with it, as when it is deleted in the overview.
        override fun delete(page: String) = runtime.widgets.deletePage(page, move = true)?.also { runtime.pageStills.drop(page) } != null
    }
    private fun lockStarPad() = object : LockStarPad {
        override val names get() = dev.omalauncher.theme.LockStarShare.names(this@PhoneService)
        override fun capture(name: String) = dev.omalauncher.theme.LockStarShare.capture(this@PhoneService, name)
        override fun apply(name: String) = dev.omalauncher.theme.LockStarShare.apply(this@PhoneService, name)
    }
    /** The looks for the look command, picked as the style screen picks one. */
    private fun lookPad() = object : LookPad {
        private val themes = (application as dev.omalauncher.App).runtime.themes
        override val looks get() = themes.lookIds
        override val current get() = themes.pickedPack
        override fun pick(id: String) = themes.pickPack(id)
    }
    fun request(input: JSONObject, reply: (JSONObject) -> Unit) {
        try {
            val command = input.getString("command")
            // The checks and their order are PhoneRequests.admit's; nothing below runs for a request it didn't let through.
            val route = PhoneRequests.admit(command, { PhoneAccess.requireAccess(this) }) { dev.omalauncher.assist.AgentTasks.pending.size }
            if (route == PhoneRequests.Route.Status) { reply(JSONObject().put("ok", true).put("active", PhoneAccess.active).put("connected", true)); return }
            if (route == PhoneRequests.Route.Stop) { PhoneAccess.stop(this); reply(JSONObject().put("ok", true)); return }
            // Home's pages: read and turned behind the same gates as everything else, and never while an approval waits.
            if (route == PhoneRequests.Route.Pages) { reply(PhonePages.reply(input, homeBook())); return }
            // What is where on a home page, behind the same gates as the page commands: it only looks.
            if (route == PhoneRequests.Route.Board) { PhoneBoard.reply(input, notePad(), reply); return }
            // A sticky note on home, behind the same gates: one note, on one page, and nothing else.
            if (route == PhoneRequests.Route.Note) { PhoneNotes.reply(input, notePad(), reply); return }
            // Stickers on home, behind the same gates: listed, stuck, turned, resized, moved or taken off, and nothing else.
            if (route == PhoneRequests.Route.Stickers) { PhoneStickers.reply(input, notePad(), stickerPad(), reply); return }
            // A page added, renamed, or deleted while it is empty, behind the same gates.
            if (route == PhoneRequests.Route.PageEdits) { PhonePageEdits.reply(input, notePad(), pagePad(), reply); return }
            // The look home is drawn in, told or picked, behind the same gates.
            if (route == PhoneRequests.Route.Looks) { reply(PhoneLooks.reply(input, lookPad())); return }
            // LockStar's layout, kept or handed back: its provider is slow and may refuse, so off the main thread.
            if (route == PhoneRequests.Route.LockStar) {
                kotlin.concurrent.thread(name = "lockstar") {
                    val answer = try { PhoneLockStar.reply(input, lockStarPad()) } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName) }
                    main.post { reply(answer) }
                }
                return
            }
            if (route == PhoneRequests.Route.Screenshot) {
                if (Build.VERSION.SDK_INT < 30) error("Screenshots require Android 11 or later.")
                // The overlay is never in the agent's picture: it comes down first and goes back once the picture is taken.
                // With no overlay up there is nothing to wait for, and the screenshot is taken at once, as before.
                answer.close()
                val settle = ring.beforeCapture()
                // It wouldn't come down: no picture is taken with it up.
                if (settle == CAPTURE_REFUSED) error("Screenshot unavailable: the ring overlay could not be removed. Try again.")
                fun captured() = lookAgain(ring.afterCapture())
                val capture = Runnable { try { takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onFailure(code: Int) { captured(); reply(JSONObject().put("ok", false).put("error", "Screenshot unavailable: $code. Protected screens cannot be captured.")) }
                    override fun onSuccess(result: ScreenshotResult) {
                        captured()
                        val hardware = result.hardwareBuffer
                        try {
                            PhoneAccess.requireAccess(this@PhoneService)
                            val image = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace) ?: error("Screenshot unavailable")
                            val bitmap = image.copy(Bitmap.Config.ARGB_8888, false); image.recycle()
                            val dir = File(filesDir, "attachments").apply { mkdirs() }
                            val file = File(dir, "phone-screen-${UUID.randomUUID()}.png")
                            val width = bitmap.width; val height = bitmap.height
                            try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
                            reply(JSONObject().put("ok", true).put("path", "/root/.local/share/omalauncher/attachments/${file.name}")
                                .put("width", width).put("height", height))
                        } catch (e: Exception) { reply(JSONObject().put("ok", false).put("error", e.message)) }
                        finally { hardware.close() }
                    }
                }) } catch (e: Exception) { captured(); reply(JSONObject().put("ok", false).put("error", e.message ?: "Phone control failed")) } }
                if (settle == 0L) capture.run() else main.postDelayed(capture, settle)
                return
            }
            val output = JSONObject().put("ok", true)
            when (command) {
                "apps" -> {
                    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    val apps = packageManager.queryIntentActivities(intent, 0).distinctBy { it.activityInfo.packageName }
                    output.put("apps", JSONArray(apps.map { JSONObject().put("package", it.activityInfo.packageName).put("name", it.loadLabel(packageManager).toString()) }))
                }
                "open" -> {
                    val name = input.getString("package")
                    val intent = if (name == "settings") android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                        else packageManager.getLaunchIntentForPackage(name) ?: error("No visible launcher activity for this package. Run apps.")
                    startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    changed()
                }
                "read" -> {
                    // Right after one of this service's own actions the screen is still on its way to what the action did.
                    val wait = PhoneTouch.settleWait(SystemClock.uptimeMillis(), acted)
                    if (wait == 0L) { reply(read()); return }
                    main.postDelayed({
                        try { PhoneAccess.requireAccess(this); reply(read()) }
                        catch (e: Exception) { reply(JSONObject().put("ok", false).put("error", e.message ?: "Phone control failed")) }
                    }, wait)
                    return
                }
                "click", "type", "scroll" -> {
                    check(input.getString("snapshot") == snapshot && snapshot.isNotEmpty()) { "Read the screen first; snapshot is stale." }
                    act(command, input.getString("id"), input, reply)
                    return
                }
                "screen" -> {
                    val until = input.optString("until")
                    val deadline = SystemClock.uptimeMillis() + if (until.isEmpty()) 0L else PhoneTouch.UNTIL_MILLIS
                    // Looks again until the words show or the time is up; what it then gives is the screen as it stands.
                    fun look() {
                        try {
                            PhoneAccess.requireAccess(this)
                            val seen = read()
                            val shows = until.isEmpty() || PhoneScreen.says(seen, until)
                            if (!shows && SystemClock.uptimeMillis() < deadline) { main.postDelayed({ look() }, PhoneTouch.UNTIL_STEP_MILLIS); return }
                            reply(PhoneScreen.brief(seen).also { if (until.isNotEmpty()) it.put("shows", shows) })
                        } catch (e: Exception) { reply(JSONObject().put("ok", false).put("error", e.message ?: "Phone control failed")) }
                    }
                    main.postDelayed({ look() }, PhoneTouch.settleWait(SystemClock.uptimeMillis(), acted))
                    return
                }
                "press", "fill" -> {
                    val label = input.getString("label")
                    val seen = read()
                    PhoneScreen.requireFront(seen, input.optString("in"))
                    val nodes = PhoneScreen.nodes(seen)
                    val id = when (val found = if (command == "press") PhoneFind.press(nodes, label) else PhoneFind.field(nodes, label)) {
                        is Found.One -> found.id
                        is Found.Several -> error("Several controls answer to \"$label\": ${found.ids.joinToString(", ")}. Nothing was done. Use a longer label, or click/type with snapshot $snapshot and one of these ids.")
                        Found.None -> error("Nothing on screen ${if (command == "press") "says" else "is a text field that says"} \"$label\". Nothing was done. Run oma-phone screen.")
                    }
                    // One call does the step and gives back what it led to, so the next step starts from the real screen.
                    act(if (command == "press") "click" else "type", id, input) { done ->
                        if (!done.optBoolean("ok")) { reply(done); return@act }
                        main.postDelayed({
                            try { PhoneAccess.requireAccess(this); reply(PhoneScreen.brief(read()).put("did", command).put("on", id)) }
                            catch (e: Exception) { reply(JSONObject().put("ok", false).put("error", e.message ?: "Phone control failed")) }
                        }, PhoneTouch.SETTLE_MILLIS)
                    }
                    return
                }
                "back", "home", "recents", "notifications" -> {
                    val action = when (command) { "back" -> GLOBAL_ACTION_BACK; "home" -> GLOBAL_ACTION_HOME; "recents" -> GLOBAL_ACTION_RECENTS; else -> GLOBAL_ACTION_NOTIFICATIONS }
                    check(performGlobalAction(action)) { "Android rejected navigation" }
                    changed()
                }
                "tap", "swipe" -> {
                    val metrics = resources.displayMetrics
                    fun coordinate(key: String, limit: Int) = input.getDouble(key).toFloat().also { require(it.isFinite() && it >= 0 && it < limit) { "Coordinate outside screen: $key" } }
                    val x = coordinate("x", metrics.widthPixels); val y = coordinate("y", metrics.heightPixels)
                    val path = Path().apply { moveTo(x, y); if (command == "swipe") lineTo(coordinate("endX", metrics.widthPixels), coordinate("endY", metrics.heightPixels)) }
                    val duration = if (command == "tap") TAP_MILLIS else input.optLong("duration", 400).also { require(it in PhoneTouch.SWIPE_MILLIS) { "Swipe duration must be ${PhoneTouch.SWIPE_MILLIS.first} to ${PhoneTouch.SWIPE_MILLIS.last} ms." } }
                    stroke(path, duration, reply)
                    return
                }
                else -> error("Unknown phone command: $command")
            }
            reply(output)
        } catch (e: Exception) { reply(JSONObject().put("ok", false).put("error", e.message ?: "Phone control failed")) }
    }
}
