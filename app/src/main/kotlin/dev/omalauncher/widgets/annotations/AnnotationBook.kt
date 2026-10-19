package dev.omalauncher.widgets.annotations

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.App
import java.io.File
import java.util.concurrent.Executors

/**
 * The pages' annotations while the launcher runs: what home draws of ink, notes and stickers is read from
 * here, and every change is made here and written to the page's own file a moment later. The home layout's
 * store (the notebook) is someone else's and is never touched.
 *
 * The rules, which exist so that nothing he wrote is lost:
 *  - a page's file is read once, off the main thread, the first time the page is [open]ed;
 *  - a file that can't be read, or that a newer launcher wrote, is left exactly as it is: the page shows
 *    nothing free on it this session, takes no change, and the owner is told once ([notice]);
 *  - nothing is written until the notebook is being kept ([keep]): in a session whose layout isn't saved the
 *    page ids aren't to be relied on, so annotations show but can't be changed;
 *  - a change is written [SAVE_AFTER_MILLIS] after the last one, never on the main thread, by replacing the
 *    file in one rename; [flush] writes at once, for when home is left;
 *  - a deleted page's file goes with it ([pageGone]), set aside, not destroyed. A file found at the start for
 *    an id no page has, or one that couldn't be set aside when its page went, is left alone, unless a new page
 *    is later given that id: then it is set aside first, and if even that fails the new page shows nothing and
 *    takes no change this session. A new page never starts with an old page's ink, and never writes over it;
 *  - what was set aside is thinned once at the start ([AnnotationFiles.prune]), off the main thread;
 *  - a picture sticker shows the launcher's own copy of its image, made when it was stuck ([stick]), so the
 *    file it was picked from can change or go; a copy no page's file speaks of any more is deleted once at the
 *    start, and never while any file couldn't be looked at ([AnnotationFiles.pictures]).
 *
 * Whatever is put on a page by itself, a note from the owner's menu, a sticker or an agent's note, is placed
 * here and nowhere else, clear of the page's tiles and of what is already stuck on it ([PageBoard.noteSpot]).
 *
 * Main thread only. [background] runs a task off it, one at a time and in order; [foreground] brings one
 * back; [defer] runs a page's save a moment later, replacing the one already waiting for that page, or with
 * null calls it off.
 */
class AnnotationBook(
    private val files: AnnotationFiles,
    private val background: (() -> Unit) -> Unit,
    private val foreground: (() -> Unit) -> Unit,
    private val defer: (page: String, save: (() -> Unit)?) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    /** A page's grid and the tiles on it, by its id: what a new note or sticker is kept clear of. */
    private val board: (page: String) -> PageBoard = { PageBoard() },
    /** The launcher's own copies of the pictures stuck on pages; without them no picture is stuck. */
    private val snapshots: StickerSnapshots? = null,
) {
    enum class Status {
        /** Its file is being read. */
        Reading,

        /** Read, or there was nothing to read: it shows, and takes changes. */
        Ready,

        /** Its file is there and can't be used: nothing shows and nothing is written. */
        Unreadable,
    }

    private class Page(
        val status: Status,
        val marks: Annotations = Annotations(),
        val ink: Ink = Ink(marks.strokes),
    )

    private val pages = mutableStateMapOf<String, Page>()

    /** Counts a page id's deletions, so what was read or failed for the page before doesn't land on the one after. */
    private val lives = HashMap<String, Int>()
    private val unsaved = HashSet<String>()
    private val waiting = HashMap<String, ArrayList<(Boolean) -> Unit>>()

    /** Files that were there at the start for ids no page had. Touched only by [background] tasks. */
    private val strays = HashSet<String>()

    /** Whether changes are taken and written: the notebook is read and this session is kept. */
    var writable by mutableStateOf(false)
        private set

    /** Something the owner has to be told, in one plain sentence, until he taps it away. */
    var notice by mutableStateOf<String?>(null)

    /** The notebook is read and kept, with the pages [ids]: changes are taken from here on. */
    fun keep(ids: Set<String>) {
        if (writable) return
        writable = true
        background {
            runCatching { strays += files.pages() - ids }
            runCatching { files.prune(now()) }
            // Before anything is stuck this session: a copy made from here on is made by a later task than this one.
            runCatching { snapshots?.let { copies -> files.pictures()?.let(copies::sweep) } }
        }
    }

    /** Has [page]'s file read, if it hasn't been. */
    fun open(page: String) {
        if (page in pages) return
        if (!files.valid(page)) {
            pages[page] = Page(Status.Unreadable)
            return
        }
        pages[page] = Page(Status.Reading)
        val life = lives[page] ?: 0
        val kept = writable
        background {
            // A file from before this page was made: an older page's, whose id this one was given.
            // If it can't be set aside it is still not this page's: nothing of it shows, and nothing is written over it.
            val others = kept && page in strays && !(runCatching { files.remove(page, now()) }.getOrDefault(false))
            if (!others) strays -= page
            val read = if (others) AnnotationsRead.Unreadable(AnnotationsRead.Why.Failed) else files.read(page)
            foreground { if ((lives[page] ?: 0) == life && pages[page]?.status == Status.Reading) arrived(page, read) }
        }
    }

    private fun arrived(
        page: String,
        read: AnnotationsRead,
    ) {
        when (read) {
            AnnotationsRead.None -> pages[page] = Page(Status.Ready)
            is AnnotationsRead.Read -> pages[page] = Page(Status.Ready, read.annotations)
            is AnnotationsRead.Unreadable -> {
                pages[page] = Page(Status.Unreadable)
                notice = if (read.why == AnnotationsRead.Why.Newer) NEWER else UNREADABLE
            }
        }
        waiting.remove(page)?.forEach { it(pages[page]?.status == Status.Ready) }
    }

    /** Calls [then] once [page] has been read, saying whether it takes changes; at once if it already has. */
    fun whenRead(
        page: String,
        then: (Boolean) -> Unit,
    ) {
        open(page)
        if (pages[page]?.status == Status.Reading) waiting.getOrPut(page) { ArrayList() } += then else then(pages[page]?.status == Status.Ready)
    }

    fun status(page: String): Status? = pages[page]?.status

    /** What is on [page]: nothing while it is being read, or if it can't be. */
    fun on(page: String): Annotations = pages[page]?.marks ?: EMPTY

    fun ink(page: String): Ink = pages[page]?.ink ?: NO_INK

    /**
     * Whether [page] has anything written or stuck on it that would go with the page: what shows, or a file
     * that couldn't be read, which holds something even though none of it shows.
     */
    fun marked(page: String): Boolean = pages[page]?.let { it.status == Status.Unreadable || !it.marks.isEmpty } ?: false

    /** Whether [page] takes a change now; says why not where that is worth saying. */
    fun takes(page: String): Boolean = editable(page) != null

    private fun editable(page: String): Page? {
        val there = pages[page]
        when {
            there?.status == Status.Unreadable -> notice = UNREADABLE
            !writable -> notice = NOT_KEPT
            there?.status == Status.Ready -> return there
        }
        return null
    }

    /** Makes one change to [page] and has it written; false, and nothing changed, if the page takes none or [edit] gives null. */
    private fun change(
        page: String,
        edit: (Page) -> Page?,
    ): Boolean {
        val was = editable(page) ?: return false
        val next = edit(was) ?: return false
        pages[page] = next
        unsaved += page
        defer(page) { save(page) }
        return true
    }

    private fun Page.with(marks: Annotations) = Page(status, marks, ink)

    private fun Page.with(ink: Ink) = Page(status, marks.copy(strokes = ink.strokes), ink)

    /** Adds a stroke; false at the bound, where the page says it is full instead of dropping old ink (PRD UP-18). */
    fun draw(
        page: String,
        stroke: Stroke,
    ): Boolean =
        change(page) { was ->
            if (stroke.count == 0) return@change null
            was.ink.drew(stroke.fitted())?.let { was.with(it) } ?: null.also { notice = FULL }
        }

    /** Whether [page] has room for another stroke; says so when it hasn't. */
    fun roomToDraw(page: String): Boolean {
        val there = editable(page) ?: return false
        if (there.ink.full) notice = FULL
        return !there.ink.full
    }

    /**
     * Erases from [from], the ink as it was when the eraser came down, the strokes at [places]: all that one
     * pass of the eraser has touched so far, as a single step to undo.
     */
    fun erase(
        page: String,
        from: Ink,
        places: Collection<Int>,
    ): Boolean = change(page) { it.with(from.erased(places)) }

    /**
     * Wipes every stroke off [page] as one step, which undo brings back (Boss, 2026-10-19: pen marks taken off at once,
     * not after an update): how many went, 0 when there were none, or null if the page takes no change now.
     */
    fun wipe(page: String): Int? {
        val there = editable(page) ?: return null
        val count = there.ink.strokes.size
        if (count == 0) return 0
        return if (change(page) { it.with(it.ink.erased(it.ink.strokes.indices.toList())) }) count else null
    }

    fun undo(page: String): Boolean = change(page) { was -> was.ink.takeIf { it.canUndo }?.let { was.with(it.undo()) } }

    fun redo(page: String): Boolean = change(page) { was -> was.ink.takeIf { it.canRedo }?.let { was.with(it.redo()) } }

    /**
     * A new note from the owner, clear of the page's tiles and of the notes and stickers on it, as near as
     * there is room to [near], the place on the page (in fractions of it) he pressed to ask for it: the note
     * comes up under his finger where that is free. Of [role]: a sticky note, or plain text on the page. Its id,
     * or null if the page takes none.
     */
    fun addNote(
        page: String,
        near: Pair<Float, Float>? = null,
        role: NoteRole = NoteRole.Sticky,
    ): String? {
        var id: String? = null
        change(page) { was ->
            if (was.marks.notes.size >= AnnotationLimits.NOTES) return@change null.also { notice = NO_ROOM_NOTE }
            val spot = board(page).noteSpot(was.marks.scraps(), near?.let { it.first - NOTE_WIDTH / 2f to it.second - NOTE_HEIGHT / 2f }).box
            val note = StickyNote(freshId(was.marks, "n", now()), spot.x, spot.y, spot.w, spot.h, "", role, z = top(was.marks) + 1, at = now()).fitted()
            id = note.id
            was.with(was.marks.copy(notes = was.marks.notes + note))
        }
        return id
    }

    /**
     * Changes the note [id] as the owner does: moved, resized, retyped or recolored. Whatever he changes of an
     * agent's note makes it his, so the agent's next note never replaces it; [raise] also puts it on top.
     */
    fun changeNote(
        page: String,
        id: String,
        raise: Boolean = false,
        edit: (StickyNote) -> StickyNote,
    ): Boolean =
        change(page) { was ->
            val note = was.marks.notes.firstOrNull { it.id == id } ?: return@change null
            val z = if (raise && note.z < top(was.marks)) top(was.marks) + 1 else note.z
            val next = edit(note).copy(id = note.id, author = Author.Owner, z = z).let { it.copy(text = noteText(it.text)) }.fitted()
            // A change that changes nothing leaves an agent's note the agent's.
            if (next.copy(author = note.author) == note) return@change null
            was.with(was.marks.copy(notes = was.marks.notes.map { if (it.id == id) next else it }))
        }

    /**
     * The owner has taken the note [id] in hand: opened it to type in, or selected it. It is his from now on,
     * whatever he then does with it, so an agent's next note never takes its place while he is at it.
     */
    fun claimNote(
        page: String,
        id: String,
    ): Boolean =
        change(page) { was ->
            val note = was.marks.notes.firstOrNull { it.id == id && it.author != Author.Owner } ?: return@change null
            was.with(was.marks.copy(notes = was.marks.notes.map { if (it.id == id) note.copy(author = Author.Owner) else it }))
        }

    /**
     * A new sticker, clear of the page's tiles and of what is stuck on it where there is room, as near as that
     * allows to [near], the place on the page he pressed; without one, to the middle of the page's top half.
     */
    fun addSticker(
        page: String,
        kind: StickerKind,
        near: Pair<Float, Float>? = null,
    ): String? = place(page, kind, null, near)

    private fun place(
        page: String,
        kind: StickerKind,
        picture: Picture?,
        near: Pair<Float, Float>?,
    ): String? {
        var id: String? = null
        change(page) { was ->
            if (was.marks.stickers.size >= AnnotationLimits.STICKERS) return@change null.also { notice = NO_ROOM_STICKER }
            val centre = near ?: STICKER_START
            // The room it takes where it is wanted: a square on screen, so less tall than wide in fractions of the page, or its picture's shape.
            val wanted = Sticker("", kind, centre.first, centre.second, STICKER_SIZE, picture = picture?.fitted()).taken()
            val tiles = board(page).let { board -> board.tiles.map { board.box(it.cell) } }
            val spot = clearSpot(tiles + was.marks.scraps(), wanted.w, wanted.h, wanted.x to wanted.y).box
            val sticker = Sticker(freshId(was.marks, "s", now()), kind, spot.x + spot.w / 2f, spot.y + spot.h / 2f, STICKER_SIZE, picture = picture).fitted()
            id = sticker.id
            was.with(was.marks.copy(stickers = was.marks.stickers + sticker))
        }
        return id
    }

    /**
     * Sticks what was picked on [page], as [addSticker] does, and tells [then] its id, or null if the page took
     * none. A built-in sticker is there at once. A picture is first copied into the launcher's own files, off
     * the main thread, and the sticker shows that copy: the file it was picked from may change or go afterwards
     * and what is stuck stays as it was. One that can't be read as a picture is not stuck, and the owner is told.
     */
    fun stick(
        page: String,
        choice: StickerChoice,
        near: Pair<Float, Float>? = null,
        then: (String?) -> Unit,
    ) {
        when (choice) {
            is StickerChoice.BuiltIn -> then(addSticker(page, choice.kind, near))
            is StickerChoice.Pictured -> {
                val copies = snapshots
                val there = editable(page)
                if (copies == null || there == null) return then(null)
                // Asked before the copy is made, so a full page leaves none behind.
                if (there.marks.stickers.size >= AnnotationLimits.STICKERS) {
                    notice = NO_ROOM_STICKER
                    return then(null)
                }
                val life = lives[page] ?: 0
                background {
                    val picture = copies.take(choice.file, choice.name)
                    foreground {
                        when {
                            (lives[page] ?: 0) != life -> then(null)
                            picture == null -> then(null).also { notice = NO_PICTURE }
                            else -> then(place(page, PICTURE_KIND, picture, near))
                        }
                    }
                }
            }
        }
    }

    fun changeSticker(
        page: String,
        id: String,
        edit: (Sticker) -> Sticker,
    ): Boolean =
        change(page) { was ->
            val sticker = was.marks.stickers.firstOrNull { it.id == id } ?: return@change null
            val next = edit(sticker).copy(id = sticker.id).fitted()
            if (next == sticker) return@change null
            was.with(was.marks.copy(stickers = was.marks.stickers.map { if (it.id == id) next else it }))
        }

    /** Takes the note or sticker [id] off the page. */
    fun remove(
        page: String,
        id: String,
    ): Boolean =
        change(page) { was ->
            if (was.marks.scrap(id) == null) return@change null
            was.with(was.marks.copy(notes = was.marks.notes.filter { it.id != id }, stickers = was.marks.stickers.filter { it.id != id }))
        }

    /**
     * Carries the note or sticker [id] from [from] over to [to], to [x], [y] of that page; see [carryScrap].
     * Its id there, or null, and nothing changed, if either page takes no change or [to] is full, which it says.
     */
    fun moveScrap(
        from: String,
        to: String,
        id: String,
        x: Float,
        y: Float,
    ): String? {
        val here = editable(from) ?: return null
        val there = editable(to) ?: return null
        val crossed = carryScrap(here.marks, there.marks, id, x, y, now())
        if (crossed == null) {
            when (here.marks.scrap(id)) {
                is Scrap.Note -> notice = NO_ROOM_NOTE
                is Scrap.Stuck -> notice = NO_ROOM_STICKER
                null -> {}
            }
            return null
        }
        change(to) { it.with(crossed.to) }
        change(from) { it.with(crossed.from) }
        return crossed.id
    }

    /**
     * An agent's note on [page], near the cell [near] (column, row) when one is asked for; see [leaveAgentNote].
     * The page must have been read ([whenRead]).
     */
    fun leaveNote(
        page: String,
        text: String,
        near: Pair<Int, Int>? = null,
    ): NoteLeft {
        val there = pages[page]
        if (!writable) return NoteLeft.Refused("Home's layout isn't being saved this session, so no note can be kept. Nothing was written.")
        if (there?.status != Status.Ready) return NoteLeft.Refused("That page's notes can't be read, so nothing was written.")
        val left = leaveAgentNote(there.marks, text, now(), board(page), near)
        if (left is NoteLeft.Left) change(page) { it.with(left.page) }
        return left
    }

    private fun top(marks: Annotations) = marks.notes.maxOfOrNull { it.z } ?: 0

    private fun save(page: String) {
        if (!unsaved.remove(page) || !writable) return
        val there = pages[page]?.takeIf { it.status == Status.Ready } ?: return
        val marks = there.marks
        val life = lives[page] ?: 0
        background {
            try {
                files.write(page, marks)
            } catch (e: Throwable) {
                // Still in memory, and written with the next change or when home is left.
                foreground {
                    if ((lives[page] ?: 0) == life && pages[page]?.status == Status.Ready) {
                        unsaved += page
                        notice = NOT_SAVED
                    }
                }
            }
        }
    }

    /** Writes what is waiting to be written, now: home is being left. */
    fun flush() {
        for (page in unsaved.toList()) {
            defer(page, null)
            save(page)
        }
    }

    /** [page] was deleted: its annotations go with it, and whatever page is given its id later starts with none. */
    fun pageGone(page: String) {
        lives[page] = (lives[page] ?: 0) + 1
        defer(page, null)
        unsaved -= page
        pages.remove(page)
        waiting.remove(page)?.forEach { it(false) }
        // In a session that isn't kept the page is back at the next start, and its file with it.
        // One that can't be set aside stays, marked as no page's: whoever is given this id next doesn't get it.
        if (writable && files.valid(page)) background { if (!runCatching { files.remove(page, now()) }.getOrDefault(false)) strays += page }
    }

    companion object {
        /** How long after the last change a page is written. */
        const val SAVE_AFTER_MILLIS = 400L

        const val UNREADABLE = "This page's ink and notes couldn't be read, so they are hidden and their file is left as it is."
        const val NEWER = "This page's ink and notes were saved by a newer Soma, so they are hidden and their file is left as it is."
        const val NOT_KEPT = "Home's layout isn't being saved this session, so ink and notes can't be changed."
        const val FULL = "This page is full of ink (2,000 strokes). Nothing more is added; erase some to draw again."
        const val NO_ROOM_NOTE = "This page has all the notes it takes. Delete one to add another."
        const val NO_ROOM_STICKER = "This page has all the stickers it takes. Delete one to add another."
        const val NO_PICTURE = "That picture couldn't be read, so no sticker was made of it."
        const val NOT_SAVED = "The last change to this page's ink and notes couldn't be saved yet. It will be tried again."

        /** Where a sticker's centre is wanted when no place was pressed: the middle of the page's top half. */
        private val STICKER_START = 0.5f to 0.25f

        private val EMPTY = Annotations()
        private val NO_INK = Ink()
    }
}

/** The one [AnnotationBook] of the running launcher, for home and for the agent's bridge alike. */
object HomeAnnotations {
    private var book: AnnotationBook? = null

    /**
     * The book, made on first use: its files are `annotations/` in the app's private files, read and written on
     * a thread of their own, and the pictures stuck on pages are [HomeStickers]' copies beside them. Each call
     * also lets it take changes once the notebook is read and kept, and from the first one the notebook tells it
     * of every page that goes. Main thread.
     */
    fun of(context: Context): AnnotationBook {
        val app = context.applicationContext
        val notebook = (app as App).runtime.widgets.notebook
        val book =
            book ?: run {
                val main = Handler(Looper.getMainLooper())
                val io = Executors.newSingleThreadExecutor { task -> Thread(task, "annotations").apply { isDaemon = true } }
                val saves = HashMap<String, Runnable>()
                AnnotationBook(
                    files = AnnotationFiles(File(app.filesDir, "annotations")),
                    snapshots = HomeStickers.of(app).snapshots,
                    background = { task -> io.execute(task) },
                    foreground = { task -> main.post(task) },
                    board = notebook::board,
                    defer = { page, save ->
                        saves.remove(page)?.let(main::removeCallbacks)
                        if (save != null) {
                            val run = Runnable {
                                saves.remove(page)
                                save()
                            }
                            saves[page] = run
                            main.postDelayed(run, AnnotationBook.SAVE_AFTER_MILLIS)
                        }
                    },
                ).also { made ->
                    this.book = made
                    notebook.onPageGone = made::pageGone
                }
            }
        if (notebook.opened && notebook.kept) book.keep(notebook.pages.map { it.id }.toSet())
        return book
    }
}
