package dev.omalauncher.messages.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import dev.omalauncher.messages.engine.Desk
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.omalauncher.messages.engine.Messages
import dev.omalauncher.ui.ComposerState
import dev.omalauncher.ui.Motion
import dev.omalauncher.ui.Pack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The whole app. On the cover the inbox, with a conversation sliding over it; Android's back gesture pulls the
 * conversation away under the finger and lets go of it as the finger does. Unfolded, the inbox and the conversation
 * side by side (the side-table workspace comes in its own slice).
 */
@Composable
fun MessagesApp(resumed: Int) {
    val context = LocalContext.current
    val book by Messages.book.collectAsState()
    val samples by Messages.samples.collectAsState()
    val mac by Messages.mac.collectAsState()
    var granted by remember { mutableStateOf(Messages.granted(context)) }
    LaunchedEffect(resumed) { granted = Messages.granted(context) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    // Each conversation keeps its own draft, kept on the phone (Desk) so it outlasts the app.
    val drafts = remember { HashMap<String, ComposerState>() }
    // And its quoted reply and attachments, waiting with it.
    val pending = remember { Pending().apply { Desk.left.value.forEach { (id, l) -> l.quote?.let { quotes[id] = it } } } }
    fun draft(id: String) = drafts.getOrPut(id) { ComposerState(Desk[id].draft) }
    // The unfolded workspace's panel and what it shows, kept here so folding and unfolding again doesn't lose them.
    val desk = remember { WorkspaceState() }
    // Cover or unfolded is decided while composing, from the window's width, never while laying out: switching
    // screens during layout tore down a focused WebView mid-measure and crashed the app on folding.
    val widthDp = LocalConfiguration.current.screenWidthDp

    Box(Modifier.fillMaxSize().background(Pack.paper).windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (!granted && !samples && mac == null) {
            AccessScreen(openBubbles = Messages.openBubblesInstalled(context))
            return@Box
        }
        val wide = widthDp >= 600
        val pick: (String) -> Unit = { id ->
            openId = id
            Messages.read(id)
        }
        if (wide) {
            // Nothing is picked yet: the newest conversation shows, but stays unread until the owner picks it.
            val shown = openId?.takeIf { book[it] != null } ?: book.conversations.firstOrNull()?.id
            Workspace(book, shown, samples, pick, ::draft, pending, desk)
        } else {
            Inbox(book, null, samples, pick, Modifier.fillMaxSize())
            val id = openId
            val conversation = id?.let { book[it] }
            if (conversation != null) {
                Sliding(widthPx = with(LocalDensity.current) { widthDp.dp.toPx() }, onGone = { openId = null }) { back ->
                    key(conversation.id) { Thread(conversation, draft(conversation.id), pending, onBack = back, Modifier.fillMaxSize()) }
                }
            }
        }
    }
}

/**
 * A screen over the inbox: it slides in from the right, and Android's back gesture (predictive back) or a drag to the
 * right anywhere on it moves it with the finger. Letting go far enough, or flicking, finishes the slide; letting go
 * early puts it back.
 */
@Composable
private fun Sliding(
    widthPx: Float,
    onGone: () -> Unit,
    content: @Composable (back: () -> Unit) -> Unit,
) {
    val x = remember { Animatable(widthPx) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { x.animateTo(0f, Motion.spatial) }
    val back: () -> Unit = {
        scope.launch {
            x.animateTo(widthPx, Motion.spatial)
            onGone()
        }
    }
    PredictiveBackHandler { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { e -> x.snapTo(e.progress * widthPx) }
            x.animateTo(widthPx, Motion.spatial)
            onGone()
        } catch (e: CancellationException) {
            // The finger let go early: the screen goes back where it was, outside the cancelled gesture.
            scope.launch { x.animateTo(0f, Motion.spatial) }
            throw e
        }
    }
    // A drag to the right anywhere a message balloon hasn't taken it (they see it first) pulls the screen away too.
    val fling = with(LocalDensity.current) { BACK_FLING.toPx() }
    Box(
        Modifier.fillMaxSize().pointerInput(widthPx) {
            val velocity = VelocityTracker()
            var pulling = false
            var decided = false
            detectHorizontalDragGestures(
                onDragStart = {
                    pulling = false
                    decided = false
                    velocity.resetTracking()
                },
                onDragEnd = {
                    if (pulling) {
                        val v = velocity.calculateVelocity().x
                        scope.launch {
                            if (x.value > widthPx * BACK_AT || v > fling) {
                                x.animateTo(widthPx, Motion.spatial)
                                onGone()
                            } else {
                                x.animateTo(0f, Motion.spatial)
                            }
                        }
                    }
                    pulling = false
                },
                onDragCancel = {
                    if (pulling) scope.launch { x.animateTo(0f, Motion.spatial) }
                    pulling = false
                },
            ) { change, dx ->
                // A drag that starts leftward isn't a way back, even if it turns.
                if (!decided) {
                    decided = true
                    pulling = dx > 0f
                }
                if (!pulling) return@detectHorizontalDragGestures
                change.consume()
                velocity.addPosition(change.uptimeMillis, change.position)
                scope.launch { x.snapTo((x.value + dx).coerceIn(0f, widthPx)) }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().offset { IntOffset(x.value.roundToInt(), 0) }.background(Pack.paper)) { content(back) }
    }
}

/** How far across the screen the back drag has to go for letting go to finish it. */
private const val BACK_AT = 0.35f

/** A flick to the right this fast finishes the back drag wherever it is. */
private val BACK_FLING = 500.dp
