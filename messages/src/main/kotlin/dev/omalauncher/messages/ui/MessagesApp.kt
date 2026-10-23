package dev.omalauncher.messages.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    var granted by remember { mutableStateOf(Messages.granted(context)) }
    LaunchedEffect(resumed) { granted = Messages.granted(context) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    // Each conversation keeps its own draft while the app runs.
    val drafts = remember { HashMap<String, ComposerState>() }
    // And its quoted reply, waiting above the field.
    val quotes = remember { mutableStateMapOf<String, String>() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Pack.paper).windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (!granted && !samples) {
            AccessScreen(openBubbles = Messages.openBubblesInstalled(context))
            return@BoxWithConstraints
        }
        val wide = maxWidth >= 600.dp
        val pick: (String) -> Unit = { id ->
            openId = id
            Messages.read(id)
        }
        if (wide) {
            val shown = openId?.takeIf { book[it] != null } ?: book.conversations.firstOrNull()?.id
            Row(Modifier.fillMaxSize()) {
                Inbox(book, shown, samples, pick, Modifier.width(390.dp).fillMaxHeight())
                if (shown != null) {
                    LaunchedEffect(shown) { Messages.read(shown) }
                    Thread(book[shown]!!, drafts.getOrPut(shown) { ComposerState() }, quotes, onBack = null, Modifier.fillMaxSize())
                }
            }
        } else {
            Inbox(book, null, samples, pick, Modifier.fillMaxSize())
            val id = openId
            val conversation = id?.let { book[it] }
            if (conversation != null) {
                Sliding(widthPx = with(LocalDensity.current) { maxWidth.toPx() }, onGone = { openId = null }) { back ->
                    Thread(conversation, drafts.getOrPut(conversation.id) { ComposerState() }, quotes, onBack = back, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * A screen over the inbox: it slides in from the right, and Android's back gesture (predictive back) moves it with
 * the finger. Letting go far enough, or flicking, finishes the slide; letting go early puts it back.
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
    Box(Modifier.fillMaxSize().offset { IntOffset(x.value.roundToInt(), 0) }.background(Pack.paper)) { content(back) }
}
