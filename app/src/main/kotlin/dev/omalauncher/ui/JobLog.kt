package dev.omalauncher.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.omalauncher.linux.JobState

/** The last lines a guest job printed, red once it failed; nothing while idle. */
@Composable
fun JobLog(
    state: JobState,
    lines: Int = 4,
) {
    val log =
        when (state) {
            is JobState.Working -> state.log
            is JobState.Failed -> listOf("failed:") + state.log
            JobState.Idle -> return
        }
    if (log.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    val style = Pack.body(Pack.size.small, if (state is JobState.Failed) Pack.red else Pack.muted)
    // A failure's reason is usually at the end of the line: let it wrap.
    for (line in log.takeLast(lines)) BasicText(line, style = style, maxLines = if (state is JobState.Failed) 3 else 1)
}
