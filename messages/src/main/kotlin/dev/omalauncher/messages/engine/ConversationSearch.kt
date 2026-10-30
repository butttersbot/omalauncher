package dev.omalauncher.messages.engine

import org.json.JSONObject

data class SearchHit(val id: String, val at: Long, val sender: String, val snippet: String, val edited: Boolean)
data class SearchPage(val hits: List<SearchHit>, val nextCursor: String?, val complete: Boolean) {
    companion object {
        fun read(o: JSONObject): SearchPage {
            val hits = o.getJSONArray("hits")
            return SearchPage((0 until hits.length()).map { i ->
                val m = hits.getJSONObject(i)
                SearchHit(m.getString("id"), m.optLong("at"), if (m.optBoolean("fromMe")) "You" else
                    m.optString("senderName").takeUnless { it == "null" || it.isBlank() } ?: m.optString("sender"),
                    m.optString("snippet"), m.optBoolean("edited"))
            }, o.optString("nextCursor").takeUnless { o.isNull("nextCursor") || it.isBlank() }, o.optBoolean("complete"))
        }
    }
}
data class ContextWindow(val messages: List<Message>, val hasOlder: Boolean, val hasNewer: Boolean)

/** New parity routes keep stable server codes; the phone gives an actionable explanation. */
internal fun conversationProblem(message: String): String = when (message.substringBefore(';').trim()) {
    "message_action_pending" -> "Another change is still in progress. Wait for its update before trying again."
    "message_action_state_unavailable" -> "Your Mac can't verify earlier changes, so this message can't be changed right now."
    "message_unavailable" -> "This message is no longer available on your Mac."
    "reply_page_expired_or_wrong_conversation" -> "This replies page expired. Reload the thread to continue."
    "reply_history_cursor_unavailable" -> "Your Mac couldn't continue loading replies. Reload the thread to try again."
    "reply_chain_cycle", "reply_chain_too_deep", "reply_context_lookup_budget_exceeded" ->
        "This reply context couldn't be fully verified. Return to the conversation and try again."
    "search_page_expired_or_wrong_query" -> "This search page expired. Search again to continue."
    "search_cursor_unavailable" -> "Your Mac couldn't continue this search. Search again to try again."
    else -> message
}
