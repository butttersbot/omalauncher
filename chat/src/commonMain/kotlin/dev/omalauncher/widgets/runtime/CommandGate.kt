package dev.omalauncher.widgets.runtime

import androidx.compose.runtime.mutableIntStateOf
import dev.omalauncher.linux.ShellResult
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
 * The run-time gate for widget commands (Soma Canvas slice 7b, PRD §6). Each machine keeps approved.json: for each widget
 * file, the hash of the file he approved and the hash of each command he approved it to run. Every command is checked as
 * it is about to run, hashed as it is then, so a command built from strings at run time can't slip past a hash of the
 * source. A widget file that arrives from elsewhere waits in quarantine until he approves the file; a changed file's new
 * commands need approving again (its unchanged ones stay approved). Approvals are written only by his tap on this machine,
 * or, for a machine with no screen (the Mac Pro's service), by an approval he taps on a paired device and sends signed,
 * naming this machine, the file's hash and the commands' hashes, with a one-time nonce. Agent-made widgets get no exemption.
 */

/** SHA-256 of [text], in hex. */
fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** A command waiting for his OK on this machine: the [widget] file that would run it, the [command] as it would run, and its [hash]. */
data class CommandAsk(val widget: String, val command: String, val hash: String)

/** A widget file waiting in quarantine for his OK: its name and the hash of the text that would load. */
data class FileAsk(val widget: String, val fileHash: String)

class CommandGate(
    /** approved.json; written beside itself and moved over, readable by this user alone. */
    private val file: File,
    /** This machine's name, as a signed approval must name it. */
    val machine: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var doc: JSONObject = read()

    private val commandAsks = LinkedHashMap<String, CommandAsk>()
    private val fileAsks = LinkedHashMap<String, FileAsk>()

    /** Goes up whenever what waits for him changes, so a screen showing the asks redraws. */
    val changes = mutableIntStateOf(0)

    private fun read(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrNull()?.takeIf { it.optJSONObject("widgets") != null }
        ?: JSONObject().put("widgets", JSONObject()).put("nonces", JSONObject())

    private fun widgets() = doc.getJSONObject("widgets")

    private fun entry(widget: String): JSONObject? = widgets().optJSONObject(widget)

    /** A list of hashes as kept; anything in it that isn't a string (a hand-edited or damaged file) is no approval. */
    private fun hashes(widget: String, key: String): Set<String> =
        entry(widget)?.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String }.toSet() }.orEmpty()

    private fun save() {
        file.parentFile?.mkdirs()
        val fresh = File(file.parentFile, "." + file.name + ".new")
        fresh.writeText(doc.toString(1))
        fresh.setReadable(false, false); fresh.setReadable(true, true); fresh.setWritable(false, false); fresh.setWritable(true, true)
        if (!fresh.renameTo(file)) { file.delete(); check(fresh.renameTo(file)) { "couldn't keep ${file.name}" } }
    }

    private fun changed() { changes.intValue++ }

    /** Whether [widget] may run [command] here now. */
    @Synchronized fun allowed(widget: String, command: String): Boolean = sha256(command) in hashes(widget, "commands")

    /** Whether [command] was denied for [widget]: it isn't asked again until the file changes. */
    @Synchronized fun denied(widget: String, command: String): Boolean = sha256(command) in hashes(widget, "denied")

    /** [widget] asked to run [command], which isn't approved: it waits for him (once, however often it is asked). */
    @Synchronized fun ask(widget: String, command: String) {
        if (allowed(widget, command) || denied(widget, command)) return
        val hash = sha256(command)
        if (commandAsks.put("$widget\u0000$hash", CommandAsk(widget, command, hash)) == null) changed()
    }

    @Synchronized fun commandAsks(): List<CommandAsk> = commandAsks.values.toList()

    @Synchronized fun fileAsks(): List<FileAsk> = fileAsks.values.toList()

    private fun add(widget: String, key: String, hash: String) {
        val e = entry(widget) ?: JSONObject().also { widgets().put(widget, it) }
        val list = e.optJSONArray(key) ?: JSONArray().also { e.put(key, it) }
        if ((0 until list.length()).none { list.getString(it) == hash }) list.put(hash)
    }

    private fun remove(widget: String, key: String, hash: String) {
        val list = entry(widget)?.optJSONArray(key) ?: return
        (list.length() - 1 downTo 0).filter { list.getString(it) == hash }.forEach { list.remove(it) }
    }

    /** His tap on this machine: [ask] may run (or, with [allow] false, may not, and isn't asked again until its file changes). */
    @Synchronized fun answer(ask: CommandAsk, allow: Boolean) {
        if (allow) { add(ask.widget, "commands", ask.hash); remove(ask.widget, "denied", ask.hash) } else add(ask.widget, "denied", ask.hash)
        save()
        commandAsks.remove("${ask.widget}\u0000${ask.hash}")
        changed()
    }

    /** Whether [text] is the approved text of [widget]: a quarantined file loads only then. */
    @Synchronized fun fileApproved(widget: String, text: String): Boolean = entry(widget)?.optString("file") == sha256(text)

    /** [widget]'s [text] waits in quarantine for him. */
    @Synchronized fun askFile(widget: String, text: String) {
        if (fileApproved(widget, text)) return
        if (fileAsks.put(widget, FileAsk(widget, sha256(text))) != FileAsk(widget, sha256(text))) changed()
    }

    /**
     * His tap: [widget]'s file with hash [fileHash] may load. Its commands are approved one by one as they ask; a denial made
     * for an older file is forgotten, since the file changed.
     */
    @Synchronized fun approveFile(widget: String, fileHash: String) {
        val e = entry(widget) ?: JSONObject().also { widgets().put(widget, it) }
        if (e.optString("file") != fileHash) e.remove("denied")
        e.put("file", fileHash)
        save()
        fileAsks.remove(widget)
        changed()
    }

    /**
     * An approval he tapped on a paired device, for this screenless machine: JSON naming the [device] it was made on, this
     * machine, the widget, its file's hash, the hashes of the commands it may run, a nonce and the time, with an HMAC-SHA256
     * over all of them in the device's key ([keyOf]). Applied once: a nonce seen before, a time more than ten minutes off,
     * another machine's name, an unknown device or a bad signature is refused. Null when applied; otherwise why not.
     */
    @Synchronized fun applySigned(message: String, keyOf: (String) -> ByteArray?): String? {
        val o = runCatching { JSONObject(message) }.getOrNull() ?: return "not an approval"
        val device = o.optString("device"); val forMachine = o.optString("machine"); val widget = o.optString("widget")
        val fileHash = o.optString("file"); val nonce = o.optString("nonce"); val time = o.optLong("time", -1); val sig = o.optString("sig")
        val commands = o.optJSONArray("commands")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: return "no commands named"
        if (forMachine != machine) return "it names another machine"
        if (!WIDGET.matches(widget) || !HASH.matches(fileHash) || commands.any { !HASH.matches(it) } || nonce.length !in 16..128 || nonce.any { it.isISOControl() }) return "it is incomplete"
        if (kotlin.math.abs(now() - time) > WINDOW_MS) return "it is too old, or from the future"
        val key = keyOf(device)?.takeIf { it.isNotEmpty() } ?: return "it is from a device that isn't paired"
        val expected = runCatching { sign(key, signedText(device, forMachine, widget, fileHash, commands, nonce, time)) }.getOrNull() ?: return "its signature is wrong"
        if (!MessageDigest.isEqual(expected.toByteArray(), sig.toByteArray())) return "its signature is wrong"
        val nonces = doc.optJSONObject("nonces") ?: JSONObject().also { doc.put("nonces", it) }
        if (nonces.has(nonce)) return "it was used before"
        // Nonces are kept as long as an approval could still be fresh: a replay after that is refused for its time.
        nonces.keys().asSequence().toList().filter { now() - nonces.optLong(it) > WINDOW_MS * 2 }.forEach { nonces.remove(it) }
        nonces.put(nonce, now())
        val e = entry(widget) ?: JSONObject().also { widgets().put(widget, it) }
        if (e.optString("file") != fileHash) e.remove("denied")
        e.put("file", fileHash)
        commands.forEach { add(widget, "commands", it) }
        save()
        fileAsks.remove(widget)
        commandAsks.entries.removeAll { it.value.widget == widget && it.value.hash in commands }
        changed()
        return null
    }

    companion object {
        private val HASH = Regex("[0-9a-f]{64}")

        /** A widget file's name as approvals name it: no separators, no control characters. */
        private val WIDGET = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        const val WINDOW_MS = 10 * 60 * 1000L

        /** What a signed approval's signature covers, one field to a line, in this order. */
        fun signedText(device: String, machine: String, widget: String, fileHash: String, commands: List<String>, nonce: String, time: Long) =
            listOf("soma-approval-1", device, machine, widget, fileHash, commands.joinToString(","), nonce, time.toString()).joinToString("\n")

        fun sign(key: ByteArray, text: String): String =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** What a widget's command says while it waits for his OK here: it didn't run. */
val WAITING_FOR_OK = ShellResult("", "waiting for your OK to run this on this machine", -1)

/** What a widget's command says once he denied it here: it doesn't run, and isn't asked again until the file changes. */
val DENIED_HERE = ShellResult("", "you denied this on this machine", -1)

/**
 * [inner], with every command [widget] runs checked by [gate] first: one he hasn't approved here doesn't run (no process is
 * started) and waits for him instead. HTTP, Android's data and files are not commands and pass through.
 */
class GatedServices(private val inner: Services, private val gate: CommandGate, private val widget: String) : Services by inner {
    private fun allowed(command: String) = gate.allowed(widget, command).also { if (!it) gate.ask(widget, command) }

    /** What a command that may not run says: denied here, or waiting for his OK. */
    private fun refused(command: String) = if (gate.denied(widget, command)) DENIED_HERE else WAITING_FOR_OK

    override suspend fun cmd(command: String, timeoutSeconds: Int): ShellResult = if (allowed(command)) inner.cmd(command, timeoutSeconds) else refused(command)

    override fun run(command: String, done: (ShellResult?) -> Unit) = if (allowed(command)) inner.run(command, done) else done(refused(command))

    override suspend fun stream(command: String, onLine: (String) -> Unit) { if (allowed(command)) inner.stream(command, onLine) }

    /**
     * A tap's action: `run(…)` and `terminal(…)` run a command, so they are gated as the sources are (round-1 review: they
     * went around the gate); every other action (a URL, an app, a copy, media) runs nothing here and passes through.
     */
    override fun act(name: String, args: List<dev.omalauncher.widgets.lang.Value>, done: () -> Unit) {
        // What this screen can't do at all is never asked about: an OK for it would do nothing.
        if (name in COMMANDS && !inner.canAct(name)) return done()
        val command = when (name) {
            "run" -> args.firstOrNull()?.takeIf { it != dev.omalauncher.widgets.lang.Null }?.toString() ?: return done()
            "terminal" -> args.firstOrNull()?.takeIf { it != dev.omalauncher.widgets.lang.Null }?.toString() ?: LOGIN_SHELL
            // On the phone `app(...)` starts a Linux program: a command like any other.
            "app" -> "app " + (args.firstOrNull()?.takeIf { it != dev.omalauncher.widgets.lang.Null }?.toString() ?: return done())
            else -> return inner.act(name, args, done)
        }
        if (allowed(command)) inner.act(name, args, done) else done()
    }

    override fun terminal(command: String?, fontSize: Double?): WidgetTerminal {
        check(allowed(command ?: LOGIN_SHELL)) { "waiting for your OK to run this on this machine" }
        return inner.terminal(command, fontSize)
    }

    override fun fileUpdates(path: String): Flow<String> = inner.fileUpdates(path)

    companion object {
        /** What a Terminal node with no command is approved as. */
        const val LOGIN_SHELL = "(the login shell)"

        /** The actions that run a program: gated, as the language itself counts them (Compiler's command set). */
        val COMMANDS = setOf("run", "terminal", "app")
    }
}

/**
 * The widget files on this machine that came from elsewhere (the shared library, another machine): each one waits in
 * quarantine until he approves that file's text here, and only then loads. A file that changes waits again.
 */
class WidgetLibrary(private val dir: File, private val gate: CommandGate) {
    private companion object {
        /** A widget file is small: anything larger is no widget. */
        const val MAX_FILE = 256L * 1024
    }

    /** The library's widget files, by name: real files only, never a link out of it. */
    fun names(): List<String> = dir.listFiles { f -> plain(f) && f.name.endsWith(".owl") }?.map { it.name }?.sorted().orEmpty()

    private fun plain(f: File) = f.isFile && !java.nio.file.Files.isSymbolicLink(f.toPath()) && f.parentFile == dir && f.length() <= MAX_FILE

    /** [name]'s text when he has approved it here; otherwise null, and it waits for him. */
    fun approvedText(name: String): String? {
        val text = runCatching { File(dir, name).takeIf { plain(it) }?.readText() }.getOrNull() ?: return null
        if (gate.fileApproved(name, text)) return text
        gate.askFile(name, text)
        return null
    }
}
