package dev.omalauncher.assist

import org.json.JSONArray
import org.json.JSONObject

/*
 * One project, however many folders it has (Soma Canvas slice 2, cockpit/canvas/PRD.md): `omalauncher`,
 * `omalauncher-fold`, the worktrees `omalauncher-s8` … and `~/ClaudeSync/projects/omalauncher` are one project. The
 * registry lives at ~/ClaudeSync/projects/_projects.json, written by `herd projects --seed` and by hand, and every
 * runner serves it (`GET /projects`); herd and the runner resolve a folder by the same rule as [projectOf] here.
 */

/** A project: its [name], its [folders] (with `~`, and a trailing `*` matching every folder that starts so), and its [handoff] file, if any. */
data class RegisteredProject(val name: String, val folders: List<String>, val handoff: String? = null)

private val PROJECT_NAME = Regex("[a-z0-9][a-z0-9._-]{0,62}")

/** The handoff file names a project may have; any other is ignored, never read. */
val HANDOFF_FILES = setOf("HANDOFF.md", "CURRENT-STATE.md")

/** The projects in a registry's JSON (the file's, or a runner's `GET /projects`); what doesn't read is left out. */
fun registryFrom(json: JSONObject?): List<RegisteredProject> {
    val list = json?.optJSONArray("projects") ?: return emptyList()
    return (0 until list.length()).mapNotNull { i ->
        val o = list.optJSONObject(i) ?: return@mapNotNull null
        val name = o.optString("name").takeIf { PROJECT_NAME.matches(it) } ?: return@mapNotNull null
        val folders = o.optJSONArray("folders")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String }.map { it.trim() }.filter { it.isNotEmpty() && it.length < 512 } }.orEmpty()
        RegisteredProject(name, folders, o.optString("handoff").takeIf { it in HANDOFF_FILES })
    }.distinctBy { it.name }
}

fun registryText(projects: List<RegisteredProject>): String = JSONObject().put("version", 1).put("projects", JSONArray(projects.map { p ->
    JSONObject().put("name", p.name).put("folders", JSONArray(p.folders)).apply { p.handoff?.let { put("handoff", it) } }
})).toString()

/**
 * [path] as folders are compared: doubled slashes and `.`/`..` steps resolved (as herd's normpath does), a home written out
 * in full as `~` ([macProjectKey]'s rule), and no closing slash.
 */
private fun canonical(path: String): String {
    val trimmed = path.trim()
    val absolute = trimmed.startsWith("/")
    val parts = ArrayDeque<String>()
    for (part in trimmed.split('/')) when (part) {
        "", "." -> Unit
        ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeLast() else if (!absolute) parts.addLast("..")
        else -> parts.addLast(part)
    }
    val joined = (if (absolute) "/" else "") + parts.joinToString("/")
    return macProjectKey(joined).trimEnd('/')
}

/**
 * The project [folder] belongs to: the registered folder that holds it, the deepest wins; a pattern (`~/Projects/omalauncher-*`)
 * matches any folder starting so, and what is under it. With no match, the folder's own name (as herd does); none for no folder.
 * The Codex app's per-chat folders and Claude's scratch folders belong to no project.
 */
fun projectOf(folder: String, registry: List<RegisteredProject>): String {
    if (folder.isBlank()) return ""
    val path = canonical(folder)
    // The home folder itself is no project.
    if (path == "~") return NO_PROJECT
    var best: String? = null
    var depth = -1
    for (project in registry) for (raw in project.folders) {
        val f = canonical(raw)
        val matched = if (f.endsWith("*")) {
            val stem = f.removeSuffix("*")
            val rest = path.removePrefix(stem)
            path.startsWith(stem) && rest.isNotEmpty() && !rest.startsWith("/")
        } else {
            path == f || path.startsWith("$f/")
        }
        if (matched && f.length > depth) {
            best = project.name
            depth = f.length
        }
    }
    best?.let { return it }
    val name = path.substringAfterLast('/')
    if (name.startsWith("scratch-") || "/Documents/Codex/" in "$path/") return NO_PROJECT
    // Under a projects folder, the project is the folder directly in it (design-lab for ~/Projects/design-lab/scratch).
    for (top in listOf("~/Projects/", "~/ClaudeSync/projects/")) if (path.startsWith(top)) return path.removePrefix(top).substringBefore('/')
    return name
}

/** What a folder that belongs to no project is shown as. */
const val NO_PROJECT = "(no folder)"

/** A project's handoff as a runner gives it (`GET /projects/NAME/handoff`): its [file], when it was last [updated], and its top section's [text]. */
data class MacHandoff(val project: String, val file: String, val updated: Long, val text: String)
