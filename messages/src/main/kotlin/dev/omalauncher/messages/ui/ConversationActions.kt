package dev.omalauncher.messages.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.omalauncher.messages.engine.*
import dev.omalauncher.ui.Motion
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.composerShape
import kotlin.math.roundToInt

@Composable internal fun ConversationActionsRow(c: Conversation, selected: Boolean, draft: String, choices: LocalChatChoices, onOpen: () -> Unit, revealed: String?, onReveal: (String?) -> Unit, modifier: Modifier = Modifier) {
    var menu by remember(c.id) { mutableStateOf(false) }
    var pull by remember(c.id) { mutableFloatStateOf(0f) }
    var dragging by remember(c.id) { mutableStateOf(false) }
    val x by animateFloatAsState(pull, if (dragging || Motion.reduced) snap() else Motion.spatial, label = "conversation actions")
    val unit = with(LocalDensity.current) { 72.dp.toPx() }
    LaunchedEffect(revealed) { if (revealed != c.id && !dragging) pull = 0f }
    val read: () -> Unit = { Messages.setUnread(c.id, c.unread == 0); pull = 0f; onReveal(null) }
    val pin: () -> Unit = { ChatChoices.update { it.pin(c.id, c.id !in it.pinned) }; pull = 0f; onReveal(null) }
    val mute: () -> Unit = { ChatChoices.update { it.mute(c.id, c.id !in it.muted) }; pull = 0f; onReveal(null) }
    val currentRead by rememberUpdatedState(read)
    Box(modifier.fillMaxWidth().clipToBounds().pointerInput(c.id, unit) {
        detectHorizontalDragGestures(onDragStart = { dragging = true; onReveal(null) }, onDragCancel = { dragging = false; pull = 0f; onReveal(null) },
            onDragEnd = {
                dragging = false
                if (pull >= unit * 1.2f) currentRead() else {
                    val opened = pull < -unit * 0.5f
                    pull = if (opened) -unit * 2 else 0f
                    onReveal(if (opened) c.id else null)
                }
            }) { change, dx -> change.consume(); pull = (pull + dx).coerceIn(-unit * 2, unit * 1.5f) }
    }) {
        Row(Modifier.matchParentSize().background(Pack.surface), verticalAlignment = Alignment.CenterVertically) {
            if (x > 0f) ActionLabel(if (c.unread == 0) "Mark unread" else "Mark read", 108, !Messages.changingRead(c.id), read, "${if (c.unread == 0) "Mark unread" else "Mark read"} conversation ${c.title}")
            Spacer(Modifier.weight(1f))
            if (x < 0f) {
                ActionLabel(if (c.id in choices.pinned) "Unpin" else "Pin", 72, c.id in choices.pinned || choices.pinned.size < 9, pin, "${if (c.id in choices.pinned) "Unpin" else "Pin"} conversation ${c.title}")
                ActionLabel(if (c.id in choices.muted) "Unmute" else "Mute", 72, true, mute, "${if (c.id in choices.muted) "Unmute" else "Mute"} conversation ${c.title}")
            }
        }
        ConversationRow(c, selected, draft, { pull = 0f; onReveal(null); onOpen() }, { onReveal(null); menu = true }, choices,
            Modifier.offset { IntOffset(x.roundToInt(), 0) }.background(Pack.paper).semantics {
                customActions = listOf(
                    CustomAccessibilityAction(if (c.unread == 0) "Mark unread" else "Mark read") { read(); true },
                    CustomAccessibilityAction(if (c.id in choices.pinned) "Unpin" else "Pin") { pin(); true },
                    CustomAccessibilityAction(if (c.id in choices.muted) "Unmute" else "Mute") { mute(); true },
                )
            })
    }
    if (menu) ChatActionsDialog(c, choices) { menu = false; pull = 0f; onReveal(null) }
}

@Composable private fun ActionLabel(label: String, width: Int, enabled: Boolean, action: () -> Unit, description: String = label) {
    Box(Modifier.width(width.dp).fillMaxHeight().semantics { role = Role.Button; contentDescription = description }.clickable(enabled = enabled, onClick = action), contentAlignment = Alignment.Center) {
        BasicText(label, style = Pack.body(Pack.size.body, if (enabled) Pack.foreground else Pack.muted))
    }
}

/** Same actions for touch/keyboard/accessibility and the unfolded rail; phone-local settings only. */
@Composable internal fun ChatActionsDialog(c: Conversation, choices: LocalChatChoices, onDismiss: () -> Unit) {
    MessageOverlay(onDismiss) {
        Column(Modifier.widthIn(max = 360.dp).fillMaxWidth().background(Pack.background, composerShape(14.dp)).border(2.dp, Pack.foreground, composerShape(14.dp)).padding(12.dp)) {
            BasicText(c.title, Modifier.padding(12.dp), style = Pack.body(Pack.size.item))
            fun act(action: () -> Unit): () -> Unit = { action(); onDismiss() }
            val canPin = c.id in choices.pinned || choices.pinned.size < 9
            val actions = listOf(
                Triple(if (c.unread == 0) "Mark unread" else "Mark read", !Messages.changingRead(c.id), act { Messages.setUnread(c.id, c.unread == 0) }),
                Triple(if (c.id in choices.pinned) "Unpin" else if (canPin) "Pin" else "Pin (9 already pinned)", canPin, act { ChatChoices.update { it.pin(c.id, c.id !in it.pinned) } }),
                Triple(if (c.id in choices.muted) "Unmute" else "Mute", true, act { ChatChoices.update { it.mute(c.id, c.id !in it.muted) } }),
            )
            actions.forEach { (label, enabled, action) ->
                BasicText(label, Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { role = Role.Button }.clickable(enabled = enabled, onClick = action).padding(12.dp),
                    style = Pack.body(Pack.size.item, if (enabled) Pack.foreground else Pack.muted))
            }
            BasicText("Cancel", Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { role = Role.Button }.clickable(onClick = onDismiss).padding(12.dp), style = Pack.body(Pack.size.body, Pack.muted))
        }
    }
}
