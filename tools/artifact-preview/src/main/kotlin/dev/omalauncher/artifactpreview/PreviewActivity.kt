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
    var narrow by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(File(folder, path)))
            }
        }
        setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxHeight().then(if (narrow) Modifier.width(400.dp) else Modifier.fillMaxWidth())) {
                    val empty = intent.getBooleanExtra("empty", false)
                    OmaChatScreen(remember { PreviewHost(this@PreviewActivity, empty, intent.getStringExtra("mail"), intent.getBooleanExtra("questions", false)) }, null, {}, if (empty) PHONE_THREAD_B else OMA_TAB, {})
                }
            }
        }
    }
}

private class PreviewHost(private val context: Context, private val empty: Boolean = false, private val mailMode: String? = null, private val questions: Boolean = false) : ChatHost {
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
            if (mailMode != null) {
                val pending = (1L..4L).map { ChatTask(it, "Codex", "Fictional update $it", mail = "Agent mail", status = TaskStatus.Queued) }
                if (mailMode == "pending") return pending
                val queue = FollowUpQueue()
                pending.forEach { queue.add(FollowUp(it.time, it.prompt, mailSender = "fictional", mailId = "m${it.time}")) }
                val bundled = bundledMailHistory(pending, queue.after(TaskStatus.Done).next!!)
                return bundled.map { if (it.time == 1L) it.copy(status = TaskStatus.Done, result = "Combined handoff complete.") else it } +
                    ChatTask(5, "Codex", "Legacy update", mail = "Agent mail", status = TaskStatus.Done, result = "Delivered in the bundled agent handoff.")
            }
            return if (empty) emptyList() else thread
        }
        override fun updateTask(time: Long, save: Boolean, change: (ChatTask) -> ChatTask) {}
        override fun working() = false
        override fun builders() = emptyMap<BuilderId, BuilderState>()
        override fun continuing() = false
        override fun front() = FrontState()
        override fun macPending() = emptyList<MacPending>()
        override fun macView() = MacView()
        override fun macLabel(task: ChatTask) = ""
        override fun macEarlier(threadId: String): MacEarlierRead = MacEarlierRead.Unread
        override fun hosts() = listOf(HostChoice("mac", "Mac Pro", true, listOf("claude", "codex", "grok")), HostChoice("omarchy-mg1", "MG1", false, listOf("claude", "grok")))
        override fun agentName() = "Claude Code"
        override fun agentBin() = "claude"
        @Composable override fun linuxInstalled() = true
        override fun frontReady() = true
        override fun threads() = listOf(AgentThread(PHONE_THREAD, "Phone", ThreadState.Working, "running the tests", "working", worked = now)) +
            (if (empty) listOf(AgentThread(PHONE_THREAD_B, "Phone B", ThreadState.Idle, "", "idle", worked = now)) else emptyList()) +
            book.chats.map { dev.omalauncher.assist.chatThread(it, NEW_CHAT_NAME, thread, MacView()) }
        override fun liveThreads(all: List<AgentThread>, pinned: Set<String>, archived: Set<String>) = all
        var book = TabMeta()
        override fun changeBook(change: (TabMeta) -> TabMeta) = change(book).also { book = it }
        override fun checkTitles() {}
        override fun builderFacts() = emptyMap<BuilderId, BuilderFacts>()
        override fun lookAtBuilders() {}
        override fun build(prompt: String, images: List<String>, way: BuildWay, builder: BuilderId?) {}
        override fun askFront(prompt: String, chat: String) {}
        override fun startMacChat(prompt: String, chat: String, images: List<java.io.File>) {}
        override fun sayToMac(prompt: String, thread: String, name: String, chat: String, images: List<java.io.File>) {}
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
        override fun sendNow(time: Long) {}
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
