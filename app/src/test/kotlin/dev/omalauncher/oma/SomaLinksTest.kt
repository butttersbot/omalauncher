package dev.omalauncher.oma

import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.PHONE_THREAD
import dev.omalauncher.assist.ThreadState
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

    @Test fun sessionIdsAndPhoneBuildersHaveLinks() {
        assertEquals("mac:t13d39067ed6a9bef", somaLinkKey("soma://thread/t13d39067ed6a9bef"))
        assertEquals("mac:macbook-pro/t13d39067ed6a9bef", somaLinkKey("soma://thread/macbook-pro/t13d39067ed6a9bef"))
        assertEquals(PHONE_THREAD, somaLinkKey("soma://phone/A"))
        assertEquals("phone-b", somaLinkKey("soma://phone/B"))
        assertEquals("phone-c", somaLinkKey("soma://phone/c"))
        assertEquals("phone-d", somaLinkKey("soma://phone/D"))
    }

    private fun thread(key: String) = AgentThread(key, key, ThreadState.Idle, "", "idle")
    private fun tab(key: String) = SomaLinkTarget.Tab(key)

    @Test fun aThreadIsFoundOnWhicheverComputerHasIt() {
        val onPhone = listOf(thread(PHONE_THREAD), thread("mac:t850cdc07"), thread("mac:macbook-pro/t13d39067ed6a9bef"))
        // The phone's first Mac has no name: a link from the Mac Pro that names it still lands, and one with the id alone.
        assertEquals(tab("mac:t850cdc07"), somaLinkTarget("soma://thread/t850cdc07", onPhone, TabMeta()))
        assertEquals(tab("mac:t850cdc07"), somaLinkTarget("soma://thread/jacks-mac-pro/t850cdc07", onPhone, TabMeta()))
        assertEquals(tab("mac:macbook-pro/t13d39067ed6a9bef"), somaLinkTarget("soma://thread/macbook-pro/t13d39067ed6a9bef", onPhone, TabMeta()))
        assertEquals(tab("mac:macbook-pro/t13d39067ed6a9bef"), somaLinkTarget("soma://thread/t13d39067ed6a9bef", onPhone, TabMeta()))
        // A desktop names every computer: the id alone finds it there too.
        val onDesktop = listOf(thread("mac:jacks-mac-pro/t850cdc07"))
        assertEquals(tab("mac:jacks-mac-pro/t850cdc07"), somaLinkTarget("soma://thread/t850cdc07", onDesktop, TabMeta()))
        // A named computer never lands on another named one.
        assertEquals(SomaLinkTarget.Nothing("Can't find that thread", listing = true), somaLinkTarget("soma://thread/omarchy-mg1/t2b7e41f0", listOf(thread("mac:macbook-pro/t2b7e41f0")), TabMeta()))
    }

    @Test fun aThreadWhoseChatLivesOnItOpensTheChatWhereverItIs() {
        val book = TabMeta().started("c1", 5).onMac("c1", "macbook-pro/t2b7e41f0")
        val threads = listOf(thread(chatKey("c1")))
        assertEquals(tab(chatKey("c1")), somaLinkTarget("soma://thread/t2b7e41f0", threads, book))
        assertEquals(tab(chatKey("c1")), somaLinkTarget("soma://thread/macbook-pro/t2b7e41f0", threads, book))
        assertEquals(tab(chatKey("c1")), somaLinkTarget("soma://chat/c1", threads, book))
    }

    @Test fun whatIsNotHereIsSaidAndGivesNoTab() {
        val threads = listOf(thread(PHONE_THREAD), thread("mac:t850cdc07"))
        assertEquals(tab(OMA_TAB), somaLinkTarget("soma://soma", threads, TabMeta()))
        assertEquals(tab(PHONE_THREAD), somaLinkTarget("soma://phone/A", threads, TabMeta()))
        assertEquals(SomaLinkTarget.Nothing("Can't find that thread", listing = true), somaLinkTarget("soma://thread/tdeadbeef", threads, TabMeta()))
        assertEquals(SomaLinkTarget.Nothing("Can't find that chat"), somaLinkTarget("soma://chat/gone", threads, TabMeta()))
        // The desktop has no phone builders, and the phone has no tab for Phone D (mail only).
        assertEquals(SomaLinkTarget.Nothing("Phone D has no tab here"), somaLinkTarget("soma://phone/D", threads, TabMeta()))
        assertEquals(SomaLinkTarget.Nothing("That link doesn't open anything"), somaLinkTarget("soma://nonsense/here", threads, TabMeta()))
    }

    @Test fun anythingElseOpensNothing() {
        for (bad in listOf(
            "https://soma/thread/t1a2b3c4d", "soma://thread/", "soma://thread/../t1a2b3c4d", "soma://thread/Host/t1a2b3c4d",
            "soma://thread/testhost/t1a2b3c4", "soma://thread/testhost/t1a2b3c4d/extra", "soma://chat/C1", "soma://chat/a b",
            "soma://settings", "soma://thread/testhost/t1a2b3c4d" + "x".repeat(300), "", "soma:thread/t1a2b3c4d",
            "soma://thread/t1a2b3c4d5", "soma://thread/t13d39067ed6a9bef0", "soma://thread/t13D39067ED6A9BEF", "soma://thread/x13d39067ed6a9bef",
            "soma://phone", "soma://phone/", "soma://phone/E", "soma://phone/AB", "soma://phone/A/x",
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
