package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import dev.omalauncher.widgets.WidgetEngine
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.widgets.WidgetSpec
import dev.omalauncher.widgets.lang.text
import dev.omalauncher.widgets.render.WidgetContent
import dev.omalauncher.widgets.render.WidgetScreen
import androidx.compose.foundation.layout.fillMaxWidth
import dev.omalauncher.widgets.runtime.WidgetInstance

@Composable
internal fun WidgetTile(
    spec: WidgetSpec,
    instance: WidgetInstance?,
    cells: Pair<Int, Int>?,
    focused: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
    needs: List<String>,
    install: WidgetEngine.Install?,
    onInstall: () -> Unit,
    onFix: (String) -> Unit,
    /** Where Linux stands, if the widget needs it and it isn't running: a placeholder keeps its cells. */
    waitsForLinux: LinuxState? = null,
    onInstallLinux: () -> Unit = {},
) {
    Box(
        modifier
            .scrap(focused, spec.file.name, dressed = true)
            .pointerInput(spec) { detectTapGestures(onTap = { onTap() }) }
            // After the pointer input: a tap on the pack's title bar is still a tap on the tile.
            .scrapContent(),
    ) {
        if (waitsForLinux != null) {
            // While an installed Linux starts up the tile stays empty; it only offers an install when there's none.
            if (waitsForLinux != LinuxState.Checking && waitsForLinux != LinuxState.Installed) {
                LinuxPlaceholder(spec.name, installing = waitsForLinux is LinuxState.Installing, onInstallLinux)
            }
        } else if (instance != null && needs.isNotEmpty()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(spec.name, style = Pack.body(color = Pack.scrapMuted))
                BasicText("needs ${needs.joinToString(", ")}", style = Pack.body(color = Pack.scrapInk))
                when (install) {
                    WidgetEngine.Install.Running -> BasicText("installing…", style = Pack.body(color = Pack.accent))
                    is WidgetEngine.Install.Failed -> {
                        BasicText(install.log.lines().lastOrNull { it.isNotBlank() }.orEmpty(), style = Pack.body(Pack.size.small, Pack.red), maxLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TileButton("retry", Pack.accent, onInstall)
                            TileButton("fix with agent", Pack.scrapMuted) { onFix(install.log) }
                        }
                    }
                    null -> TileButton("install", Pack.accent, onInstall)
                }
            }
        } else if (instance == null) {
            Column(Modifier.padding(12.dp)) {
                BasicText(spec.file.name, style = Pack.body(color = Pack.red))
                BasicText(spec.error.orEmpty(), style = Pack.body(Pack.size.small, Pack.scrapMuted))
            }
        } else {
            WidgetContent(instance, Modifier.fillMaxSize(), cells)
        }
    }
}

/** A widget that runs Linux commands, before Linux is installed: its name and an offer to install. */
@Composable
private fun LinuxPlaceholder(
    name: String,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(name, style = Pack.body(color = Pack.scrapMuted), maxLines = 1)
        Spacer(Modifier.weight(1f))
        BasicText("needs Linux", style = Pack.body(color = Pack.accent))
        BasicText(
            "runs commands in the optional Arch Linux",
            style = Pack.body(Pack.size.small, Pack.scrapMuted),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TileButton(if (installing) "installing…" else "install", Pack.accent, onInstall)
    }
}

@Composable
private fun TileButton(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    BasicText(
        label,
        Modifier
            .framed(Pack.hair, color, radius = 8.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = Pack.body(color = color),
    )
}

/** A widget's screen { }, full screen over home in an Omarchy window titled by its `title` or the widget's name. */
@Composable
internal fun WidgetScreenPanel(
    spec: WidgetSpec,
    instance: WidgetInstance,
    onDismiss: () -> Unit,
) {
    val title = instance.program.screenTitle?.let { instance.eval(it, instance.root, "title").text() } ?: spec.name
    OverlayPanel(title, onDismiss) {
        WidgetScreen(instance, Modifier.weight(1f).fillMaxWidth())
    }
}
