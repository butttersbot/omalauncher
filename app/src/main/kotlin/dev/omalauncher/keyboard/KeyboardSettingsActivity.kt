package dev.omalauncher.keyboard

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Turning the Oma keyboard on and its few choices. Android asks the owner to switch a keyboard on
 * themselves (it warns that a keyboard sees what is typed), so this only opens those screens.
 */
class KeyboardSettingsActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(OmaKeyboardService.PREFS, Context.MODE_PRIVATE)
        val d = resources.displayMetrics.density
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((20 * d).toInt(), (40 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
            }

        fun text(
            value: String,
            size: Float = 16f,
        ) = TextView(this).apply {
            text = value
            textSize = size
            setPadding(0, (6 * d).toInt(), 0, (10 * d).toInt())
        }.also(column::addView)

        fun button(
            label: String,
            action: () -> Unit,
        ) = column.addView(Button(this).apply { text = label; setOnClickListener { action() } })

        fun check(
            label: String,
            key: String,
            default: Boolean,
        ) = column.addView(
            CheckBox(this).apply {
                text = label
                textSize = 16f
                isChecked = prefs.getBoolean(key, default)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
            },
        )

        text("Soma keyboard", 22f)
        text("Apple's layout in Soma's look, with the big mic. It learns where your thumbs land, but only writes that down for a review to suggest changes from; keys never move by themselves. Nothing is learned in password fields, and nothing you type is kept.")
        status = text("")
        button("1. Turn on Soma keyboard") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        button("2. Switch to it") { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        text("Hold 🙂 on the keyboard to switch back to another keyboard.", 14f)
        check("Mechanical keys (off: the look's own keys)", OmaKeyboardService.MECH, false)
        check("Aim fix: touches go where your thumb means", OmaKeyboardService.AIM, true)
        check("Buzz on each key", OmaKeyboardService.HAPTICS, true)
        check("Thock sound on each key", OmaKeyboardService.SOUND, false)
        text("Try it here:", 14f)
        column.addView(EditText(this).apply { hint = "Type something" })
        text("In every look:", 14f)
        for ((name, pack) in looks()) {
            text(name, 14f)
            column.addView(
                KeyboardView(this, Still).apply {
                    preview = true
                    mech = prefs.getBoolean(OmaKeyboardService.MECH, false)
                    look = keyboardLook(pack, assets)
                    val w = resources.displayMetrics.widthPixels / d - 40
                    layout = KeyGeometry.layout(w, Screen.of(w + 40, resources.displayMetrics.heightPixels / d), Layer.ABC, mech, false, false, "return")
                },
            )
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        status.text =
            when {
                !enabled() -> "Not on yet: step 1, then step 2."
                !chosen() -> "On, but another keyboard is in use: step 2."
                else -> "Soma keyboard is in use."
            }
    }

    /** Every look the app ships, Console first, each resolved as the launcher would draw it. */
    private fun looks(): List<Pair<String, dev.omalauncher.theme.pack.ThemePack>> {
        val ids = runCatching { assets.list("packs").orEmpty().toList() }.getOrDefault(emptyList())
        val packs =
            ids.sorted().mapNotNull { id ->
                dev.omalauncher.theme.pack.PackRegistry.load(id) { dev.omalauncher.theme.pack.AssetPackSource(assets, "packs/$it") }
                    ?.takeIf { it.error == null }?.pack
            }
        return (listOf(dev.omalauncher.theme.pack.ConsolePack.pack) + packs).map { it.name to it }
    }

    /** A keyboard that is only looked at. */
    private object Still : KeyboardView.Host {
        override fun hit(x: Float, y: Float): Key? = null

        override fun press(key: Key, x: Float, y: Float) {}

        override fun slide(steps: Int) {}

        override fun swipeDown() {}

        override fun longPressEmoji() {}

        override val haptics = false

        override fun thock(big: Boolean) {}
    }

    private fun me() = ComponentName(this, OmaKeyboardService::class.java)

    private fun enabled() = (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.any { it.component == me() }

    private fun chosen() = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.let(ComponentName::unflattenFromString) == me()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) onResume()
    }
}
