package dev.omalauncher.assist

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.omalauncher.ui.Motion
import dev.omalauncher.ui.Pack
import kotlinx.coroutines.launch

/** Where a request to Oma stands while the listening screen is up. */
enum class ListeningPhase {
    /** The microphone is open: what is heard is written over the page. */
    Listening,

    /** The microphone has closed and the words wait for his go: nothing is sent until he says so. */
    Heard,

    /** Nothing was heard. */
    Missed,

    /** Listening or sending went wrong; the detail says how. */
    Failed,

    /** The microphone isn't allowed yet. */
    NoMic,

    /** An approval is waiting for the owner. */
    NeedsOk,

    /** The words are on their way to a device. */
    Sending,

    /** A device typed them; the detail says where. */
    Typed,
}

/** The underlined words under what was heard. */
enum class ListeningAct(val label: String) {
    Send("Send"),
    Add("Add more"),
    Cancel("Cancel"),
    Retry("Try again"),
    Allow("Allow"),
    Review("Review"),
    Close("Close"),
}

/** What the listening screen says at one moment: design/oma-scrapbook-prototype.html's caption, handwriting and links. */
data class ListeningLook(
    val caption: String,
    val words: String,
    val acts: List<ListeningAct>,
    /** A smaller line over the caption, for the screen that goes with the request; tapping it lets the screen go. */
    val note: String = "",
    /** The words are his to change before sending: tapped, they take a cursor, a selection and the keyboard. */
    val editable: Boolean = false,
)

/** The look of [phase]: [heard] is what the microphone has so far, [detail] what a problem or a waiting approval says. */
fun listeningLook(
    phase: ListeningPhase,
    heard: String = "",
    detail: String = "",
    note: String = "",
): ListeningLook =
    when (phase) {
        ListeningPhase.Listening -> ListeningLook("Listening", heard, listOf(ListeningAct.Cancel), note)
        // The words stay as they were written; sending is his to tap, and he can say more first.
        // A device that didn't type them says so here, in place of the question, and the words stay to send again.
        ListeningPhase.Heard -> ListeningLook(detail.ifEmpty { "Send this?" }, heard, listOf(ListeningAct.Send, ListeningAct.Add, ListeningAct.Cancel), note, editable = true)
        ListeningPhase.Missed -> ListeningLook("Didn't catch that", "", listOf(ListeningAct.Retry, ListeningAct.Cancel), note)
        ListeningPhase.Failed -> ListeningLook("That didn't work", detail, listOf(ListeningAct.Retry, ListeningAct.Cancel), note)
        ListeningPhase.NoMic -> ListeningLook("Microphone is off", "Allow the microphone to talk to Soma", listOf(ListeningAct.Allow, ListeningAct.Cancel))
        ListeningPhase.NeedsOk -> ListeningLook("Needs your OK", detail, listOf(ListeningAct.Review, ListeningAct.Close))
        ListeningPhase.Sending -> ListeningLook("Sending", heard, emptyList(), note)
        ListeningPhase.Typed -> ListeningLook(detail, "", emptyList(), note)
    }

/**
 * The agent's status [label] as the prototype pencils it in the margin: what it is doing, in a few lower-case
 * words that trail off. Home puts "✎ Oma: " before it.
 */
fun marginNote(label: String): String {
    val text = label.trim().trimEnd('…', '.').trim()
    val doing =
        when {
            text.isEmpty() || text.endsWith("is working", ignoreCase = true) -> "working"
            text.equals("Approval needed", ignoreCase = true) -> return "needs your OK"
            else -> text.replaceFirstChar { it.lowercase() }
        }
    return "$doing…"
}

/** The prototype's `.dim` and `.listen` transitions, in milliseconds. */
const val LISTENING_DIM_MILLIS = 450
private const val BLOCK_MILLIS = 400
private const val WORD_MILLIS = 250

/** How much of the paper lies over the page while listening. */
private const val DIM = 0.88f

/** The prototype's phone is 380 units wide closed and 760 open; a wider screen draws its units larger, up to half again. */
fun prototypeUnit(widthDp: Float, heightDp: Float): Float {
    val across = if (widthDp > heightDp * 1.1f) 760f else 380f
    return (widthDp / across).coerceIn(1f, 1.5f)
}

/**
 * The listening screen: the paper laid over whatever is underneath, the words handwritten bottom left as
 * they are heard, and an underlined way out. [look] null is the screen going away; it fades as it came.
 * A tap on the paper is [onAway], [ListeningAct.Cancel] unless the host says otherwise. With [onDim] the host lays the paper itself (a window does,
 * so it reaches under the system bars) and is told how much of it to show. [onEdit] is told the words as he
 * changes them by hand, while they wait for his go.
 */
@Composable
fun ListeningLayer(
    look: ListeningLook?,
    onAct: (ListeningAct) -> Unit,
    modifier: Modifier = Modifier,
    onNote: () -> Unit = {},
    onDim: ((Float) -> Unit)? = null,
    onEdit: (String) -> Unit = {},
    onAway: () -> Unit = { onAct(ListeningAct.Cancel) },
) {
    var last by remember { mutableStateOf(look) }
    if (look != null) last = look
    val shown = look != null
    val dim by animateFloatAsState(if (shown) DIM else 0f, Motion.timed(LISTENING_DIM_MILLIS), label = "dim")
    val rise by animateFloatAsState(if (shown) 1f else 0f, Motion.timed(BLOCK_MILLIS), label = "listen")
    if (onDim != null) SideEffect { onDim(dim) }
    val showing = last ?: return
    if (!shown && dim == 0f && rise == 0f) return
    val ink = Pack.onPage
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .then(if (onDim == null) Modifier.background(Pack.paper.copy(alpha = dim)) else Modifier)
            .then(
                // Gone, it takes no touch: what is underneath is back in reach while the paper is still lifting.
                if (shown) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = if (showing.acts == listOf(ListeningAct.Cancel)) "Stop" else "Cancel") { onAway() } else Modifier,
            ),
    ) {
        val u = prototypeUnit(maxWidth.value, maxHeight.value)
        val below = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                // The links stand on 12 units of their own, for the finger: the block ends where the prototype's does,
                // 70 units up, which already clears a navigation bar; only a taller bar or a keyboard lifts it further.
                .padding(start = (26 * u).dp, bottom = maxOf((58 * u).dp, below))
                .width((330 * u).dp)
                .graphicsLayer {
                    alpha = rise
                    translationY = (1f - rise) * 14 * u * density
                }.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            if (showing.note.isNotEmpty()) {
                BasicText(
                    showing.note,
                    Modifier.clickable(enabled = shown, onClick = onNote).padding(bottom = (10 * u).dp),
                    style = Pack.mono((10 * u).sp, ink.copy(alpha = ink.alpha * 0.8f)).copy(fontWeight = FontWeight.Medium, lineHeight = (14 * u).sp),
                )
            }
            BasicText(
                showing.caption.uppercase(),
                style = Pack.mono((10 * u).sp, ink.copy(alpha = ink.alpha * 0.8f)).copy(fontWeight = FontWeight.Medium, letterSpacing = 0.12.em, lineHeight = (10 * u).sp),
            )
            if (showing.editable && shown) {
                Written(showing.words, ink, u, onEdit, Modifier.padding(top = (10 * u).dp))
            } else {
                Said(showing.words, ink, u, Modifier.padding(top = (10 * u).dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy((22 * u).dp)) {
                for (act in showing.acts) {
                    BasicText(
                        act.label,
                        Modifier.clickable(enabled = shown, role = Role.Button) { onAct(act) }.padding(vertical = (12 * u).dp),
                        style = Pack.mono((12 * u).sp, ink).copy(fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline, lineHeight = (12 * u).sp),
                    )
                }
            }
        }
    }
}

/** The most lines of handwriting on the screen at once; the prototype keeps three and a cover screen has room for these. */
private const val SAID_LINES = 7

/** The prototype's handwriting: three lines kept for it, each word coming up as it is heard. */
@Composable
private fun Said(
    words: String,
    ink: Color,
    u: Float,
    modifier: Modifier = Modifier,
) {
    val list = remember(words) { words.split(Regex("\\s+")).filter { it.isNotEmpty() } }
    val seen = remember { mutableStateListOf<Animatable<Float, AnimationVector1D>>() }
    val reduced = Motion.reduced
    // Each word's fade is its own: the next word arriving doesn't cut it short.
    val scope = rememberCoroutineScope()
    LaunchedEffect(list.size) {
        while (seen.size > list.size) seen.removeAt(seen.lastIndex)
        while (seen.size < list.size) {
            val word = Animatable(if (reduced) 1f else 0f)
            seen += word
            if (!reduced) scope.launch { word.animateTo(1f, tween(WORD_MILLIS)) }
        }
    }
    val text =
        buildAnnotatedString {
            list.forEachIndexed { i, word ->
                withStyle(SpanStyle(color = ink.copy(alpha = ink.alpha * (seen.getOrNull(i)?.value ?: 0f)))) { append(word) }
                append(' ')
            }
        }
    // A long request would climb off the top of the screen: past [SAID_LINES] it scrolls, and stays at its newest words.
    val scroll = rememberScrollState()
    LaunchedEffect(words, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    BasicText(
        text,
        modifier.heightIn(max = (40 * u * SAID_LINES).dp).verticalScroll(scroll),
        style = Pack.hand((40 * u).sp, ink).copy(fontWeight = FontWeight.Bold, lineHeight = (40 * u).sp),
        minLines = 3,
    )
}

/**
 * The words once the microphone has closed, the same handwriting as [Said] but a text field (Boss, 2026-10-13):
 * a tap puts the cursor there, a press and drag selects, and the keyboard deletes and types. Nothing about the
 * screen changes until he touches them. The cursor and selection are kept here; words added by speaking again
 * come back as new [words] and take their place, with the cursor at the end.
 */
@Composable
private fun Written(
    words: String,
    ink: Color,
    u: Float,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var field by remember { mutableStateOf(TextFieldValue(words, TextRange(words.length))) }
    if (field.text != words) field = TextFieldValue(words, TextRange(words.length))
    val scroll = rememberScrollState()
    BasicTextField(
        field,
        { next -> field = next; if (next.text != words) onEdit(next.text) },
        modifier.heightIn(max = (40 * u * SAID_LINES).dp).verticalScroll(scroll),
        textStyle = Pack.hand((40 * u).sp, ink).copy(fontWeight = FontWeight.Bold, lineHeight = (40 * u).sp),
        cursorBrush = SolidColor(ink),
        minLines = 3,
    )
}
