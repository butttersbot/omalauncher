package dev.omalauncher.oma

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.omalauncher.ui.BackHandler
import dev.omalauncher.assist.MAC_CLAUDE
import dev.omalauncher.assist.MAC_KINDS
import dev.omalauncher.assist.macKindName
import dev.omalauncher.assist.MacPick
import dev.omalauncher.assist.macModelName
import dev.omalauncher.assist.macPickName
import dev.omalauncher.assist.macSwitchTargets
import dev.omalauncher.assist.macMachineOf
import dev.omalauncher.assist.macKindOf
import dev.omalauncher.ui.MenuTitle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.assist.forTheBuilder
import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.ApprovalOutcome
import dev.omalauncher.assist.FrontQueueFull
import dev.omalauncher.assist.MacEarlierRead
import dev.omalauncher.assist.MacPending
import dev.omalauncher.assist.MacTurn
import dev.omalauncher.assist.PendingApproval
import dev.omalauncher.assist.appendSpeech
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.assist.builderApprovalTitle
import dev.omalauncher.assist.builderId
import dev.omalauncher.assist.chatRetried
import dev.omalauncher.assist.sendToBuildOffered
import dev.omalauncher.assist.macApprovalDetail
import dev.omalauncher.assist.macApprovalNotice
import dev.omalauncher.assist.macBackgroundLine
import dev.omalauncher.assist.macEarlier
import dev.omalauncher.assist.macEarlierCaption
import dev.omalauncher.assist.macThreadId
import dev.omalauncher.assist.macTurnSteps
import dev.omalauncher.assist.replyName
import dev.omalauncher.assist.waitingLine
import dev.omalauncher.linux.MAX_PROMPT_IMAGES
import dev.omalauncher.linux.PromptImage
import dev.omalauncher.ui.BracketAction
import dev.omalauncher.ui.Menu
import dev.omalauncher.ui.MenuItem
import dev.omalauncher.ui.MicIcon
import dev.omalauncher.ui.Motion
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.PageGap
import dev.omalauncher.ui.ReadingWidth
import dev.omalauncher.ui.ThreadDot
import dev.omalauncher.ui.framed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * The Soma app's screen: one thread of requests to Soma, a tab for each thread beside it, and the field. Everything
 * it needs of the device comes from [host] (ChatHost.kt), so the phone and the desktop draw the same chat.
 * [prefill] is text another screen asked to put in the field, taken once ([onPrefilled]).
 */
@Composable
fun OmaChatScreen(
    host: ChatHost,
    prefill: String?,
    onPrefilled: () -> Unit,
    /** The key of the thread whose tab was asked for; null for Oma. */
    wanted: String?,
    /** A tab was picked in here, by its key. */
    onTab: (String) -> Unit,
) = CompositionLocalProvider(LocalChatHost provides host) { ChatScreen(host, prefill, onPrefilled, wanted, onTab) }

@Composable
private fun ChatScreen(
    host: ChatHost,
    prefill: String?,
    onPrefilled: () -> Unit,
    /** The key of the thread whose tab was asked for; null for Oma. */
    wanted: String?,
    /** A tab was picked in here, by its key. */
    onTab: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The agent works on its own thread and reports through plain fields, read here twice a second like the home rail does.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            tick++
        }
    }
    // Unsent attachments are listed on disk, so the ones a killed process leaves are deleted at the next start.
    val drafts = remember { host.drafts() }
    val tasks = remember(tick) { host.tasks() }
    val working = remember(tick) { host.working() }
    // Each builder's own state, read the same way: each one's goes only to its own entries.
    val builders = remember(tick) { host.builders() }
    val followUp = remember(tick) { host.continuing() }
    // The front agent's own state, read the same way and kept apart: each one's goes only to its own entry.
    val front = remember(tick) { host.front() }
    val frontWorking = front.working
    val frontTime = front.time
    val frontLabel = front.label
    val frontAnswer = front.answer
    // The Mac's waiting requests, read the same way. Its entries are in the thread itself, written as its events come.
    val macPending = remember(tick) { host.macPending() }
    val begun = "${host.agentName() ?: "Soma"} is working"
    val linuxInstalled = host.linuxInstalled()
    // Every thread as it stands, and what he made of the tabs: an archived one that has new work comes back here, and
    // a new chat that has rested its hours goes.
    val all = remember(tick) { host.threads() }
    val book = remember(tick) { host.changeBook { it.settled(all, tasks, System.currentTimeMillis()) } }
    LaunchedEffect(tick) { host.checkTitles() }
    // The header's dots and the tabs: the threads home has tiles for, from the same stamps, the pinned first. The tab that
    // shows follows from what was asked for.
    val live = remember(tick) { host.liveThreads(all, book.pinned.toSet(), book.archived.keys) }
    val threads = shownThreads(live, book.pinned, all, book.archived.keys, wanted)
    val tab = openTab(wanted, threads)
    // Where there is no Soma tab (the desktop), nothing asked for opens a new chat instead: an empty one again, or a new one.
    // A thread asked for that isn't listed yet (a link to one just made elsewhere) is waited for, never replaced.
    LaunchedEffect(tab, wanted) { if (!host.somaTab && tab == OMA_TAB && (wanted == null || wanted == OMA_TAB)) onTab(newChatTab(host)) }
    val to = sendTo(tab, threads, book, tasks)
    // On a builder's tab the field and its stop are that builder's alone; on the Oma tab they are every builder's;
    // on any other tab no builder's.
    val tabWorking = to.builder?.let { builders[it]?.working == true } ?: (to == SendTo.Oma && working)
    // A request the front agent has neither answered nor handed on reads as a task just begun, here and in its entry.
    // On a new chat's tab only that chat's counts.
    val deciding = frontWorking && tasks.any { it.time == frontTime && it.undecided && (tab == OMA_TAB || it.chat == to.chat) }
    // The computers a new chat's thread can be made on, read with everything else, and the one this tab's would be.
    val hosts = remember(tick) { host.hosts() }
    val chatOn = (to as? SendTo.Chat)?.let { chatHostShown(hosts, book.chat(it.chat))?.name } ?: "the Mac"
    // The pick menu open, if any, drawn over everything with the tab menu.
    var picking by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(wanted) { picking = null }
    fun hostPicked(chat: String, on: String, agent: String, model: String) {
        host.changeBook { it.chose(chat, on, agent, model) }
        tick++
    }
    // The tab strip is folded away each time the chat opens: the dots say how the threads stand without it.
    var tabsOpen by remember { mutableStateOf(false) }
    // The tab whose long-press menu is open, if any.
    var menuFor by remember { mutableStateOf<String?>(null) }
    // A new chat puts the caret in the field, asked once for each chat started.
    val fieldFocus = remember { FocusRequester() }
    var focusAsked by remember { mutableIntStateOf(0) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusAsked) {
        if (focusAsked > 0) {
            fieldFocus.requestFocus()
            keyboard?.show()
        }
    }
    // Any other tab he opens, by its dot, its tab, a receipt or Restore, is opened to be read: the caret leaves the
    // field and the keyboard goes, rather than coming up over what he came to see.
    val focusManager = LocalFocusManager.current
    fun pick(key: String) {
        focusManager.clearFocus()
        keyboard?.hide()
        onTab(key)
    }

    // What is in the field, one for each tab: switching tabs puts away this tab's words and brings back the other's.
    var typed by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    val draft = typed[tab].orEmpty()
    fun type(text: String) {
        typed = withDraft(typed, tab, text)
    }
    var images by remember { mutableStateOf(listOf<PromptImage>()) }
    var importing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val speech = host.rememberVoice()
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
            type(appendSpeech(draft, prefill))
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
            // On the Oma tab with what the front agent said since: then "yeah, let's do it" says what "it" is.
            host.build(prompt, paths, if (to == SendTo.Oma) BuildWay.Direct else if (queue) BuildWay.FollowUp else BuildWay.Send, to.builder)
            true
        } catch (e: Exception) {
            notice = e.message
            false
        }

    fun send() {
        val out = outgoing(
            draft, images.size, images.any { !it.file.isFile }, importing || speech.listening, linuxInstalled, host.agentBin(), tabWorking,
            // A view on the work going on is the builder's to give, not the front agent's (Boss, 2026-10-15).
            frontReady = host.frontReady() && !forTheBuilder(draft, host.tasks().filter { it.chat.isEmpty() }, System.currentTimeMillis(), host.working()),
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
                host.askFront(out.prompt, to.chat)
            } catch (full: FrontQueueFull) {
                // Too many already wait to be read: he is told, and the draft stays, rather than his words going past the front agent.
                notice = full.message
                return
            } catch (e: Exception) {
                // A front agent that can't even be asked is not his problem: the request goes on as it would have without one.
                // A new chat's words never go to the phone's builders: they go straight to the Mac, as with no front agent.
                if (to is SendTo.Chat) host.startMacChat(out.prompt, to.chat)
                else if (!build(out.prompt, queue = tabWorking)) return
            }
            // A new chat's first words with no front agent to read them: as they are, to a new thread on the Mac.
            is Outgoing.ChatMac -> host.startMacChat(out.prompt, out.chat)
            // From a Mac thread's own tab: the same sending a hand-over has, with nobody asked first and nothing added.
            is Outgoing.Mac -> host.sayToMac(out.prompt, out.thread, out.name, to.chat)
            is Outgoing.Terminal -> host.runInTerminal(out.prompt)
        }
        // Sent files stay: the agent reads them while it works, and the saved task names them.
        drafts.remove(images.map { it.file })
        images = emptyList()
        notice = null
        type("")
        tick++
    }

    /**
     * The one-tap fix for an answer where he wanted work done: the entry's request goes to the build lane word
     * for word, as new work, sent or queued as Send would, and the entry is marked so it can't be sent twice. The
     * builder that takes it is also told, as context, what he and the front agent said in the Oma stream since it
     * last got anything, this answer included: "look into it" needs what "it" was. None of that is shown or logged.
     * Only the Oma stream's answers have it: a new chat's work is never the phone's.
     */
    fun sendToBuild(time: Long) {
        val marked = host.tasks().firstOrNull { it.time == time }?.takeIf(::sendToBuildOffered)?.let(ChatHistory::sentToBuild) ?: return
        val said = host.tasks().filter { it.chat.isEmpty() }
        try {
            host.handOverToBuild(marked.prompt, said)
        } catch (e: Exception) {
            notice = e.message
            return
        }
        host.updateTask(time, save = true) { ChatHistory.sentToBuild(it) ?: it }
        host.routingCorrected(marked.prompt)
        notice = null
        tick++
    }

    fun answer(entry: PendingApproval, allow: Boolean) {
        // The same path and guards as the notification's buttons.
        notice = when (host.answerBuilder(entry, allow)) {
            ApprovalOutcome.Decided -> null
            ApprovalOutcome.Expired -> "That request has expired"
            ApprovalOutcome.Locked -> "Unlock your phone to answer that request"
        }
        tick++
    }

    /** His tap on a request of a thread on the Mac. The answer travels on another thread; what there is to say comes back here. */
    fun answerMac(entry: MacPending, allow: Boolean) {
        host.answerMac(entry.ticket, allow) { outcome ->
            notice = macApprovalNotice(outcome)
            tick++
        }
    }

    fun macCard(entry: MacPending) = ApprovalCard(entry.request.explanation) { allow -> answerMac(entry, allow) }

    val pickImages = host.rememberImagePicker(MAX_PROMPT_IMAGES) { uris ->
        if (uris.isEmpty()) return@rememberImagePicker
        val (take, tooMany) = imagesToTake(images.size, uris.size)
        importing = true
        notice = null
        scope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    uris.take(take).map { uri -> runCatching { host.importImage(uri).also { drafts.add(listOf(it.file)) } } }
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
        speech.start(hold = false) { heard -> type(appendSpeech(before, heard)) }
    }
    val talk = host.rememberMicrophone { listen() }

    val listening = speech.listening
    Box(Modifier.fillMaxSize().background(Pack.background).safeDrawingPadding().padding(PageGap), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().widthIn(max = ReadingWidth)) {
            // The open tab's name at the left; at the right, a dot for each thread in its state's colour, close together
            // as in the mock-up, and the arrow that drops the tabs down.
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(Pack.heading(tabTitle(tab, threads)), Modifier.weight(1f), style = Pack.display(color = Pack.accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.widthIn(max = DOTS_WIDTH).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    for (thread in threads) {
                        Box(
                            Modifier
                                .semantics { contentDescription = "${thread.name}${if (thread.machine.isEmpty()) "" else " on the ${thread.machine}"}: ${thread.label}" }
                                .clickable { pick(dotTap(tab, thread.key, host.somaTab)) }
                                .padding(vertical = 12.dp)
                                .width(DOT_PITCH),
                            contentAlignment = Alignment.Center,
                        ) { ThreadDot(thread.state, Pack.muted, here = thread.key == tab) }
                    }
                }
                BasicText(
                    if (tabsOpen) "▴" else "▾",
                    Modifier.semantics { contentDescription = if (tabsOpen) "Hide the tabs" else "Show the tabs" }.clickable { tabsOpen = !tabsOpen }.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = Pack.body(Pack.size.large),
                )
            }
            // A bot's tab says what it is under its name: its brain and model, its machine, how full its memory is, and what's wrong.
            threads.firstOrNull { it.key == tab }?.bot?.let { bot ->
                val model = host.macView().threads.firstOrNull { it.id == macThreadId(tab) }?.model.orEmpty()
                BasicText(botInfoLine(bot, model), Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (bot.problem.isNotBlank()) BasicText(bot.problem, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = Pack.body(Pack.size.small, Pack.red))
            }
            if (tabsOpen) {
                TabStrip(
                    threads, tab, ::pick,
                    soma = host.somaTab,
                    onMenu = { menuFor = it },
                    onNew = {
                        // An empty new chat is opened again rather than a second one made.
                        onTab(newChatTab(host))
                        notice = null
                        focusAsked++
                        tick++
                    },
                )
            }
            // A Mac thread's tab reads the thread's own record once each time it is opened, to show above the phone's entries
            // what was done there before this phone came in. Only shown: none of it is kept, sent or answered.
            val earlierThread = macThreadId(tab)
            var earlierRead by remember(tab) { mutableStateOf<MacEarlierRead>(MacEarlierRead.Asking) }
            LaunchedEffect(tab) { if (earlierThread != null) earlierRead = withContext(Dispatchers.IO) { host.macEarlier(earlierThread) } }
            val earlier = remember(earlierRead, tasks) {
                (earlierRead as? MacEarlierRead.Read)?.takeIf { earlierThread != null }?.let { macEarlier(it.events, tasks, earlierThread.orEmpty()) }.orEmpty()
            }
            // Newest at the bottom. A list keeps what is in view where it is when something is added, which would leave
            // a message just sent below the edge, under the field and the keyboard: so a new one is scrolled to.
            val thread = rememberLazyListState()
            val entries = tabEntries(tasks, tab, book)
            val newest = entries.lastOrNull()?.time
            // A tab just opened shows its newest entry too.
            LaunchedEffect(newest, tab) { if (newest != null) thread.scrollToItem(0) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp), state = thread, reverseLayout = true) {
                if (entries.isEmpty() && earlier.isEmpty()) {
                    // Where its thread will be made and who runs it, picked before the first words (cockpit slice 5). First,
                    // so it sits under the line in this list that grows upwards.
                    if (to is SendTo.Chat && hosts.isNotEmpty()) item { ChatPicks(hosts, book.chat(to.chat)) { picking = it } }
                    item {
                        BasicText(
                            when {
                                tab == OMA_TAB -> "Ask Soma for something. For a fresh conversation, start a new chat with ＋ at the end of the tabs (▾)."
                                to is SendTo.Chat -> "A new chat, for work on $chatOn. Its first message starts a new thread there and names the tab. For the phone, hold to talk."
                                else -> "Nothing has been sent to ${tabTitle(tab, threads)} from this phone yet."
                            },
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
                    .filter { tab == OMA_TAB || it.threadId == tabMacThread(tab, book) }
                items(loose, key = { it.ticket }) { waiting ->
                    ApprovalBox("Allow ${waiting.request.tool} on ${waiting.threadName}?", macApprovalDetail(waiting.request), macCard(waiting))
                }
                // Work the thread left running on the Mac, quietly under its newest entry, for as long as the Mac says it runs.
                val background = tabMacThread(tab, book)?.let { id -> macBackgroundLine(host.macView().threads.firstOrNull { it.id == id }?.background.orEmpty()) }
                if (background != null) {
                    item(key = "background") {
                        BasicText("· $background", Modifier.fillMaxWidth().padding(vertical = 6.dp), style = Pack.mono(Pack.size.small, Pack.muted), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                items(entries.asReversed(), key = { it.time }) { task ->
                    val front = frontWorking && task.time == frontTime
                    when {
                        // The Mac does this one: its answer is in the entry as it arrives, and only its own thread's requests show under it.
                        task.mac -> TaskEntry(
                            task, task.status == TaskStatus.Running, host.macLabel(task), task.result,
                            macPending.filter { it.threadId == task.thread }.associate { it.request.approval to macCard(it) },
                            onStop = {
                                host.stopMac(task.thread) { said ->
                                    notice = said
                                    tick++
                                }
                            },
                            onAgain = {
                                host.tryMacAgain(task.time)
                                tick++
                            },
                            // In the Oma stream the receipt is the way into its thread's tab; on that tab it would say where he already is.
                            onReceipt = receiptTab(task, book)?.takeIf { tab == OMA_TAB }?.let { key -> { pick(key) } },
                        )
                        // No answer and no stop of its own yet: he can't tell it from a task the build lane has just begun.
                        // One the front agent has not come to says so, and can be taken back until it is read.
                        task.undecided -> TaskEntry(
                            task, front, begun, task.result, emptyMap(),
                            ahead = tasks.count { it.undecided && it.status == TaskStatus.Queued && it.time < task.time },
                            onCancel = {
                                host.cancelFront(task.time)
                                tick++
                            },
                        )
                        // Never the build task's answer or label, and never an approval: the front agent can't be asked for one.
                        task.quick -> TaskEntry(
                            task, front, frontLabel, if (front) frontAnswer else task.result, emptyMap(),
                            onStop = {
                                host.stopFront()
                                tick++
                            },
                            // A new chat's answers have none: its work is the Mac's, never the phone's. Nor have
                            // agent mail's or a refused request for a secret's ([sendToBuildOffered]).
                            onBuild = if (sendToBuildOffered(task)) ({ sendToBuild(task.time) }) else null,
                            // Its words the Mac was out of reach for are Oma's to read again, never the phone's to do.
                            onAgain = if (chatRetried(task) != null) ({
                                host.askFrontAgain(task.time)
                                tick++
                            }) else null,
                        )
                        // A builder's entry reads that builder's state alone, and only its approvals are cards under it.
                        else -> {
                            val by = builders[task.builderId] ?: BuilderState()
                            val running = by.working && task.time == building[task.builderId]
                            val cards = if (running) by.pending.associate { it.nonce to ApprovalCard(it.approval.explanation) { allow -> answer(it, allow) } } else emptyMap()
                            TaskEntry(
                                task, running, by.label, if (running) by.answer else task.result, cards,
                                onCancel = {
                                    host.cancelFollowUp(task.time)
                                    tick++
                                },
                                onSendNow = {
                                    host.sendNow(task.time)
                                    tick++
                                },
                            )
                        }
                    }
                }
                // Above the oldest entry of a Mac thread's tab: what was done at the Mac itself, or before this phone came in,
                // from the thread's own record. A thread made for a new chat began here and has its tab's key of its own.
                items(earlier.asReversed(), key = { "earlier-${it.id}" }) { turn -> EarlierEntry(turn) }
                if (earlierThread != null) {
                    macEarlierCaption(earlierRead, earlier.size)?.let { caption ->
                        item(key = "earlier") {
                            BasicText(caption, Modifier.fillMaxWidth().padding(vertical = 12.dp), style = Pack.mono(Pack.size.small, Pack.muted).copy(textAlign = TextAlign.Center))
                        }
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
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).framed(Pack.mark, if (listening || focused) Pack.accent else Pack.border, Pack.surface),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shown = if (listening) appendSpeech(before, speech.text) else draft
                BasicTextField(
                    value = shown,
                    onValueChange = {
                        if (!listening) type(it)
                        notice = null
                    },
                    readOnly = listening,
                    maxLines = 6,
                    textStyle = Pack.body(Pack.size.item, if (listening) Pack.accent else Pack.foreground),
                    cursorBrush = SolidColor(Pack.accent),
                    modifier =
                        Modifier
                            .weight(1f)
                            .focusRequester(fieldFocus)
                            .onFocusChanged { focused = it.isFocused }
                            // Its words are what accessibility reads and sets: words set from outside (`oma-phone fill`) become
                            // the tab's draft, as typed ones do, so the next drawing shows them and Send sends them.
                            .semantics {
                                editableText = AnnotatedString(shown)
                                setText { set ->
                                    val next = setDraft(typed, tab, set.text, listening) ?: return@setText false
                                    typed = next
                                    notice = null
                                    true
                                }
                            }
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
                                val oma = if (working) "ask, or add to this, any time…" else if (followUp) "follow up on this thread…" else "ask soma…"
                                val hint = if (listening) "listening…" else fieldHint(to, chatOn) ?: oma
                                // In the pack's own face, sloped: what stands in for his words is not his words (Boss, 2026-10-13).
                                BasicText(hint, style = Pack.body(Pack.size.item, Pack.muted).copy(fontStyle = FontStyle.Italic), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            inner()
                        }
                    },
                )
                if (!listening && host.attaches) {
                    Box(
                        Modifier
                            .semantics { contentDescription = "Attach images" }
                            .clickable(enabled = !importing && images.size < MAX_PROMPT_IMAGES) {
                                pickImages()
                            }.padding(horizontal = 12.dp, vertical = 12.dp),
                    ) { BasicText("+", style = Pack.body(color = Pack.accent)) }
                }
                // Tap to dictate into the field; it stops at the next pause, or at another tap.
                if (host.dictates) Box(
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
                        if (deciding) host.stopFront()
                        host.dropFrontWaiting(to.chat.ifEmpty { null })
                        if (tabWorking) host.stopBuilder(to.builder)
                        tick++
                    }
                }
            }
        }
        // Over everything, as home's menu is.
        val pickChat = (to as? SendTo.Chat)?.chat
        if (picking != null && pickChat != null) PickMenu(picking!!, hosts, book.chat(pickChat), { on, agent, model -> hostPicked(pickChat, on, agent, model) }) { picking = null }
        menuFor?.let { key ->
            val thread = all.firstOrNull { it.key == key }
            val shown = tabEntries(tasks, key, book)
            val answer = lastAnswer(shown)
            val stop = tabStop(key, thread, book)
            // From the last look at the builders; on a phone tab a fresh one is taken as the menu opens, and its rows follow it.
            val mend = tabMend(key, host.builderFacts())
            LaunchedEffect(key) {
                if (tabBuilder(key) != null) {
                    withContext(Dispatchers.IO) { host.lookAtBuilders() }
                    tick++
                }
            }
            val archived = all.filter { it.key in book.archived }
            // What its thread on a computer can move to: every agent and model that computer runs but the one it runs now.
            val moving = switchThread(key, book)
            val switches = moving?.let { id ->
                val known = host.macView().threads
                val now = known.firstOrNull { it.id == id } ?: return@let emptyList()
                val on = hosts.firstOrNull { it.id == macMachineOf(id) }
                macSwitchTargets(MacPick(now.kind, now.model), on?.agents.orEmpty(), known, on?.models.orEmpty())
            }.orEmpty()
            TabMenu(
                title = tabTitle(key, threads + all),
                actions = tabMenu(key, key in book.pinned, stop != null, answer != null, archived.size, mend, switchable = switches.isNotEmpty(), bot = thread?.bot != null),
                readBot = { told -> macThreadId(key)?.let { host.botFiles(it, told) } ?: told(null) },
                archived = archived,
                switches = switches,
                onSwitch = { to ->
                    moving?.let { host.switchMac(it, to) }
                    tick++
                },
                onDismiss = { menuFor = null },
                onRename = { name ->
                    host.changeBook { it.renamed(key, name, System.currentTimeMillis()) }
                    tick++
                },
                onRestore = { back ->
                    host.changeBook { it.restored(back, System.currentTimeMillis()) }
                    pick(back)
                    tick++
                },
                onAction = { action ->
                    when (action) {
                        TabAction.Pin, TabAction.Unpin -> host.changeBook { it.pinnedAs(key, action == TabAction.Pin) }
                        TabAction.Stop -> when (stop) {
                            is TabStop.Builder -> host.stopBuilder(stop.builder)
                            is TabStop.Mac -> host.stopMac(stop.thread) { said ->
                                notice = said
                                tick++
                            }
                            null -> {}
                        }
                        // The watch looks again and checks every guard again before it stops anything; what it did is said here.
                        TabAction.Restart -> mend.restart?.let { builder ->
                            host.restartBuilder(builder) { said ->
                                notice = said
                                tick++
                            }
                        }
                        TabAction.Clean -> host.cleanBuilders { said ->
                            notice = said
                            tick++
                        }
                        TabAction.Copy -> answer?.let { host.copy(it) }
                        TabAction.Archive -> {
                            // Archived after its newest entry, so only what is sent to it from now on brings it back.
                            val at = maxOf(System.currentTimeMillis(), shown.maxOfOrNull { it.time } ?: 0L)
                            host.changeBook { it.archivedAs(key, at) }
                            if (key == tab) pick(OMA_TAB)
                        }
                        TabAction.Fresh -> macThreadId(key)?.let { id ->
                            host.freshBot(id) { said ->
                                notice = said
                                tick++
                            }
                        }
                        TabAction.Rename, TabAction.Archived, TabAction.Switch, TabAction.Instructions, TabAction.Notes -> {}
                    }
                    menuFor = null
                    tick++
                },
            )
        }
    }
}

/**
 * The tabs, dropped down under the header: Oma, then each thread with its dot, scrolling sideways when they
 * are more than fit, and a square ＋ after the last that starts a new chat. The open one is filled in. A long
 * press on a tab opens its menu, with a tick the hand can feel.
 */
@Composable
private fun TabStrip(
    threads: List<AgentThread>,
    open: String,
    onTab: (String) -> Unit,
    onMenu: (String) -> Unit,
    onNew: () -> Unit,
    soma: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val tabs = chatTabs(threads, soma)
    // Where each tab and the ＋ lie along the strip, as last laid out, and how wide the strip shows.
    val spans = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
    var width by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val margin = with(LocalDensity.current) { STRIP_MARGIN.roundToPx() }
    val want = stripSpan(open, tabs, spans)
    // The open tab is brought wholly into view as it opens, after ＋ or Restore too, and again if it changes size.
    LaunchedEffect(open, want, width) {
        val (start, end) = want ?: return@LaunchedEffect
        if (width <= 0) return@LaunchedEffect
        val to = stripScroll(scroll.value, width, start - margin, end + margin).coerceIn(0, scroll.maxValue)
        if (to == scroll.value) return@LaunchedEffect
        if (Motion.reduced) scroll.scrollTo(to) else scroll.animateScrollTo(to, Motion.spatial)
    }
    fun Modifier.spans(key: String) = onPlaced { spot ->
        val x = spot.positionInParent().x.roundToInt()
        spans[key] = x to x + spot.size.width
    }
    Row(
        Modifier.fillMaxWidth().onSizeChanged { width = it.width }.horizontalScroll(scroll).padding(horizontal = 6.dp, vertical = 4.dp).height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (key in tabs) {
            val thread = threads.firstOrNull { it.key == key }
            val on = key == open
            Row(
                Modifier
                    .spans(key)
                    .padding(end = 6.dp)
                    .fillMaxHeight()
                    .framed(Pack.hair, if (on) Pack.foreground else Pack.border, if (on) Pack.foreground else Pack.surface, radius = 6.dp)
                    .combinedClickable(
                        onClick = { onTab(key) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onMenu(key)
                        },
                    ).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (thread != null) {
                    ThreadDot(thread.state, if (on) Pack.background else Pack.muted)
                    Spacer(Modifier.width(6.dp))
                }
                BasicText(Pack.caption(tabTitle(key, threads)), style = Pack.mono(Pack.size.small, if (on) Pack.background else Pack.foreground), maxLines = 1)
                // With two Macs each Mac thread's tab says which it is on, quieter than its name.
                if (!thread?.machine.isNullOrEmpty()) {
                    BasicText(
                        Pack.caption(thread!!.machine),
                        Modifier.padding(start = 6.dp),
                        style = Pack.mono(Pack.size.small, if (on) Pack.background else Pack.muted),
                        maxLines = 1,
                    )
                }
            }
        }
        // As tall as the tabs and as wide as it is tall.
        Box(
            Modifier
                .spans(NEW_TAB)
                .fillMaxHeight()
                .aspectRatio(1f, matchHeightConstraintsFirst = true)
                .framed(Pack.hair, Pack.border, Pack.surface, radius = 6.dp)
                .semantics { contentDescription = "New chat" }
                .clickable(onClick = onNew),
            contentAlignment = Alignment.Center,
        ) { BasicText("+", style = Pack.mono(Pack.size.body, Pack.foreground)) }
    }
}

/** The room left beside the open tab when it is scrolled into view: the strip's own padding, which its tabs are measured inside of. */
private val STRIP_MARGIN = 6.dp

/** How far apart the header's dots stand, centre to centre: the 9 dp dot with the mock-up's 7 dp between. */
private val DOT_PITCH = 16.dp

/** The most of the header the dots take before they scroll, so the tab's name always has room. */
private val DOTS_WIDTH = DOT_PITCH * 12

/**
 * The menu a long press on a tab opens (Boss, 2026-10-13: "long press on a tab, and then have some options
 * there"), in home's menu look: the tab's name as a caption over [actions] ([tabMenu]). Rename opens a field in
 * place, with done and cancel; the last row lists the [archived] tabs, and tapping one brings it back. Back
 * goes where [menuBack] says, as home's own menu closes on Back rather than leaving home.
 */
@Composable
private fun TabMenu(
    title: String,
    actions: List<TabAction>,
    archived: List<AgentThread>,
    switches: List<MacPick> = emptyList(),
    onSwitch: (MacPick) -> Unit = {},
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onRestore: (String) -> Unit,
    onAction: (TabAction) -> Unit,
    readBot: ((dev.omalauncher.assist.MacBotFiles?) -> Unit) -> Unit = { it(null) },
) {
    var page by remember { mutableStateOf(MenuPage.Rows) }
    // Kept over the whole menu, so a name half typed is still there if he steps back to the rows and in again.
    var name by remember { mutableStateOf(title) }
    // Back steps out of a page to the rows, and from the rows closes the menu, never the chat under it.
    BackHandler { menuBack(page)?.let { page = it } ?: onDismiss() }
    val caption = Pack.mono(Pack.size.small, Pack.muted)
    Menu(onDismiss) {
        when (page) {
            MenuPage.Rows -> {
                BasicText(Pack.caption(title), Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                for (action in actions) {
                    val label = if (action == TabAction.Archived) archivedRow(archived.size) else action.label
                    MenuItem(label, if (action == TabAction.Stop || action == TabAction.Restart) Pack.red else Pack.foreground) {
                        when (action) {
                            TabAction.Rename -> page = MenuPage.Rename
                            TabAction.Archived -> page = MenuPage.Archived
                            TabAction.Switch -> page = MenuPage.Switch
                            TabAction.Instructions -> page = MenuPage.Instructions
                            TabAction.Notes -> page = MenuPage.Notes
                            else -> onAction(action)
                        }
                    }
                }
            }
            MenuPage.Rename -> {
                BasicText(Pack.caption("Rename $title"), Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.replace("\n", " ").take(TAB_NAME_CHARS) },
                    singleLine = true,
                    textStyle = Pack.body(Pack.size.item, Pack.foreground),
                    cursorBrush = SolidColor(Pack.accent),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).framed(Pack.mark, Pack.accent, Pack.surface).padding(10.dp).focusRequester(focus),
                )
                Row(Modifier.padding(top = 4.dp)) {
                    BracketAction("done", Pack.accent) {
                        onRename(name)
                        onDismiss()
                    }
                    BracketAction("cancel", Pack.muted, onDismiss)
                }
            }
            MenuPage.Switch -> {
                // A new thread of the pick takes over, with a handoff from this one; this one stays, and says where it went.
                BasicText(Pack.caption("move $title to"), Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Taken once: a second tap before the menu has closed never asks for a second switch.
                var taken by remember { mutableStateOf(false) }
                for (pick in switches) {
                    MenuItem(macPickName(pick)) {
                        if (!taken) {
                            taken = true
                            onSwitch(pick)
                        }
                        onDismiss()
                    }
                }
            }
            MenuPage.Instructions, MenuPage.Notes -> {
                // Read from the bot's computer each time the page opens; to change either, he asks an agent there.
                var read by remember(page) { mutableStateOf<dev.omalauncher.assist.MacBotFiles?>(null) }
                var asked by remember(page) { mutableStateOf(false) }
                LaunchedEffect(page) { readBot { read = it; asked = true } }
                val instructions = page == MenuPage.Instructions
                BasicText(Pack.caption(if (instructions) "$title · instructions" else "$title · notes"), Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val text = read?.let { if (instructions) it.instructions else it.notes }
                BasicText(
                    when {
                        !asked -> "reading…"
                        text == null -> "Its computer can't be reached right now."
                        text.isBlank() -> if (instructions) "No instructions." else "No notes yet."
                        else -> text
                    },
                    Modifier.padding(horizontal = 12.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    style = Pack.body(Pack.size.small, Pack.foreground),
                )
                BasicText("To change this, ask an agent on its computer.", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = caption)
            }
            MenuPage.Archived -> {
                BasicText(Pack.caption(archivedCaption(archived.size)), Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                for (thread in archived) {
                    MenuItem(thread.name) {
                        onRestore(thread.key)
                        onDismiss()
                    }
                }
            }
        }
    }
}

/** The computer a new [chat]'s thread is shown as going to: his pick, else the first that answers, else the first. */
fun chatHostShown(hosts: List<HostChoice>, chat: NewChat?): HostChoice? =
    chat?.host?.let { pick -> hosts.firstOrNull { it.id == pick } } ?: hosts.firstOrNull { it.reachable } ?: hosts.firstOrNull()

/**
 * Two short lines under a new chat's first line: "on [Mac Pro]" and "with [Claude]", the computer its thread will be made
 * on and the agent that runs it. Each bracket opens a menu ([PickMenu], drawn over everything by the screen). Until he
 * picks, it shows what would be used anyway: the first computer that answers and the first agent it runs. A computer
 * out of reach is marked beside it, and his words wait there until it answers ([macChatMachine]).
 */
@Composable
private fun ChatPicks(hosts: List<HostChoice>, chat: NewChat?, onMenu: (String) -> Unit) {
    val on = chatHostShown(hosts, chat) ?: return
    val small = Pack.mono(Pack.size.small, Pack.muted)
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("on", style = small)
            BracketAction(pickName(on.name), Pack.accent) { onMenu(PICK_HOST) }
            if (!on.reachable) BasicText("offline", style = small)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val agent = chatAgentShown(on, chat)
            BasicText("with", style = small)
            BracketAction(macKindName(agent), Pack.accent) { onMenu(PICK_AGENT) }
            // The model, where the agent has more than its own default to pick from.
            if (chatModels(on, agent).size > 1) BracketAction(pickName(macModelName(chatModelShown(on, chat))), Pack.accent) { onMenu(PICK_MODEL) }
        }
    }
}

/** A computer's name as a pick shows it: short enough for the phone's cover screen. */
private fun pickName(name: String) = if (name.length <= PICK_NAME_CHARS) name else name.take(PICK_NAME_CHARS - 1).trimEnd() + "…"

private const val PICK_NAME_CHARS = 18
private const val PICK_HOST = "host"
private const val PICK_AGENT = "agent"
private const val PICK_MODEL = "model"

/** The models [agent]'s threads on [on] can be made with, its own default first. */
fun chatModels(on: HostChoice, agent: String): List<String> = on.models[macKindOf(agent)].orEmpty().ifEmpty { listOf("") }

/**
 * The model a new [chat]'s thread on [on] is shown as running: his pick with the agent he picked, else the default new
 * chats remember when it is for the agent shown, else the agent's own ("").
 */
fun chatModelShown(on: HostChoice, chat: NewChat?): String {
    val agent = chatAgentShown(on, chat)
    val model = if (!chat?.agent.isNullOrBlank()) chat?.model.orEmpty() else on.default?.takeIf { it.kind == agent }?.model.orEmpty()
    return model.takeIf { it in chatModels(on, agent) } ?: ""
}

/** The agents [on] runs, in the order work goes from one to the next; every agent the runner knows while it hasn't said. */
fun chatAgents(on: HostChoice): List<String> {
    val said = on.agents.map(::macKindOf)
    return if (said.isEmpty()) MAC_KINDS else MAC_KINDS.filter { it in said } + said.filter { it !in MAC_KINDS }
}

/** The agent a new [chat]'s thread on [on] is shown as running: his pick when [on] runs it, else the default new chats remember, else the first it runs. */
fun chatAgentShown(on: HostChoice, chat: NewChat?): String {
    val agents = chatAgents(on)
    return chat?.agent?.takeIf { it.isNotBlank() }?.let(::macKindOf)?.takeIf { it in agents } ?: on.default?.kind?.takeIf { it in agents } ?: agents.firstOrNull() ?: MAC_CLAUDE
}

/** The menu a pick opened, over everything as the tab menu is; Back closes it and never the chat. */
@Composable
private fun PickMenu(which: String, hosts: List<HostChoice>, chat: NewChat?, onPick: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    val on = chatHostShown(hosts, chat) ?: return onDismiss()
    BackHandler { onDismiss() }
    Menu(onDismiss) {
        if (which == PICK_HOST) {
            MenuTitle("make its thread on")
            for (choice in hosts) {
                MenuItem(choice.name + if (choice.reachable) "" else " · offline", if (choice.reachable) Pack.foreground else Pack.muted) {
                    // Another computer may not run the agent picked: then its first is used, on its own default model.
                    val agent = chat?.agent?.takeIf { macKindOf(it) in chatAgents(choice) }.orEmpty()
                    onPick(choice.id, agent, if (agent.isEmpty()) "" else chat?.model.orEmpty())
                    onDismiss()
                }
            }
        } else if (which == PICK_MODEL) {
            val agent = chatAgentShown(on, chat)
            MenuTitle("${macKindName(agent)} on")
            for (model in chatModels(on, agent)) {
                MenuItem(macModelName(model)) {
                    onPick(on.id, agent, model)
                    onDismiss()
                }
            }
        } else {
            MenuTitle(if (on.agents.isEmpty()) "with · ${on.name} hasn't said which it runs yet" else "with")
            for (kind in chatAgents(on)) {
                MenuItem(macKindName(kind)) {
                    onPick(on.id, kind, "")
                    onDismiss()
                }
            }
        }
    }
}

/** The host of the chat on screen, for the parts that draw what only the device can read (the pictures). */
private val LocalChatHost = staticCompositionLocalOf<ChatHost> { error("OmaChatScreen provides the host") }

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
        // What a bot's command does beyond itself (runs as administrator, code from outside Arch's repos) stands out in red.
        val (marks, rest) = cardMarks(card.explanation)
        for (mark in marks) BasicText(mark, Modifier.padding(top = 4.dp), style = Pack.body(Pack.size.small, Pack.red))
        if (rest.isNotBlank()) BasicText(rest.take(300), Modifier.padding(top = 4.dp), style = small)
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
    /** A builder's follow-up only: stop the turn it waits behind and send it at once. */
    onSendNow: (() -> Unit)? = null,
    onReceipt: (() -> Unit)? = null,
) {
    val small = Pack.mono(Pack.size.small, Pack.muted)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val sent = remember(task.time) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(task.time)) }
        BasicText(listOfNotNull("new thread".takeIf { task.newThread }, sent).joinToString(" · "), Modifier.fillMaxWidth().padding(bottom = 4.dp), style = small.copy(textAlign = TextAlign.Center))
        // Another agent's words, not his: labeled over them, in a frame of the accent's, set in from his side.
        val mail = task.mail.isNotEmpty()
        if (mail) BasicText(task.mail, Modifier.padding(bottom = 4.dp, start = 24.dp), style = small.copy(color = Pack.accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column(Modifier.fillMaxWidth().padding(start = if (mail) 24.dp else 0.dp).framed(Pack.hair, if (mail) Pack.accent else Pack.border, Pack.surface, radius = 12.dp).padding(10.dp)) {
            BasicText(task.prompt, style = Pack.body())
            // What he sent with it stays in the chat, as a small rail of pictures (Boss, 2026-10-15, as the Claude app does).
            if (task.images.isNotEmpty()) SentImages(task.images)
            if (task.screen) BasicText("with this screen", Modifier.padding(top = 4.dp), style = small)
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
                // Send now stops the turn ahead, so it reads in the accent, as what acts on the agent does.
                if (waiting.cancel && onSendNow != null) BasicText("[ send now ]", Modifier.clickable(onClick = onSendNow).padding(horizontal = 8.dp, vertical = 8.dp), style = small.copy(color = Pack.accent))
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
        // What its thread on the Mac said after the answer, between his messages: notes and steps quietly, as the
        // steps above are, and the agent's own report on its background work as an answer is.
        for (said in task.later) {
            if (said.words) SelectionContainer { MarkdownText(said.text.trim(), Modifier.padding(top = 6.dp)) }
            else BasicText("· ${said.text}", Modifier.padding(top = 4.dp), style = small, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * One turn of a Mac thread from its own record, read only: the words it was sent, its steps in one quiet line and
 * its answer. Framed in the muted border and dated as an entry is, but with no receipt, stop or try again: it
 * was never sent from here and nothing on it can be done again.
 */
@Composable
private fun EarlierEntry(turn: MacTurn) {
    val small = Pack.mono(Pack.size.small, Pack.muted)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val sent = remember(turn.time) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(turn.time * 1000)) }
        BasicText("on the Mac · $sent", Modifier.fillMaxWidth().padding(bottom = 4.dp), style = small.copy(textAlign = TextAlign.Center))
        if (turn.words.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().framed(Pack.hair, Pack.border, Pack.background, radius = 12.dp).padding(10.dp)) {
                BasicText(turn.words, style = Pack.body(color = Pack.muted))
            }
        }
        macTurnSteps(turn)?.let { BasicText("· $it", Modifier.padding(top = 4.dp), style = small, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (turn.answer.isNotBlank()) {
            SelectionContainer {
                if (turn.failed) BasicText(turn.answer, Modifier.padding(top = 6.dp), style = Pack.body()) else MarkdownText(turn.answer, Modifier.padding(top = 6.dp))
            }
        }
        val ended = when {
            turn.stopped -> "stopped"
            turn.failed -> "ended with an error"
            else -> null
        }
        if (ended != null) BasicText(ended, Modifier.padding(top = 4.dp), style = small)
    }
}

/** The pictures sent with a request, small and in a row; one that is gone from the phone says so. */
@Composable
private fun SentImages(paths: List<String>) {
    val host = LocalChatHost.current
    Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState())) {
        paths.forEachIndexed { i, path ->
            val picture by produceState<ImageBitmap?>(null, path) {
                value = withContext(Dispatchers.IO) { host.thumbnail(host.sentImage(path), 144) }
            }
            val shown = picture
            Box(Modifier.padding(end = 6.dp).size(48.dp).framed(Pack.hair, Pack.border, Pack.background, radius = 6.dp), contentAlignment = Alignment.Center) {
                if (shown != null) Image(shown, "Sent image ${i + 1}", Modifier.size(48.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                else BasicText("image", style = Pack.body(Pack.size.small, Pack.muted))
            }
        }
    }
}

@Composable
private fun AttachmentPreview(
    image: PromptImage,
    number: Int,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    val host = LocalChatHost.current
    val preview by produceState<ImageBitmap?>(null, image.file) {
        value = withContext(Dispatchers.IO) { host.thumbnail(image.file, 160) }
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
