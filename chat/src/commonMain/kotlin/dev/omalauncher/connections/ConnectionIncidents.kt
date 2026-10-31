package dev.omalauncher.connections

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Only allowlisted observations belong here, never raw provider/authentication responses. */
data class ConnectionCheck(
    val name: String, val target: String, val code: Int? = null, val result: String,
    val fields: Map<String, String> = emptyMap(), val stderrTail: String = "",
)
data class ConnectionNextStep(val actor: String, val action: String, val reason: String)

class ConnectionRedactor(private val secrets: Collection<String> = emptyList()) {
    fun text(value: String): String {
        var result = value
        for (secret in secrets.filter { it.length >= 4 }.sortedByDescending { it.length }) result = result.replace(secret, "[redacted]")
        result = result.replace(Regex("(?i)Bearer\\s+[^\\s,;\\\"']+"), "Bearer [redacted]")
        result = result.replace(Regex("eyJ[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"), "[redacted JWT]")
        result = result.replace(Regex("(?i)((?:access_token|refresh_token|token|api[_-]?key|password|secret|authorization|cookie|owner_signature|nonce)[\"']?\\s*[:=]\\s*[\"']?)[^\\s,;\\\"']+"), "$1[redacted]")
        result = result.replace(Regex("(?i)(https?://)[^/@\\s]+:[^/@\\s]+@"), "$1[redacted]@")
        result = result.replace(Regex("(?im)^([^\\r\\n]*(?:authorization|set-cookie|cookie):)[^\\r\\n]+"), "$1 [redacted]")
        result = result.replace(Regex("sk-(?:ant-|proj-)?[A-Za-z0-9_-]{20,}"), "[redacted API key]")
        result = result.replace(Regex("-----BEGIN (?:EC |RSA |OPENSSH )?PRIVATE KEY-----[\\s\\S]*?-----END (?:EC |RSA |OPENSSH )?PRIVATE KEY-----"), "[redacted private key]")
        return result.takeLast(8192)
    }
    fun json(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { out -> for (key in value.keys()) {
            val sensitive = Regex("(?i)^(?:key|token|password|secret|authorization|cookie|credentials|access_token|refresh_token|api[_-]?key|private[_-]?key|owner_signature|nonce)$").matches(key)
            out.put(text(key).take(100), if (sensitive) "[redacted]" else json(value.opt(key)))
        } }
        is JSONArray -> JSONArray((0 until value.length()).map { json(value.opt(it)) })
        is String -> text(value)
        else -> value
    }
}

/** Redacted, bounded, atomic diagnostic files. These records NEVER authorize a repair. */
class ConnectionIncidentJournal(private val directory: File, private val secrets: () -> Collection<String> = { emptyList() }) {
    fun record(service: String, host: String, stage: String, summary: String, rootCause: String,
        checks: List<ConnectionCheck>, nextSteps: List<ConnectionNextStep>, now: Long = System.currentTimeMillis()): JSONObject {
        synchronized(locks.computeIfAbsent(directory.canonicalPath) { Any() }) {
            directory.mkdirs()
            require(!Files.isSymbolicLink(directory.toPath())) { "Incident directory is not a plain directory" }
            java.nio.channels.FileChannel.open(File(directory, ".journal.lock").toPath(), java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { channel ->
                channel.lock().use { return recordLocked(service, host, stage, summary, rootCause, checks, nextSteps, now) }
            }
        }
    }
    private fun recordLocked(service: String, host: String, stage: String, summary: String, rootCause: String,
        checks: List<ConnectionCheck>, nextSteps: List<ConnectionNextStep>, now: Long): JSONObject {
        require(service.length <= 100 && host.length <= 200)
        directory.mkdirs()
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) { "Incident directory is not a plain directory" }
        directory.setReadable(false, false); directory.setWritable(false, false); directory.setExecutable(false, false)
        directory.setReadable(true, true); directory.setWritable(true, true); directory.setExecutable(true, true)
        val redactor = ConnectionRedactor(secrets())
        val base = JSONObject().put("schema", 1).put("service", service).put("host", host).put("stage", stage)
            .put("summary", redactor.text(summary).take(1024)).put("root_cause", redactor.text(rootCause).take(8192))
            .put("checks", JSONArray(checks.take(16).map { c -> JSONObject().put("check", redactor.text(c.name).take(100)).put("target", redactor.text(c.target).take(256))
                .put("code", c.code ?: JSONObject.NULL).put("result", redactor.text(c.result).take(512)).put("fields", JSONObject(c.fields.entries.take(4).associate { redactor.text(it.key).take(80) to redactor.text(it.value).takeLast(512) }))
                .put("stderr_tail", redactor.text(c.stderrTail).takeLast(2048)) }))
            .put("next_steps", JSONArray(nextSteps.take(16).map { step -> JSONObject().put("actor", step.actor.takeIf { it in setOf("agent", "owner") } ?: "owner")
                .put("action", redactor.text(step.action).take(128)).put("reason", redactor.text(step.reason).take(1024)) }))
        val safe = redactor.json(base) as JSONObject
        safe.put("checks_truncated", checks.size > 16)
        require(safe.toString().toByteArray().size <= 500_000) { "Diagnostic exceeds storage limit" }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(canonical(safe, MOVING).toByteArray()).joinToString("") { "%02x".format(it) }
        val indexFile = File(directory, "index.json")
        val index = runCatching { if (indexFile.length() in 1..65_536 && !Files.isSymbolicLink(indexFile.toPath())) JSONObject(indexFile.readText()) else JSONObject() }.getOrDefault(JSONObject())
        val previousId = index.optString(fingerprint).takeIf { ID.matches(it) }
        val previous = previousId?.let { id -> read(id) }
        val id = previous?.optString("id") ?: "$now-${UUID.randomUUID().toString().replace("-", "").take(16)}"
        safe.put("id", id).put("time", previous?.optLong("time") ?: now).put("last_seen", now)
            .put("occurrences", (previous?.optLong("occurrences") ?: 0) + 1)
        atomic(File(directory, "$id.json"), safe.toString(2))
        atomic(File(directory, "newest.json"), safe.toString(2))
        index.put(fingerprint, id)
        val records = directory.listFiles().orEmpty().filter { it.name.endsWith(".json") && ID.matches(it.name.removeSuffix(".json")) }.sortedByDescending { it.lastModified() }
        val retained = records.take(256).map { it.name.removeSuffix(".json") }.toSet()
        for (old in records.drop(256)) Files.deleteIfExists(old.toPath())
        val boundedIndex = JSONObject()
        for (key in index.keys()) if (index.optString(key) in retained) boundedIndex.put(key, index.optString(key))
        atomic(indexFile, boundedIndex.toString())
        return safe
    }
    @Synchronized fun read(id: String = "newest"): JSONObject? {
        require(id == "newest" || ID.matches(id)) { "Invalid incident id" }
        val file = File(directory, "$id.json")
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() > 524_288) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }
    private fun atomic(file: File, text: String) {
        val temp = File(directory, ".${UUID.randomUUID()}.new")
        try {
            FileOutputStream(temp).use { out ->
                temp.setReadable(false, false); temp.setWritable(false, false)
                temp.setReadable(true, true); temp.setWritable(true, true)
                out.write(text.toByteArray()); out.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        } finally { Files.deleteIfExists(temp.toPath()) }
    }
    private fun canonical(value: Any?, skip: Set<String> = emptySet()): String = when (value) {
        is JSONObject -> value.keys().asSequence().filter { it !in skip }.toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it), skip) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it), skip) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        /** Fields that move on every round (a stamp, an age, a free-space figure): the same incident hashes the same however they change. */
        private val MOVING = setOf("at", "time", "checked_at", "last_seen", "since", "seconds", "free_gb", "fetched_at", "last_received_at", "last_synced_at", "last_delivery_at", "sync_status_since")
        private val ID = Regex("[0-9]{1,16}-[0-9a-f]{16}") }
}
