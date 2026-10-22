package dev.omalauncher.herd

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PetWidgetTest {
    private val pikachu = """{"name":"pikachu","url":"http://100.120.219.46:8479/v1/skins/pikachu/sheet.png?v=3","frame":[32,32],"rows":["down","up","left","right"],"stand":0,"walk":[0,1]}"""

    @Test fun readsAWalkerSkin() {
        val s = PetSkin.parse(JSONObject(pikachu))!!
        assertEquals(32 to 32, s.frame)
        assertEquals(0 to 0, s.cell("down", -1, 2))
        assertEquals(1 to 3, s.cell("right", 1, 2))
        assertEquals(0 to 3, s.cell("right", 2, 2))
    }

    @Test fun readsCronosPoses() {
        val s = PetSkin.parse(JSONObject("""{"name":"crono","url":"http://jacks-mac-pro:8479/v1/skins/crono/sheet.png","frame":[64,64],"rows":["down","up","left","right"],"stand":0,"walk":[1,2],"poses":{"down":[0,4,5,6],"right":[2,16,17]}}"""))!!
        assertEquals(0 to 0, s.cell("down", -1, 7))
        assertEquals(4 to 0, s.cell("down", 0, 7))
        assertEquals(2 to 2, s.cell("right", 0, 7))
        assertEquals(3 to 2, s.cell("right", 1, 7))
    }

    @Test fun takesSheetsOnlyFromAHerdReporter() {
        assertNull(PetSkin.parse(JSONObject(pikachu.replace("8479", "80"))))
        assertNull(PetSkin.parse(JSONObject(pikachu.replace("/v1/skins/pikachu/sheet.png", "/v1/file?p=/etc/passwd"))))
        assertNull(PetSkin.parse(JSONObject(pikachu.replace("\"pikachu\"", "\"../x\""))))
        assertNotNull(PetSkin.parse(JSONObject(pikachu)))
    }

    @Test fun framesBecomeSpriteRows() {
        val o = 0xff112233.toInt()
        val y = 0xfff8e038.toInt()
        // A 4x2 sheet of two 2x2 frames.
        val pixels = intArrayOf(o, 0, y, y, 0, o, y, 0)
        val palette = LinkedHashMap<Int, Char>()
        assertEquals(listOf("a.", ".a"), PetWidget.spriteRows(pixels, 4, 2, 0, 0, 2, 2, palette))
        assertEquals(listOf("bb", "b."), PetWidget.spriteRows(pixels, 4, 2, 2, 0, 2, 2, palette))
        assertEquals(mapOf(o to 'a', y to 'b'), palette)
    }

    @Test fun pageSaysWhatItWears() {
        assertEquals(PetCommand.Skin(null), petCommand("""{"cmd":"skin","skin":null}"""))
        assertEquals("pikachu", (petCommand("""{"cmd":"skin","skin":$pikachu}""") as PetCommand.Skin).skin!!.name)
        assertEquals(PetCommand.Unknown, petCommand("""{"cmd":"skin","skin":{"name":"x"}}"""))
    }
}
