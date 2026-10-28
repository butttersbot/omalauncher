package dev.omalauncher.widgets.annotations

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.omalauncher.App
import java.io.File
import java.util.concurrent.Executors

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
