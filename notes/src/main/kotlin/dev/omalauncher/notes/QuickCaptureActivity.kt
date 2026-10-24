package dev.omalauncher.notes

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.notes.engine.Notes
import dev.omalauncher.notes.ui.Look
import dev.omalauncher.notes.ui.Tool
import dev.omalauncher.notes.ui.mono

/**
 * Quick capture: a sheet dropped over whatever is on screen (from home, the cover, or another app's Share), for a
 * thought in passing. Put away, what was written is a note; "Open as page" carries on with it in the app.
 */
class QuickCaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notes.init(applicationContext)
        Look.use(applicationContext)
        val shared = shared(intent)
        setContent {
            var text by rememberSaveable { mutableStateOf(shared) }
            var done by remember { mutableStateOf(false) }
            fun finishWith(page: Boolean) {
                if (done) return
                done = true
                if (text.isNotBlank()) {
                    val id = Notes.add(text.trim())
                    if (page) {
                        startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN).putExtra(MainActivity.EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else {
                        Toast.makeText(this, "Saved to Notes", Toast.LENGTH_SHORT).show()
                    }
                }
                finish()
            }
            BackHandler { finishWith(page = false) }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Box(
                Modifier.fillMaxSize().background(Color(0x66111113))
                    .clickable(MutableInteractionSource(), null) { finishWith(page = false) },
            ) {
                Column(
                    Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp).widthIn(max = 560.dp).fillMaxWidth()
                        .background(Look.paper).border(2.dp, Look.ink)
                        .clickable(MutableInteractionSource(), null) {}
                        .padding(16.dp),
                ) {
                    BasicText("QUICK CAPTURE", style = mono(Look.muted))
                    Box(Modifier.fillMaxWidth().heightIn(min = 130.dp).padding(vertical = 8.dp)) {
                        if (text.isEmpty()) BasicText("A thought, a list, a link…", style = TextStyle(fontFamily = Look.body, fontSize = 20.sp, color = Look.muted))
                        BasicTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus), textStyle = TextStyle(fontFamily = Look.body, fontSize = 20.sp, lineHeight = 27.sp, color = Look.ink), cursorBrush = SolidColor(Look.red))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Tool("Open as page") { finishWith(page = true) }
                        Spacer(Modifier.weight(1f))
                        BasicText(
                            "Save",
                            Modifier.background(Look.ink).clickable { finishWith(page = false) }.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = TextStyle(fontFamily = Look.body, fontSize = 15.sp, color = Look.paper),
                        )
                    }
                }
            }
        }
    }

    /** What another app shared: its subject as the title, then the text (a link, a passage). */
    private fun shared(intent: Intent?): String {
        if (intent?.action != Intent.ACTION_SEND) return ""
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        return if (subject.isNotEmpty() && subject != body) "$subject\n\n$body" else body
    }
}
