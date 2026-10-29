package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** One row of Soma's settings: what it is ([title]), how it stands now ([subtitle]), and what a click on it does. */
data class SettingRow(val title: String, val subtitle: String, val onClick: () -> Unit)

/**
 * Soma's settings as rows, in the active look: each its name, how it stands, and a chevron, as the phone's settings
 * hub draws them (SettingsScreen). Shared, so the desktop's settings window (desktop Soma, 2026-10-29) is the phone's.
 */
@Composable
fun SettingsList(rows: List<SettingRow>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in rows) SettingsItem(row)
    }
}

@Composable
private fun SettingsItem(row: SettingRow) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Pack.shape(12.dp))
            .background(Pack.surface)
            .clickable(onClick = row.onClick)
            .padding(14.dp)
            .semantics { contentDescription = "setting ${row.title}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(row.title, style = Pack.mono(Pack.size.item))
            BasicText(row.subtitle, style = Pack.mono(Pack.size.small, Pack.muted))
        }
        Symbol(Glyph.ChevronRight, Pack.accent, fontSize = Pack.size.title)
    }
}
