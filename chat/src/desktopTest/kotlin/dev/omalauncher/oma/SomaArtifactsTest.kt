package dev.omalauncher.oma

import dev.omalauncher.assist.MacEvent
import dev.omalauncher.assist.macApply
import dev.omalauncher.assist.macTurns
import dev.omalauncher.assist.parseMacEvent
import org.junit.Assert.*
import org.junit.Test

class SomaArtifactsTest {
    @Test fun anArtifactEventIsReadWithEveryPart() {
        val event = parseMacEvent("""{"id": 7, "time": 100, "type": "artifact", "name": "neat-tracker.html", "version": 3, "title": "NEAT tracker", "mime": "text/html", "state": "ready", "note": "Red ring"}""")
        assertEquals(MacEvent.Artifact(7, 100, "neat-tracker.html", 3, "NEAT tracker", "text/html", "ready", "Red ring"), event)
        val settled = parseMacEvent("""{"id": 8, "type": "artifact", "name": "x.html", "version": 2, "state": "ready", "settled": true}""") as MacEvent.Artifact
        assertTrue(settled.settled)
    }

    @Test fun anArtifactEventWithoutAUsableNameKeepsItsPlaceAndShowsNothing() {
        for (name in listOf("", "a/b.html")) {
            val event = parseMacEvent("""{"id": 9, "type": "artifact", "name": "$name", "version": 1, "state": "ready"}""")
            assertEquals(MacEvent.Other(9, 0, "artifact"), event)
        }
    }

    @Test fun locationsNameOnlyArtifactsAndVersions() {
        assertEquals("soma-artifact:neat.html", somaArtifactPath("neat.html"))
        assertEquals("soma-artifact:neat.html?v=2", somaArtifactPath("neat.html", 2))
        assertEquals("neat.html" to null, parseSomaArtifactPath("soma-artifact:neat.html"))
        assertEquals("neat.html" to 2, parseSomaArtifactPath("soma-artifact:neat.html?v=2"))
        for (bad in listOf("soma-artifact:../x.html", "soma-artifact:a/b.html", "soma-artifact:x.html?v=0", "soma-artifact:x.html?v=x", "soma-artifact:.x.html", "page.html"))
            assertNull(bad, parseSomaArtifactPath(bad))
    }

    @Test fun stepsSayWhatWasMadeAndEditingGivesWayToTheVersion() {
        var steps = listOf("Bash: ls")
        steps = withArtifactStep(steps, "neat.html", 1, "editing", false)
        assertEquals(listOf("Bash: ls", "editing neat.html · v1"), steps)
        steps = withArtifactStep(steps, "neat.html", 1, "ready", false)
        assertEquals(listOf("Bash: ls", "made neat.html · v1"), steps)
        steps = withArtifactStep(steps, "neat.html", 2, "ready", false)
        assertEquals(listOf("Bash: ls", "made neat.html · v1", "updated neat.html · v2"), steps)
        steps = withArtifactStep(steps, "neat.html", 3, "editing", false)
        steps = withArtifactStep(steps, "neat.html", 2, "ready", true)
        assertEquals("an edit that ended with nothing new leaves no line", listOf("Bash: ls", "made neat.html · v1", "updated neat.html · v2"), steps)
        assertEquals(listOf(StepArtifact("neat.html", 2)), stepArtifacts(steps))
    }

    @Test fun aRunningEntryGetsTheStepsAndItsCardsComeFromThem() {
        val tasks = listOf(ChatTask(1L, "→ FF4", "make a mockup", thread = "t1", status = TaskStatus.Running))
        var now = macApply(tasks, "t1", MacEvent.Artifact(2, 10, "neat.html", 1, "NEAT", "text/html", "editing"))
        now = macApply(now, "t1", MacEvent.Artifact(3, 11, "neat.html", 1, "NEAT", "text/html", "ready"))
        assertEquals(listOf("made neat.html · v1"), now.single().steps)
        val cards = taskSomaArtifacts(now.single())
        assertEquals(listOf(ChatArtifact("t1:soma:neat.html", "neat.html", "soma-artifact:neat.html", "t1", ArtifactKind.Html, version = 1)), cards)
        assertTrue(cards.single().soma)
    }

    @Test fun aVersionMadeBetweenHisMessagesIsAQuietLineThatStillMakesACard() {
        val tasks = listOf(ChatTask(1L, "→ FF4", "make a mockup", thread = "t1", status = TaskStatus.Done, result = "Done."))
        val now = macApply(tasks, "t1", MacEvent.Artifact(4, 12, "neat.html", 2, "NEAT", "text/html", "ready"))
        assertEquals(listOf(ChatLater("updated neat.html · v2")), now.single().later)
        assertEquals(2, taskSomaArtifacts(now.single()).single().version)
        val edit = macApply(tasks, "t1", MacEvent.Artifact(5, 12, "neat.html", 3, "NEAT", "text/html", "editing"))
        assertEquals("an edit between messages says nothing in the chat", tasks, edit)
    }

    @Test fun earlierTurnsKeepTheirArtifacts() {
        val turns = macTurns(listOf(
            MacEvent.Message(1, 1, "make it"),
            MacEvent.Artifact(2, 2, "neat.html", 1, "", "text/html", "editing"),
            MacEvent.Artifact(3, 3, "neat.html", 1, "", "text/html", "ready"),
            MacEvent.Result(4, 4, "Done.", false),
        ))
        assertEquals(listOf(StepArtifact("neat.html", 1)), stepArtifacts(turns.single().steps))
    }

    @Test fun theLiveBookFollowsEditsAndVersionsAndIgnoresOldNews() {
        val book = ArtifactLiveBook()
        book.heard("t1", "neat.html", 4, "editing", false, "NEAT", "", "Claude", "Mac Pro", 100, 10)
        assertEquals(ArtifactLive("t1", "neat.html", 0, 4, "NEAT", "", "Claude", "Mac Pro", 100, 10), book.snapshot().single())
        book.heard("t1", "neat.html", 4, "ready", false, "", "Water row", "", "", 110, 11)
        assertEquals(ArtifactLive("t1", "neat.html", 4, null, "NEAT", "Water row", "Claude", "Mac Pro", 110, 11), book.snapshot().single())
        book.heard("t1", "neat.html", 2, "ready", false, "", "", "", "", 90, 9)
        assertEquals("an older event changes nothing", 4, book.snapshot().single().version)
        book.heard("t1", "neat.html", 5, "editing", false, "", "", "", "", 120, 12)
        book.heard("t1", "neat.html", 4, "ready", true, "", "", "", "", 130, 13)
        assertEquals("a settled edit ends the edit and keeps the version", ArtifactLive("t1", "neat.html", 4, null, "NEAT", "Water row", "Claude", "Mac Pro", 120, 13), book.snapshot().single())
        book.forget("t1")
        assertTrue(book.snapshot().isEmpty())
    }

    @Test fun newsIsWhatChangedLatelyForThisTabsThreadsNewestFirst() {
        val a = ArtifactLive("t1", "a.html", 1, null, time = 1000, event = 5)
        val b = ArtifactLive("t1", "b.html", 2, 3, time = 1001, event = 7)
        val other = ArtifactLive("t2", "c.html", 1, null, time = 1001, event = 8)
        val old = ArtifactLive("t1", "d.html", 1, null, time = 100, event = 9)
        val seen = mapOf(a.key to (1 to null))
        assertEquals(listOf(b), artifactNews(seen, listOf(a, b, other, old), setOf("t1"), 1010))
        assertEquals(emptyList<ArtifactLive>(), artifactNews(seen + (b.key to (2 to 3)), listOf(a, b), setOf("t1"), 1010))
    }

    @Test fun theRunnersListIsReadAndAnythingOddIsLeftOut() {
        val body = """{"ok": true, "artifacts": [{"name": "neat.html", "title": "NEAT", "mime": "text/html", "latest": 2, "editing": null,
            "versions": [{"v": 1, "time": 5, "size": 10, "sha": "abc", "note": "first"}, {"v": 2, "time": 6, "size": 11, "sha": "def", "note": ""}]},
            {"name": "../bad.html", "latest": 1, "versions": []}]}"""
        val listed = parseArtifactList(200, body)!!
        assertEquals(listOf(ArtifactVersions("neat.html", "NEAT", 2, null, listOf(ArtifactVersion(1, 5, 10, "first"), ArtifactVersion(2, 6, 11, "")))), listed)
        assertNull(parseArtifactList(404, body))
        assertNull(parseArtifactList(200, "not json"))
    }

    @Test fun aVersionsDocumentCarriesItsNumbers() {
        val read = decodeArtifact(200, """{"ok": true, "name": "neat.html", "mime": "text/html", "data": "PHA+aGk8L3A+", "version": "abcdef123456", "v": 2, "latest": 3, "title": "NEAT"}""")
        val document = (read as ArtifactRead.Ready).document
        assertEquals(listOf(2, 3), listOf(document.v, document.latest))
        assertEquals("NEAT", document.title)
    }
}
