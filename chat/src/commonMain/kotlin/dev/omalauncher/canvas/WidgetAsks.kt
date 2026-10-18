package dev.omalauncher.canvas

import dev.omalauncher.widgets.runtime.CommandAsk
import dev.omalauncher.widgets.runtime.CommandGate
import dev.omalauncher.widgets.runtime.FileAsk

/*
 * What a widget waits for his OK on, in Needs you (Soma Canvas slice 7b): a widget file that arrived from elsewhere, and a
 * command a widget wants to run on this machine. Approved or denied from the module like any other ask, wherever he put
 * it; the answer is this machine's, written by [CommandGate] alone.
 */

/** [source], with what waits on [gate] among its asks. */
fun withWidgetAsks(source: ModuleSource, gate: CommandGate): ModuleSource = object : ModuleSource by source {
    override fun asks(): List<ModuleAsk> {
        gate.changes.intValue
        return source.asks() + gate.fileAsks().map { a ->
            ModuleAsk("widget-file:${a.widget}", "A widget arrived: ${a.widget}", "Let it load on ${gate.machine}? Its commands are asked for one by one.", "", gate.machine, a)
        } + gate.commandAsks().map { a ->
            ModuleAsk("widget-cmd:${a.widget}:${a.hash}", "${a.widget} wants to run", a.command, "", gate.machine, a, wholeDetail = true)
        }
    }

    override fun answer(ask: ModuleAsk, allow: Boolean, said: (String?) -> Unit) {
        when (val ref = ask.ref) {
            is FileAsk -> { if (allow) gate.approveFile(ref.widget, ref.fileHash); said(null) }
            is CommandAsk -> { gate.answer(ref, allow); said(null) }
            else -> source.answer(ask, allow, said)
        }
    }
}
