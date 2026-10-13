package dev.omalauncher.oma

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.App
import dev.omalauncher.Runtime
import dev.omalauncher.assist.AgentTasks
import dev.omalauncher.assist.ApprovalOutcome
import dev.omalauncher.assist.PendingApproval
import dev.omalauncher.assist.QuickLane
import dev.omalauncher.assist.appendSpeech
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.linux.MAX_PROMPT_IMAGES
import dev.omalauncher.linux.PromptImage
import dev.omalauncher.linux.agentInvocation
import dev.omalauncher.linux.importPromptImage
import dev.omalauncher.phone.PhoneBridge
import dev.omalauncher.ui.BracketAction
import dev.omalauncher.ui.MicIcon
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.PageGap
import dev.omalauncher.ui.ReadingWidth
import dev.omalauncher.ui.Speech
import dev.omalauncher.ui.applySystemBars
import dev.omalauncher.ui.framed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * The Oma app: one thread of requests to the background agent, with a field for typed messages and image
 * attachments, and a choice of lane: build sends to that agent, quick asks [QuickLane] beside it. Opened by
 * [ACTION] from the dock, Super+A, the agent's notifications and the tiles; text passed as `Intent.EXTRA_TEXT`
 * is put in the field and not sent. It never attaches the screen: only the assistant gesture over another app
 * does (see ScreenContext.kt).
 */
class OmaChatActivity : ComponentActivity() {
    private val runtime get() = (application as App).runtime

    /** Text another screen asked to put in the field; taken once. */
    private var prefill by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime.start()
        AgentTasks.restore(this)
        QuickLane.restore(this)
        if (savedInstanceState == null) prefill = textOf(intent)
        applySystemBars(this, Pack.isLight)
        setContent {
            LaunchedEffect(Pack.isLight) { applySystemBars(this@OmaChatActivity, Pack.isLight) }
            OmaChatScreen(runtime, prefill, onPrefilled = { prefill = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        textOf(intent)?.let { prefill = it }
    }

    private fun textOf(intent: Intent?) = intent?.getStringExtra(Intent.EXTRA_TEXT)?.take(ChatHistory.MAX_PROMPT)?.takeIf { it.isNotBlank() }

    companion object {
        const val ACTION = "dev.omalauncher.action.OMA_CHAT"

        fun intent(context: android.content.Context): Intent = Intent(context, OmaChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@Composable
private fun OmaChatScreen(
    runtime: Runtime,
    prefill: String?,
    onPrefilled: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The agent works on its own thread and reports through plain fields, read here twice a second like the home rail does.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            tick++
        }
    }
    val store = remember { ChatLog.of(context) }
    // Unsent attachments are listed on disk, so the ones a killed process leaves are deleted at the next start.
    val drafts = remember { ChatDrafts.of(context) }
    val tasks = remember(tick) { store.tasks }
    val working = remember(tick) { AgentTasks.working }
    val pending = remember(tick) { AgentTasks.pending }
    val label = remember(tick) { AgentTasks.label }
    val liveAnswer = remember(tick) { AgentTasks.answer }
    val followUp = remember(tick) { AgentTasks.thread != null }
    // The quick lane's own state, read the same way and kept apart: each lane's goes only to its own entry.
    val quickWorking = remember(tick) { QuickLane.working }
    val quickTime = remember(tick) { QuickLane.time }
    val quickLabel = remember(tick) { QuickLane.label }
    val quickAnswer = remember(tick) { QuickLane.answer }
    val quickContinuing = remember(tick) { QuickLane.continuing }
    val linuxState by runtime.bootstrap.state.collectAsState()

    var draft by rememberSaveable { mutableStateOf("") }
    var images by remember { mutableStateOf(listOf<PromptImage>()) }
    var importing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("oma-chat", android.content.Context.MODE_PRIVATE) }
    var lane by remember { mutableStateOf(Lane.of(prefs.getString("lane", null))) }
    val speech = remember { Speech(context.applicationContext) }
    /** What was in the field when listening began; speech is added after it. */
    var before by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        onDispose {
            speech.cancel()
            // Attached but never sent: the copies in the guest go with the screen.
            images.forEach { it.file.delete() }
            drafts.remove(images.map { it.file })
        }
    }
    LaunchedEffect(prefill) {
        if (prefill != null) {
            draft = appendSpeech(draft, prefill)
            onPrefilled()
        }
    }

    fun send() {
        val agent = runtime.agents.default
        val out = outgoing(
            draft, images.size, images.any { !it.file.isFile }, importing || speech.listening, linuxState == LinuxState.Installed, agent?.bin, working,
            lane, quickReady = runtime.agents.installed.any { it.bin == "claude" }, quickBusy = QuickLane.working,
        )
        when (out) {
            Outgoing.Nothing -> return
            is Outgoing.Refused -> {
                notice = out.reason
                return
            }
            is Outgoing.Background ->
                try {
                    AgentTasks.send(context, out.prompt, images.map { it.guestPath })
                } catch (e: Exception) {
                    notice = e.message
                    return
                }
            is Outgoing.Queued ->
                try {
                    AgentTasks.followUp(context, out.prompt, images.map { it.guestPath })
                } catch (e: Exception) {
                    notice = e.message
                    return
                }
            is Outgoing.Quick ->
                try {
                    QuickLane.ask(context, out.prompt)
                } catch (e: Exception) {
                    notice = e.message
                    return
                }
            is Outgoing.Terminal -> if (agent != null) runtime.terminal("${agentInvocation(agent.bin, out.prompt + "\n\n" + PhoneBridge.INSTRUCTIONS)}; exec bash", agent.name, agent)
        }
        // Sent files stay: the agent reads them while it works, and the saved task names them.
        drafts.remove(images.map { it.file })
        images = emptyList()
        notice = null
        draft = ""
        tick++
    }

    fun answer(entry: PendingApproval, allow: Boolean) {
        // The same path and guards as the notification's buttons.
        notice = when (AgentTasks.answerApproval(context, entry.id, entry.nonce, allow)) {
            ApprovalOutcome.Decided -> null
            ApprovalOutcome.Expired -> "That request has expired"
            ApprovalOutcome.Locked -> "Unlock your phone to answer that request"
        }
        tick++
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PROMPT_IMAGES)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val (take, tooMany) = imagesToTake(images.size, uris.size)
        importing = true
        notice = null
        scope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    uris.take(take).map { uri -> runCatching { importPromptImage(context.contentResolver, uri, runtime.proot).also { drafts.add(listOf(it.file)) } } }
                }
                images = images + results.mapNotNull { it.getOrNull() }
                notice = results.firstNotNullOfOrNull { it.exceptionOrNull()?.message } ?: tooMany
            } finally {
                importing = false
            }
        }
    }

    fun listen() {
        before = draft
        speech.start(hold = false) { heard -> draft = appendSpeech(before, heard) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) listen() }
    fun talk() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val listening = speech.listening
    Box(Modifier.fillMaxSize().background(Pack.background).safeDrawingPadding().padding(PageGap), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().widthIn(max = ReadingWidth)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(Pack.heading("oma"), style = Pack.display(color = Pack.accent))
                Spacer(Modifier.width(10.dp))
                BasicText(
                    listOfNotNull(runtime.agents.default?.name, label.takeIf { it != "Ready" }).joinToString(" · ").ifEmpty { "no agent yet" },
                    Modifier.weight(1f),
                    style = Pack.body(Pack.size.small, Pack.muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if ((followUp || quickContinuing) && !working) {
                    BracketAction("new thread", Pack.accent) {
                        try {
                            // Both lanes start over together; with no build conversation there is nothing of the build lane to clear.
                            if (followUp) AgentTasks.newThread(context)
                            QuickLane.newThread(context)
                            notice = null
                        } catch (e: Exception) {
                            notice = e.message
                        }
                        tick++
                    }
                }
            }
            // Newest at the bottom. A list keeps what is in view where it is when something is added, which would leave
            // a message just sent below the edge, under the field and the keyboard: so a new one is scrolled to.
            val thread = rememberLazyListState()
            val newest = tasks.lastOrNull()?.time
            LaunchedEffect(newest) { if (newest != null) thread.scrollToItem(0) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp), state = thread, reverseLayout = true) {
                if (tasks.isEmpty()) {
                    item {
                        BasicText(
                            "Ask Oma for something. Follow-ups continue the same conversation until you start a new thread.",
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            style = Pack.body(color = Pack.muted).copy(textAlign = TextAlign.Center),
                        )
                    }
                }
                // Follow-ups wait below the task that is running, so it is the last one running, not the last one; and a
                // quick question running beside it is not it.
                val building = tasks.lastOrNull { it.status == TaskStatus.Running && !it.quick }?.time
                items(tasks.asReversed(), key = { it.time }) { task ->
                    if (task.quick) {
                        // Never the build task's answer or label, and never an approval: the quick lane can't be asked for one.
                        val running = quickWorking && task.time == quickTime
                        TaskEntry(task, running, quickLabel, if (running) quickAnswer else task.result, emptyList(), ::answer) {
                            QuickLane.stop()
                            tick++
                        }
                    } else {
                        val running = working && task.time == building
                        TaskEntry(task, running, label, if (running) liveAnswer else task.result, if (running) pending else emptyList(), ::answer)
                    }
                }
            }
            notice?.let {
                BasicText(it, Modifier.clickable { notice = null }.padding(8.dp), style = Pack.body(Pack.size.small, Pack.red))
            }
            speech.error?.takeIf { !listening }?.let {
                BasicText(it, Modifier.clickable { speech.error = null }.padding(8.dp), style = Pack.body(Pack.size.small, Pack.red))
            }
            if (images.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp)) {
                    images.forEachIndexed { index, image ->
                        AttachmentPreview(image, index + 1, enabled = !importing) {
                            images = images - image
                            image.file.delete()
                            drafts.remove(listOf(image.file))
                            notice = null
                        }
                    }
                }
            }
            if (importing) BasicText("adding images…", Modifier.padding(8.dp), style = Pack.body(color = Pack.muted))
            // Where Send goes: kept across launches, and changed without touching what is running in either lane.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                for (choice in Lane.entries) {
                    BracketAction(choice.id, if (choice == lane) Pack.accent else Pack.muted) {
                        lane = choice
                        prefs.edit().putString("lane", choice.id).apply()
                        notice = null
                    }
                }
                BasicText(lane.does, Modifier.weight(1f).padding(horizontal = 4.dp), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).framed(Pack.mark, if (listening || focused) Pack.accent else Pack.border, Pack.surface),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shown = if (listening) appendSpeech(before, speech.text) else draft
                BasicTextField(
                    value = shown,
                    onValueChange = {
                        if (!listening) draft = it
                        notice = null
                    },
                    readOnly = listening,
                    maxLines = 6,
                    textStyle = Pack.body(Pack.size.item, if (listening) Pack.accent else Pack.foreground),
                    cursorBrush = SolidColor(Pack.accent),
                    modifier =
                        Modifier
                            .weight(1f)
                            .onFocusChanged { focused = it.isFocused }
                            // A hardware Enter sends; Shift+Enter starts a new line.
                            .onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                                    send()
                                    true
                                } else {
                                    false
                                }
                            },
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                            if (shown.isEmpty()) {
                                val hint =
                                    if (listening) "listening…"
                                    else if (lane == Lane.Quick) "ask something quick…"
                                    else if (working) "follow up when this is done…"
                                    else if (followUp) "follow up on this thread…"
                                    else "ask oma…"
                                BasicText(hint, style = Pack.body(Pack.size.item, Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            inner()
                        }
                    },
                )
                if (!listening) {
                    Box(
                        Modifier
                            .semantics { contentDescription = "Attach images" }
                            .clickable(enabled = !importing && images.size < MAX_PROMPT_IMAGES) {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }.padding(horizontal = 12.dp, vertical = 12.dp),
                    ) { BasicText("+", style = Pack.body(color = Pack.accent)) }
                }
                // Tap to dictate into the field; it stops at the next pause, or at another tap.
                Box(
                    Modifier
                        .size(48.dp)
                        .background(if (listening) Pack.red else Pack.surface)
                        .semantics { contentDescription = if (listening) "Stop listening" else "Dictate" }
                        .clickable { if (speech.listening) speech.stop() else talk() },
                    contentAlignment = Alignment.Center,
                ) { MicIcon(if (listening) Pack.background else Pack.foreground) }
                // While a task runs Send queues a follow-up behind it, and Stop stays beside it. Stop is the build task's:
                // a quick question has its own, on its entry.
                if (!listening) BracketAction("send", if (draft.isBlank() && images.isEmpty()) Pack.muted else Pack.accent, ::send)
                if (working) {
                    BracketAction("stop", Pack.red) {
                        AgentTasks.stop(context)
                        tick++
                    }
                }
            }
        }
    }
}

/**
 * One request in the thread: the prompt, the steps the agent reported, its approvals and the result. A quick
 * question says so beside its time, and while it runs has [onStop] beside what it is doing.
 */
@Composable
private fun TaskEntry(
    task: ChatTask,
    running: Boolean,
    label: String,
    result: String,
    pending: List<PendingApproval>,
    onAnswer: (PendingApproval, Boolean) -> Unit,
    onStop: (() -> Unit)? = null,
) {
    val small = Pack.mono(Pack.size.small, Pack.muted)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val sent = remember(task.time) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(task.time)) }
        BasicText(listOfNotNull(Lane.Quick.id.takeIf { task.quick }, "new thread".takeIf { task.newThread }, sent).joinToString(" · "), Modifier.fillMaxWidth().padding(bottom = 4.dp), style = small.copy(textAlign = TextAlign.Center))
        Column(Modifier.fillMaxWidth().framed(Pack.hair, Pack.border, Pack.surface, radius = 12.dp).padding(10.dp)) {
            BasicText(task.prompt, style = Pack.body())
            val attached = listOfNotNull(
                task.images.size.takeIf { it > 0 }?.let { if (it == 1) "1 image" else "$it images" },
                "this screen".takeIf { task.screen },
            )
            if (attached.isNotEmpty()) BasicText("with " + attached.joinToString(" and "), Modifier.padding(top = 4.dp), style = small)
        }
        for (step in task.steps) BasicText("· $step", Modifier.padding(top = 4.dp), style = small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        for (approval in task.approvals) {
            val waiting = if (approval.answer == ApprovalAnswer.Waiting) pending.firstOrNull { it.nonce == approval.key } else null
            if (waiting != null) {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp).framed(Pack.mark, Pack.accent, Pack.surface, radius = 12.dp).padding(10.dp)) {
                    BasicText("Allow ${approval.tool}?", style = Pack.body(color = Pack.accent))
                    if (waiting.approval.explanation.isNotBlank()) BasicText(waiting.approval.explanation.take(300), Modifier.padding(top = 4.dp), style = small)
                    BasicText(approval.detail, Modifier.padding(top = 4.dp), style = Pack.body(), maxLines = 10, overflow = TextOverflow.Ellipsis)
                    BasicText("Approve once allows this action only.", Modifier.padding(top = 4.dp), style = small)
                    Row {
                        BracketAction("approve once", Pack.green) { onAnswer(waiting, true) }
                        BracketAction("deny", Pack.red) { onAnswer(waiting, false) }
                    }
                }
            } else {
                // A waiting approval that can no longer be answered here reads as not answered.
                val answer = if (approval.answer == ApprovalAnswer.Waiting && !running) ApprovalAnswer.Unanswered else approval.answer
                val color = when (answer) {
                    ApprovalAnswer.Approved -> Pack.green
                    ApprovalAnswer.Denied, ApprovalAnswer.Refused -> Pack.red
                    else -> Pack.muted
                }
                BasicText("${approval.tool} · ${answer.label}", Modifier.padding(top = 6.dp), style = small.copy(color = color))
                BasicText(approval.detail, style = small, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (running) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("$label…", style = small.copy(color = Pack.accent))
                if (onStop != null) BracketAction("stop", Pack.red, onStop)
            }
        }
        if (task.status == TaskStatus.Queued) BasicText("waiting for the agent to finish…", Modifier.padding(top = 6.dp), style = small.copy(color = Pack.accent))
        if (result.isNotBlank()) {
            // What stands here for a task that failed or was never sent is an error or the launcher's own words, not the agent's Markdown.
            val answered = task.status != TaskStatus.Failed && task.status != TaskStatus.Dropped
            SelectionContainer {
                if (answered) MarkdownText(result.trim(), Modifier.padding(top = 6.dp)) else BasicText(result.trim(), Modifier.padding(top = 6.dp), style = Pack.body())
            }
        }
        val ended = when (task.status) {
            TaskStatus.Stopped -> "stopped"
            TaskStatus.Failed -> "needs attention"
            TaskStatus.Interrupted -> "interrupted: the app was closed while this ran"
            TaskStatus.Dropped -> "not sent"
            else -> null
        }
        if (ended != null && !running) BasicText(ended, Modifier.padding(top = 4.dp), style = small.copy(color = Pack.red))
    }
}

@Composable
private fun AttachmentPreview(
    image: PromptImage,
    number: Int,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    val preview by produceState<ImageBitmap?>(null, image.file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(image.file)) { decoder, info, _ ->
                    val ratio = 160f / maxOf(info.size.width, info.size.height).coerceAtLeast(160)
                    decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
                }.asImageBitmap()
            }.getOrNull()
        }
    }
    Column(Modifier.padding(4.dp)) {
        val bitmap = preview
        if (bitmap != null) {
            Image(bitmap, "Attached image $number", Modifier.size(56.dp))
        } else {
            BasicText("image $number", Modifier.size(56.dp), style = Pack.body(color = Pack.muted))
        }
        BasicText(
            "remove",
            Modifier.semantics { contentDescription = "Remove image $number" }.clickable(enabled = enabled, onClick = onRemove).padding(vertical = 8.dp),
            style = Pack.body(Pack.size.small, Pack.accent),
        )
    }
}
