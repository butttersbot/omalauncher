package dev.omalauncher.ui

import dev.omalauncher.theme.pack.moduleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Home's transitions take their spring, their fade and their easing from [Motion] and nowhere else, so
 * they feel alike and are tuned in one place. This reads the sources that have a transition and fails
 * for one that builds a spec of its own, animates without naming the shared one, or lets a tile's
 * tilt turn a layer.
 */
class MotionSourceCheckTest {
    /** The files with a transition in them, under the source root. ui/Motion.kt is where the specs are made. */
    private val moving =
        listOf(
            "ui/WidgetTile.kt",
            // The working dot's pulse (Boss, 2026-10-13): on the shared timing like every other transition.
            "ui/AgentTile.kt",
            "ui/ThreadDot.kt",
            "ui/Home.kt",
            "ui/HomeGrid.kt",
            "ui/HomeMenu.kt",
            "ui/Menu.kt",
            "ui/Folders.kt",
            "ui/Overview.kt",
            "ui/Notes.kt",
            "ui/Stickers.kt",
            "ui/Dock.kt",
            "ui/Launcher.kt",
            "ui/LauncherState.kt",
            "oma/ring/HomeRing.kt",
            "oma/ring/RingFrame.kt",
            "oma/ring/RingPainter.kt",
            "oma/ring/RingState.kt",
            "oma/ring/OmaRing.kt",
        )

    /** A spec made on the spot, or an animation started without one. */
    private val adHoc =
        linkedMapOf(
            "tween(" to Regex("""\btween\s*\("""),
            "spring(" to Regex("""\bspring\s*\("""),
            "keyframes" to Regex("""\bkeyframes\b"""),
            "repeatable(" to Regex("""\b(?:infiniteR|r)epeatable\s*\("""),
            "snap(" to Regex("""\bsnap\s*\("""),
            "Spring." to Regex("""\bSpring\s*\."""),
            // An Animatable goes where it is going through moveTo, which is the shared spring and honors "remove animations".
            ".animateTo(" to Regex("""\.animateTo\s*\("""),
            "AnimatedVisibility" to Regex("""\b(?:AnimatedVisibility|AnimatedContent|Crossfade)\b"""),
            "updateTransition" to Regex("""\b(?:updateTransition|rememberInfiniteTransition)\b"""),
        )

    /** `animateXAsState(` is allowed only on a line that hands it the shared spec. */
    private val asState = Regex("""\banimate\w+AsState\s*\(""")

    /**
     * Deliberate exceptions, which none of the patterns above match and which are left as they were:
     * the pager turning to a page (`pager.animateScrollToPage`, in ui/HomeGrid.kt and ui/Home.kt) and the
     * overview scrolling to a new page's tile (`grid.animateScrollToItem`, in ui/Overview.kt) are the
     * scroll containers' own motion, with their own fling physics. The ring's breathing and its coming up
     * are on the ring's own clock (oma/ring/RingFrame.kt), not an animation spec at all.
     */
    private fun code(file: File) =
        file.readLines().map { it.trim() }.filterNot {
            it.startsWith("import ") || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
        }

    @Test fun theTransitionsUseOnlyTheSharedSpecs() {
        val found = ArrayList<String>()
        for (name in moving) {
            val file = dev.omalauncher.theme.pack.mainSource(name)
            assertTrue("$name is listed as having a transition but isn't a source file", file.isFile)
            for (line in code(file)) {
                for ((what, pattern) in adHoc) if (pattern.containsMatchIn(line)) found += "$name: $what in `$line`"
                if (asState.containsMatchIn(line) && "Motion." !in line) found += "$name: an animate…AsState without the shared spec in `$line`"
            }
        }
        assertEquals("transitions with a spec of their own", emptyList<String>(), found)
    }

    @Test fun theSpecsAreMadeInOnePlace() {
        val motion = code(dev.omalauncher.theme.pack.mainSource("ui/Motion.kt"))
        assertEquals("one spring", 1, motion.count { adHoc.getValue("spring(").containsMatchIn(it) })
        // The fade, and Motion.timed: the one door for a transition the prototype times itself.
        assertEquals("one fade and one prototype timing", 2, motion.count { adHoc.getValue("tween(").containsMatchIn(it) })
        // And the one decision about "remove animations" is made here, from the platform's one answer: on the phone,
        // its platform file is the only place that reads the system's setting (the desktop has none to read).
        assertEquals(1, motion.count { "systemAnimationsOn()" in it })
        assertTrue(motion.any { "reducedMotion(" in it && "systemAnimationsOn()" in it })
        val asks = dev.omalauncher.theme.pack.mainSources("ui", "oma").filter { file -> code(file).any { "areAnimatorsEnabled" in it || "ANIMATOR_DURATION_SCALE" in it } }
        assertEquals(listOf("Platform.android.kt"), asks.map { it.name })
    }

    /** ScrapFrame's rule stands in what was added: a tilt is turned on the canvas while drawing, never by a layer, which would turn where a tile is touched. */
    @Test fun noTransitionTurnsALayerForATilt() {
        fun source(name: String) = dev.omalauncher.theme.pack.mainSource(name)
        for (name in listOf("ui/Motion.kt", "ui/WidgetTile.kt", "ui/HomeGrid.kt", "ui/Folders.kt", "ui/HomeMenu.kt", "ui/Menu.kt", "ui/Notes.kt", "ui/Overview.kt")) {
            assertTrue("$name turns a layer", code(source(name)).none { "rotationZ" in it || "rotationX" in it || "rotationY" in it })
        }
        // The one layer that turns is a sticker's own picture, inside its upright box, as before this slice.
        assertEquals(1, code(source("ui/Stickers.kt")).count { "rotationZ" in it })
        val tile = code(source("ui/WidgetTile.kt"))
        assertTrue(tile.any { "rotate(growTilt(tilt, t)" in it })
    }

    /**
     * No transition changes what layout measures: the modifiers that ease things in and out are a layer's
     * scale, opacity and clip, and Motion.kt has no layout or size modifier in it at all.
     */
    @Test fun motionIsOnlyEverALayer() {
        val motion = code(dev.omalauncher.theme.pack.mainSource("ui/Motion.kt"))
        val lays = Regex("""\b(?:layout|size|width|height|padding|offset|requiredSize|animateContentSize)\s*[({]""")
        assertEquals(emptyList<String>(), motion.filter { lays.containsMatchIn(it) && "Modifier" in it })
        assertTrue(motion.none { "animateContentSize" in it })
        assertTrue(motion.none { "withFrameNanos" in it || "withFrameMillis" in it })
    }
}
