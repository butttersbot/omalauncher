package dev.omalauncher.phone

import dev.omalauncher.assist.BuildBoard
import dev.omalauncher.assist.boardText
import dev.omalauncher.theme.pack.moduleFile
import org.junit.Assert.*
import org.junit.Test

/**
 * When phone control is put back (Boss, 2026-10-14: "we had it on. So we need that to be self-healing also"), and
 * that it never is against his own switch-off. Nothing here writes a setting: the decision and the list edit are
 * pure, and what the shell may name is checked in its source.
 */
class PhoneControlHealTest {
    private val mine = "dev.omalauncher.boss/dev.omalauncher.phone.PhoneService"
    private val talkback = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
    private val other = "com.example.reader/.ReaderService"
    private val minute = 60_000L

    /** Wanted on, missing from the list, no tries yet, long after start: the case that is healed by an add. */
    private val missing = PhoneHealFacts(granted = true, wanted = true, offByOwner = false, listed = false, bound = false,
        upMillis = 10 * minute, tries = 0, sinceTry = null)

    @Test fun wantedAndMissingIsAddedAndWantedAndStuckIsRebound() {
        assertEquals(PhoneHealAction.Add, phoneHeal(missing))
        assertEquals(PhoneHealAction.Rebind, phoneHeal(missing.copy(listed = true)))
    }

    @Test fun decisionTable() {
        for (granted in listOf(true, false)) for (wanted in listOf(true, false)) for (listed in listOf(true, false))
            for (bound in listOf(true, false)) for (owner in listOf(true, false)) for (tries in 0..4)
                for (since in listOf(null, 0L, PHONE_HEAL_WAIT_MILLIS - 1, PHONE_HEAL_WAIT_MILLIS)) {
                    val f = PhoneHealFacts(granted, wanted, owner, listed, bound, 10 * minute, tries, since.takeIf { tries > 0 })
                    val expected = when {
                        !granted || !wanted || owner || bound -> PhoneHealAction.Leave
                        tries >= PHONE_HEAL_TRIES -> PhoneHealAction.GiveUp
                        f.sinceTry != null && f.sinceTry < PHONE_HEAL_WAIT_MILLIS -> PhoneHealAction.Leave
                        listed -> PhoneHealAction.Rebind
                        else -> PhoneHealAction.Add
                    }
                    assertEquals(f.toString(), expected, phoneHeal(f))
                }
    }

    @Test fun neverWithoutTheGrantOrAgainstHisSwitchOff() {
        assertEquals(PhoneHealAction.Leave, phoneHeal(missing.copy(granted = false)))
        assertEquals(PhoneHealAction.Leave, phoneHeal(missing.copy(offByOwner = true)))
        assertEquals(PhoneHealAction.Leave, phoneHeal(missing.copy(wanted = false)))
        // He switched it off with its tries spent: still his choice, not a failure to show.
        assertEquals(PhoneHealAction.Leave, phoneHeal(missing.copy(offByOwner = true, tries = PHONE_HEAL_TRIES)))
    }

    @Test fun listedAndUnboundWaitsOutTheTimeAndroidTakesToBind() {
        assertEquals(PhoneHealAction.Leave, phoneHeal(missing.copy(listed = true, upMillis = PHONE_BIND_GRACE_MILLIS - 1)))
        assertEquals(PhoneHealAction.Rebind, phoneHeal(missing.copy(listed = true, upMillis = PHONE_BIND_GRACE_MILLIS)))
        // Missing from the list there is nothing to wait for.
        assertEquals(PhoneHealAction.Add, phoneHeal(missing.copy(upMillis = 0)))
    }

    @Test fun atMostOnceInFiveMinutesAndThreeTimesABoot() {
        assertEquals(PhoneHealAction.Leave, phoneHeal(missing.copy(tries = 1, sinceTry = PHONE_HEAL_WAIT_MILLIS - 1)))
        assertEquals(PhoneHealAction.Add, phoneHeal(missing.copy(tries = 1, sinceTry = PHONE_HEAL_WAIT_MILLIS)))
        assertEquals(PhoneHealAction.Add, phoneHeal(missing.copy(tries = 2, sinceTry = 10 * minute)))
        assertEquals(PhoneHealAction.GiveUp, phoneHeal(missing.copy(tries = 3, sinceTry = 10 * minute)))
        assertEquals(PhoneHealAction.GiveUp, phoneHeal(missing.copy(tries = 3, sinceTry = 0)))
        assertEquals(5 * minute, PHONE_HEAL_WAIT_MILLIS)
        assertEquals(3, PHONE_HEAL_TRIES)
    }

    @Test fun givenUpReadsAsDisconnectedOnTheTileAndOffOtherwise() {
        assertEquals(PhoneLink.Disconnected, phoneLink(bound = false, switchedOn = false, upMillis = 10 * minute, healGaveUp = true))
        assertEquals(PhoneLink.Off, phoneLink(bound = false, switchedOn = false, upMillis = 10 * minute))
        assertEquals(PhoneLink.On, phoneLink(bound = true, switchedOn = false, upMillis = 10 * minute, healGaveUp = true))
    }

    @Test fun onlyHisSettingsInFrontMakeAnUnbindHisChoice() {
        assertTrue(ownerTurnedOff("com.android.settings"))
        assertTrue(ownerTurnedOff("com.samsung.accessibility"))
        assertTrue(ownerTurnedOff("com.android.systemui"))
        assertFalse(ownerTurnedOff(null))
        assertFalse(ownerTurnedOff("dev.omalauncher.boss"))
        assertFalse(ownerTurnedOff("com.android.chrome"))
    }

    @Test fun addingAppendsAndLeavesEveryOtherServiceAsItWas() {
        assertEquals(mine, servicesWith(null, mine))
        assertEquals(mine, servicesWith("", mine))
        assertEquals("$talkback:$mine", servicesWith(talkback, mine))
        assertEquals("$talkback:$other:$mine", servicesWith("$talkback:$other", mine))
        // Already there once: nothing moves.
        assertEquals("$mine:$talkback", servicesWith("$mine:$talkback", mine))
        // Twice, or in Android's short form and the long one: one of it, at the end, the others in their order.
        assertEquals("$talkback:$other:$mine", servicesWith("$mine:$talkback:$mine:$other", mine))
        assertEquals("$talkback:$mine", servicesWith("dev.omalauncher.boss/dev.omalauncher.phone.PhoneService:$talkback:$mine", mine))
        // Empty entries are not services: they are dropped, and nothing else.
        assertEquals("$talkback:$other:$mine", servicesWith("$talkback::$other:", mine))
    }

    @Test fun removingTakesOnlyThisOne() {
        assertEquals("$talkback:$other", servicesWithout("$talkback:$mine:$other", mine))
        assertEquals(talkback, servicesWithout("$mine:$talkback:$mine", mine))
        assertNull(servicesWithout(mine, mine))
        assertNull(servicesWithout(null, mine))
        assertEquals("$talkback:$other", servicesWithout("$talkback:$other", mine))
    }

    @Test fun shortFormIsTheSameService() {
        assertTrue(sameService("dev.omalauncher.boss/dev.omalauncher.phone.PhoneService", mine))
        assertTrue(sameService("dev.omalauncher/.phone.PhoneService", "dev.omalauncher/dev.omalauncher.phone.PhoneService"))
        // Oma Dev's service has the same class in another package: another service, never touched.
        assertFalse(sameService("dev.omalauncher.dev/dev.omalauncher.phone.PhoneService", mine))
        assertEquals(listOf("dev.omalauncher.dev/dev.omalauncher.phone.PhoneService"), otherServices("dev.omalauncher.dev/dev.omalauncher.phone.PhoneService:$mine", mine))
    }

    @Test fun accessibilityIsSwitchedOnOnlyWhenThisServiceWasAllThatWasMissing() {
        assertTrue(turnAccessibilityOn(0, null, mine))
        assertTrue(turnAccessibilityOn(null, "", mine))
        assertTrue(turnAccessibilityOn(0, mine, mine))
        assertFalse(turnAccessibilityOn(1, null, mine))
        // Off with others listed: someone turned accessibility off, and it stays so.
        assertFalse(turnAccessibilityOn(0, talkback, mine))
    }

    @Test fun theBoardSaysWhetherHeTurnedItOffOrItWasPutBack() {
        val now = 1_760_000_000_000L
        assertEquals(PHONE_OFF_BY_HIM, phoneHealNote(offByOwner = true, bound = false, healed = null, gaveUp = false, now = now))
        assertEquals("Phone control is off (you turned it off).", PHONE_OFF_BY_HIM)
        assertEquals("Phone control was off; turned back on (3 min ago).", phoneHealNote(false, bound = true, healed = now - 3 * minute, gaveUp = false, now = now))
        assertEquals("Phone control was off; turned back on (just now).", phoneHealNote(false, bound = true, healed = now - 5_000, gaveUp = false, now = now))
        assertEquals("Phone control was off; turning it back on.", phoneHealNote(false, bound = false, healed = now - minute, gaveUp = false, now = now))
        assertTrue(phoneHealNote(false, bound = false, healed = now - 30 * minute, gaveUp = true, now = now)!!.endsWith(PHONE_DISCONNECTED))
        // Long since, or never healed and on: nothing to say.
        assertNull(phoneHealNote(false, bound = true, healed = now - PHONE_HEALED_SHOWN_MILLIS, gaveUp = false, now = now))
        assertNull(phoneHealNote(false, bound = true, healed = null, gaveUp = false, now = now))
        assertNull(phoneHealNote(false, bound = false, healed = null, gaveUp = false, now = now))
    }

    @Test fun theLineGoesOnTheBoardAndNoLineLeavesItAsItWas() {
        val board = BuildBoard(working = false)
        assertEquals(boardText(board), boardText(board, phone = null))
        assertTrue(boardText(board, phone = PHONE_OFF_BY_HIM).contains("\n$PHONE_OFF_BY_HIM\n"))
    }

    /** The heal's own source names no service but its own, and writes only the two settings it is about. */
    @Test fun theSourceNeverNamesAnotherService() {
        val source = moduleFile("src/main/kotlin/dev/omalauncher/phone/PhoneControlHeal.kt").readText()
        assertFalse("no flattened component in a string", Regex("\"[A-Za-z0-9_.]+/[A-Za-z0-9_.]+\"").containsMatchIn(source))
        assertEquals(listOf("ComponentName(context, PhoneService::class.java)"), Regex("ComponentName\\([^)]*\\)").findAll(source).map { it.value }.toList())
        assertFalse(source.contains("unflattenFromString"))
        val writes = Regex("Settings\\.(Secure|Global|System)\\.put\\w+\\([^,]+, ([A-Za-z_.]+)").findAll(source).map { it.groupValues[2] }.toSet()
        assertEquals(setOf("Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES", "Settings.Secure.ACCESSIBILITY_ENABLED"), writes)
        // Every write sits behind the grant check.
        assertTrue(source.contains("if (!granted(app) || !busy.compareAndSet(false, true)) return"))
    }
}
