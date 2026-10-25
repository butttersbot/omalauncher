package dev.omalauncher.oma

import org.json.JSONObject

/*
 * Artifacts an agent makes on purpose with `soma-artifact put` (features/artifact-panel/PLAN.md), as the chat keeps and
 * shows them. Plain Kotlin with no Compose in it, so all of it is unit tested.
 *
 * - In the chat they live as steps of the entry whose turn made them ("made neat-tracker.html · v1"), so they are kept
 *   with the conversation like any step and the card under the reply comes back after a restart.
 * - What is happening now (the newest version, an agent still editing) is [ArtifactLive], from the thread's events.
 * - They are read by location "soma-artifact:NAME", with "?v=N" for an older version, through [ChatHost.readArtifact].
 */

/** Where an agent-made artifact is read from: never a path, only its name in the thread's own store. */
const val SOMA_ARTIFACT = "soma-artifact:"

private val SOMA_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_-]*(\\.[A-Za-z0-9_-]+)*\\.[A-Za-z0-9]{1,8}")

/** Whether [name] is one an artifact may have, as the runner's own rule says. */
fun somaArtifactName(name: String): Boolean = name.length <= 80 && SOMA_NAME.matches(name)

/** The location of [name], at [version] when an older one is wanted, at the newest when null. */
fun somaArtifactPath(name: String, version: Int? = null): String = SOMA_ARTIFACT + name + (version?.let { "?v=$it" } ?: "")

/** The name and version a location names; null for any location that is not an agent-made artifact's. */
fun parseSomaArtifactPath(path: String): Pair<String, Int?>? {
    if (!path.startsWith(SOMA_ARTIFACT)) return null
    val rest = path.removePrefix(SOMA_ARTIFACT)
    val name = rest.substringBefore('?')
    val version = if ('?' in rest) rest.substringAfter('?').removePrefix("v=").toIntOrNull()?.takeIf { it > 0 } ?: return null else null
    return if (somaArtifactName(name)) name to version else null
}

/** The step an artifact event leaves under its entry; null for one that says nothing new (a settled edit, a bad name). */
fun artifactStep(name: String, version: Int, state: String, settled: Boolean): String? = when {
    settled || version < 1 || !somaArtifactName(name) -> null
    state == "editing" -> "editing $name · v$version"
    state == "ready" && version == 1 -> "made $name · v1"
    state == "ready" -> "updated $name · v$version"
    else -> null
}

private val MADE_STEP = Regex("^(made|updated) (\\S{1,80}) · v(\\d{1,6})$")
private val EDITING_STEP = Regex("^editing (\\S{1,80}) · v(\\d{1,6})$")

/**
 * [steps] with the step of an artifact event added: a version that is ready takes the place of the line that said it was
 * being edited, so the entry reads "updated x · v4" where it read "editing x · v4", and an edit that ended with nothing
 * new ([settled]) takes that line away.
 */
fun withArtifactStep(steps: List<String>, name: String, version: Int, state: String, settled: Boolean): List<String> {
    val editing = steps.indexOfLast { line -> EDITING_STEP.find(line)?.groupValues?.get(1) == name }
    if (settled) return if (editing >= 0) steps.filterIndexed { index, _ -> index != editing } else steps
    val step = artifactStep(name, version, state, false) ?: return steps
    if (steps.lastOrNull() == step) return steps
    if (state == "ready" && editing >= 0) return steps.mapIndexed { index, line -> if (index == editing) step else line }
    return steps + step
}

/** One artifact a step names: its [name] and the [version] that step made. */
data class StepArtifact(val name: String, val version: Int)

/** The artifacts [lines] say were made, newest version of each, in the order they were first made. */
fun stepArtifacts(lines: List<String>): List<StepArtifact> {
    val found = LinkedHashMap<String, Int>()
    for (line in lines) {
        val match = MADE_STEP.find(line.trim()) ?: continue
        val name = match.groupValues[2]
        if (!somaArtifactName(name)) continue
        val version = match.groupValues[3].toInt()
        found[name] = maxOf(found[name] ?: 0, version)
    }
    return found.map { (name, version) -> StepArtifact(name, version) }
}

/** The chat's handle on an agent-made artifact of [thread]: one per name, whichever version, so selection follows new versions. */
fun somaArtifact(thread: String, name: String, version: Int): ChatArtifact =
    ChatArtifact("$thread:soma:$name", name, somaArtifactPath(name), thread, artifactKind(name), version = version)

/** The artifacts an entry's steps and quiet later lines say its thread made. */
fun taskSomaArtifacts(task: ChatTask): List<ChatArtifact> =
    stepArtifacts(task.steps + task.later.filterNot { it.words }.map { it.text }).map { somaArtifact(task.thread, it.name, it.version) }

/** What the card says an artifact is, under its name. */
fun artifactKindLabel(kind: ArtifactKind): String = when (kind) {
    ArtifactKind.Html -> "interactive mockup"
    ArtifactKind.Markdown -> "document"
    ArtifactKind.Image -> "image"
    ArtifactKind.Code -> "file"
    ArtifactKind.Web -> "web page"
}

/** The few letters in the card's square. */
fun artifactGlyph(name: String): String = when (name.substringAfterLast('.').lowercase()) {
    "html", "htm" -> "HTML"
    "md", "markdown" -> "MD"
    "png", "jpg", "jpeg", "webp", "gif" -> "IMG"
    "svg" -> "SVG"
    else -> name.substringAfterLast('.').uppercase().take(4)
}

/**
 * An artifact as its thread's events last said: [version] the newest kept (0 while the first is still being made),
 * [editing] the version an agent is working on, null when none is. [agent] says who ("Claude"), [where] on which
 * computer, [time] when (Unix seconds, the computer's clock) and [event] which of the thread's events said it.
 */
data class ArtifactLive(
    val thread: String,
    val name: String,
    val version: Int,
    val editing: Int?,
    val title: String = "",
    val note: String = "",
    val agent: String = "",
    val where: String = "",
    val time: Long = 0L,
    val event: Long = 0L,
) {
    val key get() = "$thread:soma:$name"
}

/** The live state of every artifact the threads' events have spoken of since the app started. Safe from any thread. */
class ArtifactLiveBook {
    private val known = LinkedHashMap<String, ArtifactLive>()

    /** One artifact event of [thread]. An event older than one already heard for the same artifact changes nothing. */
    @Synchronized fun heard(thread: String, name: String, version: Int, state: String, settled: Boolean, title: String, note: String,
                            agent: String, where: String, time: Long, event: Long) {
        if (!somaArtifactName(name)) return
        val key = "$thread:soma:$name"
        val before = known[key]
        if (before != null && event <= before.event && before.thread == thread) return
        val now = when {
            state == "editing" -> (before ?: ArtifactLive(thread, name, 0, null)).copy(editing = version.coerceAtLeast(1))
            state == "ready" -> (before ?: ArtifactLive(thread, name, 0, null)).copy(version = maxOf(version, before?.version ?: 0), editing = null,
                note = if (settled) before?.note.orEmpty() else note)
            else -> (before ?: ArtifactLive(thread, name, 0, null)).copy(editing = null)
        }
        known[key] = now.copy(title = title.ifBlank { now.title }, agent = agent.ifBlank { now.agent }, where = where.ifBlank { now.where },
            time = if (settled) now.time else time, event = event)
        while (known.size > 200) known.remove(known.keys.first())
    }

    /** A thread's record was cleared: what it said of its artifacts no longer holds. */
    @Synchronized fun forget(thread: String) {
        known.keys.removeAll { it.startsWith("$thread:soma:") }
    }

    @Synchronized fun snapshot(): List<ArtifactLive> = known.values.toList()
}

/** Who an artifact's agent is, as the strip says it ("Claude is editing"), from the thread's kind. */
fun artifactAgent(kind: String): String = when (kind.lowercase()) {
    "claude", "" -> "Claude"
    "codex" -> "Codex"
    "cursor" -> "Cursor"
    "grok" -> "Grok"
    "venice" -> "Venice"
    else -> kind.replaceFirstChar { it.uppercase() }
}

/** How long after an artifact event it still opens the panel or the strip by itself; older ones (caught up after being offline) don't. */
const val ARTIFACT_FRESH_SECONDS = 180L

/**
 * What changed among [live] for the threads of the tab shown ([threads]) since [seen] (artifact key → (version, editing)),
 * newest first: the ones to open beside the chat, or to tell of in the strip on a narrow screen. Only events at most
 * [ARTIFACT_FRESH_SECONDS] old by [nowSeconds] count, so catching up after a dropped link opens nothing.
 */
fun artifactNews(seen: Map<String, Pair<Int, Int?>>, live: List<ArtifactLive>, threads: Set<String>, nowSeconds: Long): List<ArtifactLive> =
    live.filter { it.thread in threads && seen[it.key] != (it.version to it.editing) && nowSeconds - it.time <= ARTIFACT_FRESH_SECONDS }
        .sortedByDescending { it.event }

/** One version as the computer keeps it. */
data class ArtifactVersion(val version: Int, val time: Long, val size: Long, val note: String)

/** An artifact's versions, oldest first, with its [title] and [latest]; [editing] while an agent works on the next. */
data class ArtifactVersions(val name: String, val title: String, val latest: Int, val editing: Int?, val versions: List<ArtifactVersion>)

/** The runner's list of a thread's artifacts (GET /threads/ID/artifacts); null when it can't be read. */
fun parseArtifactList(code: Int, body: String): List<ArtifactVersions>? = runCatching {
    if (code != 200 || body.length > 2_000_000) return null
    val json = JSONObject(body)
    if (!json.optBoolean("ok")) return null
    val items = json.optJSONArray("artifacts") ?: return null
    (0 until minOf(items.length(), 200)).mapNotNull { index ->
        val item = items.optJSONObject(index) ?: return@mapNotNull null
        val name = item.optString("name")
        if (!somaArtifactName(name)) return@mapNotNull null
        val versions = item.optJSONArray("versions")
        ArtifactVersions(
            name, if (item.isNull("title")) "" else item.optString("title").take(80), item.optInt("latest"),
            if (item.isNull("editing")) null else item.optInt("editing").takeIf { it > 0 },
            (0 until (versions?.length() ?: 0)).mapNotNull { at ->
                val one = versions!!.optJSONObject(at) ?: return@mapNotNull null
                ArtifactVersion(one.optInt("v"), one.optLong("time"), one.optLong("size"), if (one.isNull("note")) "" else one.optString("note").take(200))
                    .takeIf { it.version > 0 }
            },
        )
    }
}.getOrNull()
