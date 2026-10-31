package dev.omalauncher.connections

/*
 * The phone's own readings that need no Android: GitHub access, free space, mail delivery and the keeper. Each takes what
 * was observed and says what it means, so the rules are tested here and the monitor only gathers the observations.
 */

private val GITHUB_UNREACHABLE = Regex("could not resolve host|failed to connect|connection (timed out|refused|reset)|timed out|network is unreachable|" +
    "returned error: 5\\d\\d|service unavailable|bad gateway|early eof|ssl|unable to access.*(5\\d\\d|resolve)", RegexOption.IGNORE_CASE)
private val GITHUB_LOGIN = Regex("could not read (username|password)|authentication failed|invalid username|bad credentials|returned error: 40[13]|" +
    "terminal prompts disabled|permission denied|repository not found|support for password authentication", RegexOption.IGNORE_CASE)

/** (code, result) for one device's GitHub access from what git said: only a refused login is a failure; GitHub's outage or no route is unreachable. */
fun classifyGithub(lsCode: Int?, lsText: String, pushCode: Int?, pushText: String): Pair<String, String> {
    val seen = listOf(lsCode to lsText, pushCode to pushText)
    if (seen.any { (code, text) -> code != null && code != 0 && GITHUB_LOGIN.containsMatchIn(text) }) return "github_login_invalid" to "fail"
    if (seen.any { (code, text) -> code != null && code != 0 && GITHUB_UNREACHABLE.containsMatchIn(text) }) return "github_unreachable" to "unverified"
    if (lsCode != 0) return "github_read_failed" to "unverified"
    if (pushCode == null) return "github_push_untested" to "unverified"
    // A dry run GitHub answered, even by refusing a stale branch, got past the login.
    if (pushCode == 0 || Regex("\\[rejected]|\\[remote rejected]|non-fast-forward|fetch first").containsMatchIn(pushText)) return "github_ok" to "pass"
    return "github_push_failed" to "unverified"
}

/** The phone's GitHub reading. [code]/[result] from [classifyGithub], or null for a phone with no checkout: it doesn't use GitHub, which is no failure. */
fun githubReading(outcome: Pair<String, String>?, now: Long): ConnectionReading {
    if (outcome == null) return ConnectionReading(Capability.GitHub, ConnectionState.Disabled, "This phone doesn't use GitHub.", now,
        checks = listOf(ConnectionCheck("github_access", "phone", result = "not_installed", fields = mapOf("code" to "no_github_use"))))
    val (code, result) = outcome
    val check = ConnectionCheck("github_access", "phone", result = result, fields = mapOf("code" to code, "fix" to "run gh auth login"))
    val root = "github:phone"
    return when (result) {
        "pass" -> ConnectionReading(Capability.GitHub, ConnectionState.Ready, "GitHub is reachable and this phone's login is valid.", now, root, checks = listOf(check))
        "fail" -> ConnectionReading(Capability.GitHub, ConnectionState.Failed, "This phone: GitHub login expired. Run gh auth login.", now, root, checks = listOf(check))
        else -> ConnectionReading(Capability.GitHub, ConnectionState.Unknown,
            if (code == "github_unreachable") "This phone can't reach GitHub right now (GitHub or the network); that is not a login problem." else "GitHub could not be checked from this phone yet.",
            now, checks = listOf(check))
    }
}

/** Free space on the phone: always in the words; a failure only below [lowBytes] (2 GB), where an install or a build would break. */
fun diskReading(freeBytes: Long?, now: Long, lowBytes: Long = 2L * 1024 * 1024 * 1024): ConnectionReading {
    if (freeBytes == null) return ConnectionReading(Capability.Disk, ConnectionState.Unknown, "This phone's free space could not be read.", now)
    val gb = "%.1f".format(freeBytes / 1024.0 / 1024 / 1024)
    val low = freeBytes < lowBytes
    val check = ConnectionCheck("disk_space", "phone", result = if (low) "fail" else "pass", fields = mapOf("free_gb" to gb, "code" to if (low) "disk_low" else "ok"))
    return ConnectionReading(Capability.Disk, if (low) ConnectionState.Failed else ConnectionState.Ready,
        "This phone has $gb GB free." + if (low) " That is low enough to break installs and builds." else "", now, "disk:phone", checks = listOf(check))
}

/** One receipt of agent mail that came to this phone. [state]: queued, delivered, not-delivered. Times in ms. */
data class MailReceipt(val id: String, val sender: String, val time: Long, val state: String, val updated: Long)

/** How long mail may wait for a free builder before it is called stuck, when nothing is working. */
const val MAIL_STUCK_MS = 30 * 60_000L
/** A mail an interrupted app left undelivered stays an incident for a day. */
const val MAIL_UNDELIVERED_KEEP_MS = 24 * 3_600_000L

/**
 * Whether agent mail to this phone is really delivered, from its receipts (a handed-off mail is not a delivered one). Mail waiting behind
 * a builder's running task is normal ([builderBusy]); mail waiting past [MAIL_STUCK_MS] with nothing working, or one an interruption left
 * undelivered, names its sender as the route. No receipts is no problem. The phone sends no probe mail.
 */
fun mailReading(receipts: List<MailReceipt>, builderBusy: Boolean, now: Long): ConnectionReading {
    val stuck = receipts.filter { it.state == "queued" && !builderBusy && now - it.updated > MAIL_STUCK_MS }
    val lost = receipts.filter { it.state == "not-delivered" && now - it.updated < MAIL_UNDELIVERED_KEEP_MS }
    val bad = stuck + lost
    if (bad.isEmpty()) {
        val waiting = receipts.count { it.state == "queued" }
        return ConnectionReading(Capability.Mail, ConnectionState.Ready, if (waiting > 0) "$waiting mail waiting behind a running task; none stuck." else "No mail is waiting or lost on this phone.", now, "mail:phone")
    }
    val routes = bad.map { it.sender.ifBlank { "unknown sender" } }.distinct().joinToString(", ")
    val oldest = bad.minOf { it.updated }
    val words = (if (stuck.isNotEmpty()) "${stuck.size} mail from $routes has waited ${ageWords(now - stuck.minOf { it.updated })} with nothing working to take it. " else "") +
        (if (lost.isNotEmpty()) "${lost.size} mail was left undelivered when the app was interrupted (${lost.map { it.sender.ifBlank { "unknown sender" } }.distinct().joinToString(", ")}). " else "") +
        "Oldest: ${ageWords(now - oldest)} ago."
    val check = ConnectionCheck("mail_delivery", "phone", result = "fail", fields = mapOf("code" to if (stuck.isNotEmpty()) "mail_stuck" else "mail_undelivered", "routes" to routes.take(200)))
    return ConnectionReading(Capability.Mail, ConnectionState.Failed, words.trim(), now, "mail:phone", checks = listOf(check))
}

/**
 * The phone keeper, asked from inside the guest (`oma keep list`): the app's own socket connect never reached it (a host path past the socket
 * limit), so it read Unknown for days while the keeper worked. [exit] is the command's exit code, null if it could not be started.
 */
fun keeperCheck(exit: Int?, output: String): ConnectionCheck = when {
    exit == null -> ConnectionCheck("phone_keeper_protocol", "phone", result = "unverified", fields = mapOf("code" to "could_not_ask"))
    exit == 0 -> ConnectionCheck("phone_keeper_protocol", "phone", result = "pass", fields = mapOf("kept_rows" to output.lines().count { it.isNotBlank() && !it.startsWith("Nothing is kept") }.toString()))
    output.contains("keeper is not running", ignoreCase = true) -> ConnectionCheck("phone_keeper_protocol", "phone", result = "fail", fields = mapOf("code" to "keeper_not_running"))
    else -> ConnectionCheck("phone_keeper_protocol", "phone", result = "unverified", fields = mapOf("code" to "unexpected_answer"))
}

/** The keeper's reading: a dead keeper is a real failure and says so plainly; the Linux runtime being off is not one. */
fun keeperReading(check: ConnectionCheck, now: Long): ConnectionReading = when (check.result) {
    "pass" -> ConnectionReading(Capability.Agents, ConnectionState.Ready, "Phone keeper answered `oma keep list`.", now, checks = listOf(check))
    "fail" -> ConnectionReading(Capability.Agents, ConnectionState.Failed, "The phone's keeper isn't running: jobs it keeps after a turn ends won't stay up. Soma starts it again by itself.", now, "phone-keeper", checks = listOf(check))
    else -> ConnectionReading(Capability.Agents, ConnectionState.Unknown, "The phone keeper could not be asked; no running jobs are inferred or terminated.", now, checks = listOf(check))
}
