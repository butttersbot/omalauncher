package dev.omalauncher.oma

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.longClick
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.use
import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.ApprovalOutcome
import dev.omalauncher.assist.BuilderFacts
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.assist.MacApproval
import dev.omalauncher.assist.MacApprovalOutcome
import dev.omalauncher.assist.MacEarlierRead
import dev.omalauncher.assist.MacPending
import dev.omalauncher.assist.MacView
import dev.omalauncher.assist.PHONE_THREAD
import dev.omalauncher.assist.PendingApproval
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.linux.PromptImage
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The shared chat screen operated on the desktop: taps and their effects (UI tests only; renders are in ChatScreenRenderTest). */
class ChatScreenTapTest : ChatScreenTestBase() {
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun rememberingACommandRequiresTheOwnersExplicitTap() = androidx.compose.ui.test.runComposeUiTest {
        val card = MacApproval("a1", "n1", "Bash", """{"command":"git status --short","cwd":"/project","access":{}}""", "check the project", canRemember = true)
        val saved = mutableListOf<String>()
        val forgotten = mutableListOf<String>()
        val host = object : SampleHost() {
            override fun tasks() = dev.omalauncher.assist.macAsked(listOf(ChatTask(now, "Codex", "check the project", thread = "mac/t2", threadName = "Build", status = TaskStatus.Running)), "mac/t2", card)
            override fun threads() = listOf(AgentThread(dev.omalauncher.assist.macThreadKey("mac/t2"), "Build", ThreadState.Working, "check the project", "working", now, mac = true))
            override fun macView() = MacView(threads = listOf(dev.omalauncher.assist.MacThread("mac/t2", "Build", "/project", dev.omalauncher.assist.ThreadHome.Mac, "codex", "", dev.omalauncher.assist.MacThreadStatus.Working, now / 1000, "", 0, 0, emptyList())))
            override fun macPending() = listOf(MacPending("mac/t2", "Build", card, "ticket-1", 0))
            override fun rememberMacCommand(ticket: String, done: (MacApprovalOutcome) -> Unit) { saved += ticket; done(MacApprovalOutcome.Decided) }
            override fun forgetMacCommands(threadId: String, done: (String) -> Unit) { forgotten += threadId; done("Saved command approvals forgotten") }
        }
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = dev.omalauncher.assist.macThreadKey("mac/t2"), onTab = {}) }
        mainClock.advanceTimeBy(1_200)
        assertEquals(emptyList<String>(), saved)
        onNodeWithText("[ always allow this command ]").performClick()
        mainClock.advanceTimeBy(500)
        assertEquals(listOf("ticket-1"), saved)
        onNodeWithContentDescription("Show the tabs").performClick()
        mainClock.advanceTimeBy(500)
        onAllNodes(hasText("Build") and hasClickAction()).onFirst().performTouchInput { longClick() }
        mainClock.advanceTimeBy(500)
        onNodeWithText("Forget saved commands").performClick()
        mainClock.advanceTimeBy(500)
        assertEquals(listOf("mac/t2"), forgotten)
    }

    /** He taps the choices and they are kept on the chat: another computer, then another agent it runs. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun tappingThePicksChangesWhereTheThreadGoes() = androidx.compose.ui.test.runComposeUiTest {
        val host = SampleHost()
        host.book = TabMeta().started("c1", host.now).chose("c1", "omarchy-mg1", "grok")
        // The screen looks again twice a second for ever, so the clock is moved by hand, never left to run to idle.
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = chatKey("c1"), onTab = {}) }
        fun settle() = mainClock.advanceTimeBy(1_200)
        settle()
        onNodeWithText("offline").assertExists()
        onNodeWithText("[ MG1 ]").performClick()
        settle()
        onNodeWithText("make its thread on").assertExists()
        onNodeWithText("Mac Pro").performClick()
        settle()
        // Mac Pro runs Grok too, so the agent he picked stays.
        assertTrue(host.book.chat("c1")!!.host == "mac" && host.book.chat("c1")!!.agent == "grok")
        onNodeWithText("[ Mac Pro ]").assertExists()
        onNodeWithText("[ Grok ]").performClick()
        settle()
        onNodeWithText("Codex").performClick()
        settle()
        assertTrue(host.book.chat("c1")!!.agent == "codex")
        onNodeWithText("[ Codex ]").assertExists()
        onNodeWithText("start this chat on Mac Pro…").assertExists()
    }

    /** Before a computer has said which agents it runs, every agent the runner knows is offered, and the menu says why. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun aComputerNotHeardFromYetOffersEveryAgent() = androidx.compose.ui.test.runComposeUiTest {
        val state = object : SampleHost() {
            override fun hosts() = listOf(HostChoice("macbook-pro", "Jack's MacBook Pro Max", false, emptyList()))
        }
        state.book = TabMeta().started("c1", state.now)
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(state, prefill = null, onPrefilled = {}, wanted = chatKey("c1"), onTab = {}) }
        mainClock.advanceTimeBy(1_200)
        // A long name is cut to fit the cover screen.
        onNodeWithText("[ Jack's MacBook Pr… ]").assertExists()
        onNodeWithText("[ Claude ]").performClick()
        mainClock.advanceTimeBy(1_200)
        onNodeWithText("with · Jack's MacBook Pro Max hasn't said which it runs yet").assertExists()
        onNodeWithText("Codex").performClick()
        mainClock.advanceTimeBy(1_200)
        assertTrue(state.book.chat("c1")!!.agent == "codex")
    }

    /** The model is a pick too, where the agent has more than its own default; another agent starts on its own default. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun tappingTheModelPickKeepsItOnTheChat() = androidx.compose.ui.test.runComposeUiTest {
        val host = object : SampleHost() {
            override fun hosts() = listOf(HostChoice("mac", "Mac Pro", true, listOf("claude", "codex", "grok"), mapOf("claude" to dev.omalauncher.assist.MAC_CLAUDE_MODELS, "codex" to listOf("", "gpt-6.1-sol"), "grok" to listOf(""))))
        }
        host.book = TabMeta().started("c1", host.now).chose("c1", "mac", "codex")
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = chatKey("c1"), onTab = {}) }
        fun settle() = mainClock.advanceTimeBy(1_200)
        settle()
        onNodeWithText("[ default ]").performClick()
        settle()
        onNodeWithText("Codex on").assertExists()
        onNodeWithText("gpt-6.1-sol").performClick()
        settle()
        assertTrue(host.book.chat("c1")!!.agent == "codex" && host.book.chat("c1")!!.model == "gpt-6.1-sol")
        onNodeWithText("[ gpt-6.1-sol ]").assertExists()
        // Grok has only its own default here: its model isn't offered, and none is kept.
        onNodeWithText("[ Codex ]").performClick()
        settle()
        onNodeWithText("Grok").performClick()
        settle()
        assertTrue(host.book.chat("c1")!!.agent == "grok" && host.book.chat("c1")!!.model == "")
        onNodeWithText("[ default ]").assertDoesNotExist()
        onNodeWithText("[ gpt-6.1-sol ]").assertDoesNotExist()
        // Claude's are its aliases and Fable.
        onNodeWithText("[ Grok ]").performClick()
        settle()
        onNodeWithText("Claude").performClick()
        settle()
        onNodeWithText("[ default ]").performClick()
        settle()
        onNodeWithText("Fable").assertExists()
        onNodeWithText("Opus").performClick()
        settle()
        assertTrue(host.book.chat("c1")!!.agent == "claude" && host.book.chat("c1")!!.model == "opus")
        onNodeWithText("[ Opus ]").assertExists()
    }

    /** Venice is an agent like the others in a new chat's pick, its models the ones the computer's runner listed. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun veniceAndItsModelsArePicked() = androidx.compose.ui.test.runComposeUiTest {
        val host = object : SampleHost() {
            override fun hosts() = listOf(HostChoice("mac", "Mac Pro", true, listOf("claude", "venice"), mapOf("claude" to dev.omalauncher.assist.MAC_CLAUDE_MODELS, "venice" to dev.omalauncher.assist.macModels("venice", emptyList(), listOf("kimi-k2-5", "venice-uncensored")))))
        }
        host.book = TabMeta().started("c1", host.now)
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = chatKey("c1"), onTab = {}) }
        fun settle() = mainClock.advanceTimeBy(1_200)
        settle()
        onNodeWithText("[ Claude ]").performClick()
        settle()
        onNodeWithText("Venice").performClick()
        settle()
        onNodeWithText("[ default ]").performClick()
        settle()
        onNodeWithText("venice-uncensored").performClick()
        settle()
        assertTrue(host.book.chat("c1")!!.agent == "venice" && host.book.chat("c1")!!.model == "venice-uncensored")
        onNodeWithText("[ venice-uncensored ]").assertExists()
    }

    /** A model list longer than the window (Venice lists dozens) scrolls, so its last model can be picked. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun aLongModelListScrollsToItsLast() = androidx.compose.ui.test.runComposeUiTest {
        val many = (1..60).map { "venice-model-$it" }
        val host = object : SampleHost() {
            override fun hosts() = listOf(HostChoice("mac", "Mac Pro", true, listOf("venice"), mapOf("venice" to dev.omalauncher.assist.macModels("venice", emptyList(), many))))
        }
        host.book = TabMeta().started("c1", host.now).chose("c1", "mac", "venice")
        mainClock.autoAdvance = false
        setContent { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(800.dp, 600.dp)) { OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = chatKey("c1"), onTab = {}) } }
        fun settle() = mainClock.advanceTimeBy(1_200)
        settle()
        onNodeWithText("[ default ]").performClick()
        settle()
        onNodeWithText("venice-model-60").assertIsNotDisplayed()
        // Dragged up as a hand would (the screen's clock is held, so an animated scroll-to would never end).
        repeat(3) {
            onNodeWithText("Venice on").performTouchInput { swipe(center, center - androidx.compose.ui.geometry.Offset(0f, 1500f), 300) }
            settle()
        }
        onNodeWithText("venice-model-60").assertIsDisplayed().performClick()
        settle()
        assertTrue(host.book.chat("c1")!!.model == "venice-model-60")
    }

    /** With no Soma tab (the desktop), nothing asked for opens a new chat, and its strip has no Soma tab. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun withNoSomaTabItOpensToANewChat() = androidx.compose.ui.test.runComposeUiTest {
        val host = object : SampleHost() {
            override val somaTab get() = false
            override fun frontReady() = false
        }
        var wanted by mutableStateOf<String?>(null)
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = wanted, onTab = { wanted = it }) }
        fun settle() = mainClock.advanceTimeBy(1_200)
        settle()
        val made = host.book.chats.single()
        assertEquals(chatKey(made.id), wanted)
        settle()
        onNodeWithText("on").assertExists()
        onNodeWithContentDescription("Show the tabs").performClick()
        settle()
        onNodeWithText("Soma").assertDoesNotExist()
        // Asked for nothing again (a soma://soma link, the canvas letting go of a tab), the empty chat opens again: no second one.
        wanted = null
        settle()
        settle()
        assertEquals(1, host.book.chats.size)
        assertEquals(chatKey(made.id), wanted)
        // A thread asked for that isn't listed yet (a link to one just made on the phone) is waited for, not replaced.
        wanted = "mac:elsewhere/t9"
        settle()
        settle()
        assertEquals("mac:elsewhere/t9", wanted)
        assertEquals(1, host.book.chats.size)
    }

    /** Before he picks, a new chat shows the default new chats remember, model and all. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun aNewChatShowsTheRememberedDefault() = androidx.compose.ui.test.runComposeUiTest {
        val host = object : SampleHost() {
            override fun hosts() = listOf(HostChoice("mac", "Mac Pro", true, listOf("claude", "codex"), mapOf("claude" to dev.omalauncher.assist.MAC_CLAUDE_MODELS, "codex" to listOf("", "gpt-6.1-sol")), dev.omalauncher.assist.MacPick("codex", "gpt-6.1-sol")))
        }
        host.book = TabMeta().started("c1", host.now)
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = chatKey("c1"), onTab = {}) }
        mainClock.advanceTimeBy(1_200)
        onNodeWithText("[ Codex ]").assertExists()
        onNodeWithText("[ gpt-6.1-sol ]").assertExists()
    }

    /** Switch agent, from a long press on a thread's tab: every agent and model the computer runs but the one it runs now. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun switchAgentFromTheTabMenuMovesTheThread() = androidx.compose.ui.test.runComposeUiTest {
        val thread = dev.omalauncher.assist.MacThread("mac/t1", "Projects view", "~/Projects/soma", dev.omalauncher.assist.ThreadHome.Mac, "claude", "", dev.omalauncher.assist.MacThreadStatus.Idle, null, "", 0, 0, emptyList(), model = "opus")
        val host = object : SampleHost() {
            override fun macView() = MacView(threads = listOf(thread), reachable = true)
        }
        host.book = TabMeta().started("c1", host.now).onMac("c1", "mac/t1")
        mainClock.autoAdvance = false
        setContent { OmaChatScreen(host, prefill = null, onPrefilled = {}, wanted = chatKey("c1"), onTab = {}) }
        fun settle() = mainClock.advanceTimeBy(1_200)
        settle()
        onNodeWithContentDescription("Show the tabs").performClick()
        settle()
        onAllNodes(hasText("New chat") and hasClickAction()).onFirst().performTouchInput { longClick() }
        settle()
        onNodeWithText("Switch agent").performClick()
        settle()
        onNodeWithText("move New chat to").assertExists()
        onNodeWithText("Claude · Opus").assertDoesNotExist()
        onNodeWithText("Claude · Sonnet").assertExists()
        onNodeWithText("Grok").assertExists()
        onNodeWithText("Codex").performClick()
        settle()
        assertEquals("mac/t1" to dev.omalauncher.assist.MacPick("codex"), host.switched)
    }

    /** Needs you answers through the chat's own path: his tap on approve or deny reaches answerMac with that ticket, once. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun needsYouApprovesAndDeniesThroughTheChatsOwnPath() = androidx.compose.ui.test.runComposeUiTest {
        val asked = dev.omalauncher.assist.MacApproval("a1", "n1", "Bash", """{"command":"swift build"}""", "build the app")
        val answered = mutableListOf<Pair<String, Boolean>>()
        val host = object : SampleHost() {
            override fun macPending() = listOf(dev.omalauncher.assist.MacPending("mac/t2", "notch trading", asked, "ticket-1", 0), dev.omalauncher.assist.MacPending("mac/t3", "MG1 runner", asked.copy(approval = "a2", nonce = "n2"), "ticket-2", 1))
            override fun answerMac(ticket: String, allow: Boolean, done: (MacApprovalOutcome) -> Unit) { answered += ticket to allow }
        }
        val source = dev.omalauncher.canvas.chatHostModules(host)
        val asks = source.asks()
        assertEquals(listOf("Allow Bash on notch trading?", "Allow Bash on MG1 runner?"), asks.map { it.title }.take(2))
        mainClock.autoAdvance = false
        setContent { dev.omalauncher.canvas.NeedsYouModule(asks, dev.omalauncher.canvas.ModuleSize.Card, onAnswer = { ask, allow, said -> source.answer(ask, allow, said) }, onOpen = {}) }
        mainClock.advanceTimeBy(500)
        onAllNodes(hasText("[ approve ]")).onFirst().performClick()
        onAllNodes(hasText("[ deny ]"))[1].performClick()
        mainClock.advanceTimeBy(500)
        assertEquals(listOf("ticket-1" to true, "ticket-2" to false), answered)
    }

    private val oneTime = dev.omalauncher.assist.MacApproval("a1", "n1", "Bash", """{"command":"swift build"}""", "build the app")

    /** (from the slice 3a Critic's probes) A host whose tickets are one-time, as the engine's book is: the first answer takes it, the next is Expired. */
    private inner class TicketHost : SampleHost() {
        val live = mutableListOf(dev.omalauncher.assist.MacPending("mac/t2", "notch trading", oneTime, "ticket-1", 0))
        val calls = mutableListOf<Pair<String, Boolean>>()
        override fun macPending() = live.toList()
        override fun answerMac(ticket: String, allow: Boolean, done: (MacApprovalOutcome) -> Unit) {
            calls += ticket to allow
            val hit = live.firstOrNull { it.ticket == ticket }
            if (hit == null) done(MacApprovalOutcome.Expired) else { live.remove(hit); done(MacApprovalOutcome.Decided) }
        }
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun nothingAnswersWithoutATapAndAStaleAskIsRefusedAndSaid() = androidx.compose.ui.test.runComposeUiTest {
        val host = TicketHost()
        val source = dev.omalauncher.canvas.chatHostModules(host)
        val asks = source.asks() // kept stale on purpose: the caller has not looked again
        mainClock.autoAdvance = false
        setContent { dev.omalauncher.canvas.NeedsYouModule(asks, dev.omalauncher.canvas.ModuleSize.Card, onAnswer = { ask, allow, said -> source.answer(ask, allow, said) }, onOpen = {}) }
        mainClock.advanceTimeBy(5_000)
        assertEquals("no answer without a tap", 0, host.calls.size)
        onAllNodes(hasText("[ approve ]")).onFirst().performClick()
        mainClock.advanceTimeBy(500)
        assertEquals(listOf("ticket-1" to true), host.calls)
        onNodeWithText("That request has expired").assertDoesNotExist()
        // Second tap on the same, now stale, ask: the host refuses it as Expired and the module says so.
        onAllNodes(hasText("[ deny ]")).onFirst().performClick()
        mainClock.advanceTimeBy(500)
        assertEquals(listOf("ticket-1" to true, "ticket-1" to false), host.calls)
        onNodeWithText("That request has expired").assertExists()
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun theOutcomeIsSaidAtLineSizeToo() = androidx.compose.ui.test.runComposeUiTest {
        val host = TicketHost()
        val source = dev.omalauncher.canvas.chatHostModules(host)
        val asks = source.asks()
        mainClock.autoAdvance = false
        setContent { dev.omalauncher.canvas.NeedsYouModule(asks, dev.omalauncher.canvas.ModuleSize.Line, onAnswer = { ask, allow, said -> source.answer(ask, allow, said) }, onOpen = {}) }
        mainClock.advanceTimeBy(500)
        onNodeWithText("[ approve ]").performClick()
        mainClock.advanceTimeBy(500)
        onNodeWithText("[ deny ]").performClick()
        mainClock.advanceTimeBy(500)
        assertEquals(2, host.calls.size)
        onNodeWithText("That request has expired").assertExists()
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun aLockedPhoneAnswersNothingAndSaysSo() = androidx.compose.ui.test.runComposeUiTest {
        val entry = dev.omalauncher.assist.PendingApproval(dev.omalauncher.assist.AgentApproval("p1", "Bash", org.json.JSONObject().put("command", "oma-install --status"), "check"), "0f0f0f0f-0000-0000-0000-000000000000", 0)
        var asked: dev.omalauncher.assist.PendingApproval? = null
        val host = object : SampleHost() {
            override fun builders() = mapOf(dev.omalauncher.assist.BuilderId.A to BuilderState(working = true, pending = listOf(entry)), dev.omalauncher.assist.BuilderId.B to BuilderState(working = true, pending = listOf(entry.copy(nonce = "0f0f0f0f-0000-0000-0000-000000000001", slot = 1))))
            override fun answerBuilder(entry: dev.omalauncher.assist.PendingApproval, allow: Boolean): dev.omalauncher.assist.ApprovalOutcome { asked = entry; return dev.omalauncher.assist.ApprovalOutcome.Locked }
        }
        val source = dev.omalauncher.canvas.chatHostModules(host)
        val asks = source.asks()
        assertEquals(listOf("phone:0f0f0f0f-0000-0000-0000-000000000000", "phone:0f0f0f0f-0000-0000-0000-000000000001"), asks.map { it.id })
        assertEquals(listOf("phone", "phone-b"), asks.map { it.threadKey })
        mainClock.autoAdvance = false
        setContent { dev.omalauncher.canvas.NeedsYouModule(asks, dev.omalauncher.canvas.ModuleSize.Full, onAnswer = { ask, allow, said -> source.answer(ask, allow, said) }, onOpen = {}) }
        mainClock.advanceTimeBy(500)
        onAllNodes(hasText("[ approve ]"))[1].performClick()
        mainClock.advanceTimeBy(500)
        assertEquals(1, asked!!.slot)
        onNodeWithText("Unlock your phone to answer that request").assertExists()
    }

    /** Nudge, from the Desktop sessions module: his tap sends agent mail to that session on its machine, and says it went. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun nudgeSendsAgentMailToThatSession() = androidx.compose.ui.test.runComposeUiTest {
        val posted = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        val herd = """{"machines": [{"name": "macbook-pro", "online": true, "report": {"machine": {}, "sessions": [{"id": "f8890b84-1019-47cd-be97-458e18f11f75", "kind": "claude", "app": "", "title": "Cockpit build", "project": "omalauncher", "state": "your-turn", "live": true, "updated": 1792220000}]}}]}"""
        val feeds = dev.omalauncher.canvas.CockpitFeeds(get = { url -> if ("8479" in url) herd else null }, post = { url, json -> posted += url to json; true })
        val source = dev.omalauncher.canvas.chatHostModules(SampleHost(), feeds)
        source.refresh()
        val sessions = source.sessions()
        assertEquals(listOf("Cockpit build"), sessions.map { it.title })
        mainClock.autoAdvance = false
        setContent { dev.omalauncher.canvas.SessionsModule(sessions, dev.omalauncher.canvas.ModuleSize.Card, onNudge = { s, done -> source.nudge(s) { ok -> done(ok) } }) }
        mainClock.advanceTimeBy(500)
        onNodeWithText("[ nudge ]").performClick()
        val deadline = System.currentTimeMillis() + 5_000
        while (posted.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        mainClock.advanceTimeBy(500)
        assertEquals("http://macbook-pro:8480/v1/msg", posted.single().first)
        assertEquals("f8890b84-1019-47cd-be97-458e18f11f75", org.json.JSONObject(posted.single().second).getJSONObject("to").getString("session"))
        onNodeWithText("Nudged Cockpit build").assertExists()
    }

    /** Timeline, Project and New thread (slice 3c): each tap opens the right tab, and nothing else happens. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun timelineProjectAndNewThreadOpenTheRightTab() = androidx.compose.ui.test.runComposeUiTest {
        val host = object : SampleHost() {
            override fun tasks() = listOf(
                ChatTask(2_000, "→ notch trading", "run swift build", thread = "mac/t2", threadName = "notch trading", status = TaskStatus.Running),
                ChatTask(1_000, "Haiku", "what's left?", status = TaskStatus.Done),
            )
            override fun macView() = MacView(listOf(
                dev.omalauncher.assist.MacThread("mac/t1", "a", "/Users/jackkim/Projects/omalauncher-c3", dev.omalauncher.assist.ThreadHome.Mac, "claude", "", dev.omalauncher.assist.MacThreadStatus.Idle, null, "", 0, 0, emptyList()),
                dev.omalauncher.assist.MacThread("mac/t2", "b", "/Users/jackkim/Projects/omalauncher", dev.omalauncher.assist.ThreadHome.Mac, "claude", "", dev.omalauncher.assist.MacThreadStatus.Idle, null, "", 0, 0, emptyList()),
            ), reachable = true)
            override fun threads() = listOf(AgentThread("mac:mac/t1", "a", ThreadState.Working, "", "working"), AgentThread("mac:mac/t2", "b", ThreadState.Attention, "", "needs attention"))
        }
        val registry = listOf(dev.omalauncher.assist.RegisteredProject("omalauncher", listOf("~/Projects/omalauncher", "~/Projects/omalauncher-*")))
        val source = dev.omalauncher.canvas.chatHostModules(host, registry = { registry })
        source.refresh(listOf("omalauncher"))
        val opened = mutableListOf<String>()
        var news = 0
        mainClock.autoAdvance = false
        setContent {
            androidx.compose.foundation.layout.Column {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(456.dp, 228.dp)) { dev.omalauncher.canvas.TimelineModule(source.events(), dev.omalauncher.canvas.ModuleSize.Card, onOpen = { opened += it }) }
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(228.dp, 152.dp)) { dev.omalauncher.canvas.ProjectModule(source.project("omalauncher"), dev.omalauncher.canvas.ModuleSize.Card, onOpen = { opened += it }) }
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(228.dp, 152.dp)) { dev.omalauncher.canvas.NewThreadModule(source.newThread(), dev.omalauncher.canvas.ModuleSize.Card, onNew = { news++ }) }
            }
        }
        mainClock.advanceTimeBy(500)
        assertEquals("nothing opens without a tap", 0, opened.size + news)
        onNodeWithText("notch trading · run swift build").performClick()
        // A request the phone answered itself has no thread: its row opens nothing.
        onNodeWithText("Haiku · what's left?").performClick()
        onNodeWithText("2 threads").performClick()
        onNodeWithText("[ claude ]").performClick()
        mainClock.advanceTimeBy(500)
        // The project opens t2, the one that needs attention, over t1, which only works.
        assertEquals(listOf("mac:mac/t2", "mac:mac/t2"), opened)
        assertEquals(1, news)
    }

    /** A thread made for a new chat is shown as that chat: every module opens the chat's tab for it, as its receipt does. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun aChatBoundThreadOpensItsChat() = androidx.compose.ui.test.runComposeUiTest {
        val book = TabMeta(chats = listOf(NewChat("c1", 1, mac = "mac/t2"), NewChat("c2", 2)))
        val asked = MacApproval("a1", "n1", "Bash", """{"command":"swift build"}""", "build")
        val host = object : SampleHost() {
            override fun changeBook(change: (TabMeta) -> TabMeta) = change(book)
            override fun tasks() = listOf(
                ChatTask(3_000, "→ notch trading", "run swift build", thread = "mac/t2", threadName = "notch trading", chat = "c1", status = TaskStatus.Running),
                ChatTask(2_000, "→ new chat", "start the herd fix", thread = dev.omalauncher.assist.MAC_NEW_THREAD, chat = "c2", status = TaskStatus.Running),
                ChatTask(1_000, "→ old", "a chat since closed", thread = dev.omalauncher.assist.MAC_NEW_THREAD, chat = "gone", status = TaskStatus.Done),
            )
            override fun macView() = MacView(listOf(
                dev.omalauncher.assist.MacThread("mac/t1", "a", "/Users/jackkim/Projects/omalauncher-c3", dev.omalauncher.assist.ThreadHome.Mac, "claude", "", dev.omalauncher.assist.MacThreadStatus.Idle, null, "", 0, 0, emptyList()),
                dev.omalauncher.assist.MacThread("mac/t2", "b", "/Users/jackkim/Projects/omalauncher", dev.omalauncher.assist.ThreadHome.Mac, "claude", "", dev.omalauncher.assist.MacThreadStatus.Idle, null, "", 0, 0, listOf(asked)),
            ), reachable = true)
            override fun threads() = listOf(AgentThread("mac:mac/t1", "a", ThreadState.Working, "", "working"), AgentThread("chat:c1", "notch trading", ThreadState.NeedsOk, "", "needs your OK"), AgentThread("chat:c2", "herd fix", ThreadState.Working, "", "working"))
            override fun macPending() = listOf(MacPending("mac/t2", "notch trading", asked, "ticket-1", 0))
        }
        val source = dev.omalauncher.canvas.chatHostModules(host, registry = { listOf(dev.omalauncher.assist.RegisteredProject("omalauncher", listOf("~/Projects/omalauncher", "~/Projects/omalauncher-*"))) })
        // Before the registry is read no project is offered: a folder's own name ("omalauncher-c3") would stand for nothing after.
        assertEquals(emptyList<String>(), source.projects())
        source.refresh(listOf("omalauncher"))
        assertEquals(listOf("omalauncher"), source.projects())
        // Every key a module hands over is a tab the chat has.
        assertEquals(listOf("chat:c1", "chat:c2", ""), source.events().map { it.threadKey })
        assertEquals(listOf("chat:c1"), source.asks().map { it.threadKey })
        val project = source.project("omalauncher")
        assertEquals(listOf(ThreadState.Working, ThreadState.NeedsOk), project.states)
        val opened = mutableListOf<String>()
        mainClock.autoAdvance = false
        setContent {
            androidx.compose.foundation.layout.Column {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(456.dp, 228.dp)) { dev.omalauncher.canvas.TimelineModule(source.events(), dev.omalauncher.canvas.ModuleSize.Card, onOpen = { opened += it }) }
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(228.dp, 152.dp)) { dev.omalauncher.canvas.ProjectModule(project, dev.omalauncher.canvas.ModuleSize.Card, onOpen = { opened += it }) }
            }
        }
        mainClock.advanceTimeBy(500)
        onNodeWithText("notch trading · run swift build").performClick()
        onNodeWithText("new chat · start the herd fix").performClick()
        onNodeWithText("old · a chat since closed").performClick()
        onNodeWithText("2 threads").performClick()
        mainClock.advanceTimeBy(500)
        assertEquals(listOf("chat:c1", "chat:c2", "chat:c1"), opened)
        assertEquals(opened, opened.map { openTab(it, host.threads()) })
    }

    /**
     * Every module as a screen draws it from its key (slice 4): each kind shows, a tap opens its thread's tab, an approval
     * goes through the source, and New thread starts a chat as ＋ does, the empty one again rather than a second.
     */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun modulesDrawnFromTheirKeysOpenAndAnswer() = androidx.compose.ui.test.runComposeUiTest {
        var book = TabMeta()
        val asked = MacApproval("a1", "n1", "Bash", """{"command":"swift build"}""", "build")
        val answered = mutableListOf<Pair<String, Boolean>>()
        val host = object : SampleHost() {
            override fun changeBook(change: (TabMeta) -> TabMeta): TabMeta = change(book).also { book = it }
            override fun tasks() = emptyList<ChatTask>()
            override fun macPending() = listOf(MacPending("mac/t2", "notch trading", asked, "ticket-1", 0))
            override fun answerMac(ticket: String, allow: Boolean, done: (MacApprovalOutcome) -> Unit) { answered += ticket to allow; done(MacApprovalOutcome.Decided) }
        }
        val source = dev.omalauncher.canvas.chatHostModules(host)
        val opened = mutableListOf<String>()
        val keys = dev.omalauncher.canvas.ModuleKind.entries.map { dev.omalauncher.canvas.moduleKey(it, if (it.perProject) "omalauncher" else null)!! }
        mainClock.autoAdvance = false
        setContent {
            androidx.compose.foundation.layout.Column {
                for (key in keys) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(456.dp, 152.dp)) {
                        androidx.compose.runtime.CompositionLocalProvider(dev.omalauncher.canvas.LocalModuleFocus provides (key == "module:needs")) {
                            dev.omalauncher.canvas.ModuleView(dev.omalauncher.canvas.moduleRef(key)!!, dev.omalauncher.canvas.ModuleSize.Card, source, 0L, onOpen = { opened += it }, onNew = { opened += newChatTab(host, now = 5_000) })
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeBy(500)
        // Each kind draws under its own name.
        for (name in listOf("Needs you", "Threads", "Computers", "Timeline", "New thread", "Sessions", "Pocket", "Handoff", "omalauncher")) onNodeWithContentDescription("$name module").assertExists()
        onNodeWithText("[ approve ]").performClick()
        mainClock.advanceTimeBy(500)
        assertEquals(listOf("ticket-1" to true), answered)
        onNodeWithContentDescription("New thread module").performClick()
        onNodeWithContentDescription("New thread module").performClick()
        mainClock.advanceTimeBy(500)
        // The second tap opens the same, still empty, new chat.
        assertEquals(1, book.chats.size)
        assertEquals(listOf(chatKey(book.chats.single().id), chatKey(book.chats.single().id)), opened.takeLast(2))
    }
}
