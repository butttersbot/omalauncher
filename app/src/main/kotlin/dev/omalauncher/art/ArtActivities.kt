package dev.omalauncher.art

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.concurrent.thread

/**
 * Copies [uri] into the store off the main thread, then hands it to the placer for ([page], [panel]) on the main thread.
 * Says why if it isn't taken; calls [done] either way, on the main thread.
 */
private fun importAndPlace(
    activity: Activity,
    uri: Uri,
    page: String?,
    panel: Int?,
    done: () -> Unit,
) {
    val app = activity.applicationContext
    thread(name = "oma-art-import", isDaemon = true) {
        val result = runCatching { ArtStore(app).import(uri) }
        Handler(Looper.getMainLooper()).post {
            result
                .onSuccess { ArtPlacers.placeFrom(app, it, page, panel) }
                .onFailure { ArtPlacers.say(app, (it as? ArtStore.Refused)?.message ?: "That couldn't be added.") }
            done()
        }
    }
}

/** "Add to a panel" in the share sheet: one shared picture or GIF goes into a manga panel ([ArtPlacers]). Shows nothing. */
class ArtShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = if (intent.action == Intent.ACTION_SEND) stream() else null
        if (uri == null) {
            finish()
            return
        }
        importAndPlace(this, uri, null, null) { finish() }
    }

    @Suppress("DEPRECATION")
    private fun stream(): Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)
}

/**
 * Opens the system photo picker (pictures and GIFs) for one panel and hands what is picked to [ArtPlacers] for that
 * (page, panel). Home opens it with [open] when an empty panel is tapped. Transparent: the picker is all that shows.
 */
class ArtPickActivity : ComponentActivity() {
    private val pick =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) finish() else importAndPlace(this, uri, page(), panel()) { finish() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated while the picker is up: its answer comes back through [pick], so it isn't asked again.
        if (savedInstanceState == null) pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun page(): String? = intent.getStringExtra(EXTRA_PAGE)

    private fun panel(): Int? = intent.getIntExtra(EXTRA_PANEL, -1).takeIf { it >= 0 }

    companion object {
        const val EXTRA_PAGE = "dev.omalauncher.art.PAGE"
        const val EXTRA_PANEL = "dev.omalauncher.art.PANEL"

        /** The intent that picks art for [panel] (its index in the page's layout) of notebook page [page]. */
        fun intent(
            context: Context,
            page: String?,
            panel: Int?,
        ): Intent =
            Intent(context, ArtPickActivity::class.java).apply {
                page?.let { putExtra(EXTRA_PAGE, it) }
                panel?.let { putExtra(EXTRA_PANEL, it) }
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        /** Opens the picker for ([page], [panel]); what is picked goes to [ArtPlacers.current]. */
        fun open(
            context: Context,
            page: String?,
            panel: Int?,
        ) = context.startActivity(intent(context, page, panel))
    }
}
