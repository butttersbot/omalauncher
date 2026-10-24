package dev.omalauncher.notes.ui

import android.app.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.notes.engine.Notes

/**
 * A note of Boss's as a whole page (Boss, 2026-10-23: "like a blank notes page, full screen"): its first line is its
 * title, set big; everything is saved as it is typed. Below, the tools: a checklist line, and hold to talk.
 */
@Composable
fun Page(
    id: String,
    talk: Talk,
    mayListen: () -> Boolean,
    back: () -> Unit,
    read: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var value by remember(id) { mutableStateOf(Notes.text(id).let { TextFieldValue(it, TextRange(it.length)) }) }
    val came by Notes.came.collectAsState()
    // Notes from the Mac (an agent added to this list, another device edited it) show at once.
    LaunchedEffect(came) {
        val now = Notes.text(id)
        if (now != value.text) value = TextFieldValue(now, TextRange(value.selection.end.coerceAtMost(now.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(id) { if (value.text.isEmpty()) runCatching { focus.requestFocus() } }
    var heard by remember { mutableStateOf<String?>(null) }

    fun set(next: TextFieldValue) {
        value = next
        Notes.save(id, next.text)
    }

    Column(modifier.fillMaxSize().background(Look.paper).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("‹ Notes", Modifier.clickable(onClick = back).padding(vertical = 6.dp, horizontal = 2.dp), style = TextStyle(fontFamily = Look.body, fontSize = 15.sp, color = Look.ink))
            Spacer(Modifier.weight(1f))
            if (value.text.isNotBlank()) {
                Tool("Read") { read() }
                Spacer(Modifier.width(6.dp))
                Tool("Delete") {
                    AlertDialog.Builder(context)
                        .setTitle("Delete this note?")
                        .setMessage("It goes to the trash in ~/Notes on the Mac Pro, where it can still be found.")
                        .setPositiveButton("Delete") { _, _ ->
                            Notes.delete(id)
                            back()
                        }
                        .setNegativeButton("Keep", null)
                        .show()
                }
            }
        }
        val note = Notes[id]
        BasicText(
            (note?.let { whenOf(it.modified, System.currentTimeMillis()) } ?: "New note").uppercase() + if (note?.dirty == true) " · NOT SENT YET" else "",
            Modifier.padding(horizontal = 16.dp),
            style = mono(Look.muted),
        )
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (value.text.isEmpty()) BasicText("Title", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = TextStyle(fontFamily = Look.display, fontSize = 26.sp, color = Look.tone))
            BasicTextField(
                value,
                { set(Lists.typed(value, it)) },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).focusRequester(focus),
                textStyle = TextStyle(fontFamily = Look.body, fontSize = 20.sp, lineHeight = 29.sp, color = Look.ink),
                cursorBrush = SolidColor(Look.red),
                visualTransformation = TitleLine,
            )
        }
        heard?.let { BasicText(it.ifEmpty { "Listening…" }, Modifier.fillMaxWidth().background(Look.ink).padding(horizontal = 16.dp, vertical = 10.dp), style = TextStyle(fontFamily = Look.hand, fontSize = 21.sp, color = Look.paper)) }
        Row(
            Modifier.fillMaxWidth().border(1.15.dp, Look.ink).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tool("☐ List") { set(Lists.toggleLine(value)) }
            Box(
                Modifier.border(1.15.dp, Look.ink).background(if (heard != null) Look.red else Look.paper)
                    .pointerInput(id) {
                        detectTapGestures(
                            onPress = {
                                if (!mayListen()) return@detectTapGestures
                                heard = ""
                                talk.start(heard = { heard = it }) { words ->
                                    heard = null
                                    if (words.isNotBlank()) set(Lists.insert(value, words))
                                }
                                tryAwaitRelease()
                                talk.finish()
                            },
                        )
                    }.padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                BasicText(if (heard != null) "● Listening" else "🎙 Hold to talk", style = TextStyle(fontFamily = Look.body, fontSize = 14.sp, color = if (heard != null) Look.paper else Look.ink))
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
fun Tool(
    label: String,
    onClick: () -> Unit,
) {
    BasicText(
        label,
        Modifier.border(1.15.dp, Look.ink).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        style = TextStyle(fontFamily = Look.body, fontSize = 14.sp, color = Look.ink),
    )
}

/** The first line set as the title, in the look's display face. */
private object TitleLine : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val end = text.indexOf('\n').let { if (it < 0) text.length else it }
        val styled =
            AnnotatedString.Builder(text).apply {
                if (end > 0) addStyle(SpanStyle(fontFamily = Look.display, fontSize = 26.sp), 0, end)
            }.toAnnotatedString()
        return TransformedText(styled, OffsetMapping.Identity)
    }
}
