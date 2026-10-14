package dev.omalauncher.assist

/*
 * Which project a new chat starts on (Boss, 2026-10-13: "The plus button should always open up a Mac session. If
 * I need to do anything on the phone, I'm gonna be using the hold to talk"). A new chat's work always goes to a
 * new thread on the Mac. When the front agent names no project for it, or would give it to the phone, the
 * launcher asks him in the tab which project folder on the Mac, and his next words there name one. Which folders
 * there are, the question and how his answer is read are plain functions here, unit tested.
 */

/**
 * A project folder on the Mac, as the threads in it show it: [folder] is its last part, which is what he is
 * asked to pick from, [thread] the thread in it that worked last, and [names] what its threads are called, which
 * he may name it by as well ("FF4" for ffiv-ds-better-menu-mod).
 */
data class MacProject(val folder: String, val thread: MacThread, val names: List<String>)

/** The project folders of [threads], the one worked in last first. Threads with no project are in none. */
fun macProjects(threads: List<MacThread>): List<MacProject> =
    threads.filter { macProjectKey(it.project).isNotEmpty() }
        .groupBy { macProjectKey(it.project) }.values
        .map { there -> there.sortedByDescending { it.lastWorked ?: Long.MIN_VALUE } }
        .sortedByDescending { it.first().lastWorked ?: Long.MIN_VALUE }
        .map { there -> MacProject(macFolder(there.first().project), there.first(), there.map { macName(it.name) }.distinct()) }

/** What a new chat says when no project folder on the Mac is known: there is nothing to pick from, so nothing is asked. */
const val MAC_NO_PROJECTS = "This chat is for work on the Mac, and no project on the Mac is known on this phone yet. Nothing was sent."

/** The one short line a new chat asks him with, naming every folder of [projects]. */
fun macProjectQuestion(projects: List<MacProject>): String = "Which project on the Mac? " + projects.joinToString(", ") { it.folder }

/** Words in lower case with everything that is not a letter or a digit a space between them, so "FF4-mod" is "ff4 mod". */
private fun plainWords(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

/** Words that say nothing about which project he means, so a reply such as "the FF4 one" is read by "ff4" alone. */
private val FILLER = setOf("the", "one", "project", "projects", "folder", "repo", "mac", "for", "and", "its", "that", "this", "please", "use", "put", "on", "in", "it")

/**
 * The project of [projects] his [words] name; null when they name none, or more than one, and he is asked again.
 * Case does not matter. A folder or thread name his words hold whole, or that holds his words whole, names it
 * first; failing that, a word of his that is a word of only one project's names. Nothing is guessed past that.
 */
fun macPickProject(words: String, projects: List<MacProject>): MacProject? {
    val said = plainWords(words)
    if (said.isEmpty()) return null
    val saying = " " + said.joinToString(" ") + " "
    fun names(project: MacProject) = (listOf(project.folder) + project.names.map(::macBaseName)).map(::plainWords).filter { it.isNotEmpty() }
    val whole = projects.filter { project ->
        names(project).any { name -> val named = " " + name.joinToString(" ") + " "; saying.contains(named) || named.contains(saying) }
    }
    if (whole.size == 1) return whole.single()
    if (whole.size > 1) return null
    val telling = said.filter { it !in FILLER && it.length >= 2 }.toSet()
    return projects.filter { project -> names(project).any { name -> name.any { it in telling } } }.singleOrNull()
}

/** Who the launcher's own words in the chat are from, such as the question above: Oma, never a model. */
const val OMA_NAME = "Oma"
