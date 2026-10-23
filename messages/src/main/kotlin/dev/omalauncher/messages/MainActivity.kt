package dev.omalauncher.messages

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import dev.omalauncher.messages.engine.MacAddress
import dev.omalauncher.messages.engine.MacLink
import dev.omalauncher.messages.engine.Names
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
        if (savedInstanceState == null) offerLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        offerLink(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CONTACTS && Names.allowed(this)) MacLink.refresh()
    }

    override fun onResume() {
        super.onResume()
        resumed.intValue++
    }

    /**
     * The link the Mac's service prints (soma-messages://link?url=…&token=…), opened on the phone. A link could come
     * from anywhere, so the owner says yes to the Mac by name before anything is sent to it.
     */
    private fun offerLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || uri.scheme != "soma-messages" || uri.host != "link") return
        val at = MacAddress.of(uri.getQueryParameter("url"), uri.getQueryParameter("token"))
        if (at == null) {
            AlertDialog.Builder(this).setTitle("Not a link to your Mac").setMessage("Soma Messages links only to a Mac on your tailnet, or over https.").setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Get your iMessages from ${at.host}?")
            .setMessage("Conversations will come from Messages on that Mac, and your replies and photos will go out through it. OpenBubbles is no longer used here.")
            .setPositiveButton("Link") { _, _ ->
                Messages.link(applicationContext, at)
                // The Mac sends numbers and emails only; names come from the phone's contacts, with the owner's OK.
                if (!Names.allowed(this)) requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), CONTACTS)
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private companion object {
        const val CONTACTS = 1
    }
}
