package dev.omalauncher.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.assist.AgentTasks
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.linux.agentInvocation
import dev.omalauncher.oma.ring.OmaRing
import dev.omalauncher.phone.PhoneBridge
import kotlinx.coroutines.flow.collectLatest

/** Where a request for the agent goes. */
internal enum class AgentRoute {
    /** Agents run in Linux: without it, the launcher offers to install it instead. */
    InstallLinux,

    /** No agent is installed yet. */
    ChooseAgent,

    /** Claude Code and Codex work in the background, with approvals. */
    Background,

    /** Any other agent opens in a terminal. */
    Terminal,
}

/** The route for the default agent [bin] (null when there is none), given whether Linux is installed. */
internal fun agentRoute(
    linux: Boolean,
    bin: String?,
) = when {
    !linux -> AgentRoute.InstallLinux
    bin == null -> AgentRoute.ChooseAgent
    bin == "claude" || bin == "codex" -> AgentRoute.Background
    else -> AgentRoute.Terminal
}

/** The agent button's microphone: what it hears while held, and how loud, for whoever draws around the button. */
internal class AgentVoice(
    context: Context,
) {
    val speech = Speech(context)
    val level = Animatable(0f)

    /** A line for whoever draws around the button to show where [Speech.error] goes: not a failure, something to do next. */
    var notice by mutableStateOf<String?>(null)
}

@Composable
internal fun rememberAgentVoice(): AgentVoice {
    val context = LocalContext.current
    val voice = remember { AgentVoice(context.applicationContext) }
    val speech = voice.speech
    DisposableEffect(voice) { onDispose { speech.cancel() } }
    BackHandler(enabled = speech.listening) { speech.cancel() }
    val listening = speech.listening
    // The ring listens for exactly as long as the recognizer is live: a release, a cancel and an error all end it here.
    DisposableEffect(voice, listening) {
        OmaRing.hearing(voice, listening)
        onDispose { OmaRing.hearing(voice, false) }
    }
    // Followed in an effect and read only when drawing the level bar: the mic updates it many times a second,
    // and reading it in composition would recompose the whole band each time.
    LaunchedEffect(voice, listening) {
        snapshotFlow { if (listening) speech.level else 0f }.collectLatest { voice.level.animateTo(it) }
    }
    return voice
}

/**
 * The agent button in the dock band, the mic that used to end the prompt bar: hold to talk and release
 * to send what was heard to the default agent; a tap opens the Oma chat. [resting] is its fill while idle.
 */
@Composable
internal fun AgentButton(
    runtime: Runtime,
    state: LauncherState,
    voice: AgentVoice,
    modifier: Modifier = Modifier,
    resting: Color = Color.Transparent,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val speech = voice.speech

    fun send(prompt: String) {
        val p = prompt.trim()
        if (p.isEmpty()) return
        // Looked up now: speech results arrive after the button may have recomposed.
        val target = runtime.agents.default
        when (agentRoute(runtime.bootstrap.state.value == LinuxState.Installed, target?.bin)) {
            AgentRoute.InstallLinux -> {
                state.closeNow()
                state.linuxOpen = true
            }
            // Nobody to send them to yet: the words wait, and go to the Oma chat's field when the agents screen closes.
            AgentRoute.ChooseAgent -> {
                state.heldPrompt = p
                state.agentsOpen = true
            }
            AgentRoute.Background ->
                try {
                    AgentTasks.send(context, p)
                } catch (e: Exception) {
                    speech.error = e.message
                }
            AgentRoute.Terminal ->
                target?.let { runtime.terminal("${agentInvocation(it.bin, p + "\n\n" + PhoneBridge.INSTRUCTIONS)}; exec bash", it.name, it) }
        }
    }

    // A press that had to ask for the mic first can't still be held: the next hold talks, and the band says so.
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) voice.notice = HOLD_TO_TALK
        }

    fun talk() {
        if (runtime.bootstrap.state.value != LinuxState.Installed) {
            state.closeNow()
            state.linuxOpen = true
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        voice.notice = null
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        speech.start(hold = true) { heard -> send(heard) }
    }

    MicButton(
        listening = speech.listening,
        resting = resting,
        modifier = modifier,
        // Still listening after a release (the last words are being recognized): a tap ends it sooner.
        onTap = { if (speech.listening) speech.stop() else openOmaChat(context) },
        onHold = ::talk,
        onRelease = { if (speech.listening) speech.stop() },
        onCancel = {
            speech.cancel()
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        },
    )
}

/** What the band says once the microphone is allowed: the press that asked is over. */
internal const val HOLD_TO_TALK = "Hold to talk"

/** How long a press on the mic waits before it counts as hold-to-talk rather than a tap or a passing swipe. */
private const val HOLD_MILLIS = 250L

/**
 * Tap, or hold to talk and release to send. Nothing starts until the press
 * is clearly one or the other: a swipe that only passes over the button (Back from
 * the screen edge, a page swipe) moves away first and is left alone.
 * Sliding well off the button while holding cancels, like voice messages in chat apps.
 */
@Composable
private fun MicButton(
    listening: Boolean,
    resting: Color,
    modifier: Modifier,
    onTap: () -> Unit,
    onHold: () -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
) {
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val release by rememberUpdatedState(onRelease)
    val cancel by rememberUpdatedState(onCancel)
    Box(
        modifier
            .size(PromptBarHeight)
            .clip(LauncherStyle.shape(PromptBarHeight / 2))
            .background(if (listening) Palette.red else resting)
            .semantics { contentDescription = "Oma agent: hold to talk, tap to open the chat" }
            .pointerInput(Unit) {
                val slop = 64.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // true: released in time (a tap). false: moved, or taken by another gesture. null: still held.
                    val early =
                        withTimeoutOrNull<Boolean>(HOLD_MILLIS) {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                                if (change.isConsumed) return@withTimeoutOrNull false
                                if (!change.pressed) {
                                    // The tap is the button's alone: what's around it (the rail) mustn't take it for a click too.
                                    change.consume()
                                    return@withTimeoutOrNull true
                                }
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                            }
                            @Suppress("UNREACHABLE_CODE")
                            false
                        }
                    when (early) {
                        true -> tap()
                        false -> return@awaitEachGesture
                        null -> {
                            hold()
                            var cancelled = false
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) {
                                    if (!cancelled) release()
                                    break
                                }
                                val p = change.position
                                val outside = p.x < -slop || p.y < -slop || p.x > size.width + slop || p.y > size.height + slop
                                if (outside && !cancelled) {
                                    cancelled = true
                                    cancel()
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) { MicIcon(if (listening) Palette.background else Palette.foreground) }
}

/** A microphone drawn on a 24-unit grid, like the home screen's other icons. */
@Composable
internal fun MicIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(2 * u, cap = StrokeCap.Round)
        drawRoundRect(
            color,
            topLeft = androidx.compose.ui.geometry.Offset(9f * u, 2f * u),
            size = androidx.compose.ui.geometry.Size(6f * u, 12f * u),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * u),
            style = stroke,
        )
        drawArc(
            color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(5f * u, 7f * u),
            size = androidx.compose.ui.geometry.Size(14f * u, 11f * u),
            style = stroke,
        )
        drawPath(
            Path().apply {
                moveTo(12f * u, 18f * u)
                lineTo(12f * u, 22f * u)
                moveTo(8f * u, 22f * u)
                lineTo(16f * u, 22f * u)
            },
            color,
            style = stroke,
        )
    }
}
