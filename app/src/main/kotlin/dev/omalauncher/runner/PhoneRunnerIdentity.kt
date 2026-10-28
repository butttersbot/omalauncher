package dev.omalauncher.runner

import org.json.JSONObject

const val PHONE_RUNNER_DEVICE = "galaxy-z-fold8"
const val RUNNER_OFFLINE_IDENTITY_MS = 7L * 24 * 60 * 60 * 1000
const val RUNNER_LIVE_IDENTITY_MS = 60_000L

sealed interface RunnerIdentityLookup {
    /** Timestamp of the actual whois confirmation, not of a later one-minute cache hit. */
    data class Confirmed(val peer: RunnerPeer, val at: Long) : RunnerIdentityLookup
    data object Rejected : RunnerIdentityLookup
    data object Unavailable : RunnerIdentityLookup
}
data class RunnerCachedIdentity(val address: String, val at: Long)
data class RunnerIdentityDecision(val accepted: Boolean, val cache: RunnerCachedIdentity?)

fun runnerTailnetIPv4(address: String): Boolean {
    val parts = address.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } }) return false
    val ip = parts.map { it.toInt() }
    return ip.all { it in 0..255 } && ip[0] == 100 && ip[1] in 64..127
}

/** Raw TCP peer/local addresses only; no forwarding headers. Even possession of a key cannot admit this phone. */
fun runnerRemoteSource(address: String, localAddresses: Set<String>): Boolean =
    runnerTailnetIPv4(address) && address !in localAddresses

/** A reachable helper's refusal always wins. Only transport/service unavailability permits the bounded cache. */
fun runnerIdentityDecision(device: String, address: String, cached: RunnerCachedIdentity?, lookup: RunnerIdentityLookup, now: Long): RunnerIdentityDecision {
    if (!runnerTailnetIPv4(address)) return RunnerIdentityDecision(false, cached)
    return when (lookup) {
        is RunnerIdentityLookup.Confirmed -> {
            val fresh = lookup.peer.owner && lookup.peer.device == device && lookup.at >= 0 &&
                now >= lookup.at && now - lookup.at <= RUNNER_LIVE_IDENTITY_MS
            if (fresh) RunnerIdentityDecision(true, RunnerCachedIdentity(address, lookup.at))
            else RunnerIdentityDecision(false, cached?.takeUnless { it.address == address })
        }
        RunnerIdentityLookup.Rejected -> RunnerIdentityDecision(false, cached?.takeUnless { it.address == address })
        RunnerIdentityLookup.Unavailable -> RunnerIdentityDecision(
            cached != null && cached.address == address && cached.at >= 0 && now >= cached.at && now - cached.at <= RUNNER_OFFLINE_IDENTITY_MS,
            cached,
        )
    }
}

/** Private phone record: digest and confirmed address/time only. Rekeying starts with a new confirmation. */
fun runnerKeyRecord(key: RunnerKey): String {
    require(Regex("[0-9a-f]{64}").matches(key.digest))
    return JSONObject().put("digest", key.digest).apply {
        key.identity?.let { put("address", it.address); put("confirmed_at", it.at) }
    }.toString()
}
fun runnerKeyFromRecord(device: String, record: String): RunnerKey? = runCatching {
    if (!Regex("[a-z0-9][a-z0-9-]{0,62}").matches(device) || device == PHONE_RUNNER_DEVICE) return null
    // Existing digest-only pairings remain valid online; no offline proof is invented for them.
    val json = if (Regex("[0-9a-f]{64}").matches(record)) JSONObject().put("digest", record) else JSONObject(record)
    val digest = json.getString("digest").takeIf { Regex("[0-9a-f]{64}").matches(it) } ?: return null
    val identity = json.optString("address").takeIf(::runnerTailnetIPv4)?.let { RunnerCachedIdentity(it, json.optLong("confirmed_at", -1)) }
    RunnerKey(device, digest, identity)
}.getOrNull()

data class RunnerBindIdentity(val address: String?, val cache: RunnerCachedIdentity?)
fun runnerBindIdentity(vpnAddresses: List<String>, cached: RunnerCachedIdentity?, lookup: (String) -> RunnerIdentityLookup, now: Long): RunnerBindIdentity {
    var saved = cached
    for (address in vpnAddresses.filter(::runnerTailnetIPv4)) {
        val result = runnerIdentityDecision(PHONE_RUNNER_DEVICE, address, saved,
            runCatching { lookup(address) }.getOrDefault(RunnerIdentityLookup.Unavailable), now)
        saved = result.cache
        if (result.accepted) return RunnerBindIdentity(address, saved)
    }
    return RunnerBindIdentity(null, saved)
}

data class RunnerCallerDecision(val key: RunnerKey? = null, val cache: RunnerCachedIdentity? = null, val peer: RunnerPeer? = null)
/** The listener uses this gate before protocol dispatch, including for every stream heartbeat. */
fun runnerCallerDecision(token: String?, address: String, local: Set<String>, keys: List<RunnerKey>, lookup: (String) -> RunnerIdentityLookup, now: Long): RunnerCallerDecision {
    if (!runnerRemoteSource(address, local) || token.isNullOrBlank() || token.length > 512) return RunnerCallerDecision()
    val digest = runnerDigest(token).toByteArray(Charsets.US_ASCII)
    val key = keys.firstOrNull { java.security.MessageDigest.isEqual(digest, it.digest.toByteArray(Charsets.US_ASCII)) } ?: return RunnerCallerDecision()
    val result = runnerIdentityDecision(key.device, address, key.identity,
        runCatching { lookup(address) }.getOrDefault(RunnerIdentityLookup.Unavailable), now)
    return RunnerCallerDecision(key, result.cache, if (result.accepted) RunnerPeer(key.device, true) else null)
}
