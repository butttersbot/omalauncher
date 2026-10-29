package dev.omalauncher.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.ui.BackHandler
import dev.omalauncher.ui.BracketAction
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.SettingRow
import dev.omalauncher.ui.SettingsList
import kotlin.concurrent.thread

/*
 * Desktop Soma's settings (Boss, 2026-10-29: "there's actually no settings button on the desktop apps"): the phone's
 * settings hub where it applies, in the shared rows (SettingsList) and the active look. Opened from the tray's Settings…,
 * the Mac's Soma > Settings… (Cmd+,), Ctrl+, in a window on Linux, and `soma --settings` (a Hyprland keybind, Walker).
 */

/** What desktop Soma keeps for its settings, in settings.json beside the look. */
object DesktopSettings {
    private const val ENTER_SENDS = "enterSends"

    /** Whether Enter sends in the composer (Shift+Enter a new line); on unless he turned it off. */
    var enterSends by mutableStateOf(SomaHome.read(SomaHome.settings).optBoolean(ENTER_SENDS, true))
        private set

    fun enterSends(on: Boolean) {
        enterSends = on
        SomaHome.write(SomaHome.settings, SomaHome.read(SomaHome.settings).put(ENTER_SENDS, on))
    }
}

/** Where the settings window is: its rows, the looks, or the computers. */
internal enum class SettingsPage { Rows, Looks, Computers }

@Composable
fun SomaSettings(onClose: () -> Unit) {
    var page by remember { mutableStateOf(SettingsPage.Rows) }
    var atLogin by remember { mutableStateOf(Login.enabled()) }
    var said by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != SettingsPage.Rows) { page = SettingsPage.Rows }
    Box(Modifier.fillMaxSize().background(Pack.background), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    when (page) { SettingsPage.Rows -> "settings"; SettingsPage.Looks -> "look"; SettingsPage.Computers -> "computers" },
                    Modifier.weight(1f), style = Pack.mono(Pack.size.title),
                )
                if (page != SettingsPage.Rows) BracketAction("back", Pack.accent) { page = SettingsPage.Rows }
                else BracketAction("close", Pack.accent, onClose)
            }
            when (page) {
                SettingsPage.Rows -> {
                    val machines = RunnerKeys.known
                    SettingsList(listOf(
                        SettingRow("look", Pack.look.pack.name.lowercase()) { page = SettingsPage.Looks },
                        SettingRow("start at login", if (atLogin) "on · Soma opens when you log in" else "off · open Soma yourself") {
                            said = if (atLogin) Login.remove() else Login.install()
                            atLogin = Login.enabled()
                        },
                        SettingRow("computers", if (machines.isEmpty()) "none yet · pair a computer or the phone" else machines.joinToString(" · ") { it.name }.lowercase()) { page = SettingsPage.Computers },
                        SettingRow("enter sends", if (DesktopSettings.enterSends) "on · Enter sends, Shift+Enter starts a new line" else "off · Enter starts a new line, Ctrl+Enter sends") {
                            DesktopSettings.enterSends(!DesktopSettings.enterSends)
                        },
                    ))
                    said?.let { BasicText(it, Modifier.padding(top = 12.dp), style = Pack.mono(Pack.size.small, Pack.muted)) }
                }
                SettingsPage.Looks -> LooksPage()
                SettingsPage.Computers -> ComputersPage()
            }
        }
    }
}

/** Every look, each its four colors and its name; a click uses it, in every Soma window at once. */
@Composable
private fun LooksPage() {
    val now = dev.omalauncher.ui.ActivePack.pick.id
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (look in Looks.swatches) {
            val on = look.id == now
            Row(
                Modifier.fillMaxWidth().background(Pack.surface, Pack.shape(12.dp))
                    .then(if (on) Modifier.border(2.dp, Pack.accent, Pack.shape(12.dp)) else Modifier)
                    .clickable { Looks.pick(look.id) }.padding(14.dp).semantics { contentDescription = "look ${look.name}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.padding(end = 14.dp)) { for (c in look.colors) Box(Modifier.size(18.dp).background(c).border(1.dp, Pack.border)) }
                BasicText(look.name.lowercase(), Modifier.weight(1f), style = Pack.mono(Pack.size.item, if (on) Pack.accent else Pack.foreground))
                if (on) BasicText("in use", style = Pack.mono(Pack.size.small, Pack.muted))
            }
        }
    }
}

/** The computers whose runners this Soma reaches, how to pair another (a computer, or the phone's sessions), and the roads' check. */
@Composable
private fun ComputersPage() {
    var me by remember { mutableStateOf<String?>(null) }
    var check by remember { mutableStateOf<List<String>?>(null) }
    var checking by remember { mutableStateOf(false) }
    remember { thread(name = "soma-settings-self", isDaemon = true) { me = Mesh.selfName() } }
    val name = me ?: "this-computer"
    val muted = Pack.mono(Pack.size.small, Pack.muted)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val machines = RunnerKeys.known
        if (machines.isEmpty()) BasicText("No computer is paired with this Soma yet.", style = muted)
        for (m in machines) {
            Column(Modifier.fillMaxWidth().background(Pack.surface, Pack.shape(12.dp)).padding(14.dp).semantics { contentDescription = "computer ${m.name}" }) {
                BasicText(m.name, style = Pack.mono(Pack.size.item))
                BasicText(m.config.url, style = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        BasicText("pair another", Modifier.padding(top = 12.dp), style = Pack.mono(Pack.size.item))
        BasicText("A computer: on it, run  OMA_RUNNER_PAIR_COMPUTERS=1 oma-runner pair $name  and move the key it Taildrops here (to ~/Inbox, or ~/Downloads) into ~/.config/soma/runners/. Pair this computer on it the same way, so the road goes both ways.", style = muted)
        BasicText("The phone's sessions: on the phone, Settings › phone sessions › pair $name.", style = muted)
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BracketAction(if (checking) "checking…" else "check the roads", Pack.accent) {
                if (checking) return@BracketAction
                checking = true
                val lines = mutableListOf<String>()
                thread(name = "soma-settings-mesh", isDaemon = true) {
                    runCatching { Mesh.run { lines += it } }.onFailure { lines += "The check couldn't run: ${it.message}" }
                    check = lines.toList(); checking = false
                }
            }
        }
        check?.let { lines ->
            BasicText(lines.joinToString("\n"), Modifier.fillMaxWidth().background(Pack.surface, Pack.shape(12.dp)).padding(14.dp).semantics { contentDescription = "roads" },
                style = Pack.mono(Pack.size.small))
        }
        Box(Modifier.height(8.dp))
    }
}
