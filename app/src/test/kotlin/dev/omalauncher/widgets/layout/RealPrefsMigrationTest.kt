package dev.omalauncher.widgets.layout

import dev.omalauncher.widgets.lang.Compiler
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the migration on preference files pulled from a phone, and prints what it would do. Skipped unless
 * `OMA_PREFS_DIR` names a folder holding the launcher's `shared_prefs/widget_grid.xml` and `shortcuts.xml`
 * (and `widget_pages.xml`, if the phone still has one). The widgets that exist aren't in any preferences, so
 * give them too: a `widgets` folder with the phone's widget files (`*.owl`), or a `widgets.txt` with a
 * `file-name page WxH` line each (`-` for no size). Without either, widgets are guessed from the saved cells.
 *
 *     OMA_PREFS_DIR=/path/to/copy ./gradlew :app:testDebugUnitTest --tests '*RealPrefsMigrationTest*' --rerun-tasks -i
 *
 * Reads only; the result is printed and written beside the build as `build/notebook-migration/`.
 */
class RealPrefsMigrationTest {
    @Test fun migratesPulledPreferences() {
        val dir = System.getenv("OMA_PREFS_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("OMA_PREFS_DIR is not set", dir != null)
        dir!!
        check(File(dir, "widget_grid.xml").exists() || File(dir, "shortcuts.xml").exists()) { "neither widget_grid.xml nor shortcuts.xml in $dir" }
        val old = readStores(dir)
        val notes = ArrayList<String>()
        val widgets = widgets(dir, old, notes)

        // Throws if an item is lost, doubled or off its page, or if saving and reading back changes anything.
        val m = checked(old, widgets)

        val text =
            buildString {
                appendLine("== Notebook migration of $dir ==")
                appendLine("widget_grid: layout ${old.layout?.length ?: "absent"} chars, cells ${old.legacyCells?.length ?: "absent"}, portrait rows ${old.portraitRows}")
                appendLine("shortcuts: items ${old.shortcuts?.length ?: "absent"} chars, folders ${old.folders?.length ?: "absent"}; widget_pages: ${old.legacyPages}")
                appendLine("widgets: ${widgets.size}")
                notes.forEach { appendLine("note: $it") }
                appendLine(m.report.summary())
                appendLine(if (m.report.clean) "clean: this would be saved" else "NOT clean: this would be shown but not saved (UP-2)")
                appendLine("old page -> new page: ${m.report.pageIds}")
                for (move in m.report.moved) appendLine("moved: ${move.key}  ${move.from ?: "no cell"} -> ${move.to} on ${move.page}${if (move.newPage) " (new page)" else ""}")
                if (m.report.orphans.isNotEmpty()) appendLine("cells of nothing that exists: ${m.report.orphans}")
                for (page in m.notebook.pages) {
                    appendLine("-- ${page.id} \"${page.name}\": ${page.items.size} items")
                    for (item in page.items) appendLine("   ${item.cell}  ${item.kind}  ${item.key}${if (item.kind == ItemKind.Folder) "  ${item.apps.map { it.label }}" else ""}")
                }
            }
        println(text)
        val out = File("build/notebook-migration").apply { mkdirs() }
        File(out, "report.txt").writeText(text)
        File(out, "notebook.json").writeText(NotebookStore.write(m.notebook))
        println("written to ${out.absolutePath}")
    }

    private fun widgets(
        dir: File,
        old: OldStores,
        notes: MutableList<String>,
    ): List<OldWidget> {
        val files = File(dir, "widgets").listFiles { f -> f.extension == "owl" }
        val listed = File(dir, "widgets.txt")
        return when {
            files != null ->
                // As the launcher lists them: by page, then the file's order, then its name. Items without a cell are
                // placed in this order. A page chosen on the phone moves a widget between pages, not within one.
                files
                    .map { f ->
                        runCatching { Compiler.compile(f.readText()) }.fold(
                            { OldWidget(f.name, it.page, it.size) to it.order },
                            {
                                notes += "${f.name} doesn't compile; taken as page 1 without a size and last in order, where the launcher would keep its last page"
                                OldWidget(f.name, 1) to 999
                            },
                        )
                    }.sortedWith(compareBy({ it.first.page }, { it.second }, { it.first.key }))
                    .map { it.first }
                    .also { notes += "order: widgets by page, the file's order, then file name, as the launcher lists them; then apps, then folders" }
            listed.exists() -> readWidgets(listed).also { notes += "order: widgets as listed in widgets.txt; then apps, then folders" }
            else -> {
                // Everything with a saved cell or page that isn't an app or a folder; its file's own page is unknown here.
                val m = migrate(old, emptyList())
                notes += "order: widgets as their cells were saved; then apps, then folders"
                notes += "no widgets folder or widgets.txt: widgets guessed from saved cells, each on page 1 unless moved on the phone. A widget whose file names another page will be on the wrong page here."
                m.report.orphans.filter { !it.startsWith("app:") && !it.startsWith("linux:") && !it.startsWith("folder:") }.map { OldWidget(it, 1) }
            }
        }
    }
}
