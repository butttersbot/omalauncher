package dev.omalauncher.theme

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Which look Soma is wearing, for agents on any machine (MG1's Claude, 2026-10-29: agents elsewhere designed in
 * Console because nothing said the phone wears Manga). Written to the guest's ~/.cache/soma/look.json whenever a look
 * is picked or the launcher starts; the Fold's herd reporter (tools/fold-development/oma-herd) passes it on as its
 * machine's `soma`, so `herd --json` answers from every machine, and the design skill reads it from there.
 */
object LookReport {
    const val FILE = "arch/root/.cache/soma/look.json"

    /** The report: the look's id (Console when the picked pack can't be drawn), its name, its pack.toml's digest and this build. */
    fun json(
        look: String,
        name: String,
        packToml: ByteArray?,
        build: Long,
        now: Long,
    ): JSONObject {
        val out = JSONObject().put("look", look).put("name", name).put("build", build).put("at", now / 1000)
        if (packToml != null) out.put("pack_digest", sha256(packToml).take(16))
        return out
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Writes [report] beside the guest's other caches, all at once; nothing when Linux isn't installed. */
    fun write(
        filesDir: File,
        report: JSONObject,
    ) {
        runCatching {
            val file = File(filesDir, FILE)
            if (!File(filesDir, "arch/root").isDirectory) return
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(report.toString())
            tmp.renameTo(file)
        }
    }
}
