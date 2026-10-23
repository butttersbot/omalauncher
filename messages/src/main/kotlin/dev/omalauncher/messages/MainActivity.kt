package dev.omalauncher.messages

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import dev.omalauncher.messages.engine.Messages
import dev.omalauncher.messages.look.Look
import dev.omalauncher.messages.ui.MessagesApp

class MainActivity : ComponentActivity() {
    /** Bumped on every return to the app, so the screens look again at whether notification access was turned on. */
    private val resumed = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Messages.init(applicationContext)
        dev.omalauncher.messages.engine.Desk.init(applicationContext)
        Look.use(applicationContext)
        setContent { MessagesApp(resumed.intValue) }
    }

    override fun onResume() {
        super.onResume()
        resumed.intValue++
    }
}
