package dev.omalauncher.assist

import dev.omalauncher.oma.ChatTask

/*
 * The phone's pool of builders (Boss, 2026-10-13: "we should be able to spin up multiple builders … the idea is
 * to never be bottlenecked by one agent that's working", and "we can start with 2 Builders"). Each builder is
 * a Claude Code conversation of its own, with its own process, follow-up queue, answer and approvals; new work
 * goes to whichever is free, and only a follow-up to what one of them is doing goes to that one. Which builder
 * gets a request, what each is called and how a bare stop is read are plain functions here, unit tested;
 * AgentTasks runs the builders and does what these say.
 */

/** How many builders the phone runs at once at most. Boss set it: more would load the phone more than he wants. */
const val PHONE_BUILDERS = 2

/**
 * One of the phone's builders. [letter] is how he, the front agent and the chat know it, and is saved in the
 * chat's entries; builder A is the one there always was, so everything saved before there were two is A's.
 */
enum class BuilderId(val letter: String) {
    A("A"),
    B("B"),
    ;

    /** What a chat entry saves for it: nothing for A, so every entry of A's keeps the shape it has always had. */
    val saved get() = if (this == A) "" else letter

    /** Its key among the threads, which its tab and its tile on the Agents page are known by. Saved: never rename. */
    val thread get() = if (this == A) PHONE_THREAD else PHONE_THREAD_B

    companion object {
        /**
         * The builder [name] stands for: its letter or its number, as he or the front agent may write it. Null for
         * anything else, so a name that is no builder's is never taken for one.
         */
        fun named(name: String?): BuilderId? = when (name?.trim()?.uppercase()) {
            "A", "1" -> A
            "B", "2" -> B
            else -> null
        }

        /** The builder a saved entry, or a notification's stop, names; anything that names none is A, which there always was. */
        fun of(saved: String?): BuilderId = named(saved) ?: A
    }
}

/** The key of builder B among the threads. Saved: never rename. */
const val PHONE_THREAD_B = "phone-b"

/** What builder B's tab and tile are called; A's are "Phone A" beside it, and "Phone" while B has never worked. */
const val PHONE_THREAD_B_NAME = "Phone B"

/** Which builder a request of the build lane's went to. */
val ChatTask.builderId: BuilderId get() = BuilderId.of(builder)

/** How busy a builder is as a request comes: whether it has a task and how many follow-ups wait behind it. */
data class BuilderLoad(val working: Boolean, val waiting: Int = 0)

/**
 * The builder that gets a request. One [named] for it is a follow-up to what that builder asked or is doing,
 * and goes to it whatever it is doing: started now if it is free, behind its task if not. New work ([named]
 * null) goes to the first free builder in order, A before B, so with A free nothing differs from before there
 * were two; with every builder busy it waits behind the one with the fewest waiting, A on a tie.
 */
fun pickBuilder(named: BuilderId?, loads: Map<BuilderId, BuilderLoad>): BuilderId {
    if (named != null) return named
    val order = BuilderId.entries.take(PHONE_BUILDERS)
    fun load(id: BuilderId) = loads[id] ?: BuilderLoad(working = false)
    return order.firstOrNull { !load(it).working } ?: order.minBy { load(it).waiting }
}

/** What a bare order to stop, which names no builder, comes to while [working] are the builders with a task. */
sealed interface BareStop {
    /** Nothing runs: nothing is stopped, and he is told so. */
    data object Nothing : BareStop

    /** One builder works, and it is the one he means. */
    data class One(val builder: BuilderId) : BareStop

    /** More than one works: none is stopped on a guess, and he is asked which. */
    data object Which : BareStop
}

fun bareStop(working: List<BuilderId>): BareStop = when (working.size) {
    0 -> BareStop.Nothing
    1 -> BareStop.One(working.single())
    else -> BareStop.Which
}

/**
 * Which builder holds the approval with this [id] and [nonce], from what each builder has [waiting]; null when
 * none does. The nonce is made on the phone for one request of one builder, so at most one can hold it, and
 * a tap is only ever answered by the builder this names.
 */
fun approvalHolder(waiting: Map<BuilderId, List<PendingApproval>>, id: String, nonce: String): BuilderId? =
    waiting.entries.firstOrNull { (_, entries) -> entries.any { it.id == id && it.nonce == nonce } }?.key

/** What he reads when he said stop while both builders work. Nothing is stopped until he says which. */
const val WHICH_BUILDER = "Both builders are working. Which one should stop, A or B?"

/** How a builder is called in the lines about what was stopped and where his words wait. */
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
fun builderBShown(tasks: List<ChatTask>, working: Boolean): Boolean = working || tasks.any { it.build && it.builderId == BuilderId.B }
