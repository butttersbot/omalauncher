package dev.omalauncher.assist

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** More than one Mac (MacMachines.kt): their files, their names, the phone's ids for their threads and how each stands. */
class MacMachinesTest {
    private val key = "k" + "ey-of-the-macbook"

    private fun file(url: String, name: String? = null) = JSONObject().put("url", url).put("key", key).apply { if (name != null) put("name", name) }.toString()

    @Test fun eachMacHasAFileOfItsOwnAndTheFirstKeepsItsOldOne() {
        assertEquals("mac-runner.json", macRunnerFile(MAC_FIRST))
        assertEquals("mac-runner-macbook-pro.json", macRunnerFile("macbook-pro"))
        assertEquals(MAC_FIRST, macMachineOfFile("mac-runner.json"))
        assertEquals("macbook-pro", macMachineOfFile("mac-runner-macbook-pro.json"))
        for (other in listOf("mac-runner-.json", "mac-runner-MacBook.json", "mac-runner-../x.json", "mac-runner.json.bak", "notes.json", "mac-runner-a b.json")) {
            assertNull(other, macMachineOfFile(other))
        }
    }

    @Test fun aMacIsCalledWhatItsFileSaysOrByItsId() {
        assertEquals("Mac Pro", macMachineName(MAC_FIRST))
        assertEquals("MacBook Pro", macMachineName("macbook-pro"))
        assertEquals("Mac mini", macMachineName("mac-mini"))
        assertEquals("iMac", macMachineName("imac"))
        assertEquals("Studio 2", macMachineName("studio-2"))
        assertEquals("Boss's Laptop", parseMacMachine("macbook-pro", file("http://100.2.2.2:8478", "  Boss's Laptop "))?.name)
        assertEquals("MacBook Pro", parseMacMachine("macbook-pro", file("http://100.2.2.2:8478"))?.name)
        assertNull(parseMacMachine("macbook-pro", "{}"))
        assertNull(parseMacMachine("macbook-pro", null))
    }

    @Test fun theKeyNeverPrints() {
        val mac = parseMacMachine("macbook-pro", file("http://100.2.2.2:8478", "MacBook Pro"))!!
        assertFalse(mac.toString().contains(key))
        assertTrue(mac.toString().contains("100.2.2.2"))
    }

    @Test fun theMacsAreReadFirstOneFirstAndAnythingElseIsLeftOut() {
        val dir = Files.createTempDirectory("macs").toFile()
        try {
            dir.resolve("mac-runner-macbook-pro.json").writeText(file("http://100.2.2.2:8478"))
            dir.resolve("mac-runner-a-studio.json").writeText(file("http://100.3.3.3:8478"))
            dir.resolve("mac-runner.json").writeText(file("http://100.1.1.1:8478"))
            dir.resolve("mac-runner-broken.json").writeText("{not json")
            dir.resolve("other.json").writeText(file("http://100.4.4.4:8478"))
            assertEquals(listOf(MAC_FIRST, "a-studio", "macbook-pro"), readMacMachines(dir).map { it.id })
            assertEquals("http://100.1.1.1:8478", readMacMachines(dir).first().config.url)
            dir.resolve("mac-runner.json").delete()
            // Without the first Mac's file the others are still there: one file gone hides no other Mac.
            assertEquals(listOf("a-studio", "macbook-pro"), readMacMachines(dir).map { it.id })
        } finally { dir.deleteRecursively() }
        assertEquals(emptyList<MacMachine>(), readMacMachines(dir))
    }

    @Test fun anotherMacsThreadsGoByItsIdAndTheRunnersAndTheFirstMacsAsTheyAlwaysDid() {
        assertEquals("t1a2b3c4d", macPhoneId(MAC_FIRST, "t1a2b3c4d"))
        assertEquals("macbook-pro/t1a2b3c4d", macPhoneId("macbook-pro", "t1a2b3c4d"))
        for (id in listOf("t1a2b3c4d", "macbook-pro/t1a2b3c4d")) {
            assertEquals(id, macPhoneId(macMachineOf(id), macRunnerId(id)))
        }
        assertEquals(MAC_FIRST, macMachineOf("t1a2b3c4d"))
        assertEquals("macbook-pro", macMachineOf("macbook-pro/t1a2b3c4d"))
        assertEquals("t1a2b3c4d", macRunnerId("macbook-pro/t1a2b3c4d"))
        // A new chat's entry waits under this word, which is the first Mac's as it always was.
        assertEquals(MAC_FIRST, macMachineOf(MAC_NEW_THREAD))
        val thread = MacThread("t1", "Herd", "/p", ThreadHome.Mac, "claude", "", MacThreadStatus.Idle, null, "", 0, 0, emptyList())
        assertEquals("macbook-pro/t1", macOnMachine("macbook-pro", thread).id)
        assertSame(thread, macOnMachine(MAC_FIRST, thread))
    }

    @Test fun oneMacOutOfReachHidesNothingOfTheOther() {
        val pro = MacSeen(MAC_FIRST, "Mac Pro", reachable = false, seen = 1_000, listed = true)
        val book = MacSeen("macbook-pro", "MacBook Pro", reachable = true, seen = 5_000, listed = true)
        val view = macWith(MacView(), listOf(pro, book))
        assertTrue(view.reachable)
        assertEquals(5_000L, view.seen)
        assertTrue(view.listed)
        assertFalse(view.reachableFor("t1"))
        assertTrue(view.reachableFor("macbook-pro/t2"))
        assertEquals("Mac Pro", view.machineFor("t1"))
        assertEquals("MacBook Pro", view.machineFor("macbook-pro/t2"))
        assertFalse(macWith(view, listOf(pro, book.copy(listed = false))).listedFor("macbook-pro/t2"))
        assertTrue(macWith(view, listOf(pro, book.copy(listed = false))).listedFor("t1"))
        assertFalse(macWith(view, listOf(pro, book.copy(reachable = false))).reachable)
        // With one Mac nothing is named and it reads as before.
        val one = macWith(MacView(), listOf(pro.copy(reachable = true)))
        assertEquals("", one.machineFor("t1"))
        assertTrue(one.reachableFor("t1"))
        // A view from before there were Macs to tell apart says what it always said.
        assertTrue(MacView(reachable = true).reachableFor("t1"))
    }

    @Test fun aNewChatStartsOnTheFirstMacWhileItAnswersElseOnOneThatDoes() {
        val pro = MacSeen(MAC_FIRST, "Mac Pro")
        val book = MacSeen("macbook-pro", "MacBook Pro")
        assertNull(macDefaultMachine(emptyList()))
        assertEquals(MAC_FIRST, macDefaultMachine(listOf(pro.copy(reachable = true), book.copy(reachable = true))))
        assertEquals("macbook-pro", macDefaultMachine(listOf(pro, book.copy(reachable = true))))
        // Neither answers: the first, where his words then wait with [ try again ].
        assertEquals(MAC_FIRST, macDefaultMachine(listOf(pro, book)))
        assertEquals("macbook-pro", macDefaultMachine(listOf(book)))
    }

    @Test fun aMacNewlySetUpIsNotHeardYetAndOneTakenAwayIsGone() {
        val machines = listOf(parseMacMachine(MAC_FIRST, file("http://100.1.1.1:8478"))!!, parseMacMachine("macbook-pro", file("http://100.2.2.2:8478"))!!)
        val view = macWith(MacView(), listOf(MacSeen(MAC_FIRST, "Mac Pro", reachable = true, seen = 9)))
        assertEquals(listOf(MacSeen(MAC_FIRST, "Mac Pro", reachable = true, seen = 9), MacSeen("macbook-pro", "MacBook Pro")), macSeen(machines, view))
        assertEquals(listOf("macbook-pro"), macSeen(machines.drop(1), view).map { it.id })
    }
}
