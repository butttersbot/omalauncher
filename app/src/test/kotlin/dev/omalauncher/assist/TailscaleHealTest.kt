package dev.omalauncher.assist

import dev.omalauncher.theme.pack.moduleFile
import org.junit.Assert.*
import org.junit.Test

/**
 * When Tailscale is asked to connect (Boss, 2026-10-14: it stays off after every reboot, and the Mac with it).
 * Nothing in here sends anything: the decision is pure, and the one broadcast is checked in the source.
 */
class TailscaleHealTest {
    private val now = 1_760_000_000_000L
    private val second = 1_000L
    private val minute = 60_000L
    private val up = MacHealthResult.Up(MacHealth(1, 0, 0))

    private fun step(link: MacLink, vpnUp: Boolean = false, installed: Boolean = true, at: Long = now, state: HealState = HealState()) =
        healStep(link, vpnUp, installed, at, state)

    @Test fun healthResultsMapToWhatTheHealCaresAbout() {
        assertEquals(MacLink.Answered, macLink(up))
        // A refused key is still the Mac answering: the network to it works.
        assertEquals(MacLink.Answered, macLink(MacHealthResult.Refused(401)))
        assertEquals(MacLink.NoAnswer, macLink(MacHealthResult.Unreachable))
        assertEquals(MacLink.NotSetUp, macLink(MacHealthResult.NotConfigured))
    }

    @Test fun onlyASilentMacWithNoVpnUpAndTailscaleInstalledSends() {
        val first = step(MacLink.NoAnswer)
        assertTrue(first.send)
        assertEquals(HealState(now, 1, off = true), first.state)
        assertFalse(step(MacLink.NoAnswer, vpnUp = true).send)
        assertFalse(step(MacLink.NoAnswer, installed = false).send)
        assertFalse(step(MacLink.Answered).send)
        assertFalse(step(MacLink.NotSetUp).send)
    }

    @Test fun theTableOfEveryCase() {
        val tried = HealState(now - 30 * second, 1, off = true)
        val waited = HealState(now - 61 * second, 1, off = true)
        // link, VPN up, installed, state before -> send, state after
        val table = listOf(
            listOf(MacLink.NoAnswer, false, true, HealState()) to (true to HealState(now, 1, true)),
            listOf(MacLink.NoAnswer, false, true, tried) to (false to tried),
            listOf(MacLink.NoAnswer, false, true, waited) to (true to HealState(now, 2, true)),
            listOf(MacLink.NoAnswer, true, true, tried) to (false to tried.copy(off = false)),
            listOf(MacLink.NoAnswer, false, false, tried) to (false to tried.copy(off = false)),
            listOf(MacLink.NotSetUp, false, true, tried) to (false to tried.copy(off = false)),
            listOf(MacLink.Answered, false, true, tried) to (false to HealState()),
            listOf(MacLink.Answered, true, true, HealState(now - minute, 4, true)) to (false to HealState()),
        )
        for ((given, want) in table) {
            val got = healStep(given[0] as MacLink, given[1] as Boolean, given[2] as Boolean, now, given[3] as HealState)
            assertEquals(given.toString(), want.first, got.send)
            assertEquals(given.toString(), want.second, got.state)
        }
    }

    /** Boss, 2026-10-17: he never turns Tailscale off himself, so an off after it was up is healed too. */
    @Test fun aTailscaleThatDroppedAfterBeingUpIsTurnedBackOn() {
        // Overnight Android stopped it after it had been up since boot: the next silent Mac asks it to connect.
        val first = step(MacLink.NoAnswer)
        assertTrue(first.send)
        assertFalse(first.state.chosen)
        // An old state that still says "his choice" is no reason to wait either.
        assertTrue(step(MacLink.NoAnswer, state = HealState(off = false, chosen = true)).send)
    }

    @Test fun seenUpCountsOnlyUntilThePhoneReboots() {
        val seen = TailscaleSeen(elapsed = 5 * minute, boot = 41)
        // seen, boot clock now, boot count now -> seen since boot
        val table = listOf(
            Triple(null, 9 * minute, 41) to false,
            // The launcher restarted, the phone did not: the boot clock ran on and the count is the same.
            Triple(seen, 9 * minute, 41) to true,
            Triple(seen, 5 * minute, 41) to true,
            // Rebooted: the boot clock started again below it, or the count moved on even past it.
            Triple(seen, 2 * minute, 41) to false,
            Triple(seen, 2 * minute, null) to false,
            Triple(seen, 90 * minute, 42) to false,
            // Where the count can't be read the boot clock alone decides.
            Triple(seen, 9 * minute, null) to true,
            Triple(seen.copy(boot = null), 9 * minute, 42) to true,
            Triple(seen.copy(boot = null), 2 * minute, 42) to false,
        )
        for ((given, want) in table) assertEquals(given.toString(), want, seenThisBoot(given.first, given.second, given.third))
    }

    @Test fun tailscaleIsKnownByItsAddressesOrByTheMacAnsweringOverAVpn() {
        for (host in listOf("100.64.0.1", "100.120.219.46", "100.127.255.254", "fd7a:115c:a1e0::1", "FD7A:115C:A1E0:ab12::5%tun0")) assertTrue(host, tailscaleAddress(host))
        for (host in listOf("100.63.255.255", "100.128.0.1", "10.0.0.2", "192.168.1.4", "fd00::1", "", "100.64.0")) assertFalse(host, tailscaleAddress(host))
        assertTrue(tailscaleSeenUp(MacLink.NoAnswer, vpnUp = true, tailscaleVpn = true))
        assertTrue(tailscaleSeenUp(MacLink.Answered, vpnUp = true, tailscaleVpn = false))
        // Another VPN with the Mac silent, or the Mac answering with no VPN at all, says nothing of Tailscale.
        assertFalse(tailscaleSeenUp(MacLink.NoAnswer, vpnUp = true, tailscaleVpn = false))
        assertFalse(tailscaleSeenUp(MacLink.Answered, vpnUp = false, tailscaleVpn = false))
    }

    @Test fun neverMoreThanOnceAMinuteThenBackingOff() {
        assertEquals(listOf(60 * second, 2 * minute, 5 * minute, 10 * minute, 10 * minute), (1..5).map { healWait(it) })
        var state = HealState()
        var at = now
        val sentAt = mutableListOf<Long>()
        // A health check every 10 s for an hour, the Mac silent throughout.
        while (at < now + 60 * minute) {
            val got = step(MacLink.NoAnswer, at = at, state = state)
            if (got.send) sentAt += at - now
            state = got.state
            at += 10 * second
        }
        assertEquals(listOf(0L, 60 * second, 3 * minute, 8 * minute, 18 * minute, 28 * minute, 38 * minute, 48 * minute, 58 * minute), sentAt)
        assertEquals(now + 58 * minute + 10 * minute, healNext(state))
        assertNull(healNext(HealState()))
    }

    @Test fun theMacAnsweringStartsTheCountAgain() {
        val after = step(MacLink.Answered, state = HealState(now - 9 * minute, 5, off = true)).state
        assertEquals(HealState(), after)
        assertTrue(step(MacLink.NoAnswer, at = now + second, state = after).send)
    }

    @Test fun aClockSetBackDoesNotHoldTheHealOff() {
        assertTrue(step(MacLink.NoAnswer, state = HealState(now + 30 * minute, 4, off = true)).send)
    }

    @Test fun theNoteSaysTailscaleOnlyWhileItIsTheCause() {
        assertEquals("Tailscale was off on this phone; reconnecting (tried 20 s ago)", healNote(HealState(now - 20 * second, 1, off = true), now))
        assertEquals("Tailscale was off on this phone; reconnecting (tried 3 min ago)", healNote(HealState(now - 3 * minute, 2, off = true), now))
        assertNull(healNote(HealState(now - 20 * second, 1, off = false), now))
        assertNull(healNote(HealState(), now))
        // Off by his choice it says so, so the front agent tells him rather than promising a reconnect.
        assertEquals("Tailscale is off on this phone (turned off by you); the Mac can't be reached until it is on.", healNote(HealState(off = false, chosen = true), now))
        // Waiting out the back-off it is still the cause, and still said.
        val waiting = step(MacLink.NoAnswer, at = now + 30 * second, state = HealState(now, 1, off = true))
        assertFalse(waiting.send)
        assertEquals("Tailscale was off on this phone; reconnecting (tried 30 s ago)", healNote(waiting.state, now + 30 * second))
    }

    @Test fun theBroadcastIsTailscalesConnectToItsOwnReceiver() {
        assertEquals(TailscaleTarget("com.tailscale.ipn.CONNECT_VPN", "com.tailscale.ipn", "com.tailscale.ipn.IPNReceiver"), TAILSCALE_CONNECT)
        val source = moduleFile("src/main/kotlin/dev/omalauncher/assist/TailscaleHeal.kt").readText()
        // The only intent made, explicit to that receiver; nothing ever sends Tailscale a disconnect.
        assertEquals(1, Regex("""Intent\(""").findAll(source).count())
        assertTrue(source.contains("sendBroadcast(Intent(TAILSCALE_CONNECT.action).setClassName(TAILSCALE_CONNECT.packageName, TAILSCALE_CONNECT.className))"))
        assertFalse(source.contains("DISCONNECT_VPN\""))
        assertFalse(source.contains("VpnService"))
    }

    @Test fun theManifestCanSeeTailscaleAndTheNetworks() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("<package android:name=\"com.tailscale.ipn\" />"))
        assertTrue(manifest.contains("android.permission.ACCESS_NETWORK_STATE"))
    }
}
