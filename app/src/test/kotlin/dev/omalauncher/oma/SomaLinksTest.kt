package dev.omalauncher.oma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SomaLinksTest {
    @Test fun aLinkNamesAThreadAChatOrSoma() {
        assertEquals("mac:testhost/t1a2b3c4d", somaLinkKey("soma://thread/testhost/t1a2b3c4d"))
        assertEquals("mac:t1a2b3c4d", somaLinkKey("soma://thread/t1a2b3c4d"))
        assertEquals("mac:omarchy-mg1/t00ff00ff", somaLinkKey("  SOMA://thread/omarchy-mg1/t00ff00ff/?from=herd#x "))
        assertEquals(chatKey("cmgx2k1a"), somaLinkKey("soma://chat/cmgx2k1a"))
        assertEquals(OMA_TAB, somaLinkKey("soma://soma"))
        assertEquals(OMA_TAB, somaLinkKey("soma://"))
        assertEquals("soma://thread/testhost/t1a2b3c4d", somaThreadLink("testhost/t1a2b3c4d"))
        assertEquals("mac:testhost/t1a2b3c4d", somaLinkKey(somaThreadLink("testhost/t1a2b3c4d")))
    }

    @Test fun anythingElseOpensNothing() {
        for (bad in listOf(
            "https://soma/thread/t1a2b3c4d", "soma://thread/", "soma://thread/../t1a2b3c4d", "soma://thread/Host/t1a2b3c4d",
            "soma://thread/testhost/t1a2b3c4", "soma://thread/testhost/t1a2b3c4d/extra", "soma://chat/C1", "soma://chat/a b",
            "soma://settings", "soma://thread/testhost/t1a2b3c4d" + "x".repeat(300), "", "soma:thread/t1a2b3c4d",
        )) assertNull(bad, somaLinkKey(bad))
    }

    @Test fun aThreadWhoseChatLivesOnItOpensTheChat() {
        val book = TabMeta().started("c1", 5).onMac("c1", "testhost/t1a2b3c4d")
        assertEquals(chatKey("c1"), somaLinkTab("soma://thread/testhost/t1a2b3c4d", book))
        assertEquals("mac:testhost/t99999999", somaLinkTab("soma://thread/testhost/t99999999", book))
        assertEquals(OMA_TAB, somaLinkTab("soma://soma", book))
        assertNull(somaLinkTab("soma://nope", book))
    }
}
