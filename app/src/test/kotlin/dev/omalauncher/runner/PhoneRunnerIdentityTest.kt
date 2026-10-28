package dev.omalauncher.runner

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneRunnerIdentityTest {
    private val at = 1_800_000_000_000L
    private val address = "100.80.1.3"
    private val token = "fictional-desktop-key"
    private val paired = RunnerKey("jacks-mac-pro", runnerDigest(token), RunnerCachedIdentity(address, at))
    private val phone = setOf("127.0.0.1", "100.80.1.2", "100.90.1.1", "192.168.1.5")
    private val offline: (String) -> RunnerIdentityLookup = { RunnerIdentityLookup.Unavailable }
    @Test fun lookupLatencyDoesNotMakeANewConfirmationLookLikeClockRollback() {
        val key = RunnerKey("mac-pro", runnerDigest("fixture"))
        val result = runnerCallerDecision("fixture", "100.80.1.3", setOf("100.80.1.2"), listOf(key),
            { RunnerIdentityLookup.Confirmed(RunnerPeer("mac-pro", true), 2000) }, 1000, { 2000 })
        assertNotNull(result.peer)
        val bind = runnerBindIdentity(listOf("100.80.1.2"), null,
            { RunnerIdentityLookup.Confirmed(RunnerPeer(PHONE_RUNNER_DEVICE, true), 2000) }, 1000, { 2000 })
        assertEquals("100.80.1.2", bind.address)
    }
    @Test fun offlineCacheIsBoundToThisKeyAndAddressForAtMostSevenDays() {
        fun ask(key: RunnerKey = paired, ip: String = address, now: Long = at) = runnerCallerDecision(token, ip, phone, listOf(key), offline, now)
        assertNotNull(ask().peer)
        assertNotNull(ask(now = at + RUNNER_OFFLINE_IDENTITY_MS).peer)
        assertNull(ask(now = at + RUNNER_OFFLINE_IDENTITY_MS + 1).peer)
        assertNull(ask(now = at - 1).peer) // wall-clock rollback cannot extend the lease
        assertNull(ask(ip = "100.80.1.4").peer)
        assertNull(ask(key = paired.copy(identity = null)).peer)
        assertNull(ask(key = paired.copy(digest = runnerDigest("new-key"))).peer)
        assertNull(runnerCallerDecision(token, address, phone, emptyList(), offline, at).peer)
    }
    @Test fun freshWhoisConfirmsANewAddressButRefusalCannotUseTheOldLease() {
        val next = "100.80.1.4"
        val now = at + 1000
        val accepted = runnerCallerDecision(token, next, phone, listOf(paired), { RunnerIdentityLookup.Confirmed(RunnerPeer(paired.device, true), now) }, now)
        assertNotNull(accepted.peer)
        assertEquals(RunnerCachedIdentity(next, now), accepted.cache)
        for (answer in listOf(RunnerIdentityLookup.Rejected,
            RunnerIdentityLookup.Confirmed(RunnerPeer("another-device", true), now),
            RunnerIdentityLookup.Confirmed(RunnerPeer(paired.device, false), now))) {
            val denied = runnerCallerDecision(token, address, phone, listOf(paired), { answer }, now)
            assertNull(denied.peer)
            assertNull(denied.cache) // authoritative revocation removes this address's offline proof
        }
    }
    @Test fun loopbackPhoneLinuxAndEveryLocalInterfaceAreRefusedEvenWithTheCorrectKey() {
        var lookups = 0
        val misleading: (String) -> RunnerIdentityLookup = { lookups++; RunnerIdentityLookup.Confirmed(RunnerPeer(paired.device, true), at) }
        for (ip in phone + setOf("::1", "::ffff:127.0.0.1", "10.0.0.1", "fd7a:115c:a1e0::3", "0.0.0.0")) {
            assertNull(ip, runnerCallerDecision(token, ip, phone, listOf(paired.copy(identity = RunnerCachedIdentity(ip, at))), misleading, at).peer)
        }
        assertEquals(0, lookups)
        assertNull(runnerCallerDecision("wrong-key", address, phone, listOf(paired), misleading, at).peer)
        assertEquals(0, lookups)
    }
    @Test fun cachedConfirmationsNeverSlideTheirTimestampOnUse() {
        val now = at + 30_000
        val confirmation = RunnerIdentityLookup.Confirmed(RunnerPeer(paired.device, true), at)
        val result = runnerCallerDecision(token, address, phone, listOf(paired), { confirmation }, now)
        assertEquals(at, result.cache!!.at)
        assertNull(runnerCallerDecision(token, address, phone, listOf(paired), { confirmation }, at + RUNNER_LIVE_IDENTITY_MS + 1).peer)
    }
    @Test fun diskRecordContainsOnlyTheDigestAndBoundedIdentityNotTheCredential() {
        val record = runnerKeyRecord(paired)
        assertFalse(record.contains(token))
        val json = JSONObject(record)
        assertEquals(setOf("digest", "address", "confirmed_at"), json.keys().asSequence().toSet())
        assertEquals(runnerDigest(token), json.getString("digest"))
        assertEquals(paired, runnerKeyFromRecord(paired.device, record))
        assertNull(runnerKeyFromRecord(PHONE_RUNNER_DEVICE, record))
        assertNull(runnerKeyFromRecord(paired.device, token))
        assertEquals(paired.copy(identity = null), runnerKeyFromRecord(paired.device, paired.digest))
    }
    @Test fun listenerCanRebindOfflineOnlyToTheSameConfirmedVpnIdentityWithinSevenDays() {
        val own = RunnerCachedIdentity("100.80.1.2", at)
        assertEquals(own.address, runnerBindIdentity(listOf(own.address), own, offline, at + RUNNER_OFFLINE_IDENTITY_MS).address)
        assertNull(runnerBindIdentity(listOf(own.address), own, offline, at + RUNNER_OFFLINE_IDENTITY_MS + 1).address)
        assertNull(runnerBindIdentity(listOf("100.80.1.9"), own, offline, at).address)
        assertNull(runnerBindIdentity(listOf("192.168.1.5", "127.0.0.1"), own, offline, at).address)
        val fresh = runnerBindIdentity(listOf(own.address), null, { RunnerIdentityLookup.Confirmed(RunnerPeer(PHONE_RUNNER_DEVICE, true), at) }, at)
        assertEquals(own, fresh.cache)
        assertEquals(own.address, fresh.address)
        val denied = runnerBindIdentity(listOf(own.address), own, { RunnerIdentityLookup.Rejected }, at)
        assertNull(denied.address); assertNull(denied.cache)
    }
}
