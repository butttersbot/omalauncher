package dev.omalauncher.canvas

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitFeedsTest {
    /** herd's /v1/herd, in its real shape (taken from the reporters on 2026-10-17, trimmed). */
    private val herd = """
        {"machines": [
          {"name": "macbook-pro", "online": true, "report": {"machine": {"load": 6.06, "remote_control": []},
            "sessions": [
              {"id": "f8890b84-1019-47cd-be97-458e18f11f75", "kind": "claude", "app": "", "title": "Cockpit build", "project": "omalauncher", "state": "working", "live": true, "updated": 1792220000.5},
              {"id": "01a1485f-a978-74e2-ad3c-896329e497e4", "kind": "codex", "app": "codex", "title": "hey i ve got another music", "project": "(no folder)", "state": "your-turn", "live": true, "updated": 1792219000},
              {"id": "jacks-mac-pro/t1a2b3c4d", "kind": "claude", "app": "oma", "title": "a runner thread", "project": "omalauncher", "state": "idle", "live": true, "updated": 1792218000},
              {"id": "../../etc", "kind": "claude", "app": "", "title": "bad id", "project": "x", "state": "working", "live": true, "updated": 1},
              {"id": "b2c3d4e5-0000-0000-0000-000000000000", "kind": "claude", "app": "", "title": "Old one", "project": "pond", "state": "closed", "live": false, "updated": 1792000000}
            ]}},
          {"name": "omarchy-mg1", "online": true, "report": {"machine": {"load": 0.11, "gpu": {"used_mb": 26009, "total_mb": 32607, "busy": 10}}, "sessions": []}},
          {"name": "omarchy-mbp", "online": false},
          "junk"
        ], "others": []}
    """

    @Test fun herdsAnswerIsRead() {
        val snap = herdSnapshot(herd)!!
        assertEquals(listOf("macbook-pro", "omarchy-mg1", "omarchy-mbp"), snap.machines.map { it.name })
        assertEquals(6.06, snap.machines[0].load!!, 0.001)
        assertEquals("GPU 25/31 GB", snap.machines[1].gpu)
        assertFalse(snap.machines[2].online)
        assertEquals("a session id that isn't one is left out", 4, snap.sessions.size)
        assertEquals(1792220000500L, snap.sessions[0].updated)
        assertNull(herdSnapshot("not json"))
        assertNull(herdSnapshot("""{"nothing": 1}"""))
    }

    @Test fun desktopSessionsAreClaudesOwnAndOpen() {
        val snap = herdSnapshot(herd)
        assertEquals(listOf("Cockpit build"), desktopSessions(snap).map { it.title })
        assertEquals(listOf("Cockpit build"), desktopSessions(snap, "omalauncher").map { it.title })
        assertTrue(desktopSessions(snap, "pond").isEmpty())
        assertTrue(desktopSessions(null).isEmpty())
    }

    @Test fun thePocketIsReadNewestFirst() {
        val items = pocketItems("""{"host": "jacks-mac-pro", "items": [
            {"id": "20261016092235-8fa2f5", "name": "older.jpg", "size": 968030, "t": 1792167755.79, "device": "jacks-z-fold8", "pinned": false, "kind": "image"},
            {"id": "20261016201553-16c69c", "name": "newer.jpg", "size": 510057, "t": 1792206953.68, "device": "jacks-z-fold8", "pinned": true, "kind": "image"},
            {"id": "../x", "name": "bad"}]}""")
        assertEquals(listOf("newer.jpg", "older.jpg"), items.map { it.name })
        assertTrue(items[0].pinned)
        assertTrue(pocketItems("nope").isEmpty())
    }

    @Test fun aNudgeIsAgentMailFromSoma() {
        val body = JSONObject(nudgeMessage("f8890b84-1019-47cd-be97-458e18f11f75")!!)
        assertEquals("f8890b84-1019-47cd-be97-458e18f11f75", body.getJSONObject("to").getString("session"))
        assertEquals("soma", body.getJSONObject("from").getString("agent"))
        assertEquals(NUDGE_TEXT, body.getString("text"))
        assertTrue(body.isNull("reply_to"))
        assertNull(nudgeMessage("../../etc"))
        assertNull(nudgeMessage("x"))
    }

    @Test fun theFeedsAskTheRightPlaces() {
        val asked = mutableListOf<String>()
        val posted = mutableListOf<Pair<String, String>>()
        val feeds = CockpitFeeds(get = { url -> asked += url; if ("macbook-pro" in url) herd else null }, post = { url, json -> posted += url to json; true })
        assertEquals(3, feeds.herd()!!.machines.size)
        assertEquals(listOf("http://jacks-mac-pro:8479/v1/herd", "http://macbook-pro:8479/v1/herd"), asked)
        // The one that answered is asked first next time.
        asked.clear(); feeds.herd()
        assertEquals(listOf("http://macbook-pro:8479/v1/herd"), asked)
        assertTrue(feeds.nudge("macbook-pro", "f8890b84-1019-47cd-be97-458e18f11f75"))
        assertFalse("a machine herd didn't list gets no mail", feeds.nudge("attacker", "f8890b84-1019-47cd-be97-458e18f11f75"))
        assertEquals("http://macbook-pro:8480/v1/msg", posted.single().first)
        assertFalse("a machine name that isn't one is refused", feeds.nudge("evil.example.com/x?", "f8890b84-1019-47cd-be97-458e18f11f75"))
        assertFalse(feeds.nudge("macbook-pro", "../../etc"))
        assertEquals(1, posted.size)
        assertTrue(CockpitFeeds(get = { throw java.io.IOException("down") }, post = { _, _ -> false }).herd() == null)
    }

    @Test fun computersMergeRunnersAndHerd() {
        val herdMachines = herdSnapshot(herd)!!.machines + listOf(HerdMachine("jacks-mac-pro", true, 4.3, address = "100.120.219.46"), HerdMachine("omarchy-macpro", true, 0.2, address = "100.64.0.9"))
        // The phone's first Mac: the empty id, "Mac Pro", its runner at the Mac Pro's address. One row, with its own load,
        // in either order herd lists them (omarchy-macpro is another machine, never taken for it).
        for (order in listOf(herdMachines, herdMachines.reversed())) {
            val merged = moduleComputers(listOf(ComputerHost("", "Mac Pro", true, "100.120.219.46"), ComputerHost("macbook-pro", "MacBook Pro", true, "macbook-pro")), order)
            assertEquals(1, merged.count { it.name == "Mac Pro" })
            assertTrue(merged.none { it.name == "jacks-mac-pro" })
            assertEquals("load 4.3", merged.first { it.name == "Mac Pro" }.detail)
            assertEquals("load 6.1", merged.first { it.name == "MacBook Pro" }.detail)
            assertEquals("load 0.2", merged.first { it.name == "omarchy-macpro" }.detail)
        }
        // A runner reached by its tailnet name (with or without the domain) matches too.
        assertEquals("load 4.3", moduleComputers(listOf(ComputerHost("", "Mac Pro", true, "jacks-mac-pro.tail1234.ts.net")), herdMachines)[0].detail)
        // "mac" is never taken for "macbook-pro"; a herd machine is used once; no address and no same name is no match.
        val mis = moduleComputers(listOf(ComputerHost("mac", "Mac", false), ComputerHost("macbook-pro", "MacBook Pro", true)), herdMachines)
        assertEquals("", mis[0].detail)
        assertEquals("load 6.1", mis[1].detail)
        assertFalse(mis[0].online)
        // Two runners at one address: only the first takes herd's row, the second gets no detail and herd's row isn't repeated.
        val twice = moduleComputers(listOf(ComputerHost("a", "Mac Pro", true, "100.120.219.46"), ComputerHost("b", "Mac Pro again", true, "100.120.219.46")), herdMachines)
        assertEquals(listOf("load 4.3", ""), twice.take(2).map { it.detail })
        assertEquals(1, twice.count { it.detail == "load 4.3" })
    }

    @Test fun aHandoffsNextLine() {
        assertEquals("Ship the canvas.", handoffNext("# Soma\n\n## Where things stand\n\nAll good.\n\n## Next\n\n- Ship the canvas.\n"))
        assertEquals("Intro line.", handoffNext("# Title\nIntro line.\n## Other\nx"))
        assertEquals("", handoffNext(""))
        assertEquals("The app is Soma", plainMarkdown("The app is **Soma**"))
        assertEquals("see the plan and run it", plainMarkdown("see [the plan](https://x) and `run` it"))
        assertEquals("snake_case_name stays", plainMarkdown("snake_case_name stays"))
        assertEquals("call __init__ and **not bold** as code", plainMarkdown("call __init__ and `**not bold**` as code"))
        // Emphasis may run across a code span (pond's handoff does), and a lone backtick is just a backtick.
        assertEquals("Boss reviewed the board (hzw5) and said fine. Then more", plainMarkdown("**Boss reviewed the board (`hzw5`) and said fine.** Then more"))
        assertEquals("a ` b c", plainMarkdown("a ` b **c**"))
        assertEquals(listOf("## Where things stand", "- ok"), handoffLines("# Title\n\n## Where things stand\n\n- ok\n```\ncode\n```\n"))
        assertEquals("real next", handoffNext("# T\n## Nextcloud setup\nnot this\n## Next\n- real next\n"))
        val doc = "# T\nIntro.\n## Background\nlong story\n## Where things stand\n- one\n- two\n## Next\n- three\n"
        // The first line of each comes first, so "next" shows even in a small card.
        assertEquals(listOf("Where things stand" to "- one", "Next" to "- three", null to "- two"), handoffCard(doc))
        // A section whose content is under sub-headings is not empty; "now" in an ordinary heading is not "where things stand".
        assertEquals(listOf("2. Current state (read this) · Runner" to "- up"), handoffCard("# T\n## 2. Current state (read this)\n### Runner\n- up\n"))
        assertEquals(listOf("Read this first" to "x", "Repo cleaned, main is the tip now" to "y"), handoffCard("# T\n## Read this first\nx\n## Repo cleaned, main is the tip now\ny\n"))
        // Blockquoted headings are headings.
        assertEquals(listOf("Current state" to "all good"), handoffCard("> # T\n> ## Current state\n> all good\n"))
        assertEquals(listOf("" to "Intro."), handoffCard("# T\nIntro.\n").map { (it.first ?: "") to it.second })
    }

    /** Real handoffs wrap paragraphs over lines and carry tables: a wrapped line is one line, a table row its cells. */
    @Test fun wrappedLinesAndTablesReadAsLines() {
        val text = "# T\n## Current state\n**Boss reviewed** the board and said\n\"fine for now\".\n- one item\n  that wraps\n- two\n\n| Agent | PID |\n|---|:--:|\n| Claudette | 42 |\n## Next\n1. ~~Restart~~ done, then\n   ship it\n"
        assertEquals(listOf(
            "Current state" to "**Boss reviewed** the board and said \"fine for now\".",
            "Next" to "1. ~~Restart~~ done, then ship it",
            null to "- one item that wraps",
            null to "- two",
            null to "Agent · PID",
            null to "Claudette · 42",
        ), handoffCard(text))
        assertEquals("Restart done, then", plainMarkdown("~~Restart~~ done, then"))
        assertEquals("1. Restart done, then ship it", handoffNext(text))
        // A paragraph after a blank line is its own line.
        assertEquals(listOf("a", "b"), handoffSections("# T\n## S\na\n\nb\n").single().second)
    }

    /** Round-3 findings: rules and sub-headings never lead, odd tables and long lines never hang or crash. */
    @Test fun handoffCardsLeadWithSentences() {
        assertEquals(listOf("S" to "para", null to "para2"), handoffCard("# T\n\n## S\n\npara\n\n---\n\npara2\n\n* * *\n"))
        assertEquals(listOf(
            "Where things stand · Sub" to "under sub",
            "Next · Next sub" to "- do it",
        ), handoffCard("# T\n\n## Where things stand\n\n### Sub\n\nunder sub\n\n## Next\n\n### Next sub\n\n- do it\n"))
        // The anchor: "Build status" is not where things stand, so the later section is.
        assertEquals("Where things stand" to "here", handoffCard("# T\n## Build status and bugs\nbugs\n## Where things stand\nhere\n").first())
        assertEquals(listOf("kind: text|image · ok"), handoffSections("## S\n| kind: text\\|image | ok |\n").single().second)
        // A pathological divider and long unmatched emphasis stay fast and never overflow the stack.
        val started = System.nanoTime()
        assertEquals(listOf("S"), handoffSections("## S\n\n" + "|--".repeat(2666) + "|").map { it.first })
        for (bad in listOf("_a ".repeat(2700), "__a b ".repeat(1400), "** a ".repeat(1700), "* a ".repeat(2000), "~~a ".repeat(2000))) plainMarkdown(bad)
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 500_000_000L)
    }

    /** Round-4 findings: a heading that only mentions "next" never displaces the top; backtick runs; === underlines. */
    @Test fun theTopIsNeverDisplaced() {
        val pond = "# P\n## READ THIS FIRST (35)\nCurrent work.\n## READ THIS FIRST (27) - V9 IS THE NEXT MOVE\nOld news.\n"
        assertEquals("READ THIS FIRST (35)" to "Current work.", handoffCard(pond).first())
        // With a real Next section and no "where things stand", the top leads and Next follows.
        assertEquals(listOf("TL;DR" to "now", "Next actions" to "1. go"), handoffCard("# P\n## TL;DR\nnow\n## Next actions\n1. go\n"))
        // A file whose first section is Next shows it once.
        assertEquals(listOf("Next" to "go"), handoffCard("# P\n## Next\ngo\n"))
        assertEquals("What's next?" to "b", handoffCard("# P\n## A\na\n## What's next?\nb\n")[1])
        assertEquals(listOf("Next" to "real"), handoffCard("# T\n## Next.js migration\nstuff\n## Next\nreal\n").filter { it.first == "Next" })
        assertEquals("What\u2019s next?" to "b", handoffCard("# P\n## A\na\n## What\u2019s next?\nb\n")[1])
        assertEquals("a`b and c", plainMarkdown("``a`b`` and **c**"))
        assertEquals("x `` y", plainMarkdown("x `` y"))
        assertEquals(listOf("Title", "after"), handoffSections("## S\nTitle\n===\nafter\n").single().second)
        val started = System.nanoTime()
        for (bad in listOf("[".repeat(8000), "**a**b ".repeat(1100), "`".repeat(8000), "`a``".repeat(2000))) plainMarkdown(bad)
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 500_000_000L)
    }
}
