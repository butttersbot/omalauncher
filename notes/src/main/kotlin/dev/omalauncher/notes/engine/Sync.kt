package dev.omalauncher.notes.engine

/** The Mac's side of the notes, as /v1/notes answers; [HttpLink] over the tailnet, a fake in the tests. */
interface Link {
    /** Every note on the Mac, or null when it can't be reached. */
    fun list(): List<Remote>?

    /** A note's text and sha, or null. */
    fun get(id: String): Pair<String, String>?

    /** [text] saved as [id]: on top of [sha], or new when [sha] is null. */
    fun put(
        id: String,
        text: String,
        sha: String?,
    ): Put

    fun delete(
        id: String,
        sha: String?,
    ): Boolean

    sealed interface Put {
        data class Saved(val sha: String) : Put

        /** Changed on the Mac since [sha] was agreed: its text now. */
        data class Conflict(val sha: String, val text: String) : Put

        /** Created, but a note of that name is there already. */
        data object Exists : Put

        data object Failed : Put
    }
}

/**
 * One round with the Mac: the phone's changes go out first (merged with the Mac's where both sides changed a note),
 * then whatever changed on the Mac comes in. The phone's copy is always written first, so nothing waits on this;
 * what can't go out now stays marked and goes next time.
 */
class Sync(
    private val shelf: Shelf,
    private val link: Link,
) {
    data class Result(val reached: Boolean, val sent: Int, val took: Int, val left: Int)

    fun run(): Result {
        val remote = link.list() ?: return Result(false, 0, 0, shelf.all().count { it.dirty || it.gone })
        val there = remote.associateBy { it.id }
        var sent = 0
        var took = 0
        // Sent this round: the list above is from before.
        val fresh = HashSet<String>()
        // Deleted here: the Mac moves it to its trash.
        for (n in shelf.all().filter { it.gone }) {
            if (n.id !in there || link.delete(n.id, n.sha)) shelf.forget(n.id)
        }
        // Changed here.
        for (n in shelf.all().filter { it.dirty && !it.gone }) {
            send(n, there)?.let {
                fresh += it
                sent++
            }
        }
        // Changed on the Mac.
        val mine = shelf.all().associateBy { it.id }
        for (r in remote) {
            val n = mine[r.id]
            if (r.id in fresh || n != null && (n.dirty || n.gone || n.sha == r.sha)) continue
            val (text, sha) = link.get(r.id) ?: continue
            shelf.took(r.copy(sha = sha), text)
            took++
        }
        // Gone from the Mac (deleted there, or moved): gone here too, unless changed here since.
        for (n in shelf.all()) {
            if (n.sha != null && !n.dirty && !n.gone && n.id !in there && n.id !in fresh) shelf.forget(n.id)
        }
        return Result(true, sent, took, shelf.all().count { it.dirty || it.gone })
    }

    /** Note [n] out to the Mac; the name it went as, or null. */
    private fun send(
        n: Note,
        there: Map<String, Remote>,
    ): String? {
        val text = shelf.text(n.id)
        if (n.local && text.isBlank()) {
            // An empty page never goes to the Mac.
            shelf.forget(n.id)
            return null
        }
        var id = if (n.local) Wire.fileFor(n.title, there.keys + shelf.all().map { it.id }) else n.id
        var sha = n.sha
        var body = text
        // Gone from the Mac while changed here: sent again as new.
        if (sha != null && id !in there) sha = null
        repeat(3) {
            when (val r = link.put(id, body, sha)) {
                is Link.Put.Saved -> {
                    shelf.sent(n.id, id, text, body, r.sha)
                    return id
                }
                is Link.Put.Conflict -> {
                    body = Merge.merge(shelf.base(n.id), body, r.text)
                    sha = r.sha
                }
                Link.Put.Exists -> id = Wire.fileFor(n.title, there.keys + shelf.all().map { it.id } + id)
                Link.Put.Failed -> return null
            }
        }
        return null
    }
}
