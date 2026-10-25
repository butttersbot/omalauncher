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
    private class DisplayState {
        var snapshot = ""
        var seen = emptyMap<String, SeenControl>()
        var acted: Long? = null
        var lastChange: Long? = null
        var opened: String? = null
        /** When [snapshot] was read, by the same clock as [lastChange]. */
        var readAt: Long? = null
        /** The last screen `screen` answered on this display, for `--delta`. */
        var lastBrief: JSONObject? = null
    }
    private val displayStates = mutableMapOf(0 to DisplayState())
    private var displayId = 0
    private val displayState get() = displayStates.getOrPut(displayId) { DisplayState() }
    private var busy = false
    private var requestGeneration = 0L
    private var activePlan: PhonePlanRunner? = null
    private val planJournal by lazy { PhonePlanJournal(File(filesDir, "phone-plan-receipts.json")) }
    fun cancelPending() { activePlan?.cancel(reason = "Phone control paused or taken over") }
    val planState get() = activePlan?.status()
    private var requestDeadline = 0L
    private var requestCommand = ""
    private var snapshot: String
        get() = displayState.snapshot
        set(value) { displayState.snapshot = value }
    /** Each control of the last read, by its id there: what an act checks it is still acting on. */
    private var seen: Map<String, SeenControl>
        get() = displayState.seen
        set(value) { displayState.seen = value }
    /** When this service last did something to the screen, by the clock that stops in sleep; null until it has. */
    private var acted: Long?
        get() = displayState.acted
        set(value) { displayState.acted = value }
    private val main = Handler(Looper.getMainLooper())
    /** The ring over other apps: on unless the stored setting says no. */
    private val ring = RingOverlay(ServiceRingWindow(this), ::ringFacts)
    /** The agent's answer over other apps. */
    private val answer = AnswerCard(this)
    /** The herd's sheep over other apps, while it is shown (Boss, 2026-10-15). */
    private val sheep = dev.omalauncher.herd.SheepOverlay(this)

    /** "Add to pocket" over Samsung's screenshot toolbar, while that is up (ShotButton.kt). */
    private val shot = ShotButton(this)

    /** Puts the button over the screenshot toolbar while it is up and phone control may draw, and takes it away after. */
    private fun syncShot() {
        val f = facts(allowed = false)
        val bar = if (f.connected && !f.paused && !f.locked && f.awake && !f.capturing && !sheepHeld) runCatching { shot.toolbar(windows) }.getOrNull() else null
        if (bar != null) shot.show(bar) else shot.hide()
    }
    /** True while a screenshot is taken for the agent: the sheep stays out of it. */
    private var sheepHeld = false
    private val checkRing = Runnable { syncRing() }
    private var unwatchRing: (() -> Unit)? = null
    private val screenChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = syncRing()
    }
    /** The package of the last window to come to the front: an unbind with his settings there is him switching it off. */
    @Volatile private var front: String? = null
    /** When the screen last said it changed, by the same clock as [acted]: a read waits for it to go quiet ([phoneSettleWait]). */
    private var lastChange: Long?
        get() = displayState.lastChange
        set(value) { displayState.lastChange = value }
    /** The last place he could be brought back to that came to the front ([phoneOrigin]): what an agent's first move leaves. */
    private var place: PhoneOrigin? = null
    /** Where he was when this request came in, which is where he was before the agent's first move if this request is it. */
    private var placeAsked: PhoneOrigin? = null
    /** Where he was before the agent's first move since its work began; null until it moves the screen. */
    private var origin: PhoneOrigin? = null
    /** The agent said the screen it is on is for him (`oma-phone stay`): it is left up when the work ends. */
    private var stay = false
    /** He took the phone himself since the agent's first move: nothing is brought back over what he chose. */
    private var took = false
    /** The app the agent last opened (`open`, `url`): its touches are for that app until it says otherwise ([PhoneGuard]). */
    private var opened: String?
        get() = displayState.opened
        set(value) { displayState.opened = value }
    /** The app the agent's touches are held to, for `status`; null for none. */
    val openedApp get() = displayStates[0]?.opened

    /** Rechecked by every delayed step; pause, vanished helper and newly pending approvals all win. */
    private fun requireCurrentAccess() {
        check(busy && SystemClock.elapsedRealtime() < requestDeadline) { "Phone request expired. Read the screen again." }
        if (displayId != 0) check(PhoneDisplays.background(this).id == displayId) { "Background display changed. Read it again." }
        PhoneAccess.requireAccess(this, background = displayId != 0, workingApp = opened)
        PhoneControlPolicy.requireNoPendingApproval(dev.omalauncher.assist.AgentTasks.pending.size + dev.omalauncher.assist.MacThreads.pending.size, requestCommand)
    }

    private fun displayWindows(id: Int) = if (Build.VERSION.SDK_INT >= 30) windowsOnAllDisplays[id].orEmpty() else windows
    private fun displayRoot(id: Int): AccessibilityNodeInfo? = if (Build.VERSION.SDK_INT < 30) rootInActiveWindow else
        displayWindows(id)
            .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isActive || it.isFocused }.thenByDescending { it.layer })
            .firstOrNull()?.root
    private fun displayMetrics() = android.util.DisplayMetrics().also {
        getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(displayId)?.getRealMetrics(it)
            ?: error("Display has gone.")
    }

    private fun launchOnDisplay(intent: Intent, pkg: String?) {
        requireCurrentAccess()
        if (displayId != 0) {
            val owner = displayRoot(0)
            try { check(pkg != null && owner?.packageName?.toString() != pkg) { "That app is on your main screen. Wait until you finish using it." } }
            finally { owner?.recycle() }
            check(getSystemService(android.app.ActivityManager::class.java).isActivityStartAllowedOnDisplay(this, displayId, intent)) { "This app cannot open on the background display. Nothing was launched." }
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        } else startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    override fun onServiceConnected() {
        PhoneAccess.load(this); instance = this
        PhoneControlHeal.connected(this, paused = !PhoneAccess.active)
        dev.omalauncher.herd.HerdSheep.restore(this)
        dev.omalauncher.herd.HerdSheep.changed = { main.post(checkRing) }
        watchRing(); dev.omalauncher.assist.AgentTasks.phoneChanged(this)
    }
    /** A window changed, the lock screen's among them: an overlay that is up looks again at once. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventDisplay = if (Build.VERSION.SDK_INT >= 30) {
            val all = windowsOnAllDisplays
            (0 until all.size()).firstOrNull { n -> all.valueAt(n).any { it.id == event.windowId } }?.let { all.keyAt(it) }
        } else 0
        eventDisplay?.let { displayStates.getOrPut(it) { DisplayState() }.lastChange = SystemClock.uptimeMillis() }
        if (eventDisplay != null && eventDisplay != 0) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            pkg?.let { front = it }
            phoneOrigin(pkg, event.className?.toString(), packageName) { HomeDoor.home?.showing }?.let { place = it }
            if (origin != null && phoneTakenOver(pkg, packageName, SystemClock.uptimeMillis(), displayStates[0]?.acted)) took = true
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && (ring.shown || answer.shown || sheep.shown)) syncRing()
        // The screenshot toolbar comes as its own window and goes with a change of the windows.
        if (event.packageName?.toString() == SMART_CAPTURE || (shot.shown && event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED)) syncShot()
    }
    override fun onInterrupt() = Unit
    /** Folded, unfolded or turned. */
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); sheep.relayout(); lookAgain(ring.relayout()) }
    override fun onUnbind(intent: Intent?): Boolean { cancelPending(); PhoneControlHeal.unbound(this, front); closeRing(); return super.onUnbind(intent) }
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
        sheep.hide()
        shot.hide()
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
        // The sheep: up while it is shown and the phone is in use, over every app and home; down at the lock, with
        // the screen off, while control is paused, under the assistant and in the agent's screenshots.
        val f = facts(allowed = false)
        if (dev.omalauncher.herd.HerdSheep.shown && !sheepHeld && f.connected && !f.paused && !f.locked && f.awake && !f.assistantUp) sheep.show() else sheep.hide()
        lookAgain(ring.sync())
    }
    /** Shows the agent's answer over whatever is in front, if it may be shown now. False: say it another way. Main thread. */
    fun showAnswer(caption: String, text: String): Boolean =
        answerCardWanted(facts(allowed = false)) && (sheepSays(caption, text, SHEEP_ANSWER_MILLIS) || answer.show(caption, text))

    /** A short note from another app over whatever is in front, in the look's own card (see [OmaToastReceiver]). Main thread. */
    fun showToast(caption: String, text: String): Boolean =
        answerCardWanted(facts(allowed = false)) && (sheepSays(caption, text, SHEEP_TOAST_MILLIS) || answer.show(caption, text, TOAST_MILLIS, opensChat = false))

    /** With the floating sheep up, cards come through its bubble instead (Boss, 2026-10-17); false shows the card. */
    private fun sheepSays(caption: String, text: String, ms: Long): Boolean = sheep.shown && sheep.say(caption, text, ms)
    private fun lookAgain(soon: Boolean) {
        main.removeCallbacks(checkRing)
        if (soon) main.postDelayed(checkRing, OVERLAY_CHECK_MILLIS)
    }
    /** Phone control was paused or resumed. From any thread. */
    fun ringChanged() { main.post(checkRing) }

    private fun control(path: String, node: AccessibilityNodeInfo): SeenControl {
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        return SeenControl(path, node.packageName?.toString(), node.viewIdResourceName, node.className?.toString(), node.text?.toString(),
            node.contentDescription?.toString(), listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))
    }
    /**
     * The window in front as it is now. Android keeps the nodes it has handed out and only drops them when the
     * app's own change events arrive, which come late and not for everything a Compose screen does; so what is
     * kept is thrown away first, and the app is asked again.
     */
    private fun root(): AccessibilityNodeInfo {
        if (Build.VERSION.SDK_INT >= 33) clearCache()
        // Android keeps some Settings pages from services that aren't accessibility tools (Keyboard, Developer options'
        // debugging): no window to read and no taps taken, while everywhere else works. Seen on the Fold, 2026-10-16.
        requireCurrentAccess()
        if (displayId != 0 && !getSystemService(PowerManager::class.java).isInteractive) {
            error("This phone does not expose background app controls while asleep. Wake the phone before retrying; no main-screen action was taken. Background screenshots remain available.")
        }
        return displayRoot(displayId) ?: error("No readable window on display $displayId. Android hides some Settings pages from phone control and ignores taps " +
            "there (Keyboard, Developer options' debugging): if one is in front, ask Boss to do that step. Otherwise try again after the screen settles.")
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
    private fun changed() {
        snapshot = ""; seen = emptyMap(); acted = SystemClock.uptimeMillis()
        // The agent's first move since its work began: where he was then is what comes back when it ends.
        if (displayId == 0 && origin == null) { origin = placeAsked; stay = false; took = false }
    }

    /** Runs [then] once the screen has shown what the last move did ([phoneSettleWait]); at once when nothing was moved. */
    private fun settled(then: () -> Unit) {
        val generation = requestGeneration
        fun look() {
            if (!busy || generation != requestGeneration) return
            val wait = phoneSettleWait(SystemClock.uptimeMillis(), acted, lastChange)
            if (wait == 0L) then() else main.postDelayed({ look() }, minOf(wait, PHONE_SETTLE_STEP_MILLIS))
        }
        look()
    }

    /**
     * The agent's work ended (AgentTasks, once no builder works): what he had in front before its first move comes back
     * ([phoneBack]), and the next work starts afresh. Safe from any thread.
     */
    fun workEnded(): Boolean = main.post {
        if (busy) { main.postDelayed({ workEnded() }, 200); return@post }
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        val back = phoneBack(origin, place, stay, took, keyguard?.isKeyguardLocked == true)
        origin = null; stay = false; took = false
        displayStates.values.forEach { it.opened = null; it.snapshot = ""; it.seen = emptyMap() }
        PhoneAccess.clearIndicator(this)
        if (back !is PhoneBack.Bring) return@post android.util.Log.i("PhoneService", "Not brought back: ${(back as PhoneBack.Leave).why}").let { }
        runCatching {
            when (val was = back.origin) {
                PhoneOrigin.Chat -> startActivity(dev.omalauncher.oma.OmaChatActivity.intent(this))
                is PhoneOrigin.Home -> {
                    was.page?.let(HomeDoor::ask)
                    startActivity(Intent(this, dev.omalauncher.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                is PhoneOrigin.App -> packageManager.getLaunchIntentForPackage(was.pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }.onFailure { android.util.Log.w("PhoneService", "Could not bring the screen back", it) }
    }
    /** Each control the user can see in the window in front, by its id, with its node for just that call. */
    private fun walk(visit: (String, AccessibilityNodeInfo) -> Unit) {
        var count = 0
        fun go(node: AccessibilityNodeInfo, path: String, depth: Int) {
            try {
                if (depth > 40 || count >= 600 || !current(node)) return
                if (node.isVisibleToUser) { count++; visit(path, node) }
                for (i in 0 until node.childCount) node.getChild(i)?.let { go(it, "$path.$i", depth + 1) }
            } finally { node.recycle() }
        }
        go(root(), "root", 0)
    }
    // Per-request counters, returned only when timing is requested.
    private var readCalls = 0
    private var readNanos = 0L
    private var nodesRead = 0
    private var searchScrolls = 0
    private var quickFinds = 0
    /** The screen's controls, under a new snapshot id. */
    private fun read(): JSONObject {
        val readStart = SystemClock.elapsedRealtimeNanos()
        readCalls++
        val nodes = JSONArray(); val next = mutableMapOf<String, SeenControl>()
        walk { path, node ->
            val seenNow = control(path, node)
            nodes.put(JSONObject().put("id", path).put("text", if (node.isPassword) "[password]" else seenNow.text.orEmpty())
                .put("description", if (node.isPassword) "" else seenNow.description.orEmpty())
                .put("package", seenNow.packageName).put("viewId", seenNow.viewId)
                .put("bounds", JSONArray(seenNow.bounds))
                .put("clickable", node.isClickable).put("editable", node.isEditable).put("scrollable", node.isScrollable)
                .put("checked", node.isChecked).put("enabled", node.isEnabled))
            next[path] = seenNow
        }
        snapshot = UUID.randomUUID().toString(); seen = next
        displayState.readAt = SystemClock.uptimeMillis()
        nodesRead += next.size
        readNanos += SystemClock.elapsedRealtimeNanos() - readStart
        return JSONObject().put("ok", true).put("snapshot", snapshot).put("nodes", nodes)
    }
    /**
     * The node of the control the last read listed at [id], as it is now, and the id it is at now. Unchanged, it is
     * taken where it stood; moved, it is found again by [PhoneRefind], and only if exactly one control is it.
     */
    private fun target(id: String): Pair<AccessibilityNodeInfo, String> {
        val old = seen[id] ?: error("Screen changed. Read it again.")
        fun usable(path: String, same: (SeenControl) -> Boolean): AccessibilityNodeInfo? {
            val node = runCatching { resolve(path) }.getOrNull() ?: return null
            if (node.isVisibleToUser && node.isEnabled && same(control(path, node))) return node
            node.recycle(); return null
        }
        usable(id) { it == old }?.let { return it to id }
        val now = mutableListOf<SeenControl>()
        walk { path, node -> now.add(control(path, node)) }
        val path = PhoneRefind.refind(old, now) ?: error("Screen changed. Read it again.")
        // Between that look and this one it may have changed again: it is still taken only if it is still the same control.
        val node = usable(path) { it.sameAs(old) } ?: error("Screen changed. Read it again.")
        return node to path
    }
    /** One finger along [path] for [duration]; [reply] hears once it has lifted, or that Android cancelled it. */
    private fun stroke(path: Path, duration: Long, reply: (JSONObject) -> Unit) {
        requireCurrentAccess()
        changed()
        val builder = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        if (Build.VERSION.SDK_INT >= 30) builder.setDisplayId(displayId)
        val gesture = builder.build()
        val generation = requestGeneration
        check(dispatchGesture(gesture, object : GestureResultCallback() {
            // The screen starts to change when the finger lifts, not when it lands.
            override fun onCompleted(gestureDescription: GestureDescription?) { if (busy && generation == requestGeneration) { changed(); reply(JSONObject().put("ok", true)) } }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (busy && generation == requestGeneration) reply(JSONObject().put("ok", false).put("error", "Gesture cancelled")) }
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
            // Home was asked for by name: the agent's touches are no longer for the app it opened before.
            opened = null
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
    /**
     * Answers an `open` or `url` once [pkg] is the app in front, so the agent's next step isn't refused for an app still
     * on its way; after [PhoneTouch.UNTIL_MILLIS] it answers anyway, saying it isn't. With no app to wait for, at once.
     */
    private fun whenInFront(pkg: String?, output: JSONObject, reply: (JSONObject) -> Unit, expected: String = "", handedTo: Set<String> = emptySet(), through: List<String> = emptyList()) {
        output.put("opened", pkg.orEmpty()).put("destinationVerified", false)
        if (pkg == null) { reply(output); return }
        val deadline = SystemClock.uptimeMillis() + PhoneTouch.UNTIL_MILLIS
        val generation = requestGeneration
        var lastRead: Long? = null
        var chose = 0
        fun look() {
            if (!busy || generation != requestGeneration) return
            try {
                requireCurrentAccess()
                val now = SystemClock.uptimeMillis()
                // Nothing said it changed since the last look: no read, unless the fallback is due or time is up.
                if (now < deadline && !PhoneWait.readAgain(now, lastRead, lastChange)) { main.postDelayed({ look() }, PhoneTouch.UNTIL_STEP_MILLIS); return }
                lastRead = now
                // Use one observation for both package and destination: activity roots can disagree
                // during a transition. A read-only wait may observe another package until its deadline.
                val observed = read()
                val front = PhoneScreen.front(observed)
                // Android asking which app, for a step that knows its answer ([through]): picked once, as he would.
                if (front == PhoneGuard.CHOOSER && through.isNotEmpty() && chose < 2) {
                    if (clickSaying(through.getOrNull(chose).orEmpty())) chose++
                    main.postDelayed({ look() }, PhoneTouch.UNTIL_STEP_MILLIS); return
                }
                // A page the app handed on to another of its own ([handedTo]) is still the page asked for: touches go there.
                val inFront = front == pkg || front in handedTo
                if (inFront && front != pkg) opened = front
                val shows = inFront && (expected.isEmpty() || PhoneScreen.says(observed, expected))
                output.put("inFront", inFront).put("front", front)
                    .put("destinationVerified", expected.isNotEmpty() && shows)
                    .put("screen", PhoneScreen.brief(observed))
                if (shows) { reply(output); return }
                if (SystemClock.uptimeMillis() >= deadline) {
                    if (expected.isNotEmpty()) output.put("ok", false).put("error", "Requested destination did not show the expected words. Read the screen; do not repeat actions blindly.")
                    reply(output); return
                }
                main.postDelayed({ look() }, PhoneTouch.UNTIL_STEP_MILLIS)
            } catch (failure: Exception) { reply(JSONObject().put("ok", false).put("error", failure.message)) }
        }
        settled { look() }
    }
    /**
     * The id of the one control on screen that says exactly [label] and of what takes its press (itself or the nearest
     * clickable control around it), found with Android's search by words instead of a whole read; null when there is
     * not exactly one. Its id is the same path a read would give, and it is noted as seen, so [act] checks it as ever.
     */
    private fun quickTarget(label: String): String? = runCatching {
        val root = root()
        val found = root.findAccessibilityNodeInfosByText(label).filter { it.isVisibleToUser }
        val seenNodes = found.map { SeenNode("", it.text?.toString().orEmpty(), it.contentDescription?.toString().orEmpty(), it.isClickable, it.isEditable, it.isEnabled) }
        val index = PhoneFind.quick(seenNodes, label) ?: return@runCatching null
        quickFinds++
        var target: AccessibilityNodeInfo = found[index]
        var up = target
        var hops = 0
        while (!up.isClickable && hops < 6) { up = up.parent ?: break; hops++ }
        if (up.isClickable && !inWebPage(up)) target = up else if (inWebPage(target)) return@runCatching null
        // Its path from the root, as [walk] numbers children.
        val parts = ArrayDeque<Int>()
        var at = target
        var depth = 0
        while (depth < 40) {
            val parent = at.parent ?: break
            val i = (0 until parent.childCount).firstOrNull { parent.getChild(it) == at } ?: return@runCatching null
            parts.addFirst(i); at = parent; depth++
        }
        if (at != root) return@runCatching null
        val path = (listOf("root") + parts.map { it.toString() }).joinToString(".")
        seen = seen + (path to control(path, target))
        path
    }.getOrNull()
    /** Clicks the control on this display whose words are exactly [label] (or the nearest clickable around it). */
    private fun clickSaying(label: String): Boolean {
        if (label.isEmpty()) return false
        val root = displayRoot(displayId) ?: return false
        val found = root.findAccessibilityNodeInfosByText(label).firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label } ?: return false
        var at: AccessibilityNodeInfo? = found
        while (at != null && !(at.isClickable && at.isEnabled)) at = at.parent
        return at?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }
    /** Whether [node] is part of a web page: it or something around it is a WebView, as Chromium-based browsers draw pages. */
    private fun inWebPage(node: AccessibilityNodeInfo): Boolean {
        var at: AccessibilityNodeInfo? = node
        var depth = 0
        while (at != null && depth < 60) {
            if (at.className?.toString() == "android.webkit.WebView") { if (at !== node) at.recycle(); return true }
            val up = at.parent
            if (at !== node) at.recycle()
            at = up; depth++
        }
        if (at != null && at !== node) at.recycle()
        return false
    }
    /** Does [command] (click, type or scroll) to the control at [id] of the screen last read; [reply] hears how it went. */
    private fun act(command: String, id: String, input: JSONObject, reply: (JSONObject) -> Unit) {
        requireCurrentAccess()
        val (node, at) = target(id)
        // Where the control was acted on: a fill reads its words back there, and the agent is told it had moved.
        fun said(answer: JSONObject) = if (at == id) answer else answer.put("moved", true).put("id", at)
        try {
            val done = when (command) {
                // A web page's controls take Android's click and do nothing (Brave, 2026-10-21: "Learn more" on
                // example.com, clicked twice, never followed), while a finger on them works: a page's control is tapped.
                "click" -> !inWebPage(node) && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
                val metrics = displayMetrics()
                val (x, y) = PhoneTouch.middle(bounds.left, bounds.top, bounds.right, bounds.bottom, metrics.widthPixels, metrics.heightPixels)
                    ?: error("Android rejected this click and the control is not on screen to tap. Read the screen again.")
                stroke(Path().apply { moveTo(x, y) }, TAP_MILLIS) { reply(if (it.optBoolean("ok")) said(it.put("tapped", true)) else it) }
                return
            }
            check(done) { "Android rejected this action. Read the screen again or use a gesture." }
            changed()
        } finally { node.recycle() }
        reply(said(JSONObject().put("ok", true)))
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
        override fun wipeInk(page: String) = book.wipe(page)
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
    /** Home's modules for the canvas command, with its saved layouts: the pad the Layouts module loads through too. */
    private fun canvasPad(): dev.omalauncher.canvas.CanvasPad = dev.omalauncher.widgets.FoldLayouts.pad(this)
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
    fun request(input: JSONObject, callback: (JSONObject) -> Unit) {
        val command = input.optString("command")
        if (command == "stop") { PhoneAccess.stop(this); callback(JSONObject().put("ok", true)); return }
        if (busy) { callback(JSONObject().put("ok", false).put("error", "Another phone request is running. Read the screen again.")); return }
        val plan = try { if (command == "plan") PhonePlan.parse(input, System.currentTimeMillis()) else null }
            catch (failure: Exception) { callback(JSONObject().put("ok", false).put("error", failure.message)); return }
        val selected = input.optString("display", "main")
        val target = try {
            require(selected == "main" || selected == "bg") { "Display must be main or bg." }
            if (selected == "bg") {
                if (plan != null) plan.steps.forEach { PhoneBackgroundCommands.requireAllowed(it.getString("command")) }
                else PhoneBackgroundCommands.requireAllowed(command)
                PhoneDisplays.background(this).id
            } else 0
        } catch (e: Exception) { callback(JSONObject().put("ok", false).put("error", e.message)); return }
        displayId = target; busy = true; requestCommand = command
        val generation = ++requestGeneration
        val requestStart = SystemClock.elapsedRealtimeNanos()
        readCalls = 0; readNanos = 0; nodesRead = 0; searchScrolls = 0; quickFinds = 0
        val now = SystemClock.elapsedRealtime()
        requestDeadline = minOf(now + (plan?.timeoutMs ?: 7500L), input.optLong("_bridgeDeadline", Long.MAX_VALUE))
        val budget = (requestDeadline - now).coerceAtLeast(0L)
        var finished = false
        var timeout: Runnable? = null
        val reply: (JSONObject) -> Unit = { output ->
            if (!finished && generation == requestGeneration) {
                finished = true
                requestGeneration++
                activePlan = null
                timeout?.let(main::removeCallbacks)
                output.put("displayId", target)
                if (input.optBoolean("timing")) output.put("timing", JSONObject()
                    .put("serviceMs", (SystemClock.elapsedRealtimeNanos() - requestStart) / 1_000_000.0)
                    .put("reads", readCalls).put("readMs", readNanos / 1_000_000.0)
                    .put("nodes", nodesRead).put("searchScrolls", searchScrolls).put("quickFinds", quickFinds))
                displayId = 0; busy = false
                callback(output)
            }
        }
        timeout = Runnable {
            if (activePlan != null) activePlan?.cancel("timed-out", "Plan deadline reached; inspect receipt before retrying.")
            else reply(JSONObject().put("ok", false).put("error", "Phone request deadline reached; action may have run.").put("actionMayHaveRun", true))
        }
        main.postDelayed(timeout!!, budget)
        if (plan == null) { execute(input, generation, reply); return }
        try {
            requireCurrentAccess()
            val existing = planJournal.begin(plan)
            if (existing != null) { reply(existing); return }
            val runner = PhonePlanRunner(plan, SystemClock::elapsedRealtime, {
                check(busy && generation == requestGeneration) { "Phone request is no longer active" }
                requireCurrentAccess()
            }, { step, done -> execute(step, generation, done) }) { result ->
                try { planJournal.end(plan, result.getString("state"), result.getInt("completed")) }
                catch (failure: Exception) {
                    result.put("ok", false).put("error", "Could not finalize receipt; do not replay mutations.")
                }
                reply(result)
            }
            runner.schedule = { step -> main.post(step) }
            activePlan = runner
            runner.start()
        } catch (failure: Exception) { reply(JSONObject().put("ok", false).put("error", failure.message)) }
    }

    /** All individual and planned steps share this action implementation and its admission checks. */
    private fun execute(input: JSONObject, generation: Long, reply: (JSONObject) -> Unit) {
        val command = input.optString("command")
        requestCommand = command
        fun requireThisRequest() {
            check(busy && generation == requestGeneration) { "Phone request is no longer active" }
            requireCurrentAccess()
        }
        try {
            // Where he was as this request came in: if it is the agent's first move, that is where it comes back to.
            placeAsked = place
            // The checks and their order are PhoneRequests.admit's; nothing below runs for a request it didn't let through.
            // A request of a thread on the Mac waits for the owner as the phone builder's does, and its card is on this screen too.
            val route = PhoneRequests.admit(command, { requireThisRequest() }) { dev.omalauncher.assist.AgentTasks.pending.size + dev.omalauncher.assist.MacThreads.pending.size }
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
            // Home's modules arranged as he asked, behind the same gates; one request of his is one restore point.
            if (route == PhoneRequests.Route.Canvas) {
                // The quick lane says it is the quick lane; anyone else working is a builder, so a builder's calls made while
                // the quick lane answers a question stay its own request.
                val quick = input.optString("actor") == "quick"
                val (request, actor) = when {
                    quick && dev.omalauncher.assist.QuickLane.working -> "quick-${dev.omalauncher.assist.QuickLane.time}" to "agent:quick"
                    dev.omalauncher.assist.AgentTasks.working -> "task-${dev.omalauncher.assist.AgentTasks.startedAt}" to "agent:builder"
                    dev.omalauncher.assist.QuickLane.working -> "quick-${dev.omalauncher.assist.QuickLane.time}" to "agent:quick"
                    else -> "phone-${System.currentTimeMillis()}" to "agent:phone"
                }
                reply(PhoneCanvas.reply(input, canvasPad(), request, actor)); changed(); return
            }
            // A widget a phone agent wrote, into the shared library on the Mac Pro (Soma Canvas slice 8a): off the main thread,
            // with this phone's own key for each Mac until one answers as the service.
            if (route == PhoneRequests.Route.WidgetInstall) {
                val machines = dev.omalauncher.assist.readMacMachines((application as dev.omalauncher.App).runtime.proot.launcherConfig)
                val name = input.getString("name"); val text = input.getString("text")
                kotlin.concurrent.thread(name = "widget-install") {
                    val answer = runCatching { dev.omalauncher.canvas.installOnSomaService(machines, name, text) }.getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "couldn't reach the Macs") }
                    main.post { reply(answer) }
                }
                return
            }
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
                sheepHeld = true; sheep.hide(); shot.hide()
                val settle = ring.beforeCapture()
                // It wouldn't come down: no picture is taken with it up.
                if (settle == CAPTURE_REFUSED) { sheepHeld = false; syncRing(); error("Screenshot unavailable: the ring overlay could not be removed. Try again.") }
                fun captured() { sheepHeld = false; lookAgain(ring.afterCapture()); syncRing() }
                val capture = Runnable { try { requireThisRequest(); takeScreenshot(displayId, mainExecutor, object : TakeScreenshotCallback {
                    override fun onFailure(code: Int) { captured(); reply(JSONObject().put("ok", false).put("error", "Screenshot unavailable: $code. Protected screens cannot be captured.")) }
                    override fun onSuccess(result: ScreenshotResult) {
                        captured()
                        val hardware = result.hardwareBuffer
                        try {
                            requireThisRequest()
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
            // A touch is for the app the agent opened, or the one it names, and never types into his own chat box.
            val currentRoot = runCatching { displayRoot(displayId) }.getOrNull()
            val currentFront = currentRoot?.packageName?.toString().orEmpty(); currentRoot?.recycle()
            PhoneGuard.refusal(command, front = currentFront,
                asked = input.optString("in"), any = input.optBoolean("any"), opened = opened, own = packageName, chat = place == PhoneOrigin.Chat)
                ?.let { error(it) }
            val output = JSONObject().put("ok", true)
            when (command) {
                "apps" -> {
                    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    val apps = packageManager.queryIntentActivities(intent, 0).distinctBy { it.activityInfo.packageName }
                    output.put("apps", JSONArray(apps.map { JSONObject().put("package", it.activityInfo.packageName).put("name", it.loadLabel(packageManager).toString()) }))
                }
                "open" -> {
                    val name = input.getString("package")
                    val settingsAction = PhoneSettings.action(name)
                    val intent = if (settingsAction != null) android.content.Intent(settingsAction)
                        else packageManager.getLaunchIntentForPackage(name) ?: error("No visible launcher activity for this package. Run apps.")
                    // Settings shares a task affinity: NEW_TASK alone can bring a different settings page forward.
                    // Clear only activities above the requested destination, without clearing the whole task.
                    if (settingsAction != null) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    val expected = input.optString("until")
                    require(expected.length <= 200) { "Expected screen text is too long." }
                    // What opens it is the app the agent's touches are for from here.
                    val handler = if (settingsAction != null) {
                        PhoneSettings.handler(packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.distinct(), settingsAction)
                            ?.also { intent.setPackage(it) }
                            ?: error("This Settings destination is unavailable on this phone. Nothing was opened.")
                    } else name
                    opened = PhoneGuard.opened(handler)
                    launchOnDisplay(intent, opened)
                    changed()
                    whenInFront(opened, output, reply, expected, if (settingsAction != null) PhoneSettings.handsOverTo(handler) else emptySet(), PhoneSettings.chooserAnswer(settingsAction))
                    return
                }
                // A link opened as a link (handoff 2026-10-21): never typed into an address bar.
                "url" -> {
                    val link = PhoneGuard.webUrl(input.getString("url")).getOrThrow()
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)
                    input.optString("app").takeIf { it.isNotEmpty() }?.let { intent.setPackage(it) }
                    val handler = packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
                        ?: error(if (intent.`package` != null) "${intent.`package`} can't open that link. Nothing was opened." else "No app on the phone opens that link. Nothing was opened.")
                    opened = PhoneGuard.opened(handler)
                    launchOnDisplay(intent, opened)
                    changed()
                    whenInFront(opened, output.put("chooser", opened == null), reply)
                    return
                }
                // The keyboard's own Enter, Go or Search, on the field that has it: no key read off a picture.
                "submit" -> {
                    if (Build.VERSION.SDK_INT < 30) error("Submit needs Android 11 or later.")
                    val root = root()
                    val field = try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } finally { root.recycle() }
                        ?: error("No text field has the keyboard. Fill one first. Nothing was done.")
                    try {
                        check(field.isEditable) { "What has the keyboard is not a text field. Nothing was done." }
                        check(field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) { "The field did not take Enter. Read the screen again." }
                    } finally { field.recycle() }
                    changed()
                    settled {
                        try { requireThisRequest(); reply(PhoneScreen.brief(read())) }
                        catch (e: Exception) { reply(JSONObject().put("ok", false).put("error", e.message ?: "Phone control failed")) }
                    }
                    return
                }
                "read" -> {
                    // Right after one of this service's own actions the screen is still on its way to what the action did.
                    settled {
                        try { requireThisRequest(); reply(read()) }
                        catch (e: Exception) { reply(JSONObject().put("ok", false).put("error", e.message ?: "Phone control failed")) }
                    }
                    return
                }
                // The screen the agent ends on is for him ("open Spotify"): it is not taken back when the work ends.
                "stay" -> stay = true
                "click", "type", "scroll" -> {
                    check(input.getString("snapshot") == snapshot && snapshot.isNotEmpty()) { "Read the screen first; snapshot is stale." }
                    act(command, input.getString("id"), input, reply)
                    return
                }
                "screen" -> {
                    val until = input.optString("until")
                    val deadline = SystemClock.uptimeMillis() + if (until.isEmpty()) 0L else PhoneTouch.UNTIL_MILLIS
                    // The reader already has this screen and nothing changed since: say so rather than send it again.
                    val since = input.optString("since")
                    if (until.isEmpty() && input.optString("in").isEmpty() && PhoneWait.unchanged(since, snapshot, displayState.readAt, lastChange)) {
                        reply(JSONObject().put("ok", true).put("snapshot", snapshot).put("unchanged", true)); return
                    }
                    var lastRead: Long? = null
                    // Looks again until the words show or the time is up; what it then gives is the screen as it stands.
                    fun look() {
                        try {
                            requireThisRequest()
                            val now = SystemClock.uptimeMillis()
                            if (lastRead != null && now < deadline && !PhoneWait.readAgain(now, lastRead, lastChange)) { main.postDelayed({ look() }, PhoneTouch.UNTIL_STEP_MILLIS); return }
                            lastRead = now
                            val seen = read()
                            val expectedPackage = input.optString("in")
                            val inFront = expectedPackage.isEmpty() || PhoneScreen.front(seen) == expectedPackage
                            val shows = inFront && (until.isEmpty() || PhoneScreen.says(seen, until))
                            if (!shows && SystemClock.uptimeMillis() < deadline) { main.postDelayed({ look() }, PhoneTouch.UNTIL_STEP_MILLIS); return }
                            PhoneScreen.requireFront(seen, expectedPackage)
                            val brief = PhoneScreen.brief(seen).also { if (until.isNotEmpty()) it.put("shows", shows) }
                            val before = displayState.lastBrief
                            displayState.lastBrief = brief
                            // Only what changed, when asked and the reader has the screen it names.
                            val delta = if (input.optBoolean("delta") && before != null && since.isNotEmpty() && before.optString("snapshot") == since) PhoneScreen.delta(before, brief) else null
                            reply(delta?.also { if (until.isNotEmpty()) it.put("shows", shows) } ?: brief)
                        } catch (e: Exception) { reply(JSONObject().put("ok", false).put("error", e.message ?: "Phone control failed")) }
                    }
                    settled { look() }
                    return
                }
                "press", "fill" -> {
                    val label = input.getString("label")
                    // One call does the step and gives back what it led to, so the next step starts from the real screen.
                    fun step(id: String, scrolled: Int) = act(if (command == "press") "click" else "type", id, input) { done ->
                        if (!done.optBoolean("ok")) { reply(done); return@act }
                        settled {
                            try {
                                requireThisRequest()
                                reply(PhoneScreen.after(read(), command, done.optString("id", id), if (command == "fill") input.getString("text") else null)
                                    .also { if (scrolled > 0) it.put("scrolled", scrolled) }.also { displayState.lastBrief = it })
                            } catch (e: Exception) { reply(JSONObject().put("ok", false).put("error", e.message ?: "Phone control failed")) }
                        }
                    }
                    // A label below or above what shows is scrolled to, the way a finger would: on, then back, a few pages each way.
                    val progress = PhoneSearchProgress()
                    fun look(forward: Boolean, tries: Int, scrolled: Int) {
                        try {
                            requireThisRequest()
                            val seen = read()
                            val nodes = PhoneScreen.nodes(seen)
                            when (val found = if (command == "press") PhoneFind.press(nodes, label) else PhoneFind.field(nodes, label)) {
                                is Found.One -> return step(found.id, scrolled)
                                is Found.Several -> error("Several controls answer to \"$label\": ${found.ids.joinToString(", ")}. Nothing was done. Use a longer label, or click/type with snapshot $snapshot and one of these ids.")
                                Found.None -> Unit
                            }
                            val list = PhoneScreen.scroller(seen)
                            val repeated = progress.repeated(nodes, forward)
                            if (forward && (repeated || tries >= PHONE_FIND_SCROLLS)) return look(false, 0, scrolled)
                            val more = tries < PHONE_FIND_SCROLLS && !repeated
                            if (list == null || !more) {
                                error("Nothing on screen ${if (command == "press") "says" else "is a text field that says"} \"$label\"${if (scrolled > 0) ", even scrolled through" else ""}. Nothing was done. Run oma-phone screen.")
                            }
                            // At the end of the list Android scrolls it no further: the other way is tried from there.
                            val turned = runCatching {
                                searchScrolls++
                                act("scroll", list, JSONObject().put("direction", if (forward) "forward" else "backward")) { settled { look(forward, tries + 1, scrolled + 1) } }
                            }
                            if (turned.isFailure) {
                                if (forward) return look(false, 0, scrolled)
                                error("Nothing on screen ${if (command == "press") "says" else "is a text field that says"} \"$label\", even scrolled through. Nothing was done. Run oma-phone screen.")
                            }
                        } catch (e: Exception) { reply(JSONObject().put("ok", false).put("error", e.message ?: "Phone control failed")) }
                    }
                    // One control that says exactly this, found by Android's own search without reading the whole screen:
                    // pressed straight away. Anything less certain reads the screen as before.
                    if (command == "press") quickTarget(label)?.let { return step(it, 0) }
                    look(forward = true, tries = 0, scrolled = 0)
                    return
                }
                "back", "home", "recents", "notifications" -> {
                    val action = when (command) { "back" -> GLOBAL_ACTION_BACK; "home" -> GLOBAL_ACTION_HOME; "recents" -> GLOBAL_ACTION_RECENTS; else -> GLOBAL_ACTION_NOTIFICATIONS }
                    check(performGlobalAction(action)) { "Android rejected navigation" }
                    // Leaving for home, recents or the shade is the agent's own choice: its touches are no longer held to the app.
                    if (command != "back") opened = null
                    changed()
                }
                "tap", "swipe" -> {
                    val metrics = displayMetrics()
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

/**
 * Home's modules for the canvas command, changed through the notebook as the overview and a drag change them: the service's
 * pad, and the Layouts module's ([dev.omalauncher.widgets.FoldLayouts.pad], with the saved layouts beside it).
 */
internal fun homeCanvasPad(context: Context): dev.omalauncher.widgets.FoldCanvasPad {
    val app = context.applicationContext
    val runtime = (app as dev.omalauncher.App).runtime
    return dev.omalauncher.widgets.FoldCanvasPad(
        runtime.widgets.notebook,
        addPage = { runtime.widgets.addPage() },
        // Only a page of modules comes here: they go with it, and its still too, as when it is deleted in the overview.
        removePage = { page -> runtime.widgets.deletePage(page, move = false)?.also { runtime.pageStills.drop(page) } != null },
        points = dev.omalauncher.widgets.foldRestorePoints(app),
        undoneHere = { request, verbs -> dev.omalauncher.widgets.foldUndonePending(app).add(request, verbs) },
        // Not read yet counts as marked: asked again in a moment, the page will have been read.
        marked = { page ->
            val book = dev.omalauncher.widgets.annotations.HomeAnnotations.of(app)
            book.whenRead(page) {}
            book.status(page) != dev.omalauncher.widgets.annotations.AnnotationBook.Status.Ready || !book.on(page).isEmpty
        },
    )
}
