package dev.omalauncher.messages.engine

import org.json.JSONObject

/** Additive request intent only. Image bytes and metadata are chosen by the authenticated Mac cache. */
internal fun linkPreviewSelection(text: String, hidden: Set<String>): JSONObject? {
    val links = Parts.links(text)
    if (links.isEmpty()) return null
    val visible = links.firstOrNull { it !in hidden && Parts.openable(it) !in hidden }
        ?: return JSONObject().put("disabled", true)
    val url = Parts.openable(visible)
    // A normalized bare domain need not literally occur in the body; retain its ordinary URL send.
    return if (url in text && url.toByteArray(Charsets.UTF_8).size <= 4096 && RichLink.read(JSONObject().put("url", url)) != null) JSONObject().put("url", url) else null
}
