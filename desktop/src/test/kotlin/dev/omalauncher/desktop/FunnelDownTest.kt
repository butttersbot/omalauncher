package dev.omalauncher.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import dev.omalauncher.canvas.ComputersModule
import dev.omalauncher.canvas.ModuleComputer
import dev.omalauncher.canvas.ModuleSize
import dev.omalauncher.canvas.PublicDoor
import dev.omalauncher.canvas.downFor
import dev.omalauncher.canvas.publicDoorOf
import org.jetbrains.skia.EncodedImageFormat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Boss, 2026-10-17: when Funnel stops answering, the phone's Computers module says so (the service runs headless). */
@OptIn(ExperimentalTestApi::class)
class FunnelDownTest {
    private val computers = listOf(ModuleComputer("jacks-mac-pro", true, "load 4.2"), ModuleComputer("macbook-pro", true, "load 3.1"), ModuleComputer("galaxy-z-fold8", true))
    private val now = 1_792_300_000_000L

    @Test fun theServicesHealthSaysHowItsPublicDoorStands() {
        fun health(public: JSONObject?) = JSONObject().put("ok", true).put("service", "soma-serve").apply { if (public != null) put("public", public) }
        assertEquals(PublicDoor(false, 5), publicDoorOf(health(JSONObject().put("url", "https://x.ts.net").put("up", false).put("since", 5))))
        assertEquals(PublicDoor(true, 5), publicDoorOf(health(JSONObject().put("url", "https://x.ts.net").put("up", true).put("since", 5))))
        // No public address, not looked yet, an older service, or no answer: nothing to say.
        assertNull(publicDoorOf(health(JSONObject().put("url", JSONObject.NULL).put("up", JSONObject.NULL).put("since", 0))))
        assertNull(publicDoorOf(health(JSONObject().put("url", "https://x.ts.net").put("up", JSONObject.NULL).put("since", 0))))
        assertNull(publicDoorOf(health(null)))
        assertNull(publicDoorOf(null))
    }

    /** Round-1 review: the refresh is what hands the door to the module; a cut there must not ship green. */
    @Test fun aRefreshReadsTheDoorAndAFailedReadShowsNothing() {
        val notices = Notices()
        val platform = DesktopThreadsPlatform(notices) { emptyList() }
        val engine = dev.omalauncher.assist.MacThreadsEngine(platform)
        val host = DesktopChatHost(platform, engine, DesktopFrontAgent(engine, platform, notices, places = emptyList()), notices)
        var door: () -> PublicDoor? = { PublicDoor(false, 5) }
        val source = dev.omalauncher.canvas.chatHostModules(host, publicDoor = { door() })
        assertNull(source.publicDoor())
        source.refresh(emptyList())
        assertEquals(PublicDoor(false, 5), source.publicDoor())
        door = { null }
        source.refresh(emptyList())
        assertNull(source.publicDoor())
        source.refresh(emptyList())
        door = { error("no answer") }
        source.refresh(emptyList())
        assertNull(source.publicDoor())
    }

    @Test fun thePhoneRemembersWhereTheServiceIsAndLooksAgainOnlyWhenItStopsAnswering() {
        val machine = dev.omalauncher.assist.MacMachine("macpro", "jacks-mac-pro", dev.omalauncher.assist.MacRunnerConfig("http://mac:8478", "k"))
        var asked = 0
        var up = true
        val reader = dev.omalauncher.canvas.PublicDoorReader({ listOf(machine) }, advertised = { _, _ -> asked++; "http://mac:8481" },
            health = { _, _ -> if (up) JSONObject().put("service", "soma-serve").put("public", JSONObject().put("url", "https://x").put("up", false).put("since", 7)) else null })
        repeat(3) { assertEquals(PublicDoor(false, 7), reader.read()) }
        assertEquals(1, asked)
        up = false
        assertNull(reader.read())
        assertEquals(2, asked)
    }

    @Test fun howLongItHasBeenDown() {
        assertEquals("just now", downFor(now - 20_000, now))
        assertEquals("12 min", downFor(now - 12 * 60_000, now))
        assertEquals("3 h", downFor(now - 3 * 3_600_000 - 60_000, now))
        assertEquals("4 days", downFor(now - 4 * 86_400_000L, now))
        assertEquals("", downFor(0, now))
        assertEquals("", downFor(now + 60_000, now))
    }

    @Test fun downIsSaidFirstAtEverySizeAndUpSaysNothing() {
        for ((size, w, h) in listOf(Triple(ModuleSize.Card, 304, 228), Triple(ModuleSize.Card, 152, 152), Triple(ModuleSize.Line, 456, 76), Triple(ModuleSize.Full, 456, 304))) {
            runComposeUiTest {
                Looks.fonts(); Looks.use("city-pop")
                setContent { Box(Modifier.size(w.dp, h.dp)) { ComputersModule(computers, size, door = PublicDoor(false, now - 12 * 60_000), now = now) } }
                val said = onAllNodesWithText("Funnel down", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
                assertTrue("$size ${w}x$h", said.isNotEmpty())
            }
            runComposeUiTest {
                Looks.fonts(); Looks.use("city-pop")
                setContent { Box(Modifier.size(w.dp, h.dp)) { ComputersModule(computers, size, door = PublicDoor(true, now), now = now) } }
                assertTrue(onAllNodesWithText("Funnel", substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            }
        }
    }

    @Test fun drawsDown() {
        Looks.fonts(); Looks.use("city-pop")
        File("build/render").mkdirs()
        for ((name, size, w, h) in listOf(Quad("card", ModuleSize.Card, 304, 228), Quad("line", ModuleSize.Line, 456, 76), Quad("dot", ModuleSize.Dot, 76, 76))) {
            val png = ImageComposeScene(width = w * 2, height = h * 2, density = Density(2f)) {
                Box(Modifier.size(w.dp, h.dp)) { ComputersModule(computers, size, door = PublicDoor(false, now - 12 * 60_000), now = now) }
            }.use { it.render(); it.render(500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes }
            File("build/render/computers-funnel-down-$name.png").writeBytes(png)
        }
    }

    private data class Quad(val name: String, val size: ModuleSize, val w: Int, val h: Int)
}
