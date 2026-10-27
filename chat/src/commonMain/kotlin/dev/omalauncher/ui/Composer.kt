package dev.omalauncher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Soma's composer, the one bar every Soma app types into (Boss, 2026-10-22: "copy Apple"). A round + on the left
 * for attachments; a field that grows to five lines; inside the field on the right, the microphone while it is
 * empty and Send once there is something to send. The microphone records a voice message to send as audio, never
 * dictation: tap it to record and Stop to end, or hold it and let go. The recording is then played back, sent, or
 * thrown away with ×. Colors, type and line are the active pack's; the pill is Apple's, square in a square-cornered look. Reference: the Soma Messages
 * prototype, features/soma-messages/prototype.html in ClaudeSync (`bindComposer`).
 */

/** A finished recording: how long it is, its loudness over time (0 to 1), and where the platform kept the audio. */
data class VoiceClip(
    val millis: Long,
    val levels: List<Float>,
    val file: String? = null,
)

/**
 * The platform's microphone for voice messages. The composer asks it to record only while a recording is on
 * screen, and nothing else in Soma listens through it.
 */
interface VoiceRecorder {
    /** Starts recording; false when it can't (no permission, the microphone is busy), and the composer stays as it was. */
    fun start(): Boolean

    /** How loud it is now, from 0 to 1. Read about ten times a second while recording. */
    fun level(): Float

    /** Ends the recording and keeps it for review; null when nothing was kept. */
    fun stop(): VoiceClip?

    /** Throws the kept recording away. */
    fun discard()

    /** Plays [clip] from the start. */
    fun play(clip: VoiceClip)

    /** Ends [play] early. */
    fun stopPlaying()
}

/** What the voice side of the composer is doing. */
sealed interface VoiceStage {
    data object Off : VoiceStage

    /** Recording since [since] (epoch millis); [held] while the finger stays on the microphone. */
    data class Recording(
        val since: Long,
        val held: Boolean,
    ) : VoiceStage

    /** Stopped, waiting to be played back, sent or thrown away. */
    data class Review(
        val clip: VoiceClip,
    ) : VoiceStage
}

/** What the button at the field's right end does when touched. */
enum class ComposerAction {
    /** Sends the text (or the attachments). */
    Send,

    /** Sends the text, but there is none: drawn faint, does nothing. */
    Idle,

    /** Starts a voice message. */
    Record,

    /** The app's own button for an empty field (Soma chat's Dictate); the composer draws nothing there. */
    Own,

    /** Ends the recording. */
    Stop,

    /** Sends the recording under review. */
    SendVoice,
}

/** The composer's rules, apart from drawing, so they can be checked on their own. */
object ComposerRules {
    /** A touch on the microphone held longer than this records until the finger lifts. */
    const val HOLD_MS = 280L

    /** A recording shorter than this is thrown away instead of reviewed. */
    const val MIN_VOICE_MS = 1_000L

    /** How often the level is read while recording. */
    const val LEVEL_MS = 90L

    /** The field grows to this many lines, then scrolls. */
    const val MAX_LINES = 5

    /** The most levels kept while recording: about a minute and a half drawn, the rest is in the clip's audio. */
    const val MAX_LEVELS = 1_000

    /** The right-end button for a field holding [text]. */
    fun action(
        stage: VoiceStage,
        text: String,
        attached: Boolean,
        voice: Boolean,
        own: Boolean,
    ): ComposerAction =
        when (stage) {
            is VoiceStage.Recording -> ComposerAction.Stop
            is VoiceStage.Review -> ComposerAction.SendVoice
            VoiceStage.Off ->
                when {
                    text.isNotBlank() || attached -> ComposerAction.Send
                    voice -> ComposerAction.Record
                    own -> ComposerAction.Own
                    else -> ComposerAction.Idle
                }
        }

    /** True when a recording [millis] long is worth reviewing. */
    fun keeps(millis: Long) = millis >= MIN_VOICE_MS

    /** [millis] as a clock: 0:07, 1:30. */
    fun clock(millis: Long): String {
        val s = (millis.coerceAtLeast(0) + 500) / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** [levels] in [n] bars, each the loudest moment of its stretch; an empty recording draws low bars. */
    fun bars(
        levels: List<Float>,
        n: Int,
    ): List<Float> {
        if (n <= 0) return emptyList()
        if (levels.isEmpty()) return List(n) { QUIET }
        return List(n) { i ->
            val from = i * levels.size / n
            val to = maxOf(from + 1, (i + 1) * levels.size / n).coerceAtMost(levels.size)
            var loudest = 0f
            for (j in from until to) loudest = maxOf(loudest, levels[j])
            maxOf(loudest, QUIET)
        }
    }

    /** The lowest bar drawn, so silence still shows as a line. */
    const val QUIET = 0.08f
}

/** What a composer holds between draws: the draft and the voice message, if any. Hoisted so an app keeps drafts per conversation. */
@Stable
class ComposerState(
    text: String = "",
) {
    var text by mutableStateOf(text)

    var stage by mutableStateOf<VoiceStage>(VoiceStage.Off)
        private set

    /** The levels heard so far in this recording. */
    val levels = mutableStateListOf<Float>()

    /** When the recording under review started playing back; null when it isn't playing. */
    var playingSince by mutableStateOf<Long?>(null)
        private set

    /** Starts a voice message if the field is empty and the microphone agrees; true when it did. */
    fun startVoice(
        recorder: VoiceRecorder,
        now: Long,
        held: Boolean,
    ): Boolean {
        if (stage != VoiceStage.Off || text.isNotBlank() || !recorder.start()) return false
        levels.clear()
        stage = VoiceStage.Recording(now, held)
        return true
    }

    /** One reading of the level while recording. */
    fun hear(level: Float) {
        if (stage !is VoiceStage.Recording) return
        levels += level.coerceIn(0f, 1f)
        if (levels.size > ComposerRules.MAX_LEVELS) levels.removeAt(0)
    }

    /** Ends the recording: to review, or thrown away when it is too short. True when there is one to review. */
    fun stopVoice(
        recorder: VoiceRecorder,
        now: Long,
    ): Boolean {
        val recording = stage as? VoiceStage.Recording ?: return false
        val kept = recorder.stop()
        val millis = kept?.millis ?: (now - recording.since)
        if (kept == null || !ComposerRules.keeps(millis)) {
            recorder.discard()
            stage = VoiceStage.Off
            return false
        }
        stage = VoiceStage.Review(kept.copy(millis = millis, levels = kept.levels.ifEmpty { levels.toList() }))
        return true
    }

    /** Throws the voice message away, recording or reviewed. */
    fun cancelVoice(recorder: VoiceRecorder) {
        when (stage) {
            is VoiceStage.Recording -> {
                recorder.stop()
                recorder.discard()
            }
            is VoiceStage.Review -> {
                if (playingSince != null) recorder.stopPlaying()
                recorder.discard()
            }
            VoiceStage.Off -> return
        }
        playingSince = null
        stage = VoiceStage.Off
    }

    /** Plays the recording under review, or stops it if it is playing. */
    fun togglePlayback(
        recorder: VoiceRecorder,
        now: Long,
    ) {
        val review = stage as? VoiceStage.Review ?: return
        if (playingSince != null) {
            recorder.stopPlaying()
            playingSince = null
        } else {
            recorder.play(review.clip)
            playingSince = now
        }
    }

    /** Marks playback as ended on its own. */
    fun playbackEnded() {
        playingSince = null
    }

    /** The recording to send, leaving the composer empty; null when none is under review. */
    fun takeVoice(recorder: VoiceRecorder?): VoiceClip? {
        val review = stage as? VoiceStage.Review ?: return null
        if (playingSince != null) recorder?.stopPlaying()
        playingSince = null
        stage = VoiceStage.Off
        return review.clip
    }

    /** The text to send, trimmed, leaving the field empty; null when there is none. */
    fun takeText(): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        text = ""
        return t
    }
}

@Composable
fun rememberComposerState(text: String = "") = remember { ComposerState(text) }

/**
 * Soma's composer. [onSend] gets the trimmed text, or "" when only attachments are waiting ([attached]).
 * With [onAttach] there is a + on the left. With a [voice] recorder an empty field offers voice messages, sent
 * through [onSendVoice]; without one, [emptyAction] may draw the app's own button there instead (Soma chat's
 * Dictate). [sendColor] fills Send: the conversation's color, an iMessage blue or a text message green.
 * [above] sits over the field (a quoted reply); [inField] inside it, over the words, as iMessage draws attachments.
 * [sendsElsewhere] draws Send as ↗ when another app finishes the sending (Soma Messages' photos go to OpenBubbles).
 * With [receive] the field takes pictures from the keyboard (a GIF from Soma's keyboard comes in as itself, not its
 * link): [receive] gets them and gives back whatever it leaves for the field, or null when it took it all.
 */
@Composable
fun SomaComposer(
    state: ComposerState,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Message",
    sendColor: Color = Pack.accent,
    onSendColor: Color = Pack.background,
    onAttach: (() -> Unit)? = null,
    voice: VoiceRecorder? = null,
    onSendVoice: (VoiceClip) -> Unit = {},
    emptyAction: (@Composable () -> Unit)? = null,
    attached: Boolean = false,
    enabled: Boolean = true,
    now: () -> Long = { System.currentTimeMillis() },
    above: (@Composable () -> Unit)? = null,
    inField: (@Composable () -> Unit)? = null,
    sendsElsewhere: Boolean = false,
    receive: ((TransferableContent) -> TransferableContent?)? = null,
) {
    val stage = state.stage
    val action = ComposerRules.action(stage, state.text, attached, voice != null, emptyAction != null)
    val recording = stage is VoiceStage.Recording

    // The button keeps its own handle on send between draws, so send reads what is current: attachments or a quote
    // added after the words are typed still go with them.
    val latestOnSend by rememberUpdatedState(onSend)
    val latestAttached by rememberUpdatedState(attached)
    val latestEnabled by rememberUpdatedState(enabled)

    fun send() {
        if (!latestEnabled) return
        val text = state.takeText()
        if (text != null || latestAttached) latestOnSend(text.orEmpty())
    }

    val latestVoice by rememberUpdatedState(voice)
    val latestOnSendVoice by rememberUpdatedState(onSendVoice)

    fun act(what: ComposerAction) {
        if (!latestEnabled) return
        val mic = latestVoice ?: return
        when (what) {
            ComposerAction.Record -> state.startVoice(mic, now(), held = false)
            ComposerAction.Stop -> state.stopVoice(mic, now())
            ComposerAction.SendVoice -> state.takeVoice(mic)?.let(latestOnSendVoice)
            else -> {}
        }
    }

    // Levels and the clock while recording; playback's progress while playing.
    var tick by remember { mutableLongStateOf(now()) }
    if (recording && voice != null) {
        LaunchedEffect(stage) {
            while (true) {
                state.hear(voice.level())
                tick = now()
                delay(ComposerRules.LEVEL_MS)
            }
        }
    }
    val playing = state.playingSince
    if (playing != null && stage is VoiceStage.Review) {
        LaunchedEffect(playing) {
            while (now() - playing < stage.clip.millis) {
                tick = now()
                delay(16)
            }
            state.playbackEnded()
        }
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
    // What the message will carry, above the field: a quoted reply, attachments.
    if (above != null) Box(Modifier.fillMaxWidth().padding(bottom = 6.dp)) { above() }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (stage != VoiceStage.Off && voice != null) {
            RoundButton("Delete voice message", Pack.surface, Modifier.padding(bottom = 3.dp), onClick = { state.cancelVoice(voice) }) {
                ComposerGlyph(GlyphKind.Cross, Pack.foreground)
            }
        } else if (onAttach != null) {
            RoundButton("Add a photo or file", Pack.surface, Modifier.padding(bottom = 3.dp), onClick = { if (enabled) onAttach() }) {
                ComposerGlyph(GlyphKind.Plus, Pack.foreground)
            }
        }
        val line = if (recording) Pack.red else Pack.border
        val fill = if (recording) Pack.red.copy(alpha = 0.07f).compositeOver(Pack.background) else Pack.background
        // Apple's pill, square in a look whose corners are square (Manga): the field is chrome, the balloons stay round.
        val pill = composerShape(20.dp)
        val edge = if (Pack.look.line > 0.dp) Pack.mark else 1.dp
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = 40.dp)
                .clip(pill)
                .background(fill, pill)
                .border(edge, line, pill),
        ) {
        // The field is a grid: what's attached across the top, then the words, with the button at the bottom right.
        if (inField != null && stage == VoiceStage.Off) Box(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp)) { inField() }
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            when (stage) {
                VoiceStage.Off -> if (receive == null) Field(state, placeholder, enabled, ::send) else ReceivingField(state, placeholder, enabled, ::send, receive)
                is VoiceStage.Recording -> RecordingBar(state, stage, tick)
                is VoiceStage.Review -> ReviewBar(state, stage, voice, tick, now)
            }
            if (action == ComposerAction.Own) {
                emptyAction?.invoke()
            } else {
                EndButton(action, sendColor, onSendColor, ::send, ::act, sendsElsewhere) { state.stage }
            }
        }
        }
    }
    }
}

/** The field itself: the draft, growing to [ComposerRules.MAX_LINES] lines. A hardware Enter sends; Shift+Enter starts a line. */
@Composable
private fun RowScope.Field(
    state: ComposerState,
    placeholder: String,
    enabled: Boolean,
    send: () -> Unit,
) {
    val style = Pack.body(Pack.size.item)
    BasicTextField(
        state.text,
        { state.text = it },
        Modifier
            .weight(1f)
            .padding(vertical = 6.dp)
            .semantics { contentDescription = "Message" }
            // A hardware Enter sends; Shift+Enter starts a new line. The on-screen keyboard's Return breaks the line, as Apple's does.
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                    send()
                    true
                } else {
                    false
                }
            },
        enabled = enabled,
        textStyle = style,
        maxLines = ComposerRules.MAX_LINES,
        cursorBrush = SolidColor(Pack.foreground),
        decorationBox = { inner ->
            Box {
                if (state.text.isEmpty()) BasicText(placeholder, style = Pack.body(Pack.size.item, Pack.muted), maxLines = 1)
                inner()
            }
        },
    )
}

/**
 * [Field], for a composer that takes pictures from the keyboard: only the state-based field tells the keyboard it
 * does (Boss, 2026-10-27: a GIF from Soma's keyboard went into Soma Messages as its link). The draft stays in [state];
 * the field's own copy follows it both ways.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.ReceivingField(
    state: ComposerState,
    placeholder: String,
    enabled: Boolean,
    send: () -> Unit,
    receive: (TransferableContent) -> TransferableContent?,
) {
    val style = Pack.body(Pack.size.item)
    val field = rememberTextFieldState(state.text)
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect { if (it != state.text) state.text = it } }
    LaunchedEffect(field) { snapshotFlow { state.text }.collect { if (it != field.text.toString()) field.setTextAndPlaceCursorAtEnd(it) } }
    val latestReceive by rememberUpdatedState(receive)
    BasicTextField(
        field,
        Modifier
            .weight(1f)
            .padding(vertical = 6.dp)
            .semantics { contentDescription = "Message" }
            .contentReceiver(ReceiveContentListener { latestReceive(it) })
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                    send()
                    true
                } else {
                    false
                }
            },
        enabled = enabled,
        textStyle = style,
        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = ComposerRules.MAX_LINES),
        cursorBrush = SolidColor(Pack.foreground),
        decorator = { inner ->
            Box {
                if (field.text.isEmpty()) BasicText(placeholder, style = Pack.body(Pack.size.item, Pack.muted), maxLines = 1)
                inner()
            }
        },
    )
}

/** Recording: a blinking dot, the levels so far running in from the right, and the time. */
@Composable
private fun RowScope.RecordingBar(
    state: ComposerState,
    stage: VoiceStage.Recording,
    tick: Long,
) {
    val blink by rememberInfiniteTransition(label = "recording").animateFloat(
        1f,
        0.2f,
        infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
        label = "dot",
    )
    Row(
        Modifier.weight(1f).height(32.dp).semantics { contentDescription = "Recording, ${ComposerRules.clock(tick - stage.since)}" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).alpha(blink).clip(CircleShape).background(Pack.red))
        Waveform(state.levels, Pack.red, Modifier.weight(1f), fromEnd = true)
        BasicText(ComposerRules.clock(tick - stage.since), Modifier.widthIn(min = 34.dp), style = Pack.mono(Pack.size.small, Pack.red))
    }
}

/** Review: Play or Pause, the whole recording with what has played in full ink, and the time left. */
@Composable
private fun RowScope.ReviewBar(
    state: ComposerState,
    stage: VoiceStage.Review,
    voice: VoiceRecorder?,
    tick: Long,
    now: () -> Long,
) {
    val since = state.playingSince
    val played = if (since == null) 0f else ((tick - since).toFloat() / stage.clip.millis).coerceIn(0f, 1f)
    Row(
        Modifier.weight(1f).height(32.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(if (since == null) "Play voice message" else "Pause", Pack.surface, size = 30, onClick = { voice?.let { state.togglePlayback(it, now()) } }) {
            ComposerGlyph(if (since == null) GlyphKind.Play else GlyphKind.Pause, Pack.foreground)
        }
        Waveform(stage.clip.levels, Pack.foreground, Modifier.weight(1f), played = played)
        BasicText(ComposerRules.clock((stage.clip.millis * (1 - played)).toLong()), Modifier.widthIn(min = 34.dp), style = Pack.mono(Pack.size.small, Pack.muted))
    }
}

/**
 * The button at the field's right end. One element for every stage, so a finger held on the microphone is still
 * followed after the recording it started has turned the button into Stop.
 */
@Composable
private fun EndButton(
    action: ComposerAction,
    sendColor: Color,
    onSendColor: Color,
    send: () -> Unit,
    act: (ComposerAction) -> Unit,
    elsewhere: Boolean,
    stage: () -> VoiceStage,
) {
    val current by rememberUpdatedState(action)
    val currentSend by rememberUpdatedState(send)
    val currentAct by rememberUpdatedState(act)
    val fill =
        when (action) {
            ComposerAction.Send, ComposerAction.SendVoice -> sendColor
            ComposerAction.Idle -> sendColor.copy(alpha = 0.25f)
            ComposerAction.Stop -> Pack.red
            else -> Color.Transparent
        }
    val label =
        when (action) {
            ComposerAction.Record -> "Record a voice message"
            ComposerAction.Stop -> "Stop recording"
            ComposerAction.SendVoice -> "Send voice message"
            else -> if (elsewhere) "Continue in the app that sends it" else "Send"
        }
    Box(
        Modifier
            .size(32.dp)
            .clip(composerShape(null))
            .background(fill)
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick {
                    if (current == ComposerAction.Send) currentSend() else currentAct(current)
                    true
                }
            }.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val what = current
                    if (what == ComposerAction.Record) {
                        val lifted = withTimeoutOrNull(ComposerRules.HOLD_MS) { waitForUpOrCancellation() }
                        if (lifted != null) {
                            currentAct(ComposerAction.Record)
                        } else {
                            // Held: record while the finger stays, review when it lifts.
                            currentAct(ComposerAction.Record)
                            waitForUpOrCancellation()
                            if (stage() is VoiceStage.Recording) currentAct(ComposerAction.Stop)
                        }
                    } else if (waitForUpOrCancellation() != null) {
                        if (what == ComposerAction.Send) currentSend() else currentAct(what)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (action) {
            ComposerAction.Record -> MicIcon(Pack.muted)
            ComposerAction.Stop -> Box(Modifier.size(11.dp).clip(Pack.round(2.dp)).background(Color.White))
            ComposerAction.Own -> {}
            else -> ComposerGlyph(if (elsewhere && action == ComposerAction.Send) GlyphKind.Out else GlyphKind.Up, onSendColor)
        }
    }
}

/** The composer's shape: [radius] (or a circle for null) in most looks, square in a look whose corners are square. */
@Composable
fun composerShape(radius: androidx.compose.ui.unit.Dp?): androidx.compose.ui.graphics.Shape =
    if (Pack.look.pack.shape.corners == dev.omalauncher.theme.pack.CornerStyle.Edge) {
        androidx.compose.ui.graphics.RectangleShape
    } else if (radius == null) {
        CircleShape
    } else {
        RoundedCornerShape(radius)
    }

/** A round button with [description] for screen readers. */
@Composable
private fun RoundButton(
    description: String,
    fill: Color,
    modifier: Modifier = Modifier,
    size: Int = 34,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val tap by rememberUpdatedState(onClick)
    Box(
        modifier
            .size(size.dp)
            .clip(composerShape(null))
            .background(fill)
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick {
                    tap()
                    true
                }
            }.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (waitForUpOrCancellation() != null) tap()
                }
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Bars for [levels]: as many as fit, 3 dp wide with 2 dp between. [fromEnd] draws the latest ones against the
 * right edge, as a recording grows; otherwise the whole recording is spread across, and the [played] share of
 * it is in full ink.
 */
@Composable
fun Waveform(
    levels: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    fromEnd: Boolean = false,
    played: Float = 1f,
) {
    Canvas(modifier.height(26.dp)) {
        val bar = 3.dp.toPx()
        val step = bar + 2.dp.toPx()
        val n = ((size.width + 2.dp.toPx()) / step).toInt().coerceAtLeast(1)
        val shown =
            if (fromEnd) {
                levels.takeLast(n).map { maxOf(it, ComposerRules.QUIET) }
            } else {
                ComposerRules.bars(levels, n)
            }
        val start = if (fromEnd) size.width - shown.size * step + 2.dp.toPx() else 0f
        shown.forEachIndexed { i, level ->
            val h = (size.height * level).coerceAtLeast(bar)
            val ink = if (fromEnd || i.toFloat() / shown.size < played) color else color.copy(alpha = color.alpha * 0.38f)
            drawRoundRect(ink, Offset(start + i * step, (size.height - h) / 2), Size(bar, h), CornerRadius(bar / 2))
        }
    }
}

private enum class GlyphKind { Plus, Cross, Up, Out, Play, Pause }

/** The composer's few marks, drawn on a 24-unit grid like [MicIcon]. */
@Composable
private fun ComposerGlyph(
    kind: GlyphKind,
    color: Color,
) {
    Canvas(Modifier.size(18.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(2.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun path(block: Path.() -> Unit) = Path().apply(block)
        when (kind) {
            GlyphKind.Plus -> drawPath(path { line(12, 5, 12, 19, u); line(5, 12, 19, 12, u) }, color, style = stroke)
            GlyphKind.Cross -> drawPath(path { line(6.5f, 6.5f, 17.5f, 17.5f, u); line(17.5f, 6.5f, 6.5f, 17.5f, u) }, color, style = stroke)
            GlyphKind.Up -> drawPath(path { line(12, 19, 12, 6, u); moveTo(6 * u, 11.5f * u); lineTo(12 * u, 5.5f * u); lineTo(18 * u, 11.5f * u) }, color, style = Stroke(3f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
            GlyphKind.Out -> drawPath(path { line(7, 17, 17, 7, u); moveTo(9 * u, 7 * u); lineTo(17 * u, 7 * u); lineTo(17 * u, 15 * u) }, color, style = Stroke(3f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
            GlyphKind.Play -> drawPath(path { moveTo(8 * u, 5 * u); lineTo(19 * u, 12 * u); lineTo(8 * u, 19 * u); close() }, color)
            GlyphKind.Pause -> {
                pauseBar(color, 7f, u)
                pauseBar(color, 14f, u)
            }
        }
    }
}

private fun Path.line(
    x1: Number,
    y1: Number,
    x2: Number,
    y2: Number,
    u: Float,
) {
    moveTo(x1.toFloat() * u, y1.toFloat() * u)
    lineTo(x2.toFloat() * u, y2.toFloat() * u)
}

private fun DrawScope.pauseBar(
    color: Color,
    x: Float,
    u: Float,
) = drawRoundRect(color, Offset(x * u, 5 * u), Size(3.5f * u, 14 * u), CornerRadius(u))
