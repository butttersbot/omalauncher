package dev.omalauncher.assist

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.*
import android.service.voice.*
import android.view.*
import android.widget.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.omalauncher.App
import dev.omalauncher.oma.ring.OmaRing
import dev.omalauncher.oma.ring.RingView
import dev.omalauncher.ui.Motion
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.Speech
import dev.omalauncher.oma.OwnScreens
import dev.omalauncher.oma.SCREENSHOT_WAIT_MILLIS
import dev.omalauncher.oma.ScreenContext
import dev.omalauncher.oma.ScreenOffer
import dev.omalauncher.oma.ScreenShots
import dev.omalauncher.oma.Shot
import dev.omalauncher.oma.Summon
import dev.omalauncher.oma.screenChipText
import java.io.File

class OmaVoiceService : VoiceInteractionService()
class OmaSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = OmaSession(this)
}
class OmaSession(context: Context) : VoiceInteractionSession(context) {
    private var panel: ListeningHost? = null
    /** This showing and what is known of its screenshot; null while hidden, so a late screenshot attaches to nothing. */
    private var offer: ScreenOffer? = null
    /** Counts showings, so a timeout or a saved file from an earlier one is recognised and dropped. */
    private var showingNo = 0
    private val main = Handler(Looper.getMainLooper())
    override fun onCreateContentView(): View {
        (context.applicationContext as App).runtime.start()
        return ListeningHost(context) { hide() }.also {
            panel = it
            // Sent, or the note tapped off: whatever screenshot arrives after that is dropped.
            it.onScreenLetGo = { offer = offer?.close() }
        }
    }
    /** The earliest word that the assistant is coming up: the ring over other apps gets out of the way of Android's screenshot. */
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        OmaRing.assistant(true)
        super.onPrepareShow(args, showFlags)
    }
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        OmaRing.assistant(true)
        // The whole screen: the words sit on the paper over the app, with the ring at the screen's edge.
        window.window?.let {
            it.setGravity(Gravity.FILL)
            it.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            panel?.dress(it)
        }
        // Only Android's own assistant gesture brings the screen along; Android then sends its screenshot to onHandleScreenshot.
        val summon = if (showFlags and SHOW_SOURCE_ASSIST_GESTURE != 0) Summon.AssistGesture else Summon.Other
        val no = ++showingNo
        offer = null
        update(ScreenOffer(summon, OwnScreens.visible, context.getSystemService(KeyguardManager::class.java).isKeyguardLocked))
        // With the system's "use screenshot" setting off, Android may never call back.
        main.postDelayed({ if (no == showingNo) offer?.let { update(it.timedOut()) } }, SCREENSHOT_WAIT_MILLIS)
        panel?.begin()
    }
    /** Shows a changed state on the chip. [file] is the screenshot just saved, and only ever comes with the attached state. */
    private fun update(next: ScreenOffer, file: File? = null) {
        if (next == offer) return ScreenShots.discard(file)
        offer = next
        val state = next.context
        if (state != ScreenContext.Attached) ScreenShots.discard(file)
        panel?.showScreen(state, file.takeIf { state == ScreenContext.Attached }) ?: ScreenShots.discard(file)
    }
    /** When Android says which app is underneath (it may not): over Oma's own screens nothing is attached. */
    override fun onHandleAssist(state: AssistState) {
        if (state.assistStructure?.activityComponent?.packageName == context.packageName) offer?.let { update(it.overOwnScreen()) }
    }
    /** The only source of the screen: Android's screenshot for this session, null for secure windows or when switched off. */
    override fun onHandleScreenshot(screenshot: Bitmap?) {
        val arrived = offer?.arrived(screenshot != null) ?: return
        update(arrived)
        if (screenshot == null || arrived.shot != Shot.Saving) return
        // Scaling and compressing a full screen takes a moment: the chip says it is getting the screen meanwhile.
        val no = showingNo
        val app = context.applicationContext
        kotlin.concurrent.thread(name = "oma-screen") {
            val file = ScreenShots.keep(app, screenshot)
            main.post {
                val now = offer
                // Hidden, or shown again, while it was being written: it belongs to no chip.
                if (no != showingNo || now == null) ScreenShots.discard(file) else update(now.saved(file != null), file)
            }
        }
    }
    override fun onHide() {
        showingNo++; offer = null
        panel?.end()
        OmaRing.assistant(false)
        super.onHide()
    }
    override fun onDestroy() { panel?.dispose(); OmaRing.assistant(false); super.onDestroy() }
}

/** Fallback assist intent and notification entry; leaves the current app visible underneath. */
class AssistantPromptActivity : Activity() {
    private lateinit var panel: ListeningHost
    override fun onCreate(state: Bundle?) {
        // The manifest's dialog theme keeps the app underneath showing; the window itself is drawn without the dialog's box.
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(state)
        (application as App).runtime.start()
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        panel = ListeningHost(this) { finish() }
        setContentView(panel)
        panel.dress(window)
        panel.begin()
    }
    override fun onDestroy() { panel.dispose(); super.onDestroy() }
    /** Covered or left: the microphone doesn't stay open behind something else. */
    override fun onPause() { super.onPause(); if (!isFinishing) { panel.end(); finish() } }
}

/**
 * The listening screen as a view, for the assistant session's window and the prompt activity: the ring along
 * the window's edge and [ListeningLayer] inside it, as design/oma-scrapbook-prototype.html draws them. The
 * microphone opens as the screen comes up; what is heard waits on the screen until he taps Send, and he can
 * speak again to add to it first. Cancel or a tap on the paper leaves and sends nothing; [ListeningRequest]
 * decides all of that. Typing, attachments, the answer and New thread live in the Oma chat.
 */
class ListeningHost(private val owner: Context, private val dismiss: () -> Unit) : FrameLayout(owner) {
    private val ring = RingView(owner)
    private val compose = ComposeHost()
    private val handler = Handler(Looper.getMainLooper())
    private val speech = Speech(owner)
    /** The paper over whatever is underneath, as the window's background so it reaches under the system bars. */
    private val paper = ColorDrawable(Color.TRANSPARENT)
    /** The request being taken; null while the screen is away or on its way out. */
    private var request by mutableStateOf<ListeningRequest?>(null)
    private var chipState by mutableStateOf(ScreenContext.None)
    /** Counts requests, so a recognizer's answer or a fade from an earlier one does nothing. */
    private var turn = 0
    private var disposed = false
    /** The screenshot that sending would attach; never set without the note saying so. */
    private var screen: File? = null
    /** Told when the request went out or the note was tapped off, so a screenshot still on its way is dropped. */
    var onScreenLetGo: (() -> Unit)? = null

    init {
        compose.attach(this)
        addView(ComposeView(owner).apply {
            setContent {
                val look = request?.let {
                    val claude = (owner.applicationContext as App).runtime.agents.default?.bin == "claude"
                    it.look(speech.text, screenChipText(chipState, keptInConversation = claude))
                }
                // The recognizer can fail without a word: that is a problem to show, not a silence.
                val failed = speech.error
                LaunchedEffect(failed) { if (failed != null) spoke("") }
                ListeningLayer(look, ::act, onNote = { showScreen(ScreenContext.None, null); onScreenLetGo?.invoke() }, onDim = { paper.color = Pack.paper.copy(alpha = it).toArgb() }, onEdit = { text -> request = request?.edited(text) })
            }
        }, LayoutParams(-1, -1))
    }

    /**
     * Makes [window] the listening screen's: the paper as its background and the ring along its real edges,
     * behind the words. Called again each time the window shows.
     */
    fun dress(window: Window) {
        window.setBackgroundDrawable(paper)
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        // No scrim of the system's behind its buttons: the paper is the only thing over the screen.
        window.isNavigationBarContrastEnforced = false; window.isStatusBarContrastEnforced = false
        // The window's own root has to carry the owners: Compose looks for them from there.
        compose.attach(window.decorView)
        if (ring.parent != null) return
        // Straight in the window's own frame, which the system bars don't inset; failing that, around the words' area.
        val edge = window.decorView as? ViewGroup ?: this
        edge.addView(ring, 0, ViewGroup.LayoutParams(-1, -1))
    }

    /**
     * The screen came up: says what is in the way of a new request, or opens the microphone. A task that is
     * running is not in the way (Boss, 2026-10-13): what he says then waits its turn, as what he writes does.
     */
    fun begin() {
        if (disposed) return
        compose.resume()
        turn++
        AgentTasks.restore(owner)
        // Asked beside the listening, so the front agent's board knows the Mac's threads by the time he has spoken.
        MacThreads.open(owner)
        when {
            AgentTasks.pending.isNotEmpty() -> show(ListeningRequest(ListeningPhase.NeedsOk, detail = "Oma is waiting for your answer"))
            owner.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> show(ListeningRequest(ListeningPhase.NoMic))
            else -> listen(ListeningRequest(ListeningPhase.Listening))
        }
    }

    /** The screen went away, whoever took it: nothing is heard, sent or kept after this. */
    fun end() {
        turn++
        speech.cancel()
        show(null)
        showScreen(ScreenContext.None, null)
        compose.pause()
    }

    fun dispose() { disposed = true; end(); handler.removeCallbacksAndMessages(null); compose.destroy() }

    /** The ring listens for as long as the microphone is open, and no longer. */
    private fun show(next: ListeningRequest?) {
        request = next
        OmaRing.hearing(this, next?.phase == ListeningPhase.Listening)
    }

    /** Opens the microphone for [next], which already holds whatever the new words are to be added to. */
    private fun listen(next: ListeningRequest) {
        val mine = ++turn
        show(next)
        speech.start(hold = false) { said -> if (mine == turn) spoke(said) }
    }

    /** The microphone closed: the words wait for his go, and nothing is sent from here. */
    private fun spoke(said: String) {
        val now = request ?: return
        if (now.phase == ListeningPhase.Listening) show(now.spoke(said, speech.error))
    }

    private fun send(text: String) {
        try {
            // The front agent hears words alone first, or next if it is still on a request, and its answer comes to
            // him as a task's does. With the screen attached, or with no front agent to ask, the request goes
            // straight to the build lane as it always did, and waits behind the task that runs there if one does.
            // Only the screenshot the note is showing goes along, and it is AgentTasks's to delete from here.
            if (screen != null || !QuickLane.offer(owner, text, away = true)) AgentTasks.direct(owner, text, screen = screen)
            screen = null; showScreen(ScreenContext.None, null); onScreenLetGo?.invoke()
            leave()
        } catch (e: Exception) { show(ListeningRequest(ListeningPhase.Failed, detail = e.message ?: "The request could not be sent")) }
    }

    private fun act(act: ListeningAct) {
        // Once the screen is on its way out a second tap finds no request, so nothing is sent twice.
        val step = request?.act(act) ?: return
        when (val effect = step.effect) {
            null -> {}
            ListeningEffect.Listen -> step.next?.let(::listen)
            is ListeningEffect.Send -> send(effect.text)
            // A screenshot on the note is discarded on the way out.
            ListeningEffect.Leave -> leave()
            ListeningEffect.Allow -> { owner.startActivity(Intent(owner, AssistantSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); leave() }
            ListeningEffect.Review -> {
                // The oldest one, chosen at the tap; the screen then answers only that one.
                AgentTasks.pending.firstOrNull()?.let { owner.startActivity(AgentNotices.reviewIntent(owner, it)) }
                turn++; show(null)
                dismiss() // VoiceInteractionSession otherwise stays above the activity's modal.
            }
        }
    }

    /** Lifts the paper, then closes the window under it. */
    private fun leave() {
        val mine = ++turn
        speech.cancel()
        show(null)
        handler.postDelayed({ if (mine == turn) dismiss() }, if (Motion.reduced) 0L else LISTENING_DIM_MILLIS.toLong())
    }

    /**
     * The "this screen" note. A screenshot is held only while the note shows it: tapping the note, closing
     * the screen and sending all let go of it, and whatever is let go of unsent is deleted.
     */
    fun showScreen(state: ScreenContext, file: File?) {
        val kept = file?.takeIf { state == ScreenContext.Attached }
        if (screen != kept) ScreenShots.discard(screen)
        if (file != kept) ScreenShots.discard(file)
        screen = kept
        // Attached without a file can't be sent, so it never reads as attached.
        chipState = if (state == ScreenContext.Attached && kept == null) ScreenContext.Unavailable else state
    }

    /** Before the Compose view inside attaches and looks for its owners from the window's root. */
    override fun onAttachedToWindow() { compose.attach(rootView); super.onAttachedToWindow() }
    override fun onDetachedFromWindow() { OmaRing.hearing(this, false); super.onDetachedFromWindow() }
}

/** What a Compose view needs from the window it is in, for windows that no activity of androidx owns. */
internal class ComposeHost : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this).apply { performRestore(null) }
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    fun attach(view: View) { view.setViewTreeLifecycleOwner(this); view.setViewTreeSavedStateRegistryOwner(this) }
    fun resume() { if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = Lifecycle.State.RESUMED }
    fun pause() { if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = Lifecycle.State.CREATED }
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}

class AssistantSetupActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val view = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        view.addView(TextView(this).apply { text = "Oma assistant\n\nChoose Oma as your default assistant to open a compact prompt over any app. Speak or type, then send; Claude/Codex works in the background. Your existing Google assistant shortcut will use Oma instead.\n\nFor the power button, check Android Settings → System → Gestures → Press and hold power button → Digital assistant. Availability depends on the phone.\n"; textSize = 18f })
        fun button(text: String, action: () -> Unit) { view.addView(Button(this).apply { this.text = text; setOnClickListener { action() } }) }
        button("Use Oma as my assistant") {
            // ASSISTANT is not requestable via RoleManager on some Pixels.
            try { startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            catch (e: android.content.ActivityNotFoundException) { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
        }
        button("Allow voice input") { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 2) }
        button("Try the prompt") { startActivity(Intent(this, AssistantPromptActivity::class.java)) }
        button("Android settings") { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        setContentView(view)
    }
}
