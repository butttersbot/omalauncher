package dev.omalauncher.assist

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.service.voice.*
import android.speech.*
import android.view.*
import android.widget.*
import dev.omalauncher.App

class OmaVoiceService : VoiceInteractionService()
class OmaSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = OmaSession(this)
}
class OmaSession(context: Context) : VoiceInteractionSession(context) {
    private var panel: AssistantPanel? = null
    override fun onCreateContentView(): View {
        (context.applicationContext as App).runtime.start()
        return AssistantPanel(context, { hide() }).also { panel = it }
    }
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        window.window?.apply { setGravity(Gravity.BOTTOM); setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT) }
        panel?.refresh()
    }
    override fun onHide() { panel?.cancelSpeech(); super.onHide() }
    override fun onDestroy() { panel?.dispose(); super.onDestroy() }
}

/** Fallback assist intent and notification entry; leaves the current app visible underneath. */
class AssistantPromptActivity : Activity() {
    private lateinit var panel: AssistantPanel
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        (application as App).runtime.start()
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        panel = AssistantPanel(this, { finish() })
        setContentView(panel)
    }
    override fun onDestroy() { panel.dispose(); super.onDestroy() }
    override fun onPause() { panel.cancelSpeech(); super.onPause() }
}

/** Shared compact prompt UI, with explicit mic activation and background dispatch. */
class AssistantPanel(private val owner: Context, private val dismiss: () -> Unit) : LinearLayout(owner) {
    private val input = EditText(owner)
    private val status = TextView(owner)
    private val output = TextView(owner)
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private lateinit var resultScroll: ScrollView
    private lateinit var stopButton: Button
    private lateinit var sendButton: Button
    private lateinit var speakButton: Button
    private lateinit var approvalButton: Button
    private lateinit var newThreadButton: Button
    private var disposed = false
    private var message: String? = null
    private val update = object : Runnable {
        override fun run() { if (!disposed) { refresh(); handler.postDelayed(this, 500) } }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    init {
        orientation = VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(20))
        background = GradientDrawable().apply { setColor(Color.rgb(24, 27, 38)); cornerRadius = dp(24).toFloat() }
        val heading = TextView(owner).apply { text = "✦ Oma agent"; setTextColor(Color.WHITE); textSize = 21f }
        addView(heading)
        status.setTextColor(Color.rgb(170, 186, 225)); status.textSize = 14f
        addView(status, LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        input.hint = "What would you like me to do?"
        input.setTextColor(Color.WHITE); input.setHintTextColor(Color.GRAY)
        input.minLines = 2; input.maxLines = 4
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        addView(input, LayoutParams(-1, -2))
        val row = LinearLayout(owner)
        fun button(text: String, action: () -> Unit): Button = Button(owner).apply {
            this.text = text; setOnClickListener { action() }
            row.addView(this, LayoutParams(0, -2, 1f))
        }
        speakButton = button("Speak") { listen() }
        sendButton = button("Send") {
            try {
                cancelSpeech()
                AgentTasks.send(owner, input.text.toString().trim())
                message = null; input.setText(""); input.clearFocus()
                owner.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
                refresh()
            } catch (e: Exception) { message = e.message; refresh() }
        }
        button("Hide") { dismiss() }
        addView(row)
        output.setTextColor(Color.WHITE); output.textSize = 14f
        resultScroll = ScrollView(owner).apply { addView(output) }
        addView(resultScroll, LayoutParams(-1, dp(140)))
        stopButton = Button(owner).apply { text = "Stop current task"; setOnClickListener { AgentTasks.stop(owner) } }
        addView(stopButton)
        approvalButton = Button(owner).apply { text = "Review approval"; setOnClickListener { reviewApproval() } }
        addView(approvalButton)
        newThreadButton = Button(owner).apply {
            text = "New thread"
            setOnClickListener {
                try { AgentTasks.newThread(owner); message = null } catch (e: Exception) { message = e.message }
                refresh()
            }
        }
        addView(newThreadButton)
        refresh(); handler.post(update)
    }
    fun refresh() {
        AgentTasks.restore(owner)
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
    fun dispose() { disposed = true; handler.removeCallbacksAndMessages(null); cancelSpeech() }
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
