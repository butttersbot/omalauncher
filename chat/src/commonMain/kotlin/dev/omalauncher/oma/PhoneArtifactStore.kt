package dev.omalauncher.oma

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import org.json.JSONObject

/*
 * The artifacts the phone's own agents make (features/artifact-panel/PLAN.md, slice 3). Their `soma-artifact` (the app's
 * asset soma-artifact.sh, in the guest's /usr/local/bin) keeps the same store the Macs' runner does, in the guest's
 * /root/.local/share/soma/artifacts/<thread>/<name>/vN.ext + meta.json, and adds a line to <thread>/events.jsonl for each
 * put or edit. This reads that store from the app's side. The guest's agents can write anything there, so nothing read
 * back is trusted: a version's file is named here from a checked name and number, never through a link, and capped.
 */

/** The phone's own conversations that keep artifacts: its builders' threads ("phone", "phone-b", ...). */
private val PHONE_THREAD_KEY = Regex("phone(-[a-z])?")

fun phoneArtifactThread(thread: String): Boolean = PHONE_THREAD_KEY.matches(thread)

/** One line of a thread's events.jsonl: an artifact was put ([state] "ready") or is being edited. */
data class PhoneArtifactEvent(val name: String, val version: Int, val state: String, val title: String, val note: String, val time: Long)

private const val MOST = 4 * 1024 * 1024
private const val EVENTS_MOST = 1L * 1024 * 1024

private val STORE_MIMES = mapOf(
    "html" to "text/html", "htm" to "text/html", "md" to "text/markdown", "markdown" to "text/markdown", "txt" to "text/plain",
    "json" to "application/json", "csv" to "text/plain", "diff" to "text/plain", "patch" to "text/plain", "css" to "text/css",
    "js" to "text/javascript", "ts" to "text/plain", "py" to "text/plain", "kt" to "text/plain", "xml" to "text/plain",
    "yaml" to "text/plain", "yml" to "text/plain", "svg" to "text/plain", "png" to "image/png", "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg", "webp" to "image/webp", "gif" to "image/gif",
)

/** The store at [root] (the guest's store as the app sees it). */
class PhoneArtifactStore(private val root: File) {
    /** The bytes of a file under the store, read without following a link at any step; null for anything else or too big. */
    private fun bytes(file: File, most: Long): ByteArray? = runCatching {
        val base = root.toPath()
        val relative = base.relativize(file.toPath())
        if (relative.isAbsolute || relative.any { it.toString() == ".." || it.toString().isEmpty() }) return null
        var at = base
        if (Files.isSymbolicLink(at)) return null
        for (part in relative) {
            at = at.resolve(part)
            if (Files.isSymbolicLink(at)) return null
        }
        if (!Files.isRegularFile(at, LinkOption.NOFOLLOW_LINKS)) return null
        FileChannel.open(at, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (channel.size() > most) return null
            val buffer = ByteBuffer.allocate(channel.size().toInt())
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
            buffer.array()
        }
    }.getOrNull()

    private fun meta(thread: String, name: String): JSONObject? {
        if (!phoneArtifactThread(thread) || !somaArtifactName(name)) return null
        return runCatching { JSONObject(String(bytes(File(root, "$thread/$name/meta.json"), 1024 * 1024) ?: return null, Charsets.UTF_8)) }.getOrNull()
    }

    /** One artifact's versions, as its meta.json says them, checked. */
    fun versions(thread: String, name: String): ArtifactVersions? {
        val meta = meta(thread, name) ?: return null
        val listed = meta.optJSONArray("versions") ?: return null
        val versions = (0 until minOf(listed.length(), 1000)).mapNotNull { at ->
            val one = listed.optJSONObject(at) ?: return@mapNotNull null
            val v = (one.opt("v") as? Number)?.toInt()?.takeIf { it > 0 && one.opt("v") !is Boolean } ?: return@mapNotNull null
            ArtifactVersion(v, (one.opt("time") as? Number)?.toLong() ?: 0L, (one.opt("size") as? Number)?.toLong() ?: 0L,
                (if (one.isNull("note")) "" else one.optString("note")).take(200))
        }.distinctBy { it.version }.sortedBy { it.version }
        val title = (if (meta.isNull("title")) "" else meta.optString("title")).replace(Regex("[\\x00-\\x1f]"), " ").take(80)
        return ArtifactVersions(name, title, versions.lastOrNull()?.version ?: 0, null, versions)
    }

    /** What a thread's agents have made, newest first. */
    fun list(thread: String): List<ArtifactVersions> {
        if (!phoneArtifactThread(thread)) return emptyList()
        val folder = File(root, thread)
        if (Files.isSymbolicLink(folder.toPath())) return emptyList()
        return folder.listFiles().orEmpty().filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) && somaArtifactName(it.name) }
            .mapNotNull { versions(thread, it.name) }.filter { it.latest > 0 }
            .sortedByDescending { item -> item.versions.maxOfOrNull { it.time } ?: 0L }.take(100)
    }

    /** One version (the newest when [path] names none), as the runner's route answers; a reason when it can't be read. */
    fun read(thread: String, path: String): ArtifactRead {
        val (name, asked) = parseSomaArtifactPath(path) ?: return ArtifactRead.Failed("Only what an agent made with soma-artifact opens from the phone's own conversations.")
        val listed = versions(thread, name) ?: return ArtifactRead.Failed("This artifact could not be found on the phone.")
        val v = asked ?: listed.latest
        if (listed.versions.none { it.version == v }) return ArtifactRead.Failed("This version could not be found on the phone.")
        val extension = name.substringAfterLast('.').lowercase()
        val data = bytes(File(root, "$thread/$name/v$v.$extension"), MOST.toLong()) ?: return ArtifactRead.Failed("This version is gone or too large to preview (4 MB limit).")
        val sha = MessageDigest.getInstance("SHA-256").digest(data).take(6).joinToString("") { "%02x".format(it) }
        return ArtifactRead.Ready(ArtifactDocument(name, STORE_MIMES[extension] ?: "text/plain", data, sha, v, listed.latest, listed.title))
    }

    /**
     * The thread's events after byte [from] of its events.jsonl, and where to read from next. A file shorter than [from]
     * (started over) is read from its start; a line that can't be read is passed over; a partial last line waits.
     */
    fun events(thread: String, from: Long): Pair<List<PhoneArtifactEvent>, Long> {
        if (!phoneArtifactThread(thread)) return emptyList<PhoneArtifactEvent>() to from
        val file = File(root, "$thread/events.jsonl")
        val path = file.toPath()
        if (Files.isSymbolicLink(File(root, thread).toPath()) || Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return emptyList<PhoneArtifactEvent>() to 0L
        val size = runCatching { Files.size(path) }.getOrDefault(0L)
        val start = if (from > size) 0L else from
        if (size == start) return emptyList<PhoneArtifactEvent>() to start
        // Far behind (a flood): only the newest part matters for what shows now.
        val begin = maxOf(start, size - EVENTS_MOST)
        val chunk = runCatching {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.allocate((size - begin).toInt())
                channel.position(begin)
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
                buffer.array()
            }
        }.getOrNull() ?: return emptyList<PhoneArtifactEvent>() to start
        val end = chunk.lastIndexOf('\n'.code.toByte())
        if (end < 0) return emptyList<PhoneArtifactEvent>() to begin
        val text = String(chunk, 0, end + 1, Charsets.UTF_8)
        val found = text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching {
                val json = JSONObject(line)
                val name = json.optString("name")
                val state = json.optString("state")
                val version = (json.opt("version") as? Number)?.toInt() ?: 0
                if (!somaArtifactName(name) || state !in setOf("ready", "editing") || version < 1) null
                else PhoneArtifactEvent(name, version, state, json.optString("title").take(80), json.optString("note").take(200), (json.opt("time") as? Number)?.toLong() ?: 0L)
            }.getOrNull()
        }.toList()
        return found to begin + end + 1
    }

    /** The artifact of [thread] that a tool call works on by its file's name, if any: Write/Edit of it, or a command naming it. */
    fun touched(thread: String, tool: String, filePath: String, command: String): String? {
        if (!phoneArtifactThread(thread)) return null
        val names = File(root, thread).listFiles().orEmpty().filter { it.isDirectory && somaArtifactName(it.name) }.map { it.name }
        return when (tool) {
            "Write", "Edit", "MultiEdit" -> filePath.substringAfterLast('/').takeIf { it in names }
            "Bash" -> if (command.trimStart().startsWith("soma-artifact")) null
                else names.sorted().firstOrNull { name -> Regex("(^|[\\s/'\"=<>])" + Regex.escape(name) + "($|[\\s'\";|&<>)])").containsMatchIn(command.take(20_000)) }
            else -> null
        }
    }
}
