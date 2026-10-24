package dev.omalauncher.notes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.notes.engine.Notes
import kotlinx.coroutines.flow.MutableStateFlow

/** What is open: a note, as a page to write on or to read. */
data class Open(val id: String, val reading: Boolean)

/** Asked from outside the app (a new page from the launcher). */
val toOpen = MutableStateFlow<Open?>(null)

/**
 * The whole app. On the cover the stream, a note over it; unfolded, the stream at the left and the note beside it.
 * The red button is a new blank page; held, it listens, and what was said becomes the page.
 */
@Composable
fun NotesApp(mayListen: () -> Boolean) {
    val context = LocalContext.current
    val notes by Notes.all.collectAsState()
    val reach by Notes.reach.collectAsState()
    var open by rememberSaveable(stateSaver = OpenSaver) { mutableStateOf<Open?>(null) }
    val asked by toOpen.collectAsState()
    LaunchedEffect(asked) {
        asked?.let {
            open = it
            toOpen.value = null
        }
    }
    val talk = remember { Talk(context.applicationContext) }
    var heard by remember { mutableStateOf<String?>(null) }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    fun fresh() = Open(Notes.fresh(), reading = false)

    BackHandler(open != null) { open = null }

    Box(Modifier.fillMaxSize().background(Look.paper).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxSize()) {
            if (wide || open == null) {
                Box(Modifier.then(if (wide) Modifier.fillMaxWidth(0.38f) else Modifier.fillMaxWidth()).fillMaxHeight()) {
                    Stream(
                        notes,
                        reach,
                        open?.id?.let(Notes::now),
                        open = { n -> open = Open(n.id, reading = n.agent || n.html) },
                        // Swiped away while open beside the stream: it closes too.
                        deleted = { id -> if (open?.id?.let(Notes::now) == id) open = null },
                    )
                    // New page; held, talk.
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 22.dp).size(64.dp).offset(y = 0.dp)
                            .clip(CircleShape).background(if (heard != null) Look.ink else Look.red).border(2.dp, Look.ink, CircleShape)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { open = fresh() },
                                    onLongPress = {
                                        if (!mayListen()) return@detectTapGestures
                                        heard = ""
                                        talk.start(heard = { heard = it }) { words ->
                                            heard = null
                                            if (words.isNotBlank()) open = Open(Notes.add(words), reading = false)
                                        }
                                    },
                                    onPress = {
                                        tryAwaitRelease()
                                        if (heard != null) talk.finish()
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) { BasicText(if (heard != null) "●" else "+", style = TextStyle(fontFamily = Look.body, fontSize = 28.sp, color = Look.paper)) }
                    heard?.let {
                        BasicText(
                            it.ifEmpty { "Listening… let go when done" },
                            Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 92.dp, bottom = 24.dp).background(Look.ink).padding(12.dp),
                            style = TextStyle(fontFamily = Look.hand, fontSize = 21.sp, color = Look.paper),
                        )
                    }
                }
            }
            if (wide) Box(Modifier.width(1.15.dp).fillMaxHeight().background(Look.ink))
            val o = open
            if (o != null) {
                key(o) {
                    val m = Modifier.weight(1f).fillMaxHeight()
                    val note = Notes[o.id]
                    if (o.reading && note != null) {
                        Reader(o.id, back = { open = null }, edit = if (note.html || note.readonly) null else ({ open = o.copy(reading = false) }), modifier = m)
                    } else {
                        Page(o.id, talk, mayListen, back = { open = null }, read = { open = Open(Notes.now(o.id), reading = true) }, modifier = m)
                    }
                }
            } else if (wide) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    BasicText("Pick a note, or tap + for a blank page.", style = TextStyle(fontFamily = Look.body, fontSize = 15.sp, color = Look.muted))
                }
            }
        }
    }
}

private val OpenSaver =
    androidx.compose.runtime.saveable.Saver<Open?, String>(
        save = { it?.let { o -> (if (o.reading) "r:" else "w:") + o.id } ?: "" },
        restore = { s -> if (s.length < 2) null else Open(s.substring(2), s.startsWith("r:")) },
    )
