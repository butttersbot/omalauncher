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
