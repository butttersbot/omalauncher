package dev.omalauncher.artifactpreview

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ImageBitmap
import dev.omalauncher.assist.*
import dev.omalauncher.oma.*
import dev.omalauncher.linux.PromptImage
import java.io.File

/** Isolated, fictional UI fixture. It cannot reach any runner or send a real prompt. */
class PreviewActivity : ComponentActivity() {
    // --ez touchVouches true: the walk's taps count as Boss's own finger, as a real one does in Soma (BossReceipts.kt);
    // a linked question passes his answer on only then.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (intent.getBooleanExtra("touchVouches", false) && ev.actionMasked == android.view.MotionEvent.ACTION_UP) dev.omalauncher.oma.BossTouch.touched(true)
        return super.dispatchTouchEvent(ev)
    }
    var narrow by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.omalauncher.ui.PlatformFonts.nerdSymbols = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(R.font.nerd_symbols))
        val folder = File(filesDir, "preview-manga").apply { mkdirs() }
        fun copyAssets(path: String, destination: File) {
            val names = assets.list(path).orEmpty()
            if (names.isEmpty()) assets.open(path).use { input -> destination.outputStream().use { input.copyTo(it) } }
            else { destination.mkdirs(); names.forEach { copyAssets("$path/$it", File(destination, it)) } }
        }
        copyAssets("manga", folder)
        val pick = dev.omalauncher.theme.pack.PackRegistry.choose("manga") { dev.omalauncher.theme.pack.DirectoryPackSource(folder) }
        dev.omalauncher.ui.ActivePack.choose(pick)
        pick.load?.pack?.type?.let { type ->
            dev.omalauncher.ui.ActivePack.fonts = listOf(type.display, type.body, type.hand, type.mono).map { it.file }.filter { it.isNotBlank() }.distinct().associateWith { path ->
                // As the launcher loads them (ThemeStore.packFont): a variable font draws each weight itself, so the
                // walk shows the semi-bold headlines the cards have, not one face at every weight.
                androidx.compose.ui.text.font.FontFamily(dev.omalauncher.theme.pack.FONT_WEIGHTS.map { w ->
                    androidx.compose.ui.text.font.Font(File(folder, path), androidx.compose.ui.text.font.FontWeight(w), androidx.compose.ui.text.font.FontStyle.Normal,
                        androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(w)))
                })
            }
        }
        setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxHeight().then(if (narrow) Modifier.width(400.dp) else Modifier.fillMaxWidth())) {
                    if (intent.getStringExtra("dictationWidget") != null) {
                        DictationPreview(intent.getStringExtra("dictationWidget")!!)
                    } else if (intent.getBooleanExtra("connections", false)) {
                        ConnectionsPreview()
                    } else if (intent.getBooleanExtra("phoneRunner", false)) {
                        val pairing = remember { object : dev.omalauncher.ui.PhoneRunnerPairing {
                            private val paired = mutableListOf<String>()
                            override fun devices() = listOf("jacks-mac-pro", "macbook-pro")
                            override fun paired() = paired.toList()
                            override fun pair(device: String) { paired += device; android.util.Log.i("SomaWalk", "fixture pairing tap $device") }
                            override fun unpair(device: String) { paired -= device; android.util.Log.i("SomaWalk", "fixture unpair tap $device") }
                        } }
                        dev.omalauncher.ui.PhoneRunnerSetting(pairing) { finish() }
                    } else {
                    val empty = intent.getBooleanExtra("empty", false)
                    val host = remember { PreviewHost(this@PreviewActivity, empty, intent.getStringExtra("mail"), intent.getBooleanExtra("questions", false), intent.getStringExtra("waitingQuestion"), intent.getBooleanExtra("rankedSessions", false), intent.getBooleanExtra("queuedBehind", false), intent.getStringExtra("reconnect")) }
                    // --ez newChat true: opens on a new chat, with its pick rows (computer, agent, model, mode; slice 6).
                    val start = remember { if (intent.getBooleanExtra("newChat", false)) dev.omalauncher.oma.newChatTab(host) else if (intent.getStringExtra("waitingQuestion") == "builder") PHONE_THREAD else if (intent.getStringExtra("waitingQuestion") in LINKED_MODES) { if (intent.getStringExtra("startTab") == "oma") OMA_TAB else PHONE_THREAD } else if (empty) PHONE_THREAD_B else OMA_TAB }
                    // --es updates offer|waiting|updating|updated|failed: the update pill, fictional offers only (UpdatesPreview).
                    val updates = remember { intent.getStringExtra("updates")?.let(::updatesFixture) }
                    if (updates != null) androidx.compose.runtime.CompositionLocalProvider(dev.omalauncher.oma.LocalTopBarExtra provides { dev.omalauncher.updates.PhoneUpdatePill(updates) }) {
                        OmaChatScreen(host, null, {}, start, {})
                    } else if (intent.getBooleanExtra("tabWidth", false) || intent.getBooleanExtra("links", false)) {
                        // The tab walk opens tabs by tapping them, so this fixture keeps the open tab.
                        var open by remember { mutableStateOf(start) }
                        OmaChatScreen(host, null, {}, open, { open = it })
                    } else OmaChatScreen(host, null, {}, start, {})
                    }
                }
            }
        }
    }
}

private class PreviewHost(private val context: Context, private val empty: Boolean = false, private val mailMode: String? = null, private val questions: Boolean = false, private val waitingQuestion: String? = null, private val rankedSessions: Boolean = false,
    /** --ez queuedBehind true: a fictional mail queued behind the waiting builder question (its [ send now ] must not show). */
    private val queuedBehind: Boolean = false,
    /** --es reconnect dropped|back|done: a fictional Mac thread whose agent dropped mid-request, then came back and finished. */
    private val reconnect: String? = null) : ChatHost {
        val now = System.currentTimeMillis()
        private val page = """<!doctype html><meta name="viewport" content="width=device-width, initial-scale=1">
<style>body{margin:0;padding:24px;background:#fdfdfb;color:#111113;font-family:system-ui}small{color:#d7262d;font-size:10px;letter-spacing:.1em}h1{font-size:32px;line-height:1.15;margin:18px 0}p{font-size:14px;line-height:1.6}button{background:#111113;color:#fdfdfb;padding:12px 18px;border:0}section{border:1px solid #111113;padding:18px;margin:22px 0}.record{border-radius:50%;width:100px;height:100px;background:repeating-radial-gradient(#111113 0 3px,#444 4px);margin:15px auto;display:grid;place-items:center}.record:after{content:"";width:28px;height:28px;border:8px solid #fdfdfb;border-radius:50%;background:#d7262d}</style>
<small>THE LISTENING ROOM / VOL. 01</small><h1>Put a record on.<br>Stay a while.</h1><p>A few records for a slower evening. Start anywhere.</p><section><div class="record"></div><h2>For an evening in</h2><p>Side A · Four selections</p><button onclick="this.textContent='Playing sample';document.querySelector('small').textContent='PLAYING / SIDE A'">Play sample</button></section><p>The first quiet hour<br>Windows left open<br>A little after midnight</p>"""
        val thread = listOf(ChatTask(now, "Claude Code", "Build a small listening room page for the records I keep coming back to. Keep it in our Manga look.", result = """
The listening room is ready. A record up front, a short queue underneath, and room to breathe.

Open the preview to try the layout. We can keep talking while you look at it.

[Listening room](outputs/listening-room.html)

[Interaction notes](outputs/interaction-notes.md)
""".trimIndent(), status = TaskStatus.Done, answeredBy = "Claude Code"))

        override fun readArtifact(thread: String, path: String): ArtifactRead {
            val html = path.endsWith(".html")
            val content = if (html && questions) context.assets.open("questions/proposal.html").bufferedReader().use { it.readText() } else if (html) page else "# Interaction notes\n\nThe chat stays beside the preview. Close it to return to the same draft."
            return ArtifactRead.Ready(ArtifactDocument(path.substringAfterLast('/'), if (html) "text/html" else "text/markdown", content.toByteArray(), "sample-v1"))
        }

        override fun tasks(): List<ChatTask> {
            if (links) return listOf(ChatTask(now - 60_000, "oma", "where is everything?", result = LINKS_ANSWER, status = TaskStatus.Done, answeredBy = "Haiku"))
            if (mailMode != null) {
                val pending = (1L..4L).map { ChatTask(it, "Codex", "Fictional update $it", mail = "Agent mail", status = TaskStatus.Queued) }
                if (mailMode == "pending") return pending
                val queue = FollowUpQueue()
                pending.forEach { queue.add(FollowUp(it.time, it.prompt, mailSender = "fictional", mailId = "m${it.time}")) }
                val bundled = bundledMailHistory(pending, queue.after(TaskStatus.Done).next!!)
                return bundled.map { if (it.time == 1L) it.copy(status = TaskStatus.Done, result = "Combined handoff complete.") else it } +
                    ChatTask(5, "Codex", "Legacy update", mail = "Agent mail", status = TaskStatus.Done, result = "Delivered in the bundled agent handoff.")
            }
            if (reconnect != null) return reconnectTasks
            if (waitingQuestion in LINKED_MODES) return listOf(ChatTask(LinkedFixture.began, "Claude Code", "Fictional skills question", builder = BuilderId.A.saved,
                status = if (LinkedFixture.phoneDone) TaskStatus.Done else TaskStatus.Running,
                approvals = listOf(dev.omalauncher.oma.ChatApproval("AskUserQuestion", "Restore or leave the skills", if (LinkedFixture.phoneDone) dev.omalauncher.oma.ApprovalAnswer.Approved else dev.omalauncher.oma.ApprovalAnswer.Waiting, linkedEntry.nonce))))
            if (waitingQuestion == "builder") return listOf(ChatTask(now, "Claude Code", "Fictional four-question phone builder", builder = BuilderId.A.saved,
                status = if (questionDone) TaskStatus.Done else TaskStatus.Running,
                result = if (questionDone) "All four phone builder answers returned." else "",
                approvals = listOf(dev.omalauncher.oma.ChatApproval("AskUserQuestion", "Four fictional choices", if (questionDone) dev.omalauncher.oma.ApprovalAnswer.Approved else dev.omalauncher.oma.ApprovalAnswer.Waiting, "builder-ticket")))) +
                if (queuedBehind) listOf(ChatTask(now + 1, "Claude Code", "Fictional mail queued behind the question", builder = BuilderId.A.saved, mail = "Agent mail from a fictional Mac lane", status = TaskStatus.Queued)) else emptyList()
            return (if (empty) emptyList() else if (questionSkipped) thread.map { it.copy(steps = it.steps + "skipped") } else thread) + modeAnswers
        }
        // /mode in a new chat (slice 6): answered with the engine's own words, as Soma's answer under his.
        private var modeAnswers by mutableStateOf(listOf<ChatTask>())
        private var chatMode = ""
        private fun answerMode(prompt: String, chat: String): Boolean {
            val command = dev.omalauncher.assist.modeCommand(prompt) ?: return false
            val said = when (command) {
                dev.omalauncher.assist.ModeCommand.Show -> dev.omalauncher.assist.modeShown("claude", "Claude", dev.omalauncher.assist.macModeFor("claude", chatMode))
                is dev.omalauncher.assist.ModeCommand.Choose -> dev.omalauncher.assist.modeChosen(command.mode, made = false).also { chatMode = command.mode }
                is dev.omalauncher.assist.ModeCommand.Refused -> command.why
            }
            android.util.Log.i("SomaWalk", "mode command: $prompt -> $said")
            modeAnswers = modeAnswers + ChatTask(System.currentTimeMillis(), dev.omalauncher.oma.QUICK_AGENT, prompt, chat = chat, direct = true, status = TaskStatus.Done, result = said, answeredBy = dev.omalauncher.assist.OMA_NAME)
            return true
        }
        override fun updateTask(time: Long, save: Boolean, change: (ChatTask) -> ChatTask) {}
        override fun working() = false
        override fun builders() = if (waitingQuestion in LINKED_MODES) mapOf(BuilderId.A to BuilderState(!LinkedFixture.phoneDone, if (LinkedFixture.phoneDone) emptyList() else listOf(linkedEntry), "Questions waiting")) else if (waitingQuestion == "builder") mapOf(BuilderId.A to BuilderState(!questionDone, if (questionDone) emptyList() else listOf(builderQuestionEntry), "Questions waiting")) else emptyMap<BuilderId, BuilderState>()
        override fun continuing() = false
        override fun front() = FrontState()
        private var questionDone by mutableStateOf(false)
        private var questionSkipped by mutableStateOf(false)
        private val questionJson = org.json.JSONObject("""{"questions":[{"id":"layout","header":"Layout","question":"Where should the form open?","options":[{"label":"Beside chat","description":"Keep the conversation visible"},{"label":"Full screen","description":"Use the whole screen"}]},{"id":"checks","header":"Checks","question":"Which screen sizes should we check?","multiSelect":true,"options":[{"label":"Cover"},{"label":"Unfolded"}]}]}""").apply { if (waitingQuestion == "codex") put("codex_request", "questions")
            if (waitingQuestion == "builder") put("questions", org.json.JSONArray((1..4).map { i -> org.json.JSONObject().put("question", "Fictional decision $i?").put("header", "Choice $i").put("options", org.json.JSONArray().put(org.json.JSONObject().put("label", "Choice $i: Keep the fixture beside this conversation and continue with the checked layout for both Fold screens").put("description", "Fictional only; no account, funds, network or real agent."))) }))
            if (waitingQuestion == "strict") getJSONArray("questions").let { qs -> (0 until qs.length()).forEach { qs.getJSONObject(it).put("isOtherAllowed", false) } }
        }
        private val builderQuestionLedger = ApprovalLedger { "builder-ticket" }
        private val builderQuestionEntry by lazy { (builderQuestionLedger.admit(AgentApproval("builder-request", "AskUserQuestion", questionJson, "Four fictional decisions")) as Admission.Shown).entry }
        // --es reconnect: the production path, stage by stage (Boss, 2026-10-30: red stuck after the agent reconnected).
        // dropped: the runner said the thread rested mid-request (macSettle); back: it works again (a status event);
        // done: it answered. The thread's dot, label and row are made by macThreadNow, as in Soma.
        private val reconnectThread get() = dev.omalauncher.assist.MacThread(RECONNECT_ID, "Reconnect test", "/Users/boss/Projects/fixture", dev.omalauncher.assist.ThreadHome.Mac, "claude", "",
            when (reconnect) { "back" -> dev.omalauncher.assist.MacThreadStatus.Working; "done" -> dev.omalauncher.assist.MacThreadStatus.Idle; else -> dev.omalauncher.assist.MacThreadStatus.Interrupted },
            null, "", 0, 0, emptyList())
        private val reconnectTasks: List<ChatTask> by lazy {
            val sent = dev.omalauncher.assist.macTaken(dev.omalauncher.assist.macHandOver(ChatTask(System.currentTimeMillis() - 120_000, dev.omalauncher.oma.UNDECIDED_AGENT, "Fictional long job"), reconnectThread))
            val dropped = dev.omalauncher.assist.macSettle(listOf(sent), RECONNECT_ID, dev.omalauncher.assist.MacThreadStatus.Interrupted, 0)
            val back = dev.omalauncher.assist.macApply(dropped, RECONNECT_ID, dev.omalauncher.assist.MacEvent.Status(1, 1, dev.omalauncher.assist.MacThreadStatus.Working))
            when (reconnect) {
                "back" -> back
                "done" -> dev.omalauncher.assist.macApply(back, RECONNECT_ID, dev.omalauncher.assist.MacEvent.Result(2, 2, "Fictional job finished after the reconnect.", failed = false))
                else -> dropped
            }.also { android.util.Log.i("SomaWalk", "fixture reconnect $reconnect: ${it.map { t -> t.status }}") }
        }
        // --es waitingQuestion linked|alike: Phone A and the MacBook's fictional "Skills cleanup" thread ask the same thing;
        // linked, Phone A's question names that thread (metadata link). Kept per process, so a relaunch on the Oma tab sees it.
        private fun linkedForm(link: Boolean) = org.json.JSONObject().put("questions", org.json.JSONArray().put(org.json.JSONObject()
            .put("question", "Restore the 3 deleted skills or leave them deleted?").put("header", "Skills")
            .put("options", org.json.JSONArray().put(org.json.JSONObject().put("label", "Restore them")).put(org.json.JSONObject().put("label", "Leave deleted")))))
            .apply { if (link) put("metadata", org.json.JSONObject().put("source", "link:t1703ae4f")) }
        // --es waitingQuestion meaning: the MacBook asks the same thing in its own words and options; the fast model's
        // verdict is the fixture's own (what Haiku said of tonight's pair), so nothing leaves the emulator.
        private val meaningMacForm = org.json.JSONObject().put("questions", org.json.JSONArray().put(org.json.JSONObject()
            .put("question", "Syncthing's first pass removed 3 skills here. Should I put them back, or were they meant to be gone?").put("header", "3 skills")
            .put("options", org.json.JSONArray().put(org.json.JSONObject().put("label", "Put them back from the backup")).put(org.json.JSONObject().put("label", "They were meant to be gone")))))
        // The Mac Pro decides which waiting questions are his answered one (POST /questions/match with them as candidates):
        // here, its verdict for tonight's pair.
        override fun matchOnMacPro(card: org.json.JSONObject, answers: org.json.JSONObject, where: String, candidates: org.json.JSONArray): org.json.JSONArray? {
            if (waitingQuestion != "meaning") return null
            android.util.Log.i("SomaWalk", "fixture mac pro match asked from $where, ${candidates.length()} candidate(s)")
            return org.json.JSONArray().put(org.json.JSONObject().put("target_id", "linked-mac-ticket").put("match", "meaning").put("deliver", true)
                .put("answers", org.json.JSONObject().put(meaningMacForm.getJSONArray("questions").getJSONObject(0).getString("question"), "Put them back from the backup")))
        }
        private val linkedEntry by lazy { PendingApproval(AgentApproval("linked-request", "AskUserQuestion", linkedForm(waitingQuestion == "linked"), "Skills"), "linked-phone-nonce", 0) }
        override fun answerBuilderQuestions(entry: PendingApproval, answers: org.json.JSONObject): ApprovalOutcome {
            if (waitingQuestion in LINKED_MODES) {
                android.util.Log.i("SomaWalk", "fixture phone answered $answers"); LinkedFixture.phoneDone = true; return ApprovalOutcome.Decided
            }
            val response = ClaudeProtocol.decision(entry.approval, true, answers)
            check(builderQuestionLedger.claim(entry.id, entry.nonce, false, !questionDone) is Claim.Granted)
            check(response.getJSONObject("response").getJSONObject("response").getString("behavior") == "allow")
            check(response.getJSONObject("response").getJSONObject("response").getJSONObject("updatedInput").getJSONObject("answers").length() == 4)
            android.util.Log.i("SomaWalk", "phone builder question allow updatedInput answers4")
            questionDone = true
            return ApprovalOutcome.Decided
        }
        override fun macPending() = if (waitingQuestion in LINKED_MODES) {
            if (LinkedFixture.macDone) emptyList() else listOf(MacPending("macbook-pro/t1703ae4f", "Skills cleanup", MacApproval("linked-mac-request", "linked-mac-nonce", "AskUserQuestion",
                (if (waitingQuestion == "meaning") meaningMacForm else linkedForm(false)).toString(), ""), "linked-mac-ticket", 2))
        } else if (waitingQuestion == null || waitingQuestion == "builder" || questionDone) emptyList() else listOf(MacPending("fixture", "Fake agent", MacApproval("question-fixture", "fixture-nonce", if (waitingQuestion == "codex") "Question" else "AskUserQuestion", questionJson.toString(), ""), "fixture-ticket", 1))
        override fun answerMacQuestions(ticket: String, answers: org.json.JSONObject, done: (MacApprovalOutcome) -> Unit) {
            if (waitingQuestion in LINKED_MODES) {
                android.util.Log.i("SomaWalk", "fixture mac answered $answers"); LinkedFixture.macDone = true; return done(MacApprovalOutcome.Decided)
            }
            android.util.Log.i("SomaWalk", "waiting question answer $ticket: $answers")
            questionDone = true
            questionSkipped = questionAnswersSkipped(answers)
            done(MacApprovalOutcome.Decided)
        }
        override fun macView() = MacView()
        override fun macLabel(task: ChatTask) = ""
        override fun macEarlier(threadId: String): MacEarlierRead = MacEarlierRead.Unread
        override fun hosts() = listOf(HostChoice("mac", "Mac Pro", true, listOf("claude", "codex", "grok")), HostChoice("omarchy-mg1", "MG1", false, listOf("claude", "grok")))
        override fun agentName() = "Claude Code"
        override fun agentBin() = "claude"
        @Composable override fun linuxInstalled() = true
        override fun frontReady() = true
        /** --ez links true: chat links' walk. Two of its threads have no tab until a link opens them. */
        private val links get() = (context as? android.app.Activity)?.intent?.getBooleanExtra("links", false) == true

        override fun threads() = if (reconnect != null) listOf(
            dev.omalauncher.assist.macThreadNow(reconnectThread, RECONNECT_ID, reconnectTasks, emptyList(), true),
        ) else if (links) listOf(
            AgentThread(PHONE_THREAD, "Phone A", ThreadState.Idle, "", "idle", worked = now),
            AgentThread(PHONE_THREAD_B, "Phone B", ThreadState.Idle, "", "idle", worked = now),
            AgentThread("mac:t850cdc07", "Connections audit", ThreadState.Idle, "", "idle", worked = now - 1000, machine = "Mac Pro"),
            AgentThread("mac:tfeed0001", "Release notes for the update pill", ThreadState.Idle, "", "idle", worked = now - 2000, machine = "Mac Pro"),
            AgentThread("mac:omarchy-mg1/tfeed0002", "Skills cleanup on the MG1 box", ThreadState.Idle, "", "idle", worked = now - 3000, machine = "MG1"),
            AgentThread("mac:tfeed0003", "Last tab on the strip", ThreadState.Idle, "", "idle", worked = now - 4000, machine = "Mac Pro"),
            AgentThread("mac:t13d39067ed6a9bef", "Old notes thread", ThreadState.Idle, "", "idle", worked = now - 900_000_000, machine = "Mac Pro"),
            AgentThread("mac:macbook-pro/t2b7e41f0", "MacBook thread", ThreadState.Idle, "", "idle", worked = now - 900_000_000, machine = "MacBook"),
        ) else if ((context as? android.app.Activity)?.intent?.getBooleanExtra("tabWidth", false) == true) listOf(
            // Tab widths and the header's device label: a phone thread, Mac threads on two machines (one with a very long name), one with no machine.
            AgentThread(PHONE_THREAD, "Phone", ThreadState.Working, "running the tests", "working", worked = now),
            AgentThread("mac:fixture-long", "Rework the whole notification settings page so every switch explains itself in plain words", ThreadState.Idle, "", "idle", worked = now - 1000, machine = "Mac Pro"),
            AgentThread("mac:fixture-mg1", "Skills cleanup", ThreadState.Idle, "", "idle", worked = now - 2000, machine = "MG1"),
            AgentThread("mac:fixture-short", "Release notes", ThreadState.Idle, "", "idle", worked = now - 3000, machine = "Mac Pro"),
            AgentThread("mac:fixture-tail", "Last tab to scroll to", ThreadState.Idle, "", "idle", worked = now - 4000, machine = "MacBook"),
        ) else if (rankedSessions) listOf(
            AgentThread(PHONE_THREAD, "Newer working", ThreadState.Working, "Working on a fictional task", "working", worked = now, since = now),
            AgentThread("mac:fixture-question", "Question second", ThreadState.NeedsOk, "Fictional question needs your answer", "needs your answer", worked = now - 300000, requestedAt = now - 2000),
            AgentThread("mac:fixture-approval", "Approval first", ThreadState.NeedsOk, "Fictional approval needs your OK", "needs your OK", worked = now - 600000, requestedAt = now - 1000),
        ) else listOf(AgentThread(PHONE_THREAD, "Phone", ThreadState.Working, "running the tests", "working", worked = now)) +
            (if (empty) listOf(AgentThread(PHONE_THREAD_B, "Phone B", ThreadState.Idle, "", "idle", worked = now)) else emptyList()) +
            book.chats.map { dev.omalauncher.assist.chatThread(it, NEW_CHAT_NAME, thread, MacView()) }
        override fun liveThreads(all: List<AgentThread>, pinned: Set<String>, archived: Set<String>) = if (links) all.filter { it.key !in LINKS_OFF_STRIP } else all
        var book = TabMeta()
        override fun changeBook(change: (TabMeta) -> TabMeta) = change(book).also { book = it }
        override fun checkTitles() {}
        override fun builderFacts() = emptyMap<BuilderId, BuilderFacts>()
        override fun lookAtBuilders() {}
        override fun build(prompt: String, images: List<String>, way: BuildWay, builder: BuilderId?) {}
        override fun askFront(prompt: String, chat: String) {}
        override fun startMacChat(prompt: String, chat: String, images: List<java.io.File>) { answerMode(prompt, chat) }
        override fun sayToMac(prompt: String, thread: String, name: String, chat: String, images: List<java.io.File>) { android.util.Log.i("SomaWalk", "fixture said to $thread: ${prompt.take(60)}") }
        private var answerCount = 0
        override fun submitArtifactReply(source: ArtifactReplySource, request: ArtifactReplyRequest, done: (ArtifactReplyResult) -> Unit) {
            answerCount++
            android.util.Log.i("SomaWalk", "artifact answer $answerCount to ${request.recipient}: ${request.text}")
            done(ArtifactReplyResult(true, "Sent to ${request.recipient}"))
        }
        override fun stopMac(thread: String, said: (String?) -> Unit) {}
        var switched: Pair<String, dev.omalauncher.assist.MacPick>? = null
        override fun switchMac(thread: String, to: dev.omalauncher.assist.MacPick) { switched = thread to to }
        override fun tryMacAgain(time: Long) {}
        override fun answerMac(ticket: String, allow: Boolean, done: (MacApprovalOutcome) -> Unit) {}
        override fun runInTerminal(prompt: String) = false
        override fun handOverToBuild(prompt: String, said: List<ChatTask>) {}
        override fun routingCorrected(request: String) {}
        override fun answerBuilder(entry: PendingApproval, allow: Boolean) = ApprovalOutcome.Expired
        override fun cancelFollowUp(time: Long) {}
        override fun sendNow(time: Long) { android.util.Log.i("SomaWalk", "fixture send now $time") }
        override fun stopBuilder(builder: BuilderId?) {}
        override fun cancelFront(time: Long) {}
        override fun stopFront() {}
        override fun askFrontAgain(time: Long) {}
        override fun dropFrontWaiting(chat: String?) {}
        override fun restartBuilder(builder: BuilderId, said: (String) -> Unit) {}
        override fun cleanBuilders(said: (String) -> Unit) {}
        override fun copy(text: String) {}
        override fun drafts() = ChatDrafts(File(context.filesDir, "drafts.txt"), File(context.filesDir, "attachments"))
        @Composable override fun rememberImagePicker(max: Int, onPicked: (List<PickedImage>) -> Unit): () -> Unit = {}
        override fun importImage(picked: PickedImage): PromptImage = error("no pictures here")
        override fun thumbnail(file: File, longest: Int): ImageBitmap? = null
        override fun sentImage(path: String) = File(path)

        @Composable
        override fun rememberVoice(): Voice =
            remember {
                object : Voice {
                    override val listening = false
                    override val text = ""
                    override var error: String? by mutableStateOf(null)
                    override fun start(hold: Boolean, onDone: (String) -> Unit) {}
                    override fun stop() {}
                    override fun cancel() {}
                }
            }

        @Composable override fun rememberMicrophone(onGranted: () -> Unit): () -> Unit = onGranted
    }

/** The linked-questions fixture's state, for the life of the process (a relaunch onto another tab keeps it). */
private val LINKED_MODES = setOf("linked", "alike", "meaning")

private object LinkedFixture {
    val began = System.currentTimeMillis()
    var phoneDone by androidx.compose.runtime.mutableStateOf(false)
    var macDone by androidx.compose.runtime.mutableStateOf(false)
}

private const val RECONNECT_ID = "t0ecc0ecc"

private val LINKS_OFF_STRIP = setOf("mac:t13d39067ed6a9bef", "mac:macbook-pro/t2b7e41f0")

/** One link to a paragraph, so a walk's tap by its words lands on the link. */
private val LINKS_ANSWER = """
[Connections on the strip](soma://thread/t850cdc07)

[Last tab past the edge](soma://thread/tfeed0003)

[Old notes with no tab](soma://thread/t13d39067ed6a9bef)

[Link to the other Mac](soma://thread/macbook-pro/t2b7e41f0)

[Phone B tab](soma://phone/B)

[A dead thread](soma://thread/macbook-pro/tdeadbeef)

[A malformed link](soma://nonsense/here)

[A web link for its colour](https://example.com)
""".trimIndent()
