package dev.omalauncher.notes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.omalauncher.notes.engine.Notes
import dev.omalauncher.notes.ui.Look
import dev.omalauncher.notes.ui.NotesApp
import dev.omalauncher.notes.ui.Open
import dev.omalauncher.notes.ui.toOpen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent?.getStringExtra("notes_folder") == "walk") Notes.walkFolder(applicationContext)
        Notes.init(applicationContext)
        Look.use(applicationContext)
        setContent { NotesApp(mayListen = ::mayListen) }
        if (savedInstanceState == null) openFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    override fun onResume() {
        super.onResume()
        // Whatever changed on the Mac while away comes in.
        Notes.sync()
    }

    private fun openFrom(intent: Intent?) {
        when (intent?.action) {
            ACTION_NEW -> toOpen.value = Open(Notes.fresh(), reading = false)
            ACTION_OPEN -> intent.getStringExtra(EXTRA_ID)?.let { id -> toOpen.value = Open(Notes.now(id), reading = Notes[id]?.let { it.agent || it.html } == true) }
        }
    }

    /** Talking needs the microphone: asked for the first time it's wanted; true when it's allowed already. */
    private fun mayListen(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC)
        return false
    }

    companion object {
        const val ACTION_NEW = "dev.omalauncher.notes.NEW"
        const val ACTION_OPEN = "dev.omalauncher.notes.OPEN"
        const val EXTRA_ID = "id"
        private const val MIC = 1
    }
}
