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

    /**
     * Stage 3 of the speed plan, a label query: of the controls Android's own search by words handed back
     * ([AccessibilityNodeInfo.findAccessibilityNodeInfosByText], a contains match on text and description), the index
     * of the one whose whole text or description is [label]; null unless there is exactly one, so anything less
     * certain falls back to reading the whole screen and [press]'s own rules.
     */
    fun quick(candidates: List<SeenNode>, label: String): Int? {
        val asked = words(label)
        if (asked.isEmpty() || asked.length > MAX_LABEL) return null
        val whole = candidates.withIndex().filter { (_, n) -> n.enabled && (words(n.text) == asked || words(n.description) == asked) }
        return whole.singleOrNull()?.index
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

    /** [id] and every node it stands inside of, nearest first. */
    private fun lineage(id: String) = generateSequence(id) { if ('.' in it) it.substringBeforeLast('.') else null }

    /**
     * The text field for [label]: the one that says it as its hint or its contents, or that the words stand
     * inside of, since a hint is often a node of its own within the field (Compose draws its placeholder as a
     * child that takes no text). Words beside a field rather than in it name the one field inside the nearest
     * control around them that takes a press, as a hint drawn next to its box does; with two fields in there,
     * neither. No label at all is the screen's only text field, when it has just one.
     */
    fun field(nodes: List<SeenNode>, label: String): Found {
        val fields = nodes.filter { it.editable && it.enabled }
        if (words(label).isEmpty()) return one(fields.map { it.id })
        val ids = fields.map { it.id }
        val pressable = nodes.filter { it.clickable && it.enabled }.map { it.id }.toSet()
        fun around(node: SeenNode): String? {
            lineage(node.id).firstOrNull { it in ids }?.let { return it }
            // The window's root taking a press holds every field on the screen: it is no box around one.
            val box = lineage(node.id).firstOrNull { it in pressable && it != "root" } ?: return null
            return ids.singleOrNull { it.startsWith("$box.") }
        }
        return one(named(nodes, label).mapNotNull(::around).distinct())
    }

    /**
     * Whether the field at [id] now shows [text]. Android can answer yes to setting a field's text that the
     * app then never shows, so a fill is only done once the field is read back holding it.
     */
    fun holds(nodes: List<SeenNode>, id: String, text: String): Boolean =
        nodes.any { it.id == id && it.editable && it.text.trim() == text.trim() }
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
        fun pressed(id: String): String? {
            var at = id
            while (true) {
                if (at in pressable) return at
                if ('.' !in at) return null
                at = at.substringBeforeLast('.')
            }
        }
        val raw = read.optJSONArray("nodes") ?: JSONArray()
        val controls = JSONArray()
        // One button is often several nodes that say the same thing, its icon and its words: it is listed once.
        val listed = HashSet<Pair<String, String>>()
        for (i in 0 until raw.length()) {
            if (controls.length() >= MAX_CONTROLS) break
            val node = raw.getJSONObject(i)
            val says = node.optString("text").ifBlank { node.optString("description") }.trim()
            val editable = node.optBoolean("editable")
            val scrollable = node.optBoolean("scrollable")
            if (says.isEmpty() && !editable && !scrollable) continue
            val press = pressed(node.getString("id"))
            // An icon drawn as a letter of its own font says nothing to read: it is listed only if it does something.
            if (press == null && !editable && !scrollable && says.none { it.isLetterOrDigit() }) continue
            if (press != null && !editable && !scrollable && !listed.add(press to says.lowercase())) continue
            val can = listOfNotNull("press".takeIf { press != null }, "fill".takeIf { editable }, "scroll".takeIf { scrollable })
            val control = JSONObject().put("id", node.getString("id")).put("says", says).put("can", JSONArray(can))
            if (node.optBoolean("checked")) control.put("checked", true)
            if (!node.optBoolean("enabled", true)) control.put("enabled", false)
            controls.put(control)
        }
        return JSONObject().put("ok", true).put("snapshot", read.optString("snapshot")).put("package", front(read)).put("controls", controls)
    }

    /**
     * Stage 3 of the speed plan, deltas: [now] (a [brief]) told as what changed since [before] (the brief the reader
     * has): the controls that are new or differ, in full; the ids no longer there; how many stayed the same. Null
     * when [before] is not a brief of the same app, which the reader is then given whole.
     */
    fun delta(before: JSONObject, now: JSONObject): JSONObject? {
        if (before.optString("package") != now.optString("package")) return null
        fun byId(brief: JSONObject): Map<String, String> {
            val list = brief.optJSONArray("controls") ?: return emptyMap()
            return (0 until list.length()).map { list.getJSONObject(it) }.associate { it.getString("id") to it.toString() }
        }
        val old = byId(before)
        val list = now.optJSONArray("controls") ?: JSONArray()
        val changed = JSONArray()
        var same = 0
        val ids = HashSet<String>()
        for (i in 0 until list.length()) {
            val control = list.getJSONObject(i)
            val id = control.getString("id")
            ids += id
            if (old[id] == control.toString()) same++ else changed.put(control)
        }
        val gone = JSONArray(old.keys.filter { it !in ids })
        return JSONObject().put("ok", true).put("snapshot", now.optString("snapshot")).put("package", now.optString("package"))
            .put("since", before.optString("snapshot")).put("changed", changed).put("gone", gone).put("same", same)
    }

    /**
     * What a press or fill answers once it is done: the screen it led to, and for a fill ([text] not null), not ok
     * unless the field holds the text, with the snapshot to type into it by id.
     */
    fun after(read: JSONObject, command: String, id: String, text: String?): JSONObject {
        val answer = brief(read).put("did", command).put("on", id)
        if (text != null && !PhoneFind.holds(nodes(read), id, text)) {
            answer.put("ok", false).put("error", "The text did not land in the field $id. Read the screen, then type into the field with its snapshot and id, and check it shows.")
        }
        return answer
    }

    /**
     * What `press` and `fill` scroll to look for a label that is not on the screen: the biggest list that scrolls, which
     * is the page's own, not a row of chips inside it. Null when nothing scrolls.
     */
    fun scroller(read: JSONObject): String? {
        val list = read.optJSONArray("nodes") ?: return null
        return (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optBoolean("scrollable") }.maxByOrNull { node ->
            val box = node.optJSONArray("bounds")
            if (box == null || box.length() < 4) 0L else (box.getLong(2) - box.getLong(0)).coerceAtLeast(0) * (box.getLong(3) - box.getLong(1)).coerceAtLeast(0)
        }?.getString("id")
    }

    /** Whether any control on the screen says [text], in whole or in part. */
    fun says(read: JSONObject, text: String): Boolean {
        val asked = text.trim().lowercase()
        return asked.isNotEmpty() && nodes(read).any { asked in it.text.lowercase() || asked in it.description.lowercase() }
    }
}

/**
 * One control as it stood when the screen was read, by what tells it apart: where it is in the window's tree, its
 * app, view id, kind and words, and its bounds. Two of these are equal only if nothing about the control changed.
 */
data class SeenControl(
    val path: String,
    val packageName: String?,
    val viewId: String?,
    val className: String?,
    val text: String?,
    val description: String?,
    val bounds: List<Int>,
) {
    /** The same control, wherever it now stands: everything but its place in the tree and on the screen. */
    fun sameAs(other: SeenControl) =
        packageName == other.packageName && viewId == other.viewId && className == other.className && text == other.text && description == other.description

    /** It says nothing and has no view id, so where it stood in the tree is all that tells it from its neighbours. */
    val anonymous get() = text.isNullOrEmpty() && description.isNullOrEmpty() && viewId.isNullOrEmpty()
}

/**
 * Finding again, at the moment of acting, the control the agent picked from a read: while a chat streams, what is
 * below the new words moves between the read and the act, and that alone used to refuse the act. A control is
 * the same only by its app, view id, kind and words; one that says nothing is the same only at the same place in
 * the tree. Exactly one such control on the screen is the one; none, or two after a second one with the same
 * words appeared, is never guessed between, since acting on a different control is worse than asking for a read.
 */
object PhoneRefind {
    /** The path of the one control on [now] that is [old], or null when there is none or more than one. */
    fun refind(old: SeenControl, now: List<SeenControl>): String? =
        now.filter { it.sameAs(old) && (!old.anonymous || it.path == old.path) }.singleOrNull()?.path
}
