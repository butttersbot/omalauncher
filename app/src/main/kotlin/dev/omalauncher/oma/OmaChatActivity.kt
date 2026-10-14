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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.ApprovalOutcome
import dev.omalauncher.assist.FrontQueueFull
import dev.omalauncher.assist.MacPending
import dev.omalauncher.assist.MacThreads
import dev.omalauncher.assist.PendingApproval
import dev.omalauncher.assist.QuickLane
import dev.omalauncher.assist.RoutingLog
import dev.omalauncher.assist.agentThreadsNow
import dev.omalauncher.assist.appendSpeech
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.assist.builderApprovalTitle
import dev.omalauncher.assist.builderId
import dev.omalauncher.assist.gotBy
import dev.omalauncher.assist.handOverContext
import dev.omalauncher.assist.handOverPreface
import dev.omalauncher.assist.macApprovalDetail
import dev.omalauncher.assist.macApprovalNotice
import dev.omalauncher.assist.macThreadId
import dev.omalauncher.assist.replyName
import dev.omalauncher.assist.waitingLine
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
 * The Oma app: one thread of requests to Oma, with a field for typed messages and image attachments. There is
 * one Oma to talk to and no lane to pick (Boss, 2026-10-13): [QuickLane], the front agent, hears a request
 * first and answers it or hands it to the background agent or to a project thread on the Mac ([MacThreads]),
 * and the thread shows which only by what comes back and, for the Mac, by a receipt naming the project. Send
 * never waits for either of them: what he sends is in the thread at once, and an entry that waits says where. Opened by [ACTION] from the dock, Super+A, the agent's notifications and the tiles; text passed as
 * `Intent.EXTRA_TEXT` is put in the field and not sent. It never attaches the screen: only the assistant
 * gesture over another app does (see ScreenContext.kt).
 *
 * That stream is the Oma tab. Each thread, the phone's builder and every project thread on the Mac, has a
 * tab of its own beside it (Boss, 2026-10-13; ChatTabs.kt): the thread's entries alone, and a field that
 * talks straight to it. A home tile of a thread opens its tab by [EXTRA_THREAD]; every other way in opens Oma.
 */
class OmaChatActivity : ComponentActivity() {
    private val runtime get() = (application as App).runtime

    /** Text another screen asked to put in the field; taken once. */
    private var prefill by mutableStateOf<String?>(null)

    /** The key of the thread whose tab was asked for, by a tile or a tap in here; null is the Oma tab. See [openTab]. */
    private var wanted by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime.start()
        AgentTasks.restore(this)
        QuickLane.restore(this)
        // Asked on a thread of its own: the chat never waits for the Mac, and shows what was last known until it answers.
        MacThreads.open(this)
        if (savedInstanceState == null) prefill = textOf(intent)
        wanted = if (savedInstanceState == null) threadOf(intent) else savedInstanceState.getString(STATE_TAB)
        applySystemBars(this, Pack.isLight)
        setContent {
            LaunchedEffect(Pack.isLight) { applySystemBars(this@OmaChatActivity, Pack.isLight) }
            OmaChatScreen(runtime, prefill, onPrefilled = { prefill = null }, wanted = wanted, onTab = { wanted = it.takeIf { tab -> tab != OMA_TAB } })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        textOf(intent)?.let { prefill = it }
        // The dock, a notification and voice name no thread, and open Oma whatever tab was left showing.
        wanted = threadOf(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, wanted)
    }

    private fun threadOf(intent: Intent?) = intent?.getStringExtra(EXTRA_THREAD)?.take(ChatHistory.MAX_THREAD + 8)?.takeIf { it.isNotBlank() }

    private fun textOf(intent: Intent?) = intent?.getStringExtra(Intent.EXTRA_TEXT)?.take(ChatHistory.MAX_PROMPT)?.takeIf { it.isNotBlank() }

    companion object {
        const val ACTION = "dev.omalauncher.action.OMA_CHAT"

        /** The key of the thread whose tab to open ([AgentThread.key]); a key that is no thread's opens Oma. */
        const val EXTRA_THREAD = dev.omalauncher.ui.OMA_CHAT_THREAD
        private const val STATE_TAB = "tab"

        fun intent(context: android.content.Context): Intent = Intent(context, OmaChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@Composable
private fun OmaChatScreen(
    runtime: Runtime,
    prefill: String?,
    onPrefilled: () -> Unit,
    /** The key of the thread whose tab was asked for; null for Oma. */
    wanted: String?,
    /** A tab was picked in here, by its key. */
    onTab: (String) -> Unit,
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
    // Each builder's own state, read the same way: each one's goes only to its own entries.
    val builders = remember(tick) { AgentTasks.builders.associate { it.id to BuilderState(it.working, it.pending, it.label, it.answer) } }
    val followUp = remember(tick) { AgentTasks.continuing }
    // The front agent's own state, read the same way and kept apart: each one's goes only to its own entry.
    val frontWorking = remember(tick) { QuickLane.working }
    val frontTime = remember(tick) { QuickLane.time }
    val frontLabel = remember(tick) { QuickLane.label }
    val frontAnswer = remember(tick) { QuickLane.answer }
    val frontContinuing = remember(tick) { QuickLane.continuing }
    // The Mac's waiting requests, read the same way. Its entries are in the thread itself, written as its events come.
    val macPending = remember(tick) { MacThreads.pending }
    // A request the front agent has neither answered nor handed on reads as a task just begun, here and in its entry.
    val deciding = frontWorking && tasks.any { it.time == frontTime && it.undecided }
    val begun = "${runtime.agents.default?.name ?: "Oma"} is working"
    val linuxState by runtime.bootstrap.state.collectAsState()
    // Every thread as it stands, for the header's dots and the tabs; the tab that shows follows from what was asked for.
    val threads = remember(tick) { agentThreadsNow(context) }
    val tab = openTab(wanted, threads)
    val to = sendTo(tab, threads)
    // On a builder's tab the field and its stop are that builder's alone; on the Oma tab they are every builder's.
    val tabWorking = to.builder?.let { builders[it]?.working } ?: working
    // The tab strip is folded away each time the chat opens: the dots say how the threads stand without it.
    var tabsOpen by remember { mutableStateOf(false) }

    var draft by rememberSaveable { mutableStateOf("") }
    var images by remember { mutableStateOf(listOf<PromptImage>()) }
    var importing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
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

    /**
     * Straight to the build lane: sent now, or behind the running task when [queue]. On a builder's tab to that
     * builder, and otherwise as new work to whichever is free. False, with why above the field, if it refuses.
     */
    fun build(prompt: String, queue: Boolean): Boolean =
        try {
            val paths = images.map { it.guestPath }
            if (queue) AgentTasks.followUp(context, prompt, paths, builder = to.builder) else AgentTasks.send(context, prompt, paths, builder = to.builder)
            true
        } catch (e: Exception) {
            notice = e.message
            false
        }

    fun send() {
        val agent = runtime.agents.default
        val out = outgoing(
            draft, images.size, images.any { !it.file.isFile }, importing || speech.listening, linuxState == LinuxState.Installed, agent?.bin, tabWorking,
            frontReady = QuickLane.ready(context),
            to = to,
        )
        when (out) {
            Outgoing.Nothing -> return
            is Outgoing.Refused -> {
                notice = out.reason
                return
            }
            is Outgoing.Background -> if (!build(out.prompt, queue = false)) return
            is Outgoing.Queued -> if (!build(out.prompt, queue = true)) return
            is Outgoing.Front -> try {
                QuickLane.ask(context, out.prompt)
            } catch (full: FrontQueueFull) {
                // Too many already wait to be read: he is told, and the draft stays, rather than his words going past the front agent.
                notice = full.message
                return
            } catch (e: Exception) {
                // A front agent that can't even be asked is not his problem: the request goes on as it would have without one.
                if (!build(out.prompt, queue = tabWorking)) return
            }
            // From a Mac thread's own tab: the same sending a hand-over has, with nobody asked first and nothing added.
            is Outgoing.Mac -> MacThreads.say(context, out.prompt, out.thread, out.name)
            is Outgoing.Terminal -> if (agent != null) runtime.terminal("${agentInvocation(agent.bin, out.prompt + "\n\n" + PhoneBridge.INSTRUCTIONS)}; exec bash", agent.name, agent)
        }
        // Sent files stay: the agent reads them while it works, and the saved task names them.
        drafts.remove(images.map { it.file })
        images = emptyList()
        notice = null
        draft = ""
        tick++
    }

    /**
     * The one-tap fix for an answer where he wanted work done: the entry's request goes to the build lane word
     * for word, as new work, sent or queued as Send would, and the entry is marked so it can't be sent twice. The
     * builder that takes it is also told, as context, what he and the front agent said since it last got
     * anything, this answer included: "look into it" needs what "it" was. None of that is shown or logged.
     */
    fun sendToBuild(time: Long) {
        val marked = store.tasks.firstOrNull { it.time == time }?.let(ChatHistory::sentToBuild) ?: return
        try {
            AgentTasks.handOver(context, marked.prompt, dev.omalauncher.phone.HomeDoor.home?.showing, null) { to -> handOverPreface(handOverContext(store.tasks, Long.MAX_VALUE, gotBy(to))) }
        } catch (e: Exception) {
            notice = e.message
            return
        }
        store.update(time, save = true) { ChatHistory.sentToBuild(it) ?: it }
        RoutingLog.corrected(context, marked.prompt)
        notice = null
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

    /** His tap on a request of a thread on the Mac. The answer travels on another thread; what there is to say comes back here. */
    fun answerMac(entry: MacPending, allow: Boolean) {
        MacThreads.answer(context, entry.ticket, allow) { outcome ->
            notice = macApprovalNotice(outcome)
            tick++
        }
    }

    fun macCard(entry: MacPending) = ApprovalCard(entry.request.explanation) { allow -> answerMac(entry, allow) }

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
            // The open tab's name, a dot for each thread in its state's colour, and the arrow that drops the tabs down,
            // laid out as the mock-up's `.bar`: 14 in from the screen's edge and the top, 8 above what follows, 10 between
            // its parts; the name takes the room that is left, so the dots sit at the right beside the arrow.
            Row(
                Modifier.fillMaxWidth().padding(start = BAR_INSET - PageGap, top = BAR_INSET - PageGap, end = BAR_INSET - PageGap, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(Pack.heading(tabTitle(tab, threads)), Modifier.weight(1f), style = Pack.display(color = Pack.accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (tab == OMA_TAB && (followUp || frontContinuing) && !working) {
                    Box(Modifier.padding(start = BAR_GAP)) {
                        BracketAction("new thread", Pack.accent) {
                            try {
                                // Both agents start over together; with no build conversation there is nothing of the build lane to clear.
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
                // `.dots`: 7 apart. They scroll sideways if there are more than fit, and the scrolling row keeps the open
                // dot's ring and a pulsing dot's growth clear of its edges, out of the gap on either side of it.
                if (threads.isNotEmpty()) {
                    Row(
                        Modifier.padding(start = BAR_GAP - HEADER_DOT_REACH).horizontalScroll(rememberScrollState()).padding(horizontal = HEADER_DOT_REACH),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (thread in threads) {
                            Box(
                                Modifier
                                    .semantics { contentDescription = "${thread.name}: ${thread.label}" }
                                    .clickable { onTab(dotTap(tab, thread.key)) },
                            ) { ThreadDot(thread.state, Pack.muted, size = HEADER_DOT, here = thread.key == tab) }
                        }
                    }
                }
                DropArrow(
                    tabsOpen,
                    Modifier
                        .padding(start = if (threads.isEmpty()) BAR_GAP else BAR_GAP - HEADER_DOT_REACH)
                        .semantics { contentDescription = if (tabsOpen) "Hide the tabs" else "Show the tabs" }
                        .clickable { tabsOpen = !tabsOpen },
                )
            }
            if (tabsOpen) TabStrip(threads, tab, onTab)
            // Newest at the bottom. A list keeps what is in view where it is when something is added, which would leave
            // a message just sent below the edge, under the field and the keyboard: so a new one is scrolled to.
            val thread = rememberLazyListState()
            val entries = tabEntries(tasks, tab)
            val newest = entries.lastOrNull()?.time
            // A tab just opened shows its newest entry too.
            LaunchedEffect(newest, tab) { if (newest != null) thread.scrollToItem(0) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp), state = thread, reverseLayout = true) {
                if (entries.isEmpty()) {
                    item {
                        BasicText(
                            if (tab == OMA_TAB) "Ask Oma for something. Follow-ups continue the same conversation until you start a new thread."
                            else "Nothing has been sent to ${tabTitle(tab, threads)} from this phone yet.",
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            style = Pack.body(color = Pack.muted).copy(textAlign = TextAlign.Center),
                        )
                    }
                }
                // Follow-ups wait below the task that is running, so it is the last one running, not the last one; and a
                // request the front agent has beside it is not it. Each builder has its own.
                val building = BuilderId.entries.associateWith { id -> tasks.lastOrNull { it.status == TaskStatus.Running && it.build && it.builderId == id }?.time }
                // A request waiting on the Mac for work that has no entry here, such as a task begun at the Mac itself: still his to answer.
                // On a thread's tab only that thread's: the builder's tab has none, since its requests are always an entry's.
                val loose = macPending.filter { waiting -> tasks.none { it.thread == waiting.threadId && it.approvals.any { line -> line.key == waiting.request.approval } } }
                    .filter { tab == OMA_TAB || it.threadId == macThreadId(tab) }
                items(loose, key = { it.ticket }) { waiting ->
                    ApprovalBox("Allow ${waiting.request.tool} on ${waiting.threadName}?", macApprovalDetail(waiting.request), macCard(waiting))
                }
                items(entries.asReversed(), key = { it.time }) { task ->
                    val front = frontWorking && task.time == frontTime
                    when {
                        // The Mac does this one: its answer is in the entry as it arrives, and only its own thread's requests show under it.
                        task.mac -> TaskEntry(
                            task, task.status == TaskStatus.Running, MacThreads.label(task), task.result,
                            macPending.filter { it.threadId == task.thread }.associate { it.request.approval to macCard(it) },
                            onStop = {
                                MacThreads.stop(context, task.thread) { said ->
                                    notice = said
                                    tick++
                                }
                            },
                            onAgain = {
                                MacThreads.tryAgain(context, task.time)
                                tick++
                            },
                            // In the Oma stream the receipt is the way into its thread's tab; on that tab it would say where he already is.
                            onReceipt = receiptTab(task)?.takeIf { tab == OMA_TAB }?.let { key -> { onTab(key) } },
                        )
                        // No answer and no stop of its own yet: he can't tell it from a task the build lane has just begun.
                        // One the front agent has not come to says so, and can be taken back until it is read.
                        task.undecided -> TaskEntry(
                            task, front, begun, task.result, emptyMap(),
                            ahead = tasks.count { it.undecided && it.status == TaskStatus.Queued && it.time < task.time },
                            onCancel = {
                                QuickLane.cancel(context, task.time)
                                tick++
                            },
                        )
                        // Never the build task's answer or label, and never an approval: the front agent can't be asked for one.
                        task.quick -> TaskEntry(
                            task, front, frontLabel, if (front) frontAnswer else task.result, emptyMap(),
                            onStop = {
                                QuickLane.stop()
                                tick++
                            },
                            onBuild = { sendToBuild(task.time) },
                        )
                        // A builder's entry reads that builder's state alone, and only its approvals are cards under it.
                        else -> {
                            val by = builders[task.builderId] ?: BuilderState()
                            val running = by.working && task.time == building[task.builderId]
                            val cards = if (running) by.pending.associate { it.nonce to ApprovalCard(it.approval.explanation) { allow -> answer(it, allow) } } else emptyMap()
                            TaskEntry(
                                task, running, by.label, if (running) by.answer else task.result, cards,
                                onCancel = {
                                    AgentTasks.cancelFollowUp(context, task.time)
                                    tick++
                                },
                            )
                        }
                    }
                }
                // Above the oldest entry of a Mac thread's tab: what was begun at the Mac itself never came through this phone.
                if (to is SendTo.Mac) {
                    item {
                        BasicText(MAC_EARLIER, Modifier.fillMaxWidth().padding(vertical = 12.dp), style = Pack.mono(Pack.size.small, Pack.muted).copy(textAlign = TextAlign.Center))
                    }
                }
            }
            straightLine(to)?.let { BasicText(Pack.caption(it), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
                                // Never a word about waiting for the task that runs: he can say more at any time.
                                val oma = if (working) "ask, or add to this, any time…" else if (followUp) "follow up on this thread…" else "ask oma…"
                                val hint = if (listening) "listening…" else fieldHint(to) ?: oma
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
                // While a task runs Send still sends, and Stop stays beside it. Stop is the build task's, and a request
                // still undecided goes with it, as one waiting behind the task would: stop means nothing more is sent.
                // So what waits to be read goes too, each entry saying so with his words kept.
                // An answer the front agent is writing has its own stop, on its entry, and so has each task on the Mac:
                // this one never reaches a thread there, and is not shown on a Mac thread's tab.
                if (!listening) BracketAction("send", if (draft.isBlank() && images.isEmpty()) Pack.muted else Pack.accent, ::send)
                if (fieldStop(to, tabWorking, deciding)) {
                    BracketAction("stop", Pack.red) {
                        if (deciding) QuickLane.stop()
                        QuickLane.dropWaiting(context)
                        if (tabWorking) AgentTasks.stop(context, to.builder)
                        tick++
                    }
                }
            }
        }
    }
}

/**
 * The tabs, dropped down under the header, as the mock-up's `.tabs`: Oma, then each thread with its dot, 6
 * apart on a line in the ink, scrolling sideways when they are more than fit. The open one is the ink, and
 * its dot is outlined in the paper's colour to show on it.
 */
@Composable
private fun TabStrip(
    threads: List<AgentThread>,
    open: String,
    onTab: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(Pack.foreground, Offset(0f, size.height - TABS_RULE.toPx()), Size(size.width, TABS_RULE.toPx())) }
            .horizontalScroll(rememberScrollState())
            .padding(start = TABS_INSET - PageGap, top = 4.dp, end = TABS_INSET - PageGap, bottom = 10.dp + TABS_RULE),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (key in chatTabs(threads)) {
            val thread = threads.firstOrNull { it.key == key }
            val on = key == open
            Row(
                Modifier
                    .background(if (on) Pack.foreground else Pack.surface, Pack.shape(TAB_RADIUS))
                    .clickable { onTab(key) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (thread != null) ThreadDot(thread.state, Pack.muted, outline = if (on) Pack.background else null)
                BasicText(Pack.caption(tabTitle(key, threads)), style = Pack.mono(Pack.size.small, if (on) Pack.background else Pack.foreground), maxLines = 1)
            }
        }
    }
}

/** `.bar`'s padding of 14 and gap of 10, the padding from the screen's edge, which the page's own gap is part of. */
private val BAR_INSET = 14.dp
private val BAR_GAP = 10.dp

/** `.tabs`: 12 in from the screen's edge, over a rule of 2 in the ink. */
private val TABS_INSET = 12.dp
private val TABS_RULE = 2.dp

/** A tab's `border-radius: 2px`, given as frames give theirs, for Console's 16: so it is City Pop's 2 there and follows the look's corners. */
private val TAB_RADIUS = 16.dp

/** One builder as the chat reads it twice a second: whether it works, what waits for his OK, its label and its live answer. */
private data class BuilderState(val working: Boolean = false, val pending: List<PendingApproval> = emptyList(), val label: String = "", val answer: String = "")

/** A waiting approval as its card needs it: why the agent asks, and where his tap goes. */
private class ApprovalCard(val explanation: String, val answer: (Boolean) -> Unit)

/** The card of one waiting approval: what would be done, and the two answers. The same for the phone's builder and a thread on the Mac. */
@Composable
private fun ApprovalBox(
    title: String,
    detail: String,
    card: ApprovalCard,
) {
    val small = Pack.mono(Pack.size.small, Pack.muted)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).framed(Pack.mark, Pack.accent, Pack.surface, radius = 12.dp).padding(10.dp)) {
        BasicText(title, style = Pack.body(color = Pack.accent))
        if (card.explanation.isNotBlank()) BasicText(card.explanation.take(300), Modifier.padding(top = 4.dp), style = small)
        BasicText(detail, Modifier.padding(top = 4.dp), style = Pack.body(), maxLines = 10, overflow = TextOverflow.Ellipsis)
        BasicText("Approve once allows this action only.", Modifier.padding(top = 4.dp), style = small)
        Row {
            BracketAction("approve once", Pack.green) { card.answer(true) }
            BracketAction("deny", Pack.red) { card.answer(false) }
        }
    }
}

/**
 * One request in the thread: the prompt, the steps the agent reported, its approvals and the result, with the
 * name of the AI that wrote it over it ([replyName]). One handed to a thread on the Mac also carries a receipt
 * naming the project, under his words: the receipt says where they went, the name who answered.
 * [cards] are the approvals of this entry still waiting, by the key their lines carry. An answer of the front
 * agent's has [onStop] beside what it is doing while it runs, and [onBuild] under it afterwards, once:
 * `[ send to build ]`, for when he wanted the work done instead. A request the Mac never got has [onAgain]
 * under it: `[ try again ]`. One that waits says where ([waitingLine], with [ahead] the requests the front
 * agent reads before it) and has `[ cancel ]` beside that while [onCancel] can still take it back. The receipt of
 * a request handed to the Mac shows while [onReceipt] is given, and a tap on it opens that thread's tab.
 */
@Composable
private fun TaskEntry(
    task: ChatTask,
    running: Boolean,
    label: String,
    result: String,
    cards: Map<String, ApprovalCard>,
    onStop: (() -> Unit)? = null,
    onBuild: (() -> Unit)? = null,
    onAgain: (() -> Unit)? = null,
    ahead: Int = 0,
    onCancel: (() -> Unit)? = null,
    onReceipt: (() -> Unit)? = null,
) {
    val small = Pack.mono(Pack.size.small, Pack.muted)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val sent = remember(task.time) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(task.time)) }
        BasicText(listOfNotNull("new thread".takeIf { task.newThread }, sent).joinToString(" · "), Modifier.fillMaxWidth().padding(bottom = 4.dp), style = small.copy(textAlign = TextAlign.Center))
        Column(Modifier.fillMaxWidth().framed(Pack.hair, Pack.border, Pack.surface, radius = 12.dp).padding(10.dp)) {
            BasicText(task.prompt, style = Pack.body())
            val attached = listOfNotNull(
                task.images.size.takeIf { it > 0 }?.let { if (it == 1) "1 image" else "$it images" },
                "this screen".takeIf { task.screen },
            )
            if (attached.isNotEmpty()) BasicText("with " + attached.joinToString(" and "), Modifier.padding(top = 4.dp), style = small)
        }
        // The receipt: where his words went. In the accent as what can be tapped is: it opens that thread's tab.
        if (task.mac && onReceipt != null) {
            BasicText(task.agent, Modifier.clickable(onClick = onReceipt).padding(top = 4.dp, bottom = 4.dp, end = 12.dp), style = small.copy(color = Pack.accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        for (step in task.steps) BasicText("· $step", Modifier.padding(top = 4.dp), style = small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        for (approval in task.approvals) {
            val waiting = if (approval.answer == ApprovalAnswer.Waiting) cards[approval.key] else null
            if (waiting != null) {
                ApprovalBox(if (task.mac) "Allow ${approval.tool} on ${task.threadName}?" else builderApprovalTitle(approval.tool, task.builderId), approval.detail, waiting)
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
        waitingLine(task, ahead, label)?.let { waiting ->
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(waiting.text, Modifier.weight(1f, fill = false), style = small.copy(color = Pack.accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Small and muted, as the other rare ways out under an entry are.
                if (waiting.cancel && onCancel != null) BasicText("[ cancel ]", Modifier.clickable(onClick = onCancel).padding(horizontal = 8.dp, vertical = 8.dp), style = small)
            }
        }
        // Who wrote the reply, quietly, in the look's mono face as the receipt and the steps are.
        replyName(task, result)?.let { BasicText(Pack.caption(it), Modifier.padding(top = 6.dp), style = small, maxLines = 1) }
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
            // One the Mac never got is his to send again, so it asks for him as a failure does.
            TaskStatus.Dropped -> if (task.mac) "needs attention" else "not sent"
            else -> null
        }
        if (ended != null && !running) BasicText(ended, Modifier.padding(top = 4.dp), style = small.copy(color = Pack.red))
        if (onBuild != null && !running) {
            // Small and muted: it is there for the rare wrong turn, not something to read past under every answer.
            if (task.sentToBuild) BasicText("sent to build", Modifier.padding(top = 4.dp), style = small)
            else BasicText("[ send to build ]", Modifier.clickable(onClick = onBuild).padding(vertical = 8.dp), style = small)
        }
        if (onAgain != null && task.status == TaskStatus.Dropped) BasicText("[ try again ]", Modifier.clickable(onClick = onAgain).padding(vertical = 8.dp), style = small)
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
