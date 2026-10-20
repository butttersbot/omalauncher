package dev.omalauncher.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.App
import dev.omalauncher.art.ArtPlacers
import dev.omalauncher.theme.pack.PackPanels
import dev.omalauncher.widgets.FoldLayouts
import dev.omalauncher.widgets.HomePanels
import dev.omalauncher.widgets.LiveNotebook
import dev.omalauncher.widgets.WidgetEngine
import dev.omalauncher.canvas.CanvasPad
import dev.omalauncher.widgets.castOf
import dev.omalauncher.widgets.fitHomeIntoPanels
import dev.omalauncher.widgets.layout.PanelLayouts
import dev.omalauncher.widgets.layout.inPanels
import dev.omalauncher.widgets.panelPageNamed

/*
 * Home's side of the Manga look (piece B) that isn't drawing: what happens as the look is switched to, and where art from
 * the share sheet or the panel picker is hung.
 */
object MangaHome {
    /** The name home keeps the arrangement under before it fits it into panels (piece D's shelf). */
    const val BEFORE = "Before Manga"

    private const val PREFS = "manga-home"

    /** Whether home was last fitted into panels for a look drawn in them, and hasn't been in another look since. */
    private const val FITTED = "fitted"

    /**
     * Whether home is in panels for the look: the switch into it has been handled (kept and fitted). Until then home draws
     * every page as the free grid, so nothing settles a page into panels before the fit has gathered and placed it all.
     */
    var ready by mutableStateOf(false)
        private set

    /** At the app's start: art from the share sheet and the panel picker is hung in home's panels, with a "パッ". */
    fun install(app: App) {
        ready = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(FITTED, false)
        ArtPlacers.home = HomePanels({ app.runtime.widgets.notebook }, onHung = { page, panel -> PanelFx.ink(page, panel, "パッ") })
    }

    /** What a look change asks of home. */
    enum class Step {
        /** Nothing: the look and home are as they were. */
        None,

        /** Keep home as [BEFORE], then fit it into panels: the look turned to one drawn in them. */
        Fit,

        /** Forget it was fitted: the look turned away from panels, so the next turn back fits again. */
        Forget,
    }

    /** The step for a look drawn in panels or not ([panels]), home [opened] or not, and fitted before or not ([fitted]). */
    fun step(
        fitted: Boolean,
        panels: Boolean,
        opened: Boolean,
    ): Step =
        when {
            !panels -> if (fitted) Step.Forget else Step.None
            fitted || !opened -> Step.None
            else -> Step.Fit
        }

    /**
     * The look drawn now has [panels] (null: it isn't drawn in them), with home open or not. Switched into such a look from one
     * that isn't, home is first kept on the shelf as [BEFORE], then each page that isn't in panels is fitted into the look's
     * default layout (its own, if it has one that fits); switched out of it, the pages stay as they are and the next switch in
     * fits again. Once per switch: remembered across starts. What to tell him, or null.
     */
    fun lookChanged(
        context: Context,
        engine: WidgetEngine,
        panels: PackPanels?,
    ): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fitted = prefs.getBoolean(FITTED, false)
        when (step(fitted, panels != null, engine.notebook.opened)) {
            Step.None -> {
                ready = fitted && panels != null
                return null
            }
            Step.Forget -> {
                ready = false
                prefs.edit().putBoolean(FITTED, false).apply()
                return null
            }
            Step.Fit -> prefs.edit().putBoolean(FITTED, true).apply()
        }
        // Only what is fitted is kept first: back in the look with every page still in panels, the arrangement from before
        // the first switch stays on the shelf rather than being replaced by the panels themselves.
        if (!needsFit(engine.notebook)) {
            ready = true
            return null
        }
        val kept = FoldLayouts.autoSave(context, BEFORE)
        val changed = fitInto(engine.notebook, FoldLayouts.pad(context), panels!!.defaultLayout)
        ready = true
        return if (!kept && changed) "Home is in panels now. Its old arrangement couldn't be kept on the Layouts shelf." else null
    }

    /** Whether a page of [notebook] isn't in panels: a free page, or one rearranged in another look. */
    fun needsFit(notebook: LiveNotebook): Boolean = notebook.snapshot().let { n -> n.pages.any { it.panels == null || !inPanels(n, it) } }

    /**
     * Each page of [notebook] not in panels fitted into [default] through home's canvas [pad] (one restore point), its loose
     * apps first gathered into one folder (the cast) where the page holds more than the layout has panels. Whether anything
     * changed.
     */
    fun fitInto(
        notebook: LiveNotebook,
        pad: CanvasPad,
        default: String,
    ): Boolean {
        var changed = false
        // As saved: a page in panels has an app filling its panel, where the pad's view shows it as one cell.
        val saved = notebook.snapshot()
        for (page in saved.pages) {
            if (page.panels != null && inPanels(saved, page)) continue
            val layout = page.panels?.takeIf { PanelLayouts.fits(it, saved.columns, saved.rows) } ?: default
            val cast = castOf(page, PanelLayouts.panels(layout, saved.columns, saved.rows).size)
            if (cast.size < 2) continue
            val folder = notebook.group(cast[1], cast[0]) ?: continue
            for (app in cast.drop(2)) notebook.group(app, folder)
            changed = true
        }
        val verbs = fitHomeIntoPanels(notebook.snapshot(), default, { now -> panelPageNamed(now, pad.rules.pageId(now)) })
        if (verbs.isEmpty()) return changed
        return pad.apply(verbs, "home:manga-" + System.currentTimeMillis().toString(36), "home") == null || changed
    }
}
