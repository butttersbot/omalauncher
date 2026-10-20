package dev.omalauncher.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/*
 * The modules as items on a canvas page (Soma Canvas slice 4): which there are, the key each has in a notebook, and the
 * size each starts at. The Fold's launcher offers them where widgets are added and draws them itself, as it does thread
 * tiles; Soma desktop's canvas (slice 5) places the same keys. A module's key names what it shows and nothing else: where
 * it sits and how big it is are the screen's own.
 */

/**
 * Ahead of a module's kind in the key it has on a page: no widget file, app, folder or thread tile has a key like it. Saved:
 * never rename. The notebook store knows a module by it, and saves one as a widget so a launcher from before modules keeps its place.
 */
const val MODULE_PREFIX = "module:"

/** What `oma-phone board` calls a module, beside "widget", "app", "folder" and "agent". */
const val MODULE_TILE_KIND = "module"

/**
 * A module, with the cells it starts at: [size] across and down on the six columns. [perProject] ones show one project;
 * a [chatScreen] one is the chat itself, a block on a screen whose window is its chat (Soma desktop), never on the Fold,
 * whose chat is an app of its own; a [foldHome] one shows what only the Fold's home keeps (its saved layouts), so Soma
 * desktop doesn't offer it. Ids are saved: never rename.
 */
enum class ModuleKind(val id: String, val title: String, val blurb: String, val size: Pair<Int, Int>, val perProject: Boolean = false, val chatScreen: Boolean = false, val foldHome: Boolean = false) {
    Needs("needs", "Needs you", "What waits for your OK, from every thread: approve or deny from the tile.", 3 to 2),
    Threads("threads", "Threads", "Every thread and how it stands; tap one to open it in the chat.", 3 to 2),
    Computers("computers", "Computers", "Each computer, whether it answers, and how busy it is.", 3 to 2),
    Timeline("timeline", "Timeline", "What you asked and where it went, newest first.", 6 to 2),
    NewThread("newthread", "New thread", "Where a new chat starts; tap to start one.", 3 to 1),
    Sessions("sessions", "Sessions", "The Claude sessions open on your computers; nudge one.", 3 to 2),
    Pocket("pocket", "Pocket", "Files in your pocket, from every machine.", 3 to 2),
    Handoff("handoff", "Handoff", "A project's handoff: where things stand, and next.", 3 to 3, perProject = true),
    Project("project", "Project", "A project's threads and its next step.", 3 to 2, perProject = true),
    Chat("chat", "Chat", "The chat itself.", 3 to 4, chatScreen = true),
    Layouts("layouts", "Layouts", "Saved layouts of home: tap one to load it, or save this one; how art is printed.", 3 to 2, foldHome = true),
    ;

    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } }
}

/** A project's name as it may stand in a key: the registry's names (`herd projects`), never a path. */
private val PROJECT_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")

/**
 * What a module's key says: its [kind], the [project] it shows for one that shows a project, and which [copy] it is. A
 * module can be on more than one page (a page for a project has its own Needs you): the first is plain, the others end
 * "#2", "#3"; every copy shows the same thing.
 */
data class ModuleRef(val kind: ModuleKind, val project: String? = null, val copy: Int = 1) {
    val key: String get() = MODULE_PREFIX + kind.id + (project?.let { ":$it" } ?: "") + (if (copy > 1) "#$copy" else "")

    /** The first copy's key: what a module is, whichever copy this is. */
    val base: String get() = copy(copy = 1).key
}

/** The copies a key may name: "#2" to "#99". */
private val COPY = Regex("#([2-9]|[1-9][0-9])$")

/** The key of [kind]'s module, for [project] when it shows one. A project's module with no good project name has none. */
fun moduleKey(kind: ModuleKind, project: String? = null): String? = when {
    kind.perProject && (project == null || !PROJECT_NAME.matches(project)) -> null
    !kind.perProject && project != null -> null
    else -> ModuleRef(kind, project).key
}

/** Whether the item [key] is a module. */
fun isModuleTile(key: String) = key.startsWith(MODULE_PREFIX)

/** The module [key] names; null for any other item, and for a module this build doesn't know, which keeps its place and shows nothing. */
fun moduleRef(key: String): ModuleRef? {
    if (!isModuleTile(key)) return null
    val copy = COPY.find(key)?.groupValues?.get(1)?.toInt() ?: 1
    val parts = key.removePrefix(MODULE_PREFIX).removeSuffix(if (copy > 1) "#$copy" else "").split(':')
    val kind = ModuleKind.of(parts[0]) ?: return null
    return when {
        kind.perProject -> parts.getOrNull(1)?.takeIf { parts.size == 2 && PROJECT_NAME.matches(it) }?.let { ModuleRef(kind, it, copy) }
        parts.size == 1 -> ModuleRef(kind, copy = copy)
        else -> null
    }
}

/** Whether the module drawn here has the screen's focus (picked to move or resize): its tile is outlined, as a thread tile's is. */
val LocalModuleFocus = compositionLocalOf { false }

/**
 * The module [ref] at [size], from [source]. What it shows is read as it is drawn, so whoever draws it recomposes it when
 * the source has news. [onOpen] opens the chat on a thread's tab; [onNew] starts a new chat. A module this build can't show
 * (its key is unknown) draws nothing.
 */
@Composable
fun ModuleView(ref: ModuleRef, size: ModuleSize, source: ModuleSource, now: Long, modifier: Modifier = Modifier, onOpen: (String) -> Unit, onNew: () -> Unit) {
    when (ref.kind) {
        ModuleKind.Needs -> NeedsYouModule(source.asks(), size, modifier, onAnswer = { ask, allow, said -> source.answer(ask, allow, said) }, onOpen = onOpen)
        ModuleKind.Threads -> ThreadsModule(source.threads(), size, modifier, onOpen = onOpen)
        ModuleKind.Computers -> ComputersModule(source.computers(), size, modifier, door = source.publicDoor(), now = now)
        ModuleKind.Timeline -> TimelineModule(source.events(), size, modifier, onOpen = onOpen)
        ModuleKind.NewThread -> NewThreadModule(source.newThread(), size, modifier, onNew = onNew)
        ModuleKind.Sessions -> SessionsModule(source.sessions(), size, modifier, onNudge = { s, done -> source.nudge(s, done) })
        ModuleKind.Pocket -> PocketModule(source.pocket(), size, now, modifier)
        ModuleKind.Handoff -> HandoffModule(ref.project.orEmpty(), source.handoff(ref.project.orEmpty()), size, modifier)
        ModuleKind.Project -> ProjectModule(source.project(ref.project.orEmpty()), size, modifier, onOpen = onOpen)
        // The chat is drawn by the screen it is on, which has the chat's host; here it is only its place.
        ModuleKind.Chat -> androidx.compose.foundation.layout.Box(modifier)
        ModuleKind.Layouts -> LayoutsModule(Layouts.here, size, now, modifier, key = ref.key)
    }
}

/** The projects the modules on a screen show, so a refresh reads their handoffs and nothing more. */
fun moduleProjects(keys: Collection<String>): List<String> = keys.mapNotNull { moduleRef(it)?.project }.distinct().sorted()
