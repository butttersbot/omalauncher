package dev.omalauncher.messages.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PictureShapesTest {
    @Test fun theMacsSizesGiveTheShapeBeforeThePictureComes() {
        PictureShapes.fromTheMac("a", 800, 1200)
        assertEquals(1.5f, PictureShapes.of("a"))
    }

    @Test fun aMissingSizeSaysNothing() {
        PictureShapes.fromTheMac("b", 0, 0)
        PictureShapes.fromTheMac("c", 800, 0)
        assertNull(PictureShapes.of("b"))
        assertNull(PictureShapes.of("c"))
    }

    @Test fun thePictureItselfOutranksTheMacWhichOutranksNothingAgain() {
        PictureShapes.fromTheMac("d", 400, 240)
        PictureShapes.learned("d", 1.666f)
        PictureShapes.fromTheMac("d", 400, 240)
        assertEquals(1.666f, PictureShapes.of("d"))
    }

    @Test fun aHistoryMessageCarriesItsPicturesShape() {
        MacWire.msg(JSONObject().put("id", "m").put("attachments", org.json.JSONArray().put(
            JSONObject().put("id", "wire").put("name", "p.jpg").put("mime", "image/jpeg").put("width", 1000).put("height", 500))))
        assertEquals(0.5f, PictureShapes.of("wire"))
    }
}
