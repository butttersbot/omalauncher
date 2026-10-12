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
    }
    private var snapshot = ""
    private var signatures = emptyMap<String, String>()
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
    override fun onServiceConnected() { PhoneAccess.load(this); instance = this; watchRing() }
    /** A window changed, the lock screen's among them: an overlay that is up looks again at once. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { if (ring.shown || answer.shown) syncRing() }
    override fun onInterrupt() = Unit
    /** Folded, unfolded or turned. */
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); lookAgain(ring.relayout()) }
    override fun onUnbind(intent: Intent?): Boolean { closeRing(); return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; closeRing(); PhoneAccess.clearIndicator(this); super.onDestroy() }

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
    private fun root(): AccessibilityNodeInfo = rootInActiveWindow ?: error("No readable window. Try again after the screen settles.")
    private fun resolve(path: String): AccessibilityNodeInfo {
        var node = root()
        try {
            if (path != "root") for (part in path.removePrefix("root.").split('.')) {
                val next = node.getChild(part.toInt()) ?: error("Screen changed. Read it again.")
                node.recycle(); node = next
            }
            return node
        } catch (e: Exception) { node.recycle(); throw e }
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
            snapshot = ""; signatures = emptyMap()
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
                    snapshot = ""; signatures = emptyMap()
                }
                "read" -> {
                    val nodes = JSONArray(); val next = mutableMapOf<String, String>()
                    fun visit(node: AccessibilityNodeInfo, path: String, depth: Int) {
                        try {
                            if (depth > 40 || nodes.length() >= 600) return
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
                    output.put("snapshot", snapshot).put("nodes", nodes)
                }
                "click", "type", "scroll" -> {
                    check(input.getString("snapshot") == snapshot && snapshot.isNotEmpty()) { "Read the screen first; snapshot is stale." }
                    val id = input.getString("id"); val node = resolve(id)
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
                        check(done) { "Android rejected this action. Read the screen again or use a gesture." }
                        snapshot = ""; signatures = emptyMap()
                    } finally { node.recycle() }
                }
                "back", "home", "recents", "notifications" -> {
                    val action = when (command) { "back" -> GLOBAL_ACTION_BACK; "home" -> GLOBAL_ACTION_HOME; "recents" -> GLOBAL_ACTION_RECENTS; else -> GLOBAL_ACTION_NOTIFICATIONS }
                    check(performGlobalAction(action)) { "Android rejected navigation" }
                    snapshot = ""; signatures = emptyMap()
                }
                "tap", "swipe" -> {
                    val metrics = resources.displayMetrics
                    fun coordinate(key: String, limit: Int) = input.getDouble(key).toFloat().also { require(it.isFinite() && it >= 0 && it < limit) { "Coordinate outside screen: $key" } }
                    val x = coordinate("x", metrics.widthPixels); val y = coordinate("y", metrics.heightPixels)
                    val path = Path().apply { moveTo(x, y); if (command == "swipe") lineTo(coordinate("endX", metrics.widthPixels), coordinate("endY", metrics.heightPixels)) }
                    val duration = if (command == "tap") 80L else input.optLong("duration", 400).also { require(it in 100..2000) }
                    snapshot = ""; signatures = emptyMap()
                    val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
                    check(dispatchGesture(gesture, object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) { reply(JSONObject().put("ok", true)) }
                        override fun onCancelled(gestureDescription: GestureDescription?) { reply(JSONObject().put("ok", false).put("error", "Gesture cancelled")) }
                    }, null)) { "Android rejected gesture" }
                    return
                }
                else -> error("Unknown phone command: $command")
            }
            reply(output)
        } catch (e: Exception) { reply(JSONObject().put("ok", false).put("error", e.message ?: "Phone control failed")) }
    }
}
