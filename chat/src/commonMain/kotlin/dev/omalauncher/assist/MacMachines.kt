package dev.omalauncher.assist

import org.json.JSONObject
import java.io.File

/*
 * More than one Mac (Boss, 2026-10-16: a second runner on the MacBook Pro beside the Mac Pro's). Each Mac has a
 * runner of its own with its own address and key, in a file of its own in the guest's ~/.config/omalauncher:
 * `mac-runner.json` is the first Mac, the Mac Pro, exactly as it always was, and `mac-runner-<id>.json` is any
 * other, `<id>` being a short name such as `macbook-pro`. A runner's thread ids are "t" and hex digits, so the
 * phone knows a thread of another Mac as `<id>/<runner's id>` and one of the first Mac by the runner's id alone:
 * nothing kept from before there were two Macs changes. The id is split again only where a request goes out.
 */

/** The first Mac's id: empty, so its threads keep the ids they always had. */
const val MAC_FIRST = ""

/** Between another Mac's id and its runner's thread id in the phone's id of a thread. A runner's ids never hold it. */
const val MAC_ID_SPLIT = '/'

/** What the first Mac is called when its file gives no name. */
const val MAC_FIRST_NAME = "Mac Pro"

private val MAC_ID = Regex("[a-z0-9][a-z0-9-]{0,31}")

/** The file another Mac's runner is described in. */
fun macRunnerFile(machine: String) = if (machine == MAC_FIRST) MAC_RUNNER_CONFIG else "mac-runner-$machine.json"

/** The Mac a file describes: [MAC_FIRST] for `mac-runner.json`, the id for `mac-runner-<id>.json`, null for any other file. */
fun macMachineOfFile(name: String): String? = when {
    name == MAC_RUNNER_CONFIG -> MAC_FIRST
    name.startsWith("mac-runner-") && name.endsWith(".json") -> name.removePrefix("mac-runner-").removeSuffix(".json").takeIf { MAC_ID.matches(it) }
    else -> null
}

/** One Mac: its [id] ([MAC_FIRST] for the first), what he calls it, and where its runner is. [config]'s key never prints. */
class MacMachine(val id: String, val name: String, val config: MacRunnerConfig) {
    override fun equals(other: Any?) = other is MacMachine && other.id == id && other.name == name && other.config == config

    override fun hashCode() = 31 * (31 * id.hashCode() + name.hashCode()) + config.hashCode()

    override fun toString() = "MacMachine(id=$id, name=$name, url=${config.url})"
}

/**
 * What a Mac is called from its id when its file gives no name: the first is [MAC_FIRST_NAME]; `macbook-pro` is
 * "MacBook Pro", `imac` "iMac", `mac-mini` "Mac mini", anything else its words with capitals.
 */
fun macMachineName(id: String): String {
    if (id == MAC_FIRST) return MAC_FIRST_NAME
    return id.split('-').filter { it.isNotEmpty() }.joinToString(" ") { word ->
        when (word) {
            "macbook" -> "MacBook"
            "imac" -> "iMac"
            "mac" -> "Mac"
            "mini" -> "mini"
            else -> word.replaceFirstChar { it.uppercase() }
        }
    }.ifEmpty { id }
}

/** A Mac from its file's text, or null when the file says no runner (see [parseMacRunnerConfig]). A `name` in it is what he is shown. */
fun parseMacMachine(id: String, text: String?): MacMachine? {
    val config = parseMacRunnerConfig(text) ?: return null
    val named = runCatching { JSONObject(text.orEmpty()).optString("name") }.getOrNull().orEmpty().trim().take(MAC_NAME_CHARS)
    return MacMachine(id, named.ifEmpty { macMachineName(id) }, config)
}

private const val MAC_NAME_CHARS = 32

/** Every Mac set up in the guest's launcher folder: the first one first, then the others by id. None is no Mac. */
fun readMacMachines(launcherConfig: File): List<MacMachine> {
    val files = runCatching { launcherConfig.listFiles() }.getOrNull().orEmpty()
    return files.mapNotNull { file ->
        val id = macMachineOfFile(file.name) ?: return@mapNotNull null
        val text = runCatching { if (file.isFile && file.length() <= 16_384) file.readText() else null }.getOrNull()
        parseMacMachine(id, text)
    }.sortedWith(compareBy<MacMachine> { it.id != MAC_FIRST }.thenBy { it.id })
}

/** The phone's id for the runner's thread [runnerId] on the Mac [machine]. */
fun macPhoneId(machine: String, runnerId: String) = if (machine == MAC_FIRST) runnerId else "$machine$MAC_ID_SPLIT$runnerId"

/** The Mac the thread [phoneId] is on. */
fun macMachineOf(phoneId: String): String = if (MAC_ID_SPLIT in phoneId) phoneId.substringBefore(MAC_ID_SPLIT) else MAC_FIRST

/** The id the runner of its own Mac knows the thread [phoneId] by. */
fun macRunnerId(phoneId: String): String = if (MAC_ID_SPLIT in phoneId) phoneId.substringAfter(MAC_ID_SPLIT) else phoneId

/** [thread], as the runner of [machine] described it, under the phone's id for it. */
fun macOnMachine(machine: String, thread: MacThread) = if (machine == MAC_FIRST) thread else thread.copy(id = macPhoneId(machine, thread.id))

/**
 * One Mac as the phone last heard of it: whether it answered at the last try, when it last did, and whether its
 * whole list of threads has been heard since this process began ([MacView.listed], for one Mac).
 */
data class MacSeen(val id: String, val name: String, val reachable: Boolean = false, val seen: Long? = null, val listed: Boolean = false)

/** The Macs as they stand in [view], each as set up in [machines]; one newly set up has not been heard yet. */
fun macSeen(machines: List<MacMachine>, view: MacView): List<MacSeen> =
    machines.map { machine -> view.macs.firstOrNull { it.id == machine.id }?.copy(name = machine.name) ?: MacSeen(machine.id, machine.name) }

/**
 * [view] with [macs] as they now stand. Whether "the Mac" answers, when it was seen and whether its list was heard
 * are then said of them all: reachable when any one answered, seen when any was last, listed when every one was.
 */
fun macWith(view: MacView, macs: List<MacSeen>): MacView = view.copy(
    macs = macs,
    reachable = macs.any { it.reachable },
    seen = macs.mapNotNull { it.seen }.maxOrNull() ?: view.seen,
    listed = macs.isNotEmpty() && macs.all { it.listed },
)

/** Whether the Mac the thread [threadId] is on answered at the last try; with only one Mac known, whether that one did. */
fun MacView.reachableFor(threadId: String): Boolean = macs.firstOrNull { it.id == macMachineOf(threadId) }?.reachable ?: reachable

/** Whether the whole list of the Mac [threadId] is on has been heard in this run. */
fun MacView.listedFor(threadId: String): Boolean = macs.firstOrNull { it.id == macMachineOf(threadId) }?.listed ?: listed

/** What a thread's Mac is called where he needs to tell two apart; empty while only one Mac is set up. */
fun MacView.machineFor(threadId: String): String = if (macs.size < 2) "" else macs.firstOrNull { it.id == macMachineOf(threadId) }?.name ?: macMachineName(macMachineOf(threadId))

/**
 * The computer a new chat's thread is made on: the one he picked ([chosen], null for none) while it is set up, else the
 * one it would have been anyway ([macDefaultMachine]). A pick out of reach is kept, never passed over: his words then wait unsent with
 * `[ try again ]`, as with one Mac out of reach, and the thread is never made somewhere he did not say.
 */
fun macChatMachine(chosen: String?, macs: List<MacSeen>): String? =
    chosen?.let { pick -> macs.firstOrNull { it.id == pick }?.id } ?: macDefaultMachine(macs)

/**
 * The Mac a new chat starts on when nothing names one: the first Mac while it answers, else the first other that
 * answers, else the first Mac all the same (its words then wait, unsent, with `[ try again ]`). Null with none set up.
 */
fun macDefaultMachine(macs: List<MacSeen>): String? = when {
    macs.isEmpty() -> null
    macs.first().reachable -> macs.first().id
    else -> (macs.firstOrNull { it.reachable } ?: macs.first()).id
}
