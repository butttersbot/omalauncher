package dev.omalauncher.phone

import org.json.JSONArray
import org.json.JSONObject

/** One control as `read` lists it, with what finding it by its words needs. */
data class SeenNode(
    val id: String,
    val text: String,
    val description: String,
    val clickable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
)

/** What a search of the screen for a control came to. */
sealed interface Found {
    data class One(val id: String) : Found

    /** More than one control answers to it, at these ids: none is chosen. */
    data class Several(val ids: List<String>) : Found

    data object None : Found
}

/**
 * Finding a control by what it says, so an agent presses "Send" and not a pair of numbers read off a picture.
 * A whole label is preferred to a part of one, and two controls that answer equally well are never chosen
 * between: the agent is told both and decides. Kept apart from the service so it can be checked without a phone.
 */
object PhoneFind {
    /** Longer than this is not a label someone read off a screen. */
    const val MAX_LABEL = 200

    private fun words(value: String) = value.trim().lowercase()

    private fun named(nodes: List<SeenNode>, label: String): List<SeenNode> {
        val asked = words(label)
        if (asked.isEmpty() || asked.length > MAX_LABEL) return emptyList()
        val able = nodes.filter { it.enabled }
        val whole = able.filter { words(it.text) == asked || words(it.description) == asked }
        return whole.ifEmpty { able.filter { asked in words(it.text) || asked in words(it.description) } }
    }

    private fun one(ids: List<String>): Found =
        when (ids.size) {
            0 -> Found.None
            1 -> Found.One(ids[0])
            else -> Found.Several(ids)
        }

    /**
     * The control to press for [label]: the one that says it, or the nearest control around it that takes a
     * press, since the words of a button are often a node inside it. Words nothing around takes a press for
     * are pressed where they stand.
     */
    fun press(nodes: List<SeenNode>, label: String): Found {
        val byId = nodes.associateBy { it.id }
        fun target(node: SeenNode): String {
            var id = node.id
            while (true) {
                val at = byId[id]
                if (at != null && at.clickable && at.enabled) return id
                if ('.' !in id) return node.id
                id = id.substringBeforeLast('.')
            }
        }
        return one(named(nodes, label).map(::target).distinct())
    }

    /** The text field for [label]: one that says it, as its hint or its contents. */
    fun field(nodes: List<SeenNode>, label: String): Found = one(named(nodes.filter { it.editable }, label).map { it.id })
}

/** The screen as `read` gives it, cut down to what an agent acts on: far fewer words to read after every step. */
object PhoneScreen {
    /** More controls than this on one screen are not listed; `read` has them all. */
    const val MAX_CONTROLS = 150

    fun nodes(read: JSONObject): List<SeenNode> {
        val list = read.optJSONArray("nodes") ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it) }.map {
            SeenNode(it.getString("id"), it.optString("text"), it.optString("description"), it.optBoolean("clickable"), it.optBoolean("editable"), it.optBoolean("enabled", true))
        }
    }

    /** The app in front: the one the window's root belongs to. */
    fun front(read: JSONObject): String {
        val list = read.optJSONArray("nodes") ?: return ""
        return (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { !it.isNull("package") }?.optString("package").orEmpty()
    }

    /** Refuses when [expected] was asked for and another app is in front: nothing is pressed on a screen that wasn't meant. */
    fun requireFront(read: JSONObject, expected: String) {
        val front = front(read)
        check(expected.isEmpty() || expected == front) { "Expected $expected in front, but ${front.ifEmpty { "nothing readable" }} is. Nothing was done." }
    }

    /**
     * The snapshot, the app in front and the controls that say something or take text: what each says, and
     * whether it takes a press (it or something around it does), text or a scroll.
     */
    fun brief(read: JSONObject): JSONObject {
        val all = nodes(read)
        val pressable = all.filter { it.clickable && it.enabled }.map { it.id }.toSet()
        fun pressed(id: String): Boolean {
            var at = id
            while (true) {
                if (at in pressable) return true
                if ('.' !in at) return false
                at = at.substringBeforeLast('.')
            }
        }
        val raw = read.optJSONArray("nodes") ?: JSONArray()
        val controls = JSONArray()
        for (i in 0 until raw.length()) {
            if (controls.length() >= MAX_CONTROLS) break
            val node = raw.getJSONObject(i)
            val says = node.optString("text").ifBlank { node.optString("description") }.trim()
            val editable = node.optBoolean("editable")
            val scrollable = node.optBoolean("scrollable")
            if (says.isEmpty() && !editable && !scrollable) continue
            val can = listOfNotNull("press".takeIf { pressed(node.getString("id")) }, "fill".takeIf { editable }, "scroll".takeIf { scrollable })
            val control = JSONObject().put("id", node.getString("id")).put("says", says).put("can", JSONArray(can))
            if (node.optBoolean("checked")) control.put("checked", true)
            if (!node.optBoolean("enabled", true)) control.put("enabled", false)
            controls.put(control)
        }
        return JSONObject().put("ok", true).put("snapshot", read.optString("snapshot")).put("package", front(read)).put("controls", controls)
    }

    /** Whether any control on the screen says [text], in whole or in part. */
    fun says(read: JSONObject, text: String): Boolean {
        val asked = text.trim().lowercase()
        return asked.isNotEmpty() && nodes(read).any { asked in it.text.lowercase() || asked in it.description.lowercase() }
    }
}
