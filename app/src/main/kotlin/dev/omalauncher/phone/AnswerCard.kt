package dev.omalauncher.phone

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.omalauncher.assist.ComposeHost
import dev.omalauncher.assist.prototypeUnit
import dev.omalauncher.oma.Markdown
import dev.omalauncher.oma.OmaChatActivity
import dev.omalauncher.ui.Motion
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.surface
import dev.omalauncher.ui.toColor
import kotlin.math.abs

// The agent's answer, over whatever app is in front: a card in the active look that comes down from the top
// of the screen, stays a while and goes. It is where design/oma-scrapbook-prototype.html has its `.heads`
// and moves as that does; what it is made of is the look's own scrap. The accessibility service draws it,
// so it needs phone control on; without it the answer is an ordinary pop-up notification instead.

/** How long an answer stays up before it folds into the notification shade. */
const val ANSWER_CARD_MILLIS = 8000L

/** The prototype's `.heads` transition: 450ms on cubic-bezier(.2, .9, .2, 1). */
const val ANSWER_CARD_MOVE_MILLIS = 450

/** The most of an answer the card shows; the rest is in the Oma chat, a tap away. */
const val ANSWER_CARD_CHARS = 600

/**
 * Whether the card may show now: the service is bound and phone control isn't paused, the phone is awake and
 * unlocked (an answer is never drawn over the lock screen), and neither the assistant nor a screenshot for
 * the agent would catch it. The ring's own setting has no say: [OverlayFacts.allowed] isn't read.
 */
fun answerCardWanted(facts: OverlayFacts): Boolean =
    facts.connected && !facts.paused && !facts.locked && facts.awake && !facts.capturing && !facts.assistantUp

/** What the card's small line says over the answer: Oma's name, or what went wrong. */
fun answerCardCaption(label: String): String = if (label == "Done") "Oma" else label

/** The card's window: a strip at the top as tall as the card, touchable but never focused, so keys and every touch outside it go to the app underneath. */
private fun cardWindow(top: Int) =
    WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).also {
        it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        it.y = top
        it.title = "Oma answer"
    }

/** Shows one answer at a time in a window of the accessibility service's. Main thread only. */
class AnswerCard(private val service: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var host: ComposeHost? = null
    private var turn = 0
    val shown get() = view != null

    /** Puts [text] up under [caption], replacing a card that is still showing. False if Android refused the window. */
    fun show(caption: String, text: String): Boolean {
        close()
        val mine = ++turn
        val up = mutableStateOf(true)
        val owner = ComposeHost()
        val compose = ComposeView(service)
        owner.attach(compose)
        owner.resume()
        compose.setContent {
            AnswerCardContent(
                caption,
                // The card writes by hand and draws no Markdown: it gets the words without their markers.
                Markdown.plain(text.trim().take(4 * ANSWER_CARD_CHARS)).take(ANSWER_CARD_CHARS),
                up,
                onOpen = {
                    runCatching { service.startActivity(OmaChatActivity.intent(service)) }
                    leave(mine, up)
                },
                onDismiss = { leave(mine, up) },
            )
        }
        val added = runCatching { service.getSystemService(WindowManager::class.java).addView(compose, cardWindow(statusBar())) }
        if (added.isFailure) {
            Log.w("answer", "The answer card could not be shown", added.exceptionOrNull())
            owner.destroy()
            return false
        }
        view = compose
        host = owner
        main.postDelayed({ leave(mine, up) }, ANSWER_CARD_MILLIS)
        return true
    }

    /** Sends the card back up, then takes its window down. */
    private fun leave(mine: Int, up: MutableState<Boolean>) {
        if (mine != turn || !up.value) return
        up.value = false
        main.postDelayed({ if (mine == turn) close() }, if (Motion.reduced) 0L else ANSWER_CARD_MOVE_MILLIS.toLong())
    }

    /** Takes the window down at once: a new task, the lock screen, a screenshot, or the service going away. */
    fun close() {
        turn++
        val card = view ?: return
        runCatching { if (card.isAttachedToWindow) service.getSystemService(WindowManager::class.java).removeViewImmediate(card) }
        view = null
        host?.destroy()
        host = null
    }

    private fun statusBar(): Int {
        if (Build.VERSION.SDK_INT >= 30) {
            return service.getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
        }
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else 0
    }
}

/**
 * The card: the look's scrap with Oma's name small in its caption voice and the answer in its handwriting.
 * A tap opens the Oma chat; a flick up or aside puts it away.
 */
@Composable
private fun AnswerCardContent(
    caption: String,
    text: String,
    up: MutableState<Boolean>,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val shown by animateFloatAsState(if (entered && up.value) 1f else 0f, Motion.timed(ANSWER_CARD_MOVE_MILLIS, Heads), label = "answer")
    val scrap = Pack.look.pack.scrap.surface
    val ink = scrap.ink.toColor()
    // The window runs the width of the screen it is on, which is what the card is measured against: the service's
    // own idea of the screen can be the other display's.
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    val u = prototypeUnit(maxWidth.value, LocalConfiguration.current.screenHeightDp.toFloat())
    // As wide as the prototype's: 360 units, or the screen less 10 a side where that is narrower. A short answer doesn't shrink it.
    val across = minOf(360 * u, maxWidth.value - 20 * u)
    // Room around the scrap for its shadow and its lean, inside the window.
    Box(
        Modifier
            .padding(start = (10 * u).dp, end = (10 * u).dp, top = (4 * u).dp, bottom = (14 * u).dp)
            .width(across.dp)
            .graphicsLayer {
                translationY = -(1f - shown) * size.height * 1.5f
                alpha = shown
            },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .surface(scrap, focused = false, tilt = -0.6f)
                .clickable(onClick = onOpen)
                .pointerInput(Unit) {
                    var x = 0f
                    var y = 0f
                    detectDragGestures(
                        onDragStart = {
                            x = 0f
                            y = 0f
                        },
                        onDragEnd = { if (y < -20.dp.toPx() || abs(x) > 60.dp.toPx()) onDismiss() },
                    ) { _, drag ->
                        x += drag.x
                        y += drag.y
                    }
                }.padding(horizontal = (16 * u).dp, vertical = (14 * u).dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy((6 * u).dp),
        ) {
            BasicText(
                Pack.caption(caption).uppercase(),
                style = Pack.mono((10 * u).sp, Pack.scrapMuted).copy(fontWeight = FontWeight.Medium, letterSpacing = 0.12.em),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                text,
                style = Pack.hand((24 * u).sp, ink).copy(fontWeight = FontWeight.Bold, lineHeight = (26 * u).sp),
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    }
}

/** The easing of the prototype's `.heads`. */
private val Heads = CubicBezierEasing(0.2f, 0.9f, 0.2f, 1f)
