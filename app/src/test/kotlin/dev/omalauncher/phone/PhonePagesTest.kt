package dev.omalauncher.phone

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Open my trip page": which page a name picks out, and what the bridge's page commands answer and do. */
class PhonePagesTest {
    private val names = listOf("Today", "Trip", "Trip to Lisbon", "Work trips", "Page 5")

    @Test fun exactBeatsPrefixBeatsContains() {
        // "Trip" is also the start of one name and inside another: the page called exactly that wins.
        assertEquals(PageMatch.Found(1), matchPage(names, "Trip"))
        // No page is called "trip to": the only one starting with it wins over nothing else.
        assertEquals(PageMatch.Found(2), matchPage(names, "trip to"))
        // Only inside a name.
        assertEquals(PageMatch.Found(3), matchPage(names, "rk tr"))
        assertEquals(PageMatch.Found(2), matchPage(names, "lisbon"))
        assertEquals(PageMatch.None, matchPage(names, "holiday"))
        assertEquals(PageMatch.None, matchPage(emptyList(), "trip"))
    }

    @Test fun caseAndSpacingDoNotCount() {
        assertEquals(PageMatch.Found(0), matchPage(names, "tODAY"))
        assertEquals(PageMatch.Found(2), matchPage(names, "  TRIP   to\tlisbon \n"))
        assertEquals(PageMatch.Found(4), matchPage(names, "page 5"))
        assertEquals(PageMatch.Found(0), matchPage(listOf("  My   Day ", "Other"), "my day"))
    }

    @Test fun aTieAtTheBestLevelPicksNothing() {
        // Two pages start with "tri" and none is called that: a page that merely contains it doesn't break the tie.
        assertEquals(PageMatch.Several(listOf(1, 2)), matchPage(names, "tri"))
        // Contained in three, the start of none.
        assertEquals(PageMatch.Several(listOf(1, 2, 3)), matchPage(names, "rip"))
        // Two pages with the same name can't be told apart by name.
        assertEquals(PageMatch.Several(listOf(0, 2)), matchPage(listOf("Trip", "Home", "trip"), "Trip"))
        // But an exact name wins over others that start with it.
        assertEquals(PageMatch.Found(0), matchPage(listOf("Trip", "Trip 2", "Trip 3"), "trip"))
    }

    @Test fun emptyAndOverlongNamesAreRefused() {
        for (asked in listOf("", " ", "\t\n  ")) assertTrue(asked, matchPage(names, asked) is PageMatch.Refused)
        assertTrue(matchPage(names, "x".repeat(MAX_PAGE_QUERY + 1)) is PageMatch.Refused)
        assertTrue(matchPage(listOf("x".repeat(500)), "x".repeat(500)) is PageMatch.Refused)
        assertEquals(PageMatch.None, matchPage(names, "x".repeat(MAX_PAGE_QUERY)))
        // A page with no name is never what an empty name picks.
        assertTrue(matchPage(listOf("", "Trip"), "") is PageMatch.Refused)
        assertEquals(PageMatch.Found(1), matchPage(listOf("", "Trip"), "tr"))
    }

    @Test fun oddCharactersAreOnlyCharacters() {
        val odd = listOf("C++ (notes)", ".*", "Café ☕", "50% [done]", "a\\b", "\$HOME; rm -rf", "日本の旅")
        assertEquals(PageMatch.Found(0), matchPage(odd, "c++ (NOTES)"))
        // Not a pattern: it matches only the page called that.
        assertEquals(PageMatch.Found(1), matchPage(odd, ".*"))
        assertEquals(PageMatch.None, matchPage(odd, ".+"))
        assertEquals(PageMatch.Found(2), matchPage(odd, "CAFÉ"))
        assertEquals(PageMatch.Found(2), matchPage(odd, "☕"))
        assertEquals(PageMatch.Found(3), matchPage(odd, "[done]"))
        assertEquals(PageMatch.Found(4), matchPage(odd, "A\\B"))
        assertEquals(PageMatch.Found(5), matchPage(odd, "\$home; rm"))
        assertEquals(PageMatch.Found(6), matchPage(odd, "の旅"))
    }

    @Test fun aLetterIsTheSameHoweverItWasTyped() {
        val composed = listOf("Café", "Trip", "Ångström")
        // The accent as a character of its own, in either case.
        assertEquals(PageMatch.Found(0), matchPage(composed, "café"))
        assertEquals(PageMatch.Found(0), matchPage(composed, "CAFÉ"))
        assertEquals(PageMatch.Found(2), matchPage(composed, "ångström"))
        // And the other way round: the page was named with the separate accent.
        assertEquals(PageMatch.Found(0), matchPage(listOf("Café notes", "Trip"), "café"))
        // Full-width letters and a no-break space are the letters and the space they stand for.
        assertEquals(PageMatch.Found(1), matchPage(composed, "ＴＲＩＰ"))
        assertEquals(PageMatch.Found(0), matchPage(listOf("My Day", "Other"), "my day"))
        // An unaccented name is still a different name from an accented one at the exact level.
        assertEquals(PageMatch.Found(1), matchPage(listOf("Café", "Cafe"), "cafe"))
        assertEquals(PageMatch.Found(0), matchPage(listOf("Café", "Cafe"), "café"))
    }

    // The order of the gates.

    private val everyCommand = listOf("apps", "open", "read", "screenshot", "click", "type", "scroll", "tap", "swipe", "back", "home", "recents", "notifications", "pages", "page", "where", "unknown", "")

    /** What [PhoneRequests.admit] consulted, in order, and what came of it. */
    private fun admitted(
        command: String,
        access: Boolean = true,
        pending: Int = 0,
    ): Pair<List<String>, Result<PhoneRequests.Route>> {
        val asked = ArrayList<String>()
        val result =
            runCatching {
                PhoneRequests.admit(command, {
                    asked += "access"
                    check(access) { "no access" }
                }) {
                    asked += "pending"
                    pending
                }
            }
        return asked to result
    }

    @Test fun statusAndStopAreAnsweredBeforeAnyCheck() {
        for ((command, route) in listOf("status" to PhoneRequests.Route.Status, "stop" to PhoneRequests.Route.Stop)) {
            for (access in listOf(true, false)) for (pending in listOf(0, 3)) {
                val (asked, result) = admitted(command, access, pending)
                assertEquals(route, result.getOrThrow())
                assertTrue(asked.isEmpty())
            }
        }
    }

    @Test fun everythingElseNeedsAccessFirstThenNoPendingApproval() {
        for (command in everyCommand) {
            // Without access nothing more is even looked at, whatever is or isn't pending.
            for (pending in listOf(0, 1)) {
                val (asked, result) = admitted(command, access = false, pending = pending)
                assertEquals(command, listOf("access"), asked)
                assertEquals("no access", result.exceptionOrNull()?.message)
            }
            // With access, the approval check comes next, once, and before any route is given.
            val (asked, result) = admitted(command, pending = 1)
            assertEquals(command, listOf("access", "pending"), asked)
            if (command in PhoneControlPolicy.whilePending) {
                assertTrue(command, result.isSuccess)
            } else {
                assertTrue(command, result.exceptionOrNull()!!.message!!.contains("approval is pending"))
            }
        }
    }

    @Test fun anAdmittedRequestGoesToItsHandler() {
        for (command in everyCommand) {
            val (asked, result) = admitted(command)
            assertEquals(command, listOf("access", "pending"), asked)
            val expected =
                when (command) {
                    "pages", "page", "where" -> PhoneRequests.Route.Pages
                    "screenshot" -> PhoneRequests.Route.Screenshot
                    else -> PhoneRequests.Route.Control
                }
            assertEquals(command, expected, result.getOrThrow())
        }
        // The page commands are never routed while an approval waits: none of them is a way round it.
        for (command in PhonePages.commands) for (pending in listOf(1, 2, 16)) assertTrue(admitted(command, pending = pending).second.isFailure)
        // The service follows this and nothing else: it has no check of its own to move.
        val service = java.io.File("src/main/kotlin/dev/omalauncher/phone/PhoneService.kt").readText()
        val request = service.substringAfter("fun request(input: JSONObject")
        assertTrue(request.indexOf("PhoneRequests.admit(") in 0 until request.indexOf("reply("))
        // Up to where the handlers begin, it goes by the route alone, never by the command's name.
        val head = request.substringBefore("if (route == PhoneRequests.Route.Screenshot)")
        assertTrue(head.length < request.length && head.contains("PhoneRequests.Route.Pages"))
        assertFalse(head.contains("command =="))
        assertFalse(head.contains("command in"))
        assertFalse(service.contains("requireNoPendingApproval"))
        assertEquals(2, service.split("PhoneAccess.requireAccess(").size - 1)
    }

    /** Home as the commands see it, recording what they turned to. */
    private class Book(
        override val pages: List<String>? = listOf("Today", "Trip", "Trip to Lisbon"),
        override val showing: Int? = 0,
        val widgets: Map<String, Pair<Int, Boolean>> = emptyMap(),
    ) : HomeBook {
        val shown = ArrayList<Int>()

        override fun pageOf(widget: String) = widgets[widget]?.first

        override fun placed(widget: String) = widgets[widget]?.second == true

        override fun show(index: Int) {
            shown += index
        }
    }

    private fun ask(
        book: Book,
        json: String,
    ) = PhonePages.reply(JSONObject(json), book)

    private fun JSONObject.list(key: String) = getJSONArray(key).let { a -> (0 until a.length()).map(a::getString) }

    @Test fun listingSaysThePagesAndWhichShows() {
        val book = Book(showing = 1)
        val reply = ask(book, """{"command":"pages"}""")
        assertTrue(reply.getBoolean("ok"))
        assertEquals(listOf("Today", "Trip", "Trip to Lisbon"), reply.list("pages"))
        assertEquals("Trip", reply.getString("showing"))
        // With no home screen to say, or one that is on a page that has gone, it just isn't said.
        assertFalse(ask(Book(showing = null), """{"command":"pages"}""").has("showing"))
        assertFalse(ask(Book(showing = 7), """{"command":"pages"}""").has("showing"))
        assertTrue(book.shown.isEmpty())
    }

    @Test fun openingAPageTurnsToTheOneMatch() {
        val book = Book()
        val reply = ask(book, """{"command":"page","name":"lisbon"}""")
        assertTrue(reply.getBoolean("ok"))
        assertEquals("Trip to Lisbon", reply.getString("page"))
        assertEquals(listOf(2), book.shown)
    }

    @Test fun noMatchOrSeveralChangesNothingAndListsTheNames() {
        val book = Book()
        val none = ask(book, """{"command":"page","name":"holiday"}""")
        assertFalse(none.getBoolean("ok"))
        assertTrue(none.getString("error").contains("No page matches"))
        assertEquals(listOf("Today", "Trip", "Trip to Lisbon"), none.list("pages"))
        assertEquals("Today", none.getString("showing"))
        val several = ask(book, """{"command":"page","name":"tri"}""")
        assertFalse(several.getBoolean("ok"))
        assertEquals(listOf("Trip", "Trip to Lisbon"), several.list("matches"))
        assertEquals(listOf("Today", "Trip", "Trip to Lisbon"), several.list("pages"))
        assertTrue(book.shown.isEmpty())
    }

    @Test fun badRequestsThrowAndTurnNothing() {
        val book = Book()
        // The service answers what is thrown as {"ok": false, "error": …}, like every other command.
        for (json in listOf(
            """{"command":"page"}""",
            """{"command":"page","name":""}""",
            """{"command":"page","name":"   "}""",
            """{"command":"page","name":"${"x".repeat(MAX_PAGE_QUERY + 1)}"}""",
            """{"command":"where"}""",
            """{"command":"where","widget":""}""",
            """{"command":"where","widget":"../notebook.owl"}""",
            """{"command":"where","widget":"a/b.owl"}""",
            """{"command":"where","widget":".hidden.owl"}""",
            """{"command":"where","widget":"${"w".repeat(300)}.owl"}""",
            """{"command":"open","package":"settings"}""",
            """{"command":"PAGE","name":"Trip"}""",
            """{}""",
        )) {
            assertThrows(json, Exception::class.java) { ask(book, json) }
        }
        // A position is not a name: nothing but the name picks a page.
        assertThrows(Exception::class.java) { ask(book, """{"command":"page","index":1}""") }
        assertTrue(book.shown.isEmpty())
    }

    @Test fun beforeTheNotebookIsReadNothingIsAnswered() {
        val book = Book(pages = null)
        for (json in listOf("""{"command":"pages"}""", """{"command":"page","name":"Trip"}""", """{"command":"where","widget":"a.owl"}""")) {
            val error = assertThrows(IllegalStateException::class.java) { ask(book, json) }
            assertTrue(error.message!!.contains("still loading"))
        }
        assertTrue(book.shown.isEmpty())
    }

    @Test fun whereSaysThePageAWidgetIsOn() {
        val book = Book(widgets = mapOf("weather.owl" to (1 to true), "new.owl" to (2 to false), "lost.owl" to (9 to true)))
        val placed = ask(book, """{"command":"where","widget":"weather.owl"}""")
        assertTrue(placed.getBoolean("ok"))
        assertEquals("Trip", placed.getString("page"))
        assertTrue(placed.getBoolean("placed"))
        assertFalse(placed.has("note"))
        // The ending may be left off.
        assertEquals("Trip", ask(book, """{"command":"where","widget":" weather "}""").getString("page"))
        // On a page, its cells still to come: the page may change, and the reply says so.
        val waiting = ask(book, """{"command":"where","widget":"new.owl"}""")
        assertEquals("Trip to Lisbon", waiting.getString("page"))
        assertFalse(waiting.getBoolean("placed"))
        assertTrue(waiting.has("note"))
        // Not in the notebook, or on a page that isn't there: not placed, and no page is named.
        for (name in listOf("unknown.owl", "lost.owl")) {
            val reply = ask(book, """{"command":"where","widget":"$name"}""")
            assertTrue(reply.getBoolean("ok"))
            assertFalse(reply.getBoolean("placed"))
            assertFalse(reply.has("page"))
        }
        // Only widget files are answered for: an app's key is not one.
        assertEquals("app:chrome#0.owl", PhonePages.widgetFile("app:chrome#0"))
        assertTrue(book.shown.isEmpty())
    }

    @Test fun noPageCommandRunsWhileAnApprovalIsPending() {
        // The service checks this before any of them: turning a page would bring the launcher over the approval.
        for (command in PhonePages.commands) {
            PhoneControlPolicy.requireNoPendingApproval(0, command)
            assertThrows(command, IllegalStateException::class.java) { PhoneControlPolicy.requireNoPendingApproval(1, command) }
            assertFalse(command in PhoneControlPolicy.whilePending)
        }
        assertEquals(setOf("pages", "page", "where"), PhonePages.commands)
    }

    @Test fun theAgentIsToldThePageCommandsBriefly() {
        for (command in listOf("oma-phone pages", "oma-phone page NAME", "oma-phone where FILE.owl")) assertTrue(command, PhoneBridge.INSTRUCTIONS.contains(command))
        val script = java.io.File("src/main/assets/oma-phone.sh").readText()
        assertTrue(script.contains("oma-phone pages | page NAME | where WIDGET_FILE"))
        assertTrue(script.contains("""'{command:"page",name:${'$'}name}'"""))
        assertTrue(script.contains("""'{command:"where",widget:${'$'}widget}'"""))
    }

    private class Home(
        override val showing: String = "p1",
    ) : HomeDoor.Home {
        val taken = ArrayList<String?>()

        override fun turn() {
            taken += HomeDoor.take(now = 1_000)
        }
    }

    @Test fun aPageAskedForReachesHomeOnceAndNotLongAfter() {
        val home = Home("p3")
        try {
            // With no home screen it waits, and is taken once.
            HomeDoor.leave(HomeDoor.home ?: home)
            assertNull(HomeDoor.home)
            HomeDoor.ask("p2", now = 500)
            assertEquals("p2", HomeDoor.take(now = 600))
            assertNull(HomeDoor.take(now = 601))
            // One that home never came for is dropped: home doesn't turn by itself later.
            HomeDoor.ask("p2", now = 500)
            assertNull(HomeDoor.take(now = 500 + HomeDoor.ASK_MILLIS + 1))
            assertNull(HomeDoor.take(now = 600))
            // With home there it is told at once.
            HomeDoor.enter(home)
            assertEquals("p3", HomeDoor.home?.showing)
            HomeDoor.ask("p4", now = 900)
            assertEquals(listOf<String?>("p4"), home.taken)
            // An activity being replaced doesn't take its successor away with it.
            val next = Home()
            HomeDoor.enter(next)
            HomeDoor.leave(home)
            assertTrue(HomeDoor.home === next)
            HomeDoor.leave(next)
            assertNull(HomeDoor.home)
        } finally {
            HomeDoor.home?.let(HomeDoor::leave)
            HomeDoor.take(now = 0)
        }
    }
}
