package dev.omalauncher.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.omalauncher.ui.BackHandler
import dev.omalauncher.ui.BracketAction
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.framed

/*
 * The update pill and its sheet, built to the approved cards (Soma Canvas soma-desktop/Updates: soma-desktop-update-pill,
 * -sheet, -states). The pill sits in the top bar before [ artifacts ]; up to date, there is no pill at all.
 */

/** The pill's words for [state], and whether it is filled (an offer, or a failure to read) or outlined (in progress, done). */
internal fun pillLabel(state: UpdateState): Pair<String, Boolean>? = when (state) {
    UpdateState.None -> null
    is UpdateState.Offer -> "update soma · ${state.release.commit}" to true
    is UpdateState.Waiting -> "waiting" to false
    is UpdateState.Updating -> "updating" to false
    is UpdateState.Updated -> "updated" to false
    is UpdateState.Failed -> "didn't update" to true
}

/**
 * The pill; a tap opens the sheet just under it ([withSheet]: a popup anchored to the pill, closed by a click outside it
 * or Escape). Tests lay the sheet out themselves.
 */
@Composable
fun UpdatePill(updater: Updater, withSheet: Boolean = true) {
    val (label, filled) = pillLabel(updater.state) ?: return
    val shape = RoundedCornerShape(999.dp)
    Box {
        Box(
            Modifier.padding(horizontal = 6.dp)
                .then(if (filled) Modifier.background(Pack.accent, shape) else Modifier.border(Pack.hair, Pack.border, shape))
                .clickable { if (updater.open) updater.close() else updater.open = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            BasicText(Pack.caption(label), style = Pack.mono(Pack.size.small, if (filled) Pack.background else Pack.foreground), maxLines = 1, softWrap = false)
        }
        if (withSheet && updater.open) {
            Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, with(LocalDensity.current) { 34.dp.roundToPx() }), onDismissRequest = updater::close, properties = PopupProperties(focusable = true)) {
                UpdateSheet(updater, Modifier.widthIn(min = 320.dp))
            }
        }
    }
}

/** The sheet the pill opens: what changed, then how the update goes. Shown over the window's top-right corner. */
@Composable
fun UpdateSheet(updater: Updater, modifier: Modifier = Modifier) {
    if (!updater.open) return
    val state = updater.state
    if (state == UpdateState.None) return
    BackHandler { updater.close() }
    Column(
        modifier.widthIn(max = 460.dp).framed(Pack.hair, Pack.border, Pack.surface).padding(14.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (state) {
            is UpdateState.Offer -> {
                val r = state.release
                BasicText("Update Soma", style = Pack.display(Pack.size.title, Pack.accent))
                Caption("${updater.own.commit} → ${r.commit} · ${whenCommitted(r.committed)}")
                Body("This computer: the app and its runner, together.")
                Column(Modifier.padding(start = 6.dp, top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (r.notes.isEmpty()) Body("(the build says nothing about what changed)", Pack.muted)
                    r.notes.forEach { Body("·  $it") }
                }
                Body("Checked: built from a commit whose tests passed; the download's sha256 must match.", Pack.muted)
                Actions {
                    BracketAction("update and restart", Pack.accent) { updater.start(r) }
                    BracketAction("later", Pack.muted) { updater.close() }
                }
            }
            is UpdateState.Waiting -> {
                StatePill("waiting", false)
                val names = state.chats.joinToString(", ") { "“$it”" }
                Body("Waits for $names to finish ${if (state.chats.size == 1) "its turn" else "their turns"}: the runner restarts only when no chat is working on this computer.")
                Actions { BracketAction("cancel", Pack.muted) { updater.cancel() } }
            }
            is UpdateState.Updating -> {
                StatePill("updating", false)
                UPDATE_STEPS.forEachIndexed { i, step ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Body(step, modifier = Modifier.weight(1f, fill = false))
                        val (word, color) = when {
                            i < state.step -> "done" to Pack.green
                            i == state.step -> "now" to Pack.foreground
                            else -> "next" to Pack.muted
                        }
                        BasicText(Pack.caption(word), style = Pack.mono(Pack.size.small, color), maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            is UpdateState.Updated -> {
                StatePill("updated", false)
                Body("Soma ${state.commit} is running, app and runner alike. The pill is gone until the next build.")
            }
            is UpdateState.Failed -> {
                StatePill("didn't update", true)
                Body("Kept ${state.kept}: ${state.reason}")
                val clipboard = LocalClipboardManager.current
                Actions {
                    if (state.release != null) BracketAction("try again", Pack.accent) { updater.start(state.release) }
                    BracketAction("copy details for an agent", Pack.muted) { clipboard.setText(AnnotatedString(state.details)) }
                }
            }
            UpdateState.None -> Unit
        }
    }
}

@Composable
private fun StatePill(label: String, filled: Boolean) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier.padding(bottom = 4.dp)
            .then(if (filled) Modifier.background(Pack.accent, shape) else Modifier.border(Pack.hair, Pack.border, shape))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        BasicText(Pack.caption(label), style = Pack.mono(Pack.size.small, if (filled) Pack.background else Pack.foreground), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Caption(text: String) = BasicText(Pack.caption(text), style = Pack.mono(Pack.size.small, Pack.muted), overflow = TextOverflow.Ellipsis)

@Composable
private fun Body(text: String, color: Color = Pack.foreground, modifier: Modifier = Modifier) = BasicText(text, modifier, style = Pack.body(color = color))

/** The sheet's [ actions ], wrapping onto a second line rather than squeezing one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(content: @Composable () -> Unit) = FlowRow(Modifier.padding(top = 6.dp)) { content() }
