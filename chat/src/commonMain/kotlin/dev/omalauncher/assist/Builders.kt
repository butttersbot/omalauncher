package dev.omalauncher.assist

import dev.omalauncher.linux.shellQuote
import dev.omalauncher.oma.ChatTask

/*
 * The phone's pool of builders (Boss, 2026-10-13: "we should be able to spin up multiple builders … the idea is
 * to never be bottlenecked by one agent that's working", and "we can start with 2 Builders"). Each builder is
 * a Claude Code conversation of its own, with its own process, follow-up queue, answer and approvals; new work
 * goes to whichever is free, and only a follow-up to what one of them is doing goes to that one. Which builder
 * gets a request and what each is called are plain functions here, unit tested; AgentTasks runs the builders
 * and does what these say.
 */

/**
 * How many builders the phone runs at once at most. Boss set it at two first, then asked for a third, Phone C, on
 * Claude Code (2026-10-24: the Fold has 16 GB and room for another idle agent).
 */
const val PHONE_BUILDERS = 3

/**
 * One of the phone's builders. [letter] is how he, the board and the chat know it, and is saved in the
 * chat's entries; builder A is the one there always was, so everything saved before there were two is A's.
 */
enum class BuilderId(val letter: String) {
    A("A"),
    B("B"),
    C("C"),
    ;

    /** What a chat entry saves for it: nothing for A, so every entry of A's keeps the shape it has always had. */
    val saved get() = if (this == A) "" else letter

    /** Its key among the threads, which its tab and its tile on the Agents page are known by. Saved: never rename. */
    val thread get() = when (this) {
        A -> PHONE_THREAD
        B -> PHONE_THREAD_B
        C -> PHONE_THREAD_C
    }

    /** What its tab and tile are called: "Phone A", "Phone B", "Phone C". */
    val title get() = when (this) {
        A -> PHONE_THREAD_A_NAME
        B -> PHONE_THREAD_B_NAME
        C -> PHONE_THREAD_C_NAME
    }

    companion object {
        /**
         * The builder [name] stands for: its letter or its number, as a saved entry or a notification may write it. Null for
         * anything else, so a name that is no builder's is never taken for one.
         */
        fun named(name: String?): BuilderId? = when (name?.trim()?.uppercase()) {
            "A", "1" -> A
            "B", "2" -> B
            "C", "3" -> C
            else -> null
        }

        /** The builder whose thread (and tab) [key] is; null for any other key. */
        fun ofThread(key: String?): BuilderId? = entries.firstOrNull { it.thread == key }

        /** The builder a saved entry, or a notification's stop, names; anything that names none is A, which there always was. */
        fun of(saved: String?): BuilderId = named(saved) ?: A
    }
}

/** The key of builder B among the threads. Saved: never rename. */
const val PHONE_THREAD_B = "phone-b"

/** What builder B's tab and tile are called; A's are "Phone A" beside it, and "Phone" while B has never worked. */
const val PHONE_THREAD_B_NAME = "Phone B"

/** The key of builder C among the threads. Saved: never rename. */
const val PHONE_THREAD_C = "phone-c"

/** What builder C's tab and tile are called. */
const val PHONE_THREAD_C_NAME = "Phone C"

/** Which builder a request of the build lane's went to. */
val ChatTask.builderId: BuilderId get() = BuilderId.of(builder)

/** How busy a builder is as a request comes: whether it has a task and how many follow-ups wait behind it. */
data class BuilderLoad(val working: Boolean, val waiting: Int = 0)

/**
 * The builder that gets a request. One [named] for it, by his tab or by [askingBuilder], is a follow-up to what
 * that builder asked or is doing, and goes to it whatever it is doing: started now if it is free, behind its task if not. New work ([named]
 * null) goes to the first free builder in order, A before B, so with A free nothing differs from before there
 * were two; with every builder busy it waits behind the one with the fewest waiting, A on a tie.
 */
fun pickBuilder(named: BuilderId?, loads: Map<BuilderId, BuilderLoad>): BuilderId {
    if (named != null) return named
    val order = BuilderId.entries.take(PHONE_BUILDERS)
    fun load(id: BuilderId) = loads[id] ?: BuilderLoad(working = false)
    return order.firstOrNull { !load(it).working } ?: order.minBy { load(it).waiting }
}

/**
 * Whether [receiver], handed work now, gets [PHONE_SHARED_NOTE] ahead of it: the builders share one home and its
 * repositories, so it does while another builder has a task. [working] is whether each builder has one; the
 * receiver's own task does not count, since work that waits behind it never runs beside it.
 */
fun builderShares(receiver: BuilderId, working: Map<BuilderId, Boolean>): Boolean = working.any { (id, busy) -> id != receiver && busy }

/** The checkout a builder works in unless told otherwise, and the one a builder sharing the phone gets a worktree of. */
const val PHONE_REPO = "/root/Projects/omalauncher"

/**
 * Whether [receiver], started now, gets a worktree of its own made before it runs: while another builder works
 * ([builderShares]), and only for Claude, whose words are sent after its process starts and so can say whether
 * the worktree was made. Any other agent is given its words on its command line and keeps [PHONE_SHARED_NOTE].
 */
fun builderWorktreeWanted(receiver: BuilderId, working: Map<BuilderId, Boolean>, claude: Boolean): Boolean =
    claude && builderShares(receiver, working)

/** Where [builder]'s own worktree of [main] goes, on a branch of its own, kept from one task to the next. */
data class WorktreePlan(val main: String, val path: String, val branch: String) {
    /**
     * A shell step run before the builder's agent: makes or finds the worktree and says how it stands on one line
     * of its own ([made] reads it), or says nothing when it can't be had. It never takes the shared branch, never
     * moves a worktree that holds work of its own (only a clean one that the shared branch has passed is brought up
     * to it, by fast-forward), and never touches a folder at [path] that is not a worktree of [main]. Its own
     * output and stdin are kept from the agent's, and whatever it does, the agent then starts.
     */
    fun script(): String = """(
exec 3>&1 >/dev/null 2>&1 </dev/null
main=${shellQuote(main)}; wt=${shellQuote(path)}; branch=${shellQuote(branch)}
[ "${'$'}(git -C "${'$'}main" rev-parse --show-toplevel)" = "${'$'}main" ] || exit 0
shared=${'$'}(git -C "${'$'}main" symbolic-ref --quiet --short HEAD) || exit 0
[ "${'$'}shared" != "${'$'}branch" ] || exit 0
git -C "${'$'}main" worktree prune
if [ ! -e "${'$'}wt" ]; then
  mkdir -p "${'$'}(dirname "${'$'}wt")" || exit 0
  if git -C "${'$'}main" show-ref --verify --quiet "refs/heads/${'$'}branch"; then git -C "${'$'}main" worktree add "${'$'}wt" "${'$'}branch" || exit 0
  else git -C "${'$'}main" worktree add -b "${'$'}branch" "${'$'}wt" "${'$'}shared" || exit 0; fi
fi
[ "${'$'}(git -C "${'$'}wt" rev-parse --show-toplevel)" = "${'$'}wt" ] || exit 0
[ "${'$'}(git -C "${'$'}wt" rev-parse --path-format=absolute --git-common-dir)" = "${'$'}(git -C "${'$'}main" rev-parse --path-format=absolute --git-common-dir)" ] || exit 0
[ "${'$'}(git -C "${'$'}wt" symbolic-ref --quiet --short HEAD)" = "${'$'}branch" ] || exit 0
clean=; [ -z "${'$'}(git -C "${'$'}wt" status --porcelain)" ] && clean=1 && git -C "${'$'}wt" merge --ff-only "${'$'}shared"
state=kept; [ -n "${'$'}clean" ] && [ "${'$'}(git -C "${'$'}wt" rev-parse HEAD)" = "${'$'}(git -C "${'$'}main" rev-parse HEAD)" ] && state=current
echo "$WORKTREE_LINE ${'$'}state ${'$'}shared" >&3
)"""

    /** The worktree as [script]'s [line] says it was left; null for any other line. */
    fun made(line: String): BuilderWorktree? {
        val parts = line.trim().split(' ')
        if (parts.size != 3 || parts[0] != WORKTREE_LINE || parts[1] !in setOf("current", "kept") || parts[2].isEmpty()) return null
        return BuilderWorktree(this, shared = parts[2], current = parts[1] == "current")
    }
}

/** How [WorktreePlan.script] begins the one line it says. */
const val WORKTREE_LINE = "OMA-WORKTREE"

/** [builder]'s worktree of [main]: under [root], named and branched after the builder. */
fun builderWorktree(builder: BuilderId, main: String = PHONE_REPO, root: String = "/root/wt"): WorktreePlan {
    val name = "builder-" + builder.letter.lowercase()
    return WorktreePlan(main, "$root/$name", name)
}

/** A worktree a builder was given: made from [shared], and [current] when it starts where [shared] is now. */
data class BuilderWorktree(val plan: WorktreePlan, val shared: String, val current: Boolean) {
    /**
     * What the builder is told ahead of its request in place of [PHONE_SHARED_NOTE]. It still starts in the home
     * folder, as every builder does, because Claude Code finds a conversation to continue by the folder it was
     * started in: so it is told where its worktree is rather than started inside it.
     */
    val note: String get() {
        val (main, path, branch) = plan
        val left = if (current) "made from $shared as it is now"
            else "left as it was, since it holds work that is not in $shared yet: look at it first and do not throw it away"
        return SHARED_NOTE_OPEN + "another agent is working on this phone right now and may be in $main. Oma has given you a git " +
            "worktree of that checkout of your own: $path, on branch $branch, $left. Do any work on $main there (cd $path), never " +
            "in $main itself. When it is done, commit it there, then merge it back with git -C $main merge --ff-only $branch, or " +
            "git -C $main merge $branch if $shared has moved on. If the merge is refused or conflicts, stop and tell Boss which " +
            "files clash instead of overwriting. Work in another git repository goes on a new branch in its own worktree " +
            "(git worktree add) the same way.]"
    }
}

/** [prompt] with [note] on its own line before it, instead of the [PHONE_SHARED_NOTE] it began with if it did. */
fun withWorktreeNote(prompt: String, note: String) = note + "\n" + prompt.removePrefix(PHONE_SHARED_NOTE + "\n")

/**
 * Which builder holds the approval with this [id] and [nonce], from what each builder has [waiting]; null when
 * none does. The nonce is made on the phone for one request of one builder, so at most one can hold it, and
 * a tap is only ever answered by the builder this names.
 */
fun approvalHolder(waiting: Map<BuilderId, List<PendingApproval>>, id: String, nonce: String): BuilderId? =
    waiting.entries.firstOrNull { (_, entries) -> entries.any { it.id == id && it.nonce == nonce } }?.key

/** How a builder is called in the lines about where his words wait and what was restarted. */
fun builderCalled(builder: BuilderId) = "builder ${builder.letter}"

/**
 * What an approval's card, notification and screen are titled: the tool, and which builder asks, so he never
 * answers one builder's request thinking it is the other's.
 */
fun builderApprovalTitle(tool: String, builder: BuilderId) = "Allow ${tool.take(60)} for Builder ${builder.letter}?"

/**
 * The task's line in the notification and the activity tile, which follow one builder: [lead], the first that
 * works, or the one that last changed. Builder B is named, since without it the line reads as A's; [more] is
 * how many others work besides it. The status file and the lock widget read a state from the label alone.
 */
fun poolLabel(lead: BuilderId, label: String, more: Int): String =
    (if (lead == BuilderId.A) label else "Builder ${lead.letter} · $label") + (if (more > 0) " · and $more more" else "")

/**
 * Whether builder B is shown at all: on the board, as a tab and as a tile. Only once it has worked, or works
 * now, or has his requests in the chat; until then the phone looks as it did with one builder.
 */
fun builderBShown(tasks: List<ChatTask>, working: Boolean): Boolean = builderShown(BuilderId.B, tasks, working)

/** Whether [builder] other than A is on the board and in the status file: once it has worked, or works now. */
fun builderShown(builder: BuilderId, tasks: List<ChatTask>, working: Boolean): Boolean =
    builder == BuilderId.A || working || tasks.any { it.build && it.builderId == builder }
