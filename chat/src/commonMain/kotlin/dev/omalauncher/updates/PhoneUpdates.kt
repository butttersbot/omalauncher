package dev.omalauncher.updates

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.omalauncher.ui.Pack

/*
 * Updates on the phone, built to the approved cards (Soma Canvas soma-chat/Updates: soma-update-pill-cover/-unfolded,
 * soma-update-sheet-cover/-unfolded, soma-update-states-cover/-unfolded; Boss approved all six, 2026-10-29).
 * Soma itself updates only on his tap (it restarts Soma and stops the agents). Hunch, Soma Notes and Soma Messages
 * update by themselves once they're closed; one that couldn't waits in the sheet with the reason and its own [ update ].
 */

/** What the walks and the design gate said of a build (only gated builds are offered at all). */
data class DesignLine(val match: Int = 0, val unapproved: Int = 0, val unchecked: Int = 0, val checked: Boolean = false) {
    /** The accent line when anything isn't plainly as approved; null when every walked card matched. */
    val warning: String? get() = when {
        !checked -> "design: not checked"
        unapproved > 0 || unchecked > 0 -> listOfNotNull("design: $match match", unapproved.takeIf { it > 0 }?.let { "$it not approved yet" },
            unchecked.takeIf { it > 0 }?.let { "$it not checked" }).joinToString(" · ")
        else -> null
    }
    val good: String? get() = if (checked && unapproved == 0 && unchecked == 0) "design: $match match" else null
}

/** A build that passed the gate and waits for this phone. [failure]: why it didn't update by itself (other apps only). */
data class AppOffer(
    val pkg: String, val name: String, val from: String, val to: String, val versionCode: Long, val built: String,
    val notes: List<String>, val walksPassed: Int, val walksTotal: Int, val design: DesignLine,
    val launcher: Boolean = false, val failure: String? = null, val phoneSteps: List<String> = emptyList(),
)
data class AutoUpdated(val name: String, val from: String, val to: String, val at: String)

sealed interface LauncherState {
    data object Idle : LauncherState
    /** [who]: the agents still at work, by name and what they're on ("Phone B (“Connections walk”)"). */
    data class Waiting(val who: List<String>) : LauncherState
    data class Updating(val step: Int) : LauncherState
    data class Updated(val version: String, val phoneChecks: Int) : LauncherState
    data class Failed(val kept: String, val reason: String, val details: String) : LauncherState
}

val UPDATE_STEPS = listOf("check the signature and version", "stage Android's installer", "Android restarts Soma", "Soma reopens")

/** The state the pill and the sheet draw. The Android side fills it and gives the hands. */
class PhoneUpdates {
    var offers by mutableStateOf<List<AppOffer>>(emptyList())
    var history by mutableStateOf<List<AutoUpdated>>(emptyList())
    var launcher by mutableStateOf<LauncherState>(LauncherState.Idle)
    var open by mutableStateOf(false)
    /** Only ever called from his tap on [ update … ]. */
    var update: (AppOffer) -> Unit = {}
    var cancel: () -> Unit = {}
    /** After he has seen "updated" or a failure, the pill goes. */
    var seen: () -> Unit = {}
    fun close() { open = false; if (launcher is LauncherState.Updated) seen() }
}

/** What waits for his tap: Soma's own update, and any app that couldn't update by itself. */
fun needsTap(offers: List<AppOffer>) = offers.filter { it.launcher || it.failure != null }.sortedBy { !it.launcher }

/** The pill's words and whether it is filled; null when there is nothing for him (no pill at all). */
fun phonePillLabel(u: PhoneUpdates): Pair<String, Boolean>? {
    when (val s = u.launcher) {
        is LauncherState.Waiting -> return "waiting" to false
        is LauncherState.Updating -> return "updating" to false
        is LauncherState.Updated -> return "updated" to false
        is LauncherState.Failed -> return "didn't update" to true
        LauncherState.Idle -> Unit
    }
    val tap = needsTap(u.offers)
    return when {
        tap.isEmpty() -> null
        tap.size == 1 -> "update ${tap[0].name.lowercase()} · ${tap[0].to}" to true
        else -> "updates · ${tap.size}" to true
    }
}

private fun sans(size: TextUnit, color: Color = Pack.foreground, weight: FontWeight? = null) =
    Pack.body(size, color).let { if (weight == null) it else it.copy(fontWeight = weight) }
private fun mono(size: TextUnit, color: Color) = Pack.mono(size, color)
/** The cards' .checks line: the mono caption at 10px/1.5 with .06em tracking (the pack's own is wider). */
private fun checks(color: Color) = Pack.mono(10.sp, color).copy(letterSpacing = 0.06.em, lineHeight = 15.sp)
private fun Modifier.ruleTop() = drawBehind { val w = Pack.hair.toPx(); drawLine(Pack.border, Offset(0f, w / 2), Offset(size.width, w / 2), w) }
private fun Modifier.ruleBottom() = drawBehind { val w = Pack.hair.toPx(); drawLine(Pack.border, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) }

@Composable private fun Words(text: String, style: TextStyle, modifier: Modifier = Modifier, lines: Int = Int.MAX_VALUE) =
    BasicText(text, modifier, style = style, maxLines = lines)
@Composable private fun Action(label: String, color: Color = Pack.accent, size: TextUnit = 15.sp, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp).clickable(onClick = onClick), contentAlignment = Alignment.CenterStart) {
        BasicText("[ $label ]", style = sans(size, color))
    }
}
@Composable private fun Tag(label: String, filled: Boolean) {
    val shape = RoundedCornerShape(999.dp)
    Box(Modifier.then(if (filled) Modifier.background(Pack.accent, shape) else Modifier.border(Pack.hair, Pack.border, shape))
        .padding(horizontal = 10.dp, vertical = 6.dp)) {
        BasicText(Pack.caption(label), style = mono(10.sp, if (filled) Pack.background else Pack.foreground), maxLines = 1, softWrap = false)
    }
}
@Composable private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().border(Pack.hair, Pack.border, Pack.shape()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)

/** The red pill before [ artifacts · N ] (cards soma-update-pill-*). A tap opens what changed. */
@Composable
fun PhoneUpdatePill(u: PhoneUpdates) {
    val (label, filled) = phonePillLabel(u) ?: return
    val shape = RoundedCornerShape(999.dp)
    Box(Modifier.padding(horizontal = 6.dp).heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.then(if (filled) Modifier.background(Pack.accent, shape) else Modifier.border(Pack.hair, Pack.border, shape))
            .clickable { u.open = true }.semantics { contentDescription = "Updates: $label. Open what changed" }
            .padding(horizontal = 13.dp, vertical = 9.dp)) {
            BasicText(Pack.caption(label), style = mono(11.sp, if (filled) Pack.background else Pack.foreground), maxLines = 1, softWrap = false)
        }
    }
    if (u.open) PhoneUpdateSheet(u)
}

/** What changed (cards soma-update-sheet-*): the modal shell, Soma's panel, any app waiting for a tap, the history. */
@Composable
fun PhoneUpdateSheet(u: PhoneUpdates) {
    Dialog(onDismissRequest = u::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Unfolded, the real screen gives the status and nav bars height the card doesn't: a tighter margin keeps the
        // card's gap between the panels and the pinned footer.
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            val wide = maxWidth >= 600.dp + 28.dp
            val tall = maxHeight
            Box(Modifier.padding(if (wide) 6.dp else 14.dp), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = if (wide) 880.dp else 447.dp).fillMaxWidth().heightIn(max = tall)
                    .background(Pack.background, Pack.shape()).border(Pack.hair, Pack.border, Pack.shape())) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).ruleBottom().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(44.dp))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Words(Pack.caption("Soma / updates"), mono(10.sp, Pack.muted))
                            BasicText(Pack.heading("What changed"), style = Pack.display(22.sp).copy(textAlign = TextAlign.Center))
                        }
                        Box(Modifier.size(44.dp).clickable(onClick = u::close).semantics { contentDescription = "Close updates" }, contentAlignment = Alignment.Center) {
                            BasicText("×", style = Pack.body(26.sp))
                        }
                    }
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = if (wide) 20.dp else 16.dp, end = if (wide) 20.dp else 16.dp, top = if (wide) 20.dp else 16.dp, bottom = if (wide) 0.dp else 16.dp)) {
                        val panels = buildList<@Composable (Modifier) -> Unit> {
                            for (offer in needsTap(u.offers)) add { m -> OfferPanel(u, offer, m) }
                            if (u.launcher != LauncherState.Idle && u.offers.none { it.launcher }) add { m -> Panel(m) { LauncherStatePanel(u) } }
                            if (u.history.isNotEmpty()) add { m -> HistoryPanel(u.history, m) }
                        }
                        if (panels.isEmpty()) Words("Everything is up to date.", sans(15.sp, Pack.muted))
                        else if (wide) {
                            // Unfolded: the first panel (Soma's own) on the left, the rest stacked on the right, the
                            // history under the app that waits (Boss, 2026-10-29: everything fits in landscape).
                            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.weight(1f)) { panels.first()(Modifier) }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    panels.drop(1).forEach { it(Modifier) }
                                }
                            }
                        } else panels.forEach { Box(Modifier.padding(bottom = 12.dp)) { it(Modifier) } }
                        if (!wide) SheetFooter(u, Modifier.padding(top = 2.dp))
                    }
                    // Unfolded, the footer stays on screen under the panels (Boss, 2026-10-29: the note and [ later ] visible
                    // without scrolling, as on soma-update-sheet-unfolded); on the cover it ends the scroll as on its card.
                    if (wide) SheetFooter(u, Modifier.padding(start = 20.dp, end = 20.dp))
                }
            }
        }
    }
}

@Composable
private fun SheetFooter(u: PhoneUpdates, modifier: Modifier) {
    Column(modifier.fillMaxWidth().ruleTop().padding(top = 10.dp)) {
        Words("Soma updates only when you tap. An app that couldn’t update by itself waits here.", sans(13.sp, Pack.muted))
        Action("later", Pack.foreground) { u.close() }
    }
}

@Composable
private fun OfferPanel(u: PhoneUpdates, offer: AppOffer, modifier: Modifier = Modifier) {
    Panel(modifier) {
        BasicText(Pack.heading(offer.name), style = Pack.display(19.sp))
        Words(Pack.caption("${offer.from} → ${offer.to} · ${offer.built}"), checks(Pack.foreground))
        if (offer.launcher && u.launcher != LauncherState.Idle) { LauncherStatePanel(u); return@Panel }
        Column(Modifier.padding(start = 4.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (offer.notes.isEmpty()) Words("(the build says nothing about what changed)", sans(14.sp, Pack.muted))
            offer.notes.forEach { Words("•  $it", sans(14.sp).copy(lineHeight = 18.9.sp)) }
        }
        Column {
            val passed = listOfNotNull(if (offer.launcher) "taken and signed for this phone" else "signed for this phone",
                "walks ${offer.walksPassed}/${offer.walksTotal} pass", offer.design.good)
            // The card's small ✓ at the caps' height (the mono face draws its own tall and heavy), inline so a wrapped
            // line starts under it, flush left, as on the cards.
            BasicText(buildAnnotatedString { appendInlineContent("ok", "✓"); append(" " + Pack.caption(passed.joinToString(" · "))) },
                style = checks(Pack.green), inlineContent = mapOf("ok" to InlineTextContent(Placeholder(8.sp, 10.sp, PlaceholderVerticalAlign.TextCenter)) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(top = 1.dp, end = 1.dp)) {
                        val p = androidx.compose.ui.graphics.Path().apply { moveTo(size.width * .1f, size.height * .55f); lineTo(size.width * .4f, size.height * .85f); lineTo(size.width * .92f, size.height * .12f) }
                        drawPath(p, Pack.green, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    }
                }))
            offer.design.warning?.let { Words(Pack.caption(it), checks(Pack.accent)) }
            offer.failure?.let { Words(Pack.caption("didn’t update by itself: $it"), checks(Pack.accent)) }
        }
        if (offer.launcher) Words("Soma closes for about 20 seconds and reopens. Linux, logins and settings stay.", sans(13.sp, Pack.muted).copy(lineHeight = 18.2.sp))
        Action(if (offer.launcher) "update and restart Soma" else "update ${offer.name}") { u.update(offer) }
    }
}

/** Soma's own update as it goes (cards soma-update-states-*): waiting, updating, updated, didn't update. */
@Composable
private fun ColumnScope.LauncherStatePanel(u: PhoneUpdates) {
    when (val s = u.launcher) {
        LauncherState.Idle -> Unit
        is LauncherState.Waiting -> {
            Tag("waiting", false)
            val names = s.who.joinToString(" and ")
            Words("Waits for $names to finish. Soma restarts only when no agent on the phone is working.", sans(14.sp))
            Action("cancel", Pack.foreground) { u.cancel() }
        }
        is LauncherState.Updating -> {
            Tag("updating", false)
            Column(Modifier.fillMaxWidth().ruleTop()) {
                UPDATE_STEPS.forEachIndexed { i, step ->
                    val (word, color) = when { i < s.step -> "done" to Pack.green; i == s.step -> "now" to Pack.foreground; else -> "next" to Pack.muted }
                    Row(Modifier.fillMaxWidth().ruleBottom().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Words(step, sans(14.sp, weight = if (i == s.step) FontWeight.SemiBold else null), Modifier.weight(1f))
                        Words(word, sans(if (word == "next") 12.sp else 14.sp, color, if (i == s.step) FontWeight.SemiBold else null), lines = 1)
                    }
                }
            }
            Words("Linux, logins and settings stay.", sans(13.sp, Pack.muted))
        }
        is LauncherState.Updated -> {
            Tag("updated", false)
            Words("Soma ${s.version} is running." + if (s.phoneChecks > 0) " ${s.phoneChecks} phone check${if (s.phoneChecks == 1) "" else "s"} wait for us; the agents will walk them and tell you." else "", sans(14.sp))
        }
        is LauncherState.Failed -> {
            Tag("didn't update", true)
            Words("Kept ${s.kept}: ${s.reason}", sans(14.sp, Pack.accent))
            val clipboard = LocalClipboardManager.current
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                u.offers.firstOrNull { it.launcher }?.let { offer -> Action("try again") { u.update(offer) } }
                Action("copy details for an agent", Pack.foreground) { clipboard.setText(AnnotatedString(s.details)) }
            }
        }
    }
}

@Composable
private fun HistoryPanel(history: List<AutoUpdated>, modifier: Modifier = Modifier) {
    Panel(modifier) {
        BasicText(Pack.heading("Updated by themselves"), style = Pack.display(19.sp, Pack.muted))
        Column(Modifier.fillMaxWidth().ruleTop()) {
            history.forEach { h ->
                Row(Modifier.fillMaxWidth().ruleBottom().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Words("${h.name} ${h.from} → ${h.to}", sans(14.sp, Pack.muted), Modifier.weight(1f))
                    Words(h.at, sans(14.sp, Pack.green), lines = 1)
                }
            }
        }
        Words("Hunch, Soma Notes and Soma Messages update on their own once they’re closed, after the same checks. Only Soma waits for your tap.", sans(13.sp, Pack.muted))
    }
}
