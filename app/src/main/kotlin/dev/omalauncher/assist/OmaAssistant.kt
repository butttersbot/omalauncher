package dev.omalauncher.assist

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.service.voice.*
import android.speech.*
import android.view.*
import android.widget.*
import androidx.compose.ui.graphics.toArgb
import dev.omalauncher.App
import dev.omalauncher.oma.ring.OmaRing
import dev.omalauncher.oma.ring.RingView
import dev.omalauncher.ui.HomeGap
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.TextSize
import dev.omalauncher.ui.ThemePalette
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
    private var panel: AssistantPanel? = null
    /** This showing and what is known of its screenshot; null while hidden, so a late screenshot attaches to nothing. */
    private var offer: ScreenOffer? = null
    /** Counts showings, so a timeout or a saved file from an earlier one is recognised and dropped. */
    private var showingNo = 0
    private val main = Handler(Looper.getMainLooper())
    override fun onCreateContentView(): View {
        (context.applicationContext as App).runtime.start()
        return AssistantPanel(context, { hide() }).also {
            panel = it
            // Sent, or the chip tapped off: whatever screenshot arrives after that is dropped.
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
        // The whole screen: the words sit over the dimmed app, with the ring at the screen's edge.
        window.window?.let {
            it.setGravity(Gravity.FILL)
            it.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            panel?.dress(it)
        }
        // Only Android's own assistant gesture brings the screen along; Android then sends its screenshot to onHandleScreenshot.
        val summon = if (showFlags and SHOW_SOURCE_ASSIST_GESTURE != 0) Summon.AssistGesture else Summon.Other
        val no = ++showingNo
        offer = null
        update(ScreenOffer(summon, OwnScreens.visible, context.getSystemService(KeyguardManager::class.java).isKeyguardLocked))
        // With the system's "use screenshot" setting off, Android may never call back.
        main.postDelayed({ if (no == showingNo) offer?.let { update(it.timedOut()) } }, SCREENSHOT_WAIT_MILLIS)
        panel?.refresh()
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
        panel?.cancelSpeech()
        showingNo++; offer = null
        panel?.showScreen(ScreenContext.None, null)
        OmaRing.assistant(false)
        super.onHide()
    }
    override fun onDestroy() { panel?.dispose(); OmaRing.assistant(false); super.onDestroy() }
}

/** Fallback assist intent and notification entry; leaves the current app visible underneath. */
class AssistantPromptActivity : Activity() {
    private lateinit var panel: AssistantPanel
    override fun onCreate(state: Bundle?) {
        // The manifest's dialog theme keeps the app underneath showing; the window itself is drawn without the dialog's box.
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(state)
        (application as App).runtime.start()
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        panel = AssistantPanel(this, { finish() })
        setContentView(panel)
        panel.dress(window)
    }
    override fun onDestroy() { panel.dispose(); super.onDestroy() }
    override fun onPause() { panel.cancelSpeech(); super.onPause() }
}

/**
 * The listening screen, shared by the assistant session and the prompt activity: the owner's words, large,
 * over the dimmed screen, with the ring at its edge and no panel around them. The words are a text field,
 * so tapping them edits; the mic starts only from Speak, and nothing is sent until Send is tapped.
 */
class AssistantPanel(private val owner: Context, private val dismiss: () -> Unit) : FrameLayout(owner) {
    private val ring = RingView(owner)
    private val column = LinearLayout(owner)
    private val input = EditText(owner)
    private val status = TextView(owner)
    private val output = TextView(owner)
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private lateinit var resultScroll: ScrollView
    private lateinit var stopButton: TextView
    private lateinit var sendButton: TextView
    private lateinit var speakButton: TextView
    private lateinit var approvalButton: TextView
    private lateinit var newThreadButton: TextView
    private lateinit var hideButton: TextView
    private lateinit var taskRow: LinearLayout
    /** The theme the views were last colored from. */
    private var dressed: ThemePalette? = null
    private var disposed = false
    private var message: String? = null
    private val chip = LinearLayout(owner)
    private val chipImage = ImageView(owner)
    private val chipText = TextView(owner)
    /** The screenshot that Send would attach; never set without the chip showing it. */
    private var screen: File? = null
    private var chipState = ScreenContext.None
    /** Told when Send went out or the chip was tapped off, so a screenshot still on its way is dropped. */
    var onScreenLetGo: (() -> Unit)? = null
    private val update = object : Runnable {
        override fun run() { if (!disposed) { refresh(); handler.postDelayed(this, 500) } }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    /** A control in the launcher's look: mono text in a frame, like its tiles. */
    private fun control(row: LinearLayout, text: String, action: () -> Unit): TextView = TextView(owner).apply {
        this.text = text; typeface = Typeface.MONOSPACE; textSize = TextSize.Body.value
        setPadding(dp(14), dp(10), dp(14), dp(10))
        isClickable = true; isFocusable = true
        setOnClickListener { action() }
        row.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(HomeGap.value.toInt()); topMargin = dp(HomeGap.value.toInt()) })
    }
    /** The launcher's frame for a plain view: Modifier.frame's fill, line and corner style. */
    private fun frame(fill: androidx.compose.ui.graphics.Color, line: androidx.compose.ui.graphics.Color) = GradientDrawable().apply {
        setColor(fill.toArgb()); setStroke(dp(2), line.toArgb()); cornerRadius = dp(16) * LauncherStyle.corners.scale
    }
    /** Colors everything from the theme; again whenever the theme has changed since. */
    private fun dress() {
        val palette = Palette.current
        if (dressed === palette) return
        dressed = palette
        status.setTextColor(palette.accent.toArgb())
        input.setTextColor(palette.foreground.toArgb()); input.setHintTextColor(palette.muted.toArgb())
        chip.background = frame(Palette.glass, palette.border)
        chipText.setTextColor(palette.foreground.toArgb())
        output.setTextColor(palette.foreground.toArgb())
        for (button in listOf(speakButton, hideButton, stopButton, newThreadButton)) { button.setTextColor(palette.foreground.toArgb()); button.background = frame(Palette.glass, palette.border) }
        // The two that act for him stand out: sending, and an approval that is waiting.
        sendButton.setTextColor(palette.accent.toArgb()); sendButton.background = frame(Palette.glass, palette.accent)
        approvalButton.setTextColor(palette.red.toArgb()); approvalButton.background = frame(Palette.glass, palette.red)
    }
    /**
     * Makes [window] the listening screen's: the dim over whatever is underneath as its background, and the
     * ring along its real edges, behind the words. Called again each time the window shows.
     */
    fun dress(window: Window) {
        window.setBackgroundDrawable(ColorDrawable(Palette.background.copy(alpha = DIM).toArgb()))
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        if (ring.parent != null) return
        // Straight in the window's own frame, which the system bars don't inset; failing that, around the words' area.
        val edge = window.decorView as? ViewGroup ?: this
        edge.addView(ring, 0, ViewGroup.LayoutParams(-1, -1))
    }
    init {
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(26), dp(24), dp(26), dp(28))
        addView(column, LayoutParams(-1, -2, Gravity.BOTTOM))
        // Clear of the system bars, the camera cutout and the keyboard, where the window doesn't already keep clear itself.
        setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val clear = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                setPadding(clear.left, clear.top, clear.right, clear.bottom)
            } else {
                @Suppress("DEPRECATION")
                setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        status.typeface = Typeface.MONOSPACE; status.textSize = TextSize.Small.value
        column.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        // His words: large, straight on the dimmed screen, and still a field to tap and edit.
        input.hint = "What would you like me to do?"
        input.background = null; input.setPadding(0, 0, 0, 0)
        input.typeface = Typeface.MONOSPACE; input.textSize = WORDS_SP
        input.minLines = 2; input.maxLines = 6
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        column.addView(input, LinearLayout.LayoutParams(-1, -2))
        chip.orientation = LinearLayout.HORIZONTAL; chip.gravity = Gravity.CENTER_VERTICAL; chip.visibility = GONE
        chip.setPadding(dp(10), dp(8), dp(10), dp(8))
        chip.addView(chipImage, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) })
        chipText.typeface = Typeface.MONOSPACE; chipText.textSize = TextSize.Body.value
        chip.addView(chipText, LinearLayout.LayoutParams(0, -2, 1f))
        chip.setOnClickListener { showScreen(ScreenContext.None, null); onScreenLetGo?.invoke() }
        column.addView(chip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val row = LinearLayout(owner)
        fun button(text: String, action: () -> Unit) = control(row, text, action)
        speakButton = button("Speak") { listen() }
        sendButton = button("Send") {
            try {
                cancelSpeech()
                // Only the screenshot the chip is showing goes along, and it is AgentTasks's to delete from here.
                // While the chip is still getting the screen there is none: the request goes without it.
                AgentTasks.send(owner, input.text.toString().trim(), screen = screen)
                screen = null; showScreen(ScreenContext.None, null); onScreenLetGo?.invoke()
                message = null; input.setText(""); input.clearFocus()
                owner.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
                refresh()
            } catch (e: Exception) { message = e.message; refresh() }
        }
        hideButton = button("Hide") { dismiss() }
        output.typeface = Typeface.MONOSPACE; output.textSize = TextSize.Body.value
        resultScroll = ScrollView(owner).apply { addView(output) }
        column.addView(resultScroll, LinearLayout.LayoutParams(-1, dp(140)).apply { topMargin = dp(8) })
        column.addView(row)
        // What only shows while there is a task, an approval or a thread to act on.
        taskRow = LinearLayout(owner)
        stopButton = control(taskRow, "Stop current task") { AgentTasks.stop(owner) }
        approvalButton = control(taskRow, "Review approval") { reviewApproval() }
        newThreadButton = control(taskRow, "New thread") {
            try { AgentTasks.newThread(owner); message = null } catch (e: Exception) { message = e.message }
            refresh()
        }
        column.addView(taskRow)
        refresh(); handler.post(update)
    }
    /** Oma is taking a request for as long as this screen is up: the ring listens, wherever it is drawn. */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        OmaRing.hearing(this, visibility == VISIBLE && !disposed)
    }
    override fun onDetachedFromWindow() { OmaRing.hearing(this, false); super.onDetachedFromWindow() }
    fun refresh() {
        AgentTasks.restore(owner)
        dress()
        status.text = message ?: AgentTasks.label
        output.text = if (AgentTasks.working && AgentTasks.answer.isBlank()) "Request received. The agent is working in the background. You can hide this panel and return with the power button or notification." else AgentTasks.answer
        sendButton.isEnabled = !AgentTasks.working
        speakButton.isEnabled = !AgentTasks.working
        input.isEnabled = !AgentTasks.working
        val followUp = AgentTasks.thread != null
        input.hint = if (followUp) "Follow up on this thread…" else "What would you like me to do?"
        newThreadButton.visibility = if (followUp && !AgentTasks.working) VISIBLE else GONE
        resultScroll.visibility = if (output.text.isNotBlank()) VISIBLE else GONE
        stopButton.visibility = if (AgentTasks.working) VISIBLE else GONE
        approvalButton.visibility = if (AgentTasks.approvals.isNotEmpty()) VISIBLE else GONE
        for (button in listOf(sendButton, speakButton)) button.alpha = if (button.isEnabled) 1f else DISABLED
        input.alpha = if (input.isEnabled) 1f else DISABLED
        labelChip()
    }
    /**
     * The "this screen" chip. A screenshot is held only while the chip shows it: tapping the chip, hiding
     * the panel and sending all let go of it, and whatever is let go of unsent is deleted.
     */
    fun showScreen(state: ScreenContext, file: File?) {
        val kept = file?.takeIf { state == ScreenContext.Attached }
        if (screen != kept) ScreenShots.discard(screen)
        if (file != kept) ScreenShots.discard(file)
        screen = kept
        chip.visibility = if (state == ScreenContext.None) GONE else VISIBLE
        chipImage.visibility = if (kept != null) VISIBLE else GONE
        chipImage.setImageBitmap(kept?.let { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 8 }) })
        // Attached without a file can't be sent, so it never reads as attached.
        chipState = if (state == ScreenContext.Attached && kept == null) ScreenContext.Unavailable else state
        labelChip()
    }
    /** A screen sent to Claude stays in its conversation, which every follow-up sends again: the chip says so. */
    private fun labelChip() {
        val claude = (owner.applicationContext as App).runtime.agents.default?.bin == "claude"
        chipText.text = screenChipText(chipState, keptInConversation = claude)
    }
    private fun reviewApproval() {
        // The oldest one, chosen at the tap; the screen then answers only that one.
        val oldest = AgentTasks.pending.firstOrNull() ?: return
        owner.startActivity(AgentNotices.reviewIntent(owner, oldest))
        dismiss() // VoiceInteractionSession otherwise stays above the activity's modal.
    }
    private fun listen() {
        if (owner.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            owner.startActivity(Intent(owner, AssistantSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            message = "Allow microphone access in Oma assistant setup, then tap Speak."; return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(owner)) { message = "Voice recognition unavailable; you can type instead."; return }
        cancelSpeech()
        // Speaking again adds to what's already in the field instead of replacing it.
        val before = input.text.toString().trim()
        fun heard(results: Bundle?) {
            val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (said.isEmpty()) return
            input.setText(appendSpeech(before, said)); input.setSelection(input.text.length)
        }
        recognizer = try { assistantRecognizer(owner) } catch (e: Exception) { message = e.message; return }
        recognizer?.apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { message = "Listening…" }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { message = "Finishing…" }
                override fun onError(error: Int) { message = "Could not hear that. Try Speak again or type."; cancelSpeech() }
                override fun onResults(results: Bundle?) {
                    heard(results)
                    message = "Ready to send"; cancelSpeech()
                }
                override fun onPartialResults(results: Bundle?) = heard(results)
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        }
    }
    fun cancelSpeech() { recognizer?.cancel(); recognizer?.destroy(); recognizer = null }
    fun dispose() { disposed = true; OmaRing.hearing(this, false); handler.removeCallbacksAndMessages(null); cancelSpeech(); showScreen(ScreenContext.None, null) }

    private companion object {
        /** How much of the theme's background lies over the screen underneath. */
        const val DIM = 0.8f
        /** The size of his words, in sp. */
        const val WORDS_SP = 28f
        /** How faint a control is while it can't be used. */
        const val DISABLED = 0.4f
    }
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
