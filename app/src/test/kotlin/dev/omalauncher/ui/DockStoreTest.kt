package dev.omalauncher.ui

import dev.omalauncher.linux.LinuxApp
import dev.omalauncher.upgrade.Versioned
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DockStoreTest {
    /** The entry the dock once always had for the Oma chat; old saved docks may still carry it. */
    private val oldChat = DockEntry(OMA_CHAT_KEY, "Oma")
    private val mail = DockEntry("app:com.mail/.Main", "Mail", component = "com.mail/com.mail.Main")
    private val workMail = DockEntry("app:com.mail/.Main@10", "Mail", component = "com.mail/com.mail.Main", profile = 10L)
    private val foot = DockEntry("linux:foot", "Foot", linux = LinuxApp("Foot", "foot", terminal = false, id = "foot", wmClass = null, icon = "foot"))

    private fun keys(entries: List<DockEntry>) = entries.map { it.key }

    @Test fun aLauncherThatNeverSavedADockHasNothingPinned() {
        assertTrue(DefaultDock.isEmpty())
        assertEquals(emptyList<DockEntry>(), readDock(null))
        assertEquals(emptyList<DockEntry>(), readDock(""))
    }

    @Test fun anUnreadableDockFallsBackToTheEmptyDefault() {
        assertEquals(emptyList<DockEntry>(), readDock("{not json"))
        assertEquals(DefaultDock, readDock("{not json"))
        // An object where the array should be.
        assertEquals(emptyList<DockEntry>(), readDock(Versioned.write(1, JSONObject())))
    }

    @Test fun roundTripsAppsWithTheirProfileAndOrder() {
        val saved = listOf(workMail, foot, mail)
        assertEquals(saved, readDock(writeDock(saved)))
    }

    @Test fun roundTripsShortcutsAndOwlApps() {
        val site = DockEntry("shortcut:org.chromium/##site-1", "Docs", shortcut = dev.omalauncher.widgets.layout.ShortcutEntry("org.chromium", "site-1"))
        val notes = DockEntry("owl:notes.owl", "Notes", owl = "notes.owl")
        val saved = listOf(site, mail, notes)
        assertEquals(saved, readDock(writeDock(saved)))
        // And through the notebook, where the dock is kept.
        assertEquals(saved, saved.map { it.pinned().dockEntry() })
    }

    @Test fun aDockSavedEmptyStaysEmptyAndNothingIsAddedToIt() {
        assertEquals(emptyList<DockEntry>(), readDock(writeDock(emptyList())))
        assertEquals(listOf(mail, foot), readDock(writeDock(listOf(mail, foot))))
    }

    @Test fun anOldSavedChatEntryIsDroppedOnReadWhereverItWas() {
        // The dock once always had the chat; an entry that is neither an Android app nor a desktop one is skipped.
        assertEquals(listOf(mail, foot), readDock(writeDock(listOf(oldChat, mail, foot))))
        assertEquals(listOf(mail, foot), readDock(writeDock(listOf(mail, oldChat, foot))))
        assertEquals(listOf(mail), readDock(writeDock(listOf(mail, oldChat))))
        assertEquals(emptyList<DockEntry>(), readDock(writeDock(listOf(oldChat))))
    }

    @Test fun aDockWhoseEntriesAreAllUnusableIsEmpty() {
        val array = JSONArray().put(JSONObject().put("key", "mystery").put("label", "?")).put(JSONObject().put("label", "no key"))
        assertEquals(emptyList<DockEntry>(), readDock(Versioned.write(1, array)))
    }

    @Test fun anyEntryCanBeUnpinned() {
        assertEquals(listOf(mail), listOf(foot, mail).unpinned(foot.key))
        assertEquals(listOf(foot), listOf(foot, mail).after(DockAction.Unpin, mail.key))
        assertEquals(emptyList<DockEntry>(), listOf(mail).unpinned(mail.key))
    }

    /** A store over one saved document, as the preferences file is. */
    private class Saved(
        var text: String? = null,
    ) {
        var writes = 0

        fun store() =
            DockStore(load = { text }, save = {
                text = it
                writes++
            })
    }

    @Test fun nothingIsPinnedBeforeTheNotebookIsOpened() {
        val notebook = dev.omalauncher.widgets.LiveNotebook()
        val store = DockStore(notebook)
        store.pin(mail)
        store.act(DockAction.Later, mail.key)
        assertEquals(emptyList<DockEntry>(), store.items.toList())
        assertTrue(notebook.dock.isEmpty())
        // Opened with a saved dock, it shows that and takes pins.
        notebook.open(dev.omalauncher.widgets.layout.Notebook(9, listOf(dev.omalauncher.widgets.layout.NotebookPage("p1", "Page 1")), dock = listOf(workMail.pinned(), foot.pinned())), kept = true)
        store.reload()
        assertEquals(listOf(workMail, foot), store.items.toList())
        store.pin(mail)
        assertEquals(listOf(workMail, foot, mail), store.items.toList())
        assertEquals(listOf(workMail, foot, mail), notebook.dock.map { it.dockEntry() })
    }

    @Test fun theStoreSavesWhatItPinsAndReadsItBack() {
        val saved = Saved()
        val store = saved.store()
        assertEquals(emptyList<DockEntry>(), store.items.toList())
        assertEquals(0, saved.writes)
        store.pin(mail)
        store.pin(workMail)
        store.pin(foot)
        assertEquals(3, saved.writes)
        assertTrue(store.isPinned(workMail.key))
        assertFalse(store.isPinned("app:gone/.Gone"))
        assertEquals(listOf(mail, workMail, foot), store.items.toList())
        assertEquals(listOf(mail, workMail, foot), saved.store().items.toList())
    }

    @Test fun theStoreUnpinsOneAppAndKeepsTheRest() {
        val saved = Saved(writeDock(listOf(mail, workMail, foot)))
        val store = saved.store()
        store.unpin(mail.key)
        assertEquals(listOf(workMail, foot), store.items.toList())
        assertEquals(listOf(workMail, foot), saved.store().items.toList())
        store.unpin(foot.key)
        assertEquals(listOf(workMail), saved.store().items.toList())
        store.unpin(workMail.key)
        assertEquals(emptyList<DockEntry>(), saved.store().items.toList())
    }

    @Test fun theStoreSavesMovesAndMenuActions() {
        val saved = Saved(writeDock(listOf(workMail, mail, foot)))
        val store = saved.store()
        store.act(DockAction.Later, workMail.key)
        assertEquals(listOf(mail, workMail, foot), saved.store().items.toList())
        store.act(DockAction.Earlier, foot.key)
        assertEquals(listOf(mail, foot, workMail), saved.store().items.toList())
        store.act(DockAction.Unpin, mail.key)
        assertEquals(listOf(foot, workMail), store.items.toList())
        assertEquals(listOf(foot, workMail), saved.store().items.toList())
    }

    @Test fun theStoreDoesNotWriteWhenNothingChanged() {
        val saved = Saved(writeDock(listOf(workMail, mail)))
        val store = saved.store()
        store.pin(mail)
        store.unpin(foot.key)
        store.act(DockAction.Earlier, workMail.key)
        assertEquals(0, saved.writes)
        assertEquals(listOf(workMail, mail), store.items.toList())
    }

    @Test fun anOldDockWithTheChatShowsWithoutItAndIsSavedWithoutItOnTheNextChange() {
        val saved = Saved(writeDock(listOf(oldChat, mail)))
        val store = saved.store()
        assertEquals(listOf(mail), store.items.toList())
        assertEquals(0, saved.writes)
        store.pin(foot)
        assertEquals(listOf(mail, foot), readDock(saved.text))
        assertEquals(listOf(mail.key, foot.key), JSONObject(saved.text!!).getJSONArray("data").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("key") } })
    }

    @Test fun isSavedVersionedUnderAShortcutsFieldNames() {
        val root = JSONObject(writeDock(listOf(workMail, foot)))
        assertEquals(1, root.getInt("v"))
        val data = root.getJSONArray("data")
        val app = data.getJSONObject(0)
        assertEquals("app:com.mail/.Main@10", app.getString("key"))
        assertEquals("Mail", app.getString("label"))
        assertEquals("com.mail/com.mail.Main", app.getString("component"))
        assertEquals(10L, app.getLong("profile"))
        val linux = data.getJSONObject(1).getJSONObject("linux")
        assertEquals("foot", linux.getString("id"))
        assertEquals("foot", linux.getString("exec"))
        assertFalse(data.getJSONObject(1).has("profile"))
    }

    @Test fun skipsEntriesItCannotUseAndRepeatedKeys() {
        val array =
            JSONArray()
                .put(JSONObject().put("key", "app:a/.A").put("label", "A").put("component", "a/a.A"))
                // No label.
                .put(JSONObject().put("key", "app:b/.B").put("component", "b/b.B"))
                // Not an app.
                .put(JSONObject().put("key", "mystery").put("label", "?"))
                .put(JSONObject().put("key", "app:a/.A").put("label", "A again").put("component", "a/a.A"))
                .put(JSONObject().put("key", OMA_CHAT_KEY).put("label", "Oma"))
        val read = readDock(Versioned.write(1, array))
        // The chat has no component or desktop entry, so it is skipped like the other.
        assertEquals(listOf("app:a/.A"), keys(read))
        assertEquals("A", read.first().label)
    }

    @Test fun pinningAddsAtTheEnd() {
        assertEquals(listOf(mail.key, foot.key), keys(DefaultDock.pinned(mail).pinned(foot)))
    }

    @Test fun pinningAgainKeepsThePlaceAndTakesTheNewLabel() {
        val dock = listOf(workMail, mail, foot).pinned(mail.copy(label = "Post"))
        assertEquals(listOf(workMail.key, mail.key, foot.key), keys(dock))
        assertEquals("Post", dock[1].label)
    }

    @Test fun theSameAppInAWorkProfileIsItsOwnEntry() {
        val dock = listOf(mail).pinned(workMail)
        assertEquals(listOf(mail.key, workMail.key), keys(dock))
        assertEquals(10L, dock[1].profile)
        assertEquals(listOf(workMail), dock.unpinned(mail.key))
        assertEquals(listOf(mail), dock.unpinned(workMail.key))
    }

    @Test fun unpinningSomethingNotPinnedChangesNothing() {
        assertEquals(listOf(workMail, mail), listOf(workMail, mail).unpinned(foot.key))
    }

    @Test fun movesByOnePlaceAndStopsAtTheEnds() {
        val dock = listOf(workMail, mail, foot)
        assertEquals(listOf(mail.key, workMail.key, foot.key), keys(dock.moved(mail.key, -1)))
        assertEquals(listOf(workMail.key, foot.key, mail.key), keys(dock.moved(mail.key, 1)))
        assertSame(dock, dock.moved(workMail.key, -1))
        assertSame(dock, dock.moved(foot.key, 1))
        assertSame(dock, dock.moved("app:gone/.Gone", 1))
        assertEquals(listOf(foot.key, workMail.key, mail.key), keys(dock.moved(foot.key, -5)))
    }

    @Test fun theMenuOffersOnlyWhatCanBeDone() {
        val dock = listOf(workMail, mail, foot)
        // Every entry can be unpinned; the ends can't move further out.
        assertEquals(listOf(DockAction.Later, DockAction.Unpin), dock.actions(workMail.key))
        assertEquals(listOf(DockAction.Earlier, DockAction.Later, DockAction.Unpin), dock.actions(mail.key))
        assertEquals(listOf(DockAction.Earlier, DockAction.Unpin), dock.actions(foot.key))
        // The only one in the dock can still be unpinned.
        assertEquals(listOf(DockAction.Unpin), listOf(mail).actions(mail.key))
        assertEquals(emptyList<DockAction>(), DefaultDock.actions(mail.key))
        assertEquals(emptyList<DockAction>(), dock.actions("app:gone/.Gone"))
    }

    @Test fun menuActionsDoWhatTheySay() {
        val dock = listOf(workMail, mail, foot)
        assertEquals(listOf(mail.key, workMail.key, foot.key), keys(dock.after(DockAction.Earlier, mail.key)))
        assertEquals(listOf(workMail.key, foot.key, mail.key), keys(dock.after(DockAction.Later, mail.key)))
        assertEquals(listOf(workMail.key, foot.key), keys(dock.after(DockAction.Unpin, mail.key)))
    }

    @Test fun drawerMenuNamesTheDockActionByWhetherTheAppIsPinned() {
        assertEquals("pin to dock", dockActionLabel(docked = false))
        assertEquals("unpin from dock", dockActionLabel(docked = true))
    }

    @Test fun fitsAsManyIconsAsTheWidthHoldsWithTheGapBetween() {
        // 48 wide, 8 apart: n icons take 48n + 8(n - 1).
        assertEquals(0, dockFits(47, 48, 8))
        assertEquals(1, dockFits(48, 48, 8))
        assertEquals(1, dockFits(103, 48, 8))
        assertEquals(2, dockFits(104, 48, 8))
        assertEquals(5, dockFits(272, 48, 8))
        assertEquals(5, dockFits(327, 48, 8))
        assertEquals(6, dockFits(328, 48, 8))
        assertEquals(0, dockFits(-10, 48, 8))
        assertEquals(0, dockFits(500, 0, 8))
    }

    @Test fun whatFitsNeverCrowdsTheGap() {
        for (room in 0..2000) {
            val n = dockFits(room, 126, 21)
            if (n > 0) assertTrue("room $room", n * 126 + (n - 1) * 21 <= room)
            assertTrue("room $room", (n + 1) * 126 + n * 21 > room)
        }
    }
}
