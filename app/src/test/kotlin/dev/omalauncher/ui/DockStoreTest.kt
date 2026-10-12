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
    private val chat = DefaultDock.single()
    private val mail = DockEntry("app:com.mail/.Main", "Mail", component = "com.mail/com.mail.Main")
    private val workMail = DockEntry("app:com.mail/.Main@10", "Mail", component = "com.mail/com.mail.Main", profile = 10L)
    private val foot = DockEntry("linux:foot", "Foot", linux = LinuxApp("Foot", "foot", terminal = false, id = "foot", wmClass = null, icon = "foot"))

    private fun keys(entries: List<DockEntry>) = entries.map { it.key }

    @Test fun aLauncherThatNeverSavedADockHasTheChatAlone() {
        assertEquals(listOf(OMA_CHAT_KEY), keys(readDock(null)))
        assertEquals(listOf(OMA_CHAT_KEY), keys(readDock("")))
        assertTrue(readDock(null).single().builtIn)
    }

    @Test fun anUnreadableDockFallsBackToTheDefault() {
        assertEquals(DefaultDock, readDock("{not json"))
        // An object where the array should be.
        assertEquals(DefaultDock, readDock(Versioned.write(1, JSONObject())))
    }

    @Test fun roundTripsAppsWithTheirProfileAndOrder() {
        val saved = listOf(chat, workMail, foot, mail)
        assertEquals(saved, readDock(writeDock(saved)))
    }

    @Test fun aDockSavedWithoutTheChatGetsItBackFirst() {
        // No menu unpins the chat, so a dock without it is damaged, and nothing else could pin it back.
        assertEquals(DefaultDock, readDock(writeDock(emptyList())))
        assertEquals(listOf(chat, mail, foot), readDock(writeDock(listOf(mail, foot))))
        // Where it was put, it stays.
        assertEquals(listOf(mail, chat), readDock(writeDock(listOf(mail, chat))))
    }

    @Test fun aDockWhoseEntriesAreAllUnusableStillHasTheChat() {
        val array = JSONArray().put(JSONObject().put("key", "mystery").put("label", "?")).put(JSONObject().put("label", "no key"))
        assertEquals(DefaultDock, readDock(Versioned.write(1, array)))
    }

    @Test fun theChatCannotBeUnpinned() {
        assertEquals(listOf(chat, mail), listOf(chat, mail).unpinned(OMA_CHAT_KEY))
        assertEquals(listOf(chat, mail), listOf(chat, mail).after(DockAction.Unpin, OMA_CHAT_KEY))
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

    @Test fun theStoreSavesWhatItPinsAndReadsItBack() {
        val saved = Saved()
        val store = saved.store()
        assertEquals(DefaultDock, store.items.toList())
        assertEquals(0, saved.writes)
        store.pin(mail)
        store.pin(workMail)
        store.pin(foot)
        assertEquals(3, saved.writes)
        assertTrue(store.isPinned(workMail.key))
        assertFalse(store.isPinned("app:gone/.Gone"))
        assertEquals(listOf(chat, mail, workMail, foot), store.items.toList())
        assertEquals(listOf(chat, mail, workMail, foot), saved.store().items.toList())
    }

    @Test fun theStoreUnpinsOneAppAndKeepsTheRest() {
        val saved = Saved(writeDock(listOf(chat, mail, workMail, foot)))
        val store = saved.store()
        store.unpin(mail.key)
        assertEquals(listOf(chat, workMail, foot), store.items.toList())
        assertEquals(listOf(chat, workMail, foot), saved.store().items.toList())
        store.unpin(OMA_CHAT_KEY)
        assertEquals(listOf(chat, workMail, foot), saved.store().items.toList())
    }

    @Test fun theStoreSavesMovesAndMenuActions() {
        val saved = Saved(writeDock(listOf(chat, mail, foot)))
        val store = saved.store()
        store.act(DockAction.Later, chat.key)
        assertEquals(listOf(mail, chat, foot), saved.store().items.toList())
        store.act(DockAction.Earlier, foot.key)
        assertEquals(listOf(mail, foot, chat), saved.store().items.toList())
        store.act(DockAction.Unpin, mail.key)
        assertEquals(listOf(foot, chat), store.items.toList())
        assertEquals(listOf(foot, chat), saved.store().items.toList())
    }

    @Test fun theStoreDoesNotWriteWhenNothingChanged() {
        val saved = Saved(writeDock(listOf(chat, mail)))
        val store = saved.store()
        store.pin(mail)
        store.unpin(foot.key)
        store.act(DockAction.Earlier, chat.key)
        assertEquals(0, saved.writes)
    }

    @Test fun aDamagedDockIsRepairedInMemoryAndSavedOnTheNextChange() {
        val saved = Saved(writeDock(listOf(mail)))
        val store = saved.store()
        assertEquals(listOf(chat, mail), store.items.toList())
        store.pin(foot)
        assertEquals(listOf(chat, mail, foot), readDock(saved.text))
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
                // Neither an app nor the chat.
                .put(JSONObject().put("key", "mystery").put("label", "?"))
                .put(JSONObject().put("key", "app:a/.A").put("label", "A again").put("component", "a/a.A"))
                .put(JSONObject().put("key", OMA_CHAT_KEY).put("label", "Oma"))
        val read = readDock(Versioned.write(1, array))
        assertEquals(listOf("app:a/.A", OMA_CHAT_KEY), keys(read))
        assertEquals("A", read.first().label)
    }

    @Test fun pinningAddsAtTheEnd() {
        assertEquals(listOf(OMA_CHAT_KEY, mail.key, foot.key), keys(DefaultDock.pinned(mail).pinned(foot)))
    }

    @Test fun pinningAgainKeepsThePlaceAndTakesTheNewLabel() {
        val dock = listOf(chat, mail, foot).pinned(mail.copy(label = "Post"))
        assertEquals(listOf(OMA_CHAT_KEY, mail.key, foot.key), keys(dock))
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
        assertEquals(listOf(chat, mail), listOf(chat, mail).unpinned(foot.key))
    }

    @Test fun movesByOnePlaceAndStopsAtTheEnds() {
        val dock = listOf(chat, mail, foot)
        assertEquals(listOf(mail.key, OMA_CHAT_KEY, foot.key), keys(dock.moved(mail.key, -1)))
        assertEquals(listOf(OMA_CHAT_KEY, foot.key, mail.key), keys(dock.moved(mail.key, 1)))
        assertSame(dock, dock.moved(chat.key, -1))
        assertSame(dock, dock.moved(foot.key, 1))
        assertSame(dock, dock.moved("app:gone/.Gone", 1))
        assertEquals(listOf(foot.key, OMA_CHAT_KEY, mail.key), keys(dock.moved(foot.key, -5)))
    }

    @Test fun theMenuOffersOnlyWhatCanBeDone() {
        val dock = listOf(chat, mail, foot)
        assertEquals(listOf(DockAction.Later), dock.actions(chat.key))
        assertEquals(listOf(DockAction.Earlier, DockAction.Later, DockAction.Unpin), dock.actions(mail.key))
        assertEquals(listOf(DockAction.Earlier, DockAction.Unpin), dock.actions(foot.key))
        assertEquals(emptyList<DockAction>(), DefaultDock.actions(chat.key))
        assertEquals(emptyList<DockAction>(), dock.actions("app:gone/.Gone"))
    }

    @Test fun menuActionsDoWhatTheySay() {
        val dock = listOf(chat, mail, foot)
        assertEquals(listOf(mail.key, OMA_CHAT_KEY, foot.key), keys(dock.after(DockAction.Earlier, mail.key)))
        assertEquals(listOf(OMA_CHAT_KEY, foot.key, mail.key), keys(dock.after(DockAction.Later, mail.key)))
        assertEquals(listOf(OMA_CHAT_KEY, foot.key), keys(dock.after(DockAction.Unpin, mail.key)))
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
