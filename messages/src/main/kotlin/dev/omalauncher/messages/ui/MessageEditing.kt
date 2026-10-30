package dev.omalauncher.messages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.omalauncher.messages.engine.*
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.composerShape
import kotlinx.coroutines.delay

@Stable internal class MessageEditDraft(val original: Message) {
    var text by mutableStateOf(original.text)
    var submitted by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/** Edit the selected bubble's words; the conversation's unsent composer draft is independent. */
@Composable internal fun InlineMessageEdit(draft: MessageEditDraft, current: Message?, pending: PendingMessageChange?,
                                          onSave: (String) -> Unit, onCancel: () -> Unit, onReload: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(draft) { focus.requestFocus(); keyboard?.show() }
    LaunchedEffect(draft) { while (true) { delay(500); now = System.currentTimeMillis() } }
    LaunchedEffect(current, draft.submitted, pending) {
        if (draft.submitted && pending == null && current?.edited == true && current.text == draft.text.trim()) onCancel()
    }
    val available = current != null && !current.unsent
    val canSave = available && current!!.changes.canEdit(now) && pending == null &&
        draft.text.isNotBlank() && draft.text.trim() != current.text
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.End) {
        Column(Modifier.widthIn(min = 220.dp, max = 300.dp).background(Pack.foreground, composerShape(20.dp)).padding(horizontal = 13.dp, vertical = 8.dp)) {
            BasicTextField(draft.text, { draft.text = it }, Modifier.fillMaxWidth().focusRequester(focus)
                .semantics { contentDescription = "Edit message" }, enabled = pending == null, maxLines = 5,
                textStyle = Pack.body(Pack.size.item, Pack.background), cursorBrush = SolidColor(Pack.background))
        }
        if (!available) BasicText("Message unavailable. Your edit is still here.", Modifier.padding(top = 6.dp), style = Pack.body(Pack.size.small, Pack.accent))
        else if (!current!!.changes.canEdit(now) && pending == null) BasicText("This message can no longer be edited.", Modifier.padding(top = 6.dp), style = Pack.body(Pack.size.small, Pack.muted))
        draft.error?.let { BasicText(it, Modifier.padding(top = 6.dp), style = Pack.body(Pack.size.small, Pack.accent)) }
        if (pending != null) BasicText(if (pending.accepted) "Edit requested · waiting for update" else "Saving…", Modifier.padding(top = 6.dp), style = Pack.body(Pack.size.small, Pack.muted))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(if (pending == null) "Cancel edit" else "Close edit", filled = false, onClick = onCancel)
            if (pending?.accepted == true) TextButton("Reload message", filled = false, onClick = onReload)
            else TextButton("Save edit", filled = true, enabled = canSave) { onSave(draft.text.trim()) }
        }
    }
}

@Composable internal fun MessageEditHistory(message: Message, onDismiss: () -> Unit) {
    MessageOverlay(onDismiss, dismissOnOutside = false) {
        Column(Modifier.widthIn(max = 360.dp).fillMaxWidth().heightIn(max = 560.dp)
            .background(Pack.background, composerShape(14.dp)).border(2.dp, Pack.foreground, composerShape(14.dp))
            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText("Edit history", style = Pack.body(Pack.size.item))
            if (!message.editHistoryComplete) BasicText("Some earlier versions are unavailable.", style = Pack.body(Pack.size.small, Pack.muted))
            message.editHistory.forEachIndexed { i, version ->
                BasicText(Pack.caption(if (message.editHistoryComplete) "Version ${i + 1}" else "Earlier version"), style = Pack.mono(Pack.size.small, Pack.muted))
                BasicText(if (version.available) version.text.orEmpty() else "Earlier text unavailable", style = Pack.body(Pack.size.body))
            }
            if (message.editHistory.isEmpty()) BasicText("No earlier versions are available from your Mac.", style = Pack.body(Pack.size.body, Pack.muted))
            BasicText(Pack.caption("Current"), style = Pack.mono(Pack.size.small, Pack.muted))
            BasicText(message.text, style = Pack.body(Pack.size.body))
            TextButton("Done", filled = false, onClick = onDismiss)
        }
    }
}
