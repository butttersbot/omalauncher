package dev.omalauncher.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** How long a toast stays up. */
const val TOAST_MILLIS = 2_800L

/** What another app asks Oma to say: [ACTION_TOAST] with [EXTRA_TO] (the machine it sent to) or [EXTRA_TEXT]. */
const val ACTION_TOAST = "dev.omalauncher.action.TOAST"
const val EXTRA_TO = "to"
const val EXTRA_TEXT = "text"
const val EXTRA_CAPTION = "caption"

/** The apps whose word is taken, by package, and the name each is captioned with. */
val TOAST_SENDERS = mapOf("com.butttersbot.tailshare" to "Tailshare")

/** What a toast says: [text] as given, else "Sent to [to]"; null when neither says anything. Cut short, one line's worth. */
fun toastText(
    text: String?,
    to: String?,
): String? {
    val said = text?.trim()?.takeIf { it.isNotEmpty() } ?: to?.trim()?.takeIf { it.isNotEmpty() }?.let { "Sent to $it" } ?: return null
    return said.replace(Regex("\\s+"), " ").take(120)
}

/**
 * A short "sent" note from another app, drawn in the active look (Boss, 2026-10-16: Tailshare should say when
 * it has sent, name the machine, and look like Oma). Only apps in [TOAST_SENDERS] are heard, known by Android's
 * own word for who sent the broadcast, which they give by sharing their identity
 * (BroadcastOptions.setShareIdentityEnabled). The card is phone control's; without it, a plain toast.
 */
class OmaToastReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_TOAST || Build.VERSION.SDK_INT < 34) return
        val from = sentFromPackage ?: return
        val name = TOAST_SENDERS[from] ?: return
        val text = toastText(intent.getStringExtra(EXTRA_TEXT), intent.getStringExtra(EXTRA_TO)) ?: return
        val caption = intent.getStringExtra(EXTRA_CAPTION)?.trim()?.takeIf { it.isNotEmpty() }?.take(40) ?: name
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            if (PhoneService.instance?.showToast(caption, text) != true) Toast.makeText(app, "$caption: $text", Toast.LENGTH_SHORT).show()
        }
    }
}
