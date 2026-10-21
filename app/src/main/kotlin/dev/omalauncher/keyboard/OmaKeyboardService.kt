package dev.omalauncher.keyboard

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import dev.omalauncher.App
import dev.omalauncher.keyboard.KeyboardView.Companion.blend
import dev.omalauncher.ui.ActivePack
import dev.omalauncher.ui.Speech
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The Oma keyboard (Boss, 2026-10-14): Apple's layout in the active look, mechanical or flat caps, the big
 * mic, return at the right with the mic between it and space, and the aim learned from his practice.
 * Edge swipes over the keys don't go back; a swipe down puts it away. It learns where he taps but only
 * writes that down for a review to suggest from ([KeyboardLearning]); it never moves keys on its own.
 * Nothing is learned in password fields or where the app asks for no learning, and the mic is off in them.
 */
class OmaKeyboardService : InputMethodService(), KeyboardView.Host, EmojiPicker.Host, ClipPanel.Host, GifPanel.Host {
    private lateinit var view: KeyboardView
    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var layer = Layer.ABC
    private var shift = false
    private var capsLock = false
    private var lastShift = 0L
    private var lastSpace = 0L
    private var secret = false
    private var screen = Screen.COVER
    private var aim: Aim? = null
    private val learning = HashMap<Screen, KeyboardLearning>()
    private var speech: Speech? = null
    private var picker: EmojiPicker? = null
    private var clipPanel: ClipPanel? = null
    private var gifPanel: GifPanel? = null

    /** The GIFs are up in the keys' place; [gifSearching]: their search, with the letters typing into it. */
    private var showingGifs = false
    private var gifSearching = false
    private val gifWords = StringBuilder()
    override var addingTag = false
        private set

    /** The clipboard panel is up in the keys' place. */
    private var showingClips = false

    /** The copy last typed from the strip's pill: it isn't offered again. */
    private var pastedFresh: String? = null
    private val history by lazy { ClipHistory.of(this) }
    private val clipboardManager by lazy { getSystemService(android.content.ClipboardManager::class.java) }
    private val clipChanged = android.content.ClipboardManager.OnPrimaryClipChangedListener { noteClip() }

    /** Searching the emoji: the letter keys type into the picker's search field, not the app's. */
    private var searching = false
    private val search = StringBuilder()

    /** The live words of the speech running now are in the field, as text still being written. */
    private var composing = false

    /** The live words last written into the field, as they stand there. */
    private var lastComposed = ""

    /** Ends the speech running now without writing anything more into the field. */
    private fun dropVoice() {
        val s = speech ?: return
        speech = null
        composing = false
        lastComposed = ""
        s.onText = null
        s.cancel()
        if (::view.isInitialized) view.listening = false
    }

    /**
     * The app gave the keyboard the field afresh (Oma's chat does on Send, clearing it). If the live words aren't
     * in it any more they were sent or cleared, and that speech is over: kept going, it wrote the whole message
     * back into the emptied field and went on listening (Boss, 2026-10-15: "I already sent this message, but it
     * stayed in my composer").
     */
    override fun onStartInput(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        super.onStartInput(attribute, restarting)
        if (speech != null && composing) {
            val before = currentInputConnection?.getTextBeforeCursor(lastComposed.length + 4, 0)?.toString()
            if (before == null || lastComposed.isEmpty() || !before.endsWith(lastComposed)) dropVoice()
        }
    }

    override val haptics get() = prefs.getBoolean(HAPTICS, true)

    override fun onCreate() {
        super.onCreate()
        // The look comes from the launcher's own state, which this process holds once the launcher has run.
        runCatching { (application as App).runtime }
        loadEmoji()
        runCatching { clipboardManager?.addPrimaryClipChangedListener(clipChanged) }
    }

    override fun onDestroy() {
        runCatching { clipboardManager?.removePrimaryClipChangedListener(clipChanged) }
        super.onDestroy()
    }

    // ---- The clipboard ----

    /**
     * Notes what is on the clipboard now in the history, as Android lets the keyboard in use read it. Not a copy
     * marked sensitive (password managers mark theirs), nor while the field the keyboard has is a password's.
     */
    private fun noteClip() {
        if (secret) return
        val clip = runCatching { clipboardManager?.primaryClip }.getOrNull() ?: return
        val description = clip.description
        if (description?.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) == true) return
        val text = runCatching { clip.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull() ?: return
        val at = description?.timestamp?.takeIf { it > 0 } ?: System.currentTimeMillis()
        if (history.add(text, at) && ::view.isInitialized) view.invalidate()
    }

    override fun clips() = history.all(System.currentTimeMillis())

    override val freshClip: String?
        get() {
            val c = history.all(System.currentTimeMillis()).firstOrNull() ?: return null
            return c.text.takeIf { System.currentTimeMillis() - c.at < ClipHistory.FRESH_MS && it != pastedFresh }
        }

    // ---- GIFs ----

    override val gifQuery get() = gifWords.toString()

    override val wide get() = screen != Screen.COVER

    override val customerId: String
        get() = prefs.getString(GIF_CUSTOMER, null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString(GIF_CUSTOMER, it).apply() }

    override fun gifTool() {
        if (showingGifs || gifSearching) {
            leaveGifs()
        } else {
            showingClips = false
            showingGifs = true
            gifPanel?.opened()
        }
        rebuild()
    }

    /** Out of the GIFs, back to the letters, their pictures let go. */
    private fun leaveGifs() {
        showingGifs = false
        gifSearching = false
        addingTag = false
        gifWords.clear()
        gifPanel?.closed()
    }

    override fun panelResized() {
        view.requestLayout()
        view.invalidate()
    }

    override fun startGifSearch(forTag: Boolean) {
        addingTag = forTag
        gifSearching = true
        showingGifs = false
        gifWords.clear()
        layer = Layer.ABC
        shift = false
        capsLock = false
        gifPanel?.queryChanged("")
        rebuild()
    }

    override fun endGifSearch() {
        // A new tag is added and shown; a search's words stay showing, now in the full panel.
        gifWords.toString().trim().takeIf { it.isNotEmpty() }?.let { if (addingTag) gifPanel?.addTag(it) else gifPanel?.showResults(it) }
        addingTag = false
        gifSearching = false
        gifWords.clear()
        showingGifs = true
        rebuild()
    }

    override fun clearGifQuery() {
        gifWords.clear()
        gifPanel?.queryChanged("")
        rebuild()
    }

    /** A key pressed while searching GIFs: it edits the search, never the app's field. */
    private fun gifSearchKey(key: Key) {
        when (val a = key.action) {
            is KeyAction.Type -> {
                gifWords.append(if (shift || capsLock) a.text.uppercase() else a.text)
                if (shift && !capsLock) shift = false
            }
            KeyAction.Space -> if (gifWords.isNotEmpty() && !gifWords.endsWith(" ")) gifWords.append(' ')
            KeyAction.Back -> if (gifWords.isNotEmpty()) gifWords.setLength(gifWords.length - 1)
            KeyAction.Shift -> {
                capsLock = false
                shift = !shift
            }
            KeyAction.Layer -> layer = if (layer == Layer.ABC) Layer.NUM else Layer.ABC
            is KeyAction.Go -> layer = if (a.to == Layer.EMOJI) Layer.ABC else a.to
            KeyAction.Emoji, KeyAction.Return -> {
                // Done: the panel again, showing what the search found.
                endGifSearch()
                return
            }
            KeyAction.Mic -> {}
        }
        gifPanel?.queryChanged(gifWords.toString())
        rebuild()
    }

    /**
     * Puts [item] in the app's message box, inline, as a GIF (Boss, 2026-10-21: "tapping on the gif should just
     * place it inline"); the app's own send sends it. The file is fetched (or read from the phone) into the cache and
     * handed over through Soma's file provider. An app that takes no pictures from a keyboard gets KLIPY's link typed
     * instead; for one of the phone's own GIFs there is nothing to type, and it says so.
     */
    override fun placeGif(item: GifItem) {
        val info = currentInputEditorInfo
        val takes = info != null && androidx.core.view.inputmethod.EditorInfoCompat.getContentMimeTypes(info).any { android.content.ClipDescription.compareMimeTypes("image/gif", it) }
        leaveGifs()
        autoShift()
        rebuild()
        if (!takes) {
            if (item.link.isNotEmpty()) currentInputConnection?.commitText(item.link, 1) else Toast.makeText(this, "This app doesn't take GIFs from the keyboard", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val file = runCatching { gifFile(item) }.getOrNull()
            android.os.Handler(mainLooper).post {
                val ic = currentInputConnection ?: return@post
                if (file == null) {
                    if (item.link.isNotEmpty()) ic.commitText(item.link, 1) else Toast.makeText(this, "Couldn't read that GIF", Toast.LENGTH_SHORT).show()
                    return@post
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
                val content = androidx.core.view.inputmethod.InputContentInfoCompat(uri, android.content.ClipDescription(item.title.ifEmpty { "GIF" }, arrayOf("image/gif")), item.link.takeIf { it.startsWith("http") }?.let(android.net.Uri::parse))
                val ok = androidx.core.view.inputmethod.InputConnectionCompat.commitContent(ic, currentInputEditorInfo ?: return@post, content, androidx.core.view.inputmethod.InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
                if (!ok && item.link.isNotEmpty()) ic.commitText(item.link, 1)
            }
        }.start()
    }

    /** The GIF itself, in the cache's gifs/sent: fetched from KLIPY, or copied from the phone's own. */
    private fun gifFile(item: GifItem): File {
        val dir = File(cacheDir, "gifs/sent").apply { mkdirs() }
        val f = File(dir, Integer.toHexString(item.id.hashCode()) + ".gif")
        if (f.length() > 0) return f
        val bytes =
            if (item.full.startsWith("content:")) {
                contentResolver.openInputStream(android.net.Uri.parse(item.full))!!.use { it.readBytes() }
            } else {
                val c = java.net.URL(item.full).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 5_000
                c.readTimeout = 15_000
                c.inputStream.use { it.readBytes() }
            }
        f.writeBytes(bytes)
        dir.listFiles()?.takeIf { it.size > 60 }?.sortedBy { it.lastModified() }?.take(20)?.forEach { it.delete() }
        return f
    }

    override fun clipboard(paste: Boolean) {
        if (paste) {
            freshClip?.let {
                pastedFresh = it
                currentInputConnection?.commitText(it, 1)
            }
        } else {
            showingClips = !showingClips
            if (showingClips) {
                if (showingGifs || gifSearching) leaveGifs()
                clipPanel?.opened()
            }
        }
        rebuild()
    }

    override fun pasteClip(text: String) {
        currentInputConnection?.commitText(text, 1) ?: return
        pastedFresh = text
        showingClips = false
        autoShift()
        rebuild()
    }

    override fun forgetClip(text: String) {
        history.remove(text)
        view.invalidate()
    }

    override fun clearClips() {
        history.clear()
        // The phone's clipboard too: what was copied last (a key, a password) doesn't linger there either.
        runCatching { clipboardManager?.clearPrimaryClip() }
        pastedFresh = null
        view.invalidate()
    }

    // ---- The emoji picker ----
    override val catalog get() = emojiCatalog

    /** Read once per process, off the main thread, keeping only what the phone's font draws whole. */
    private fun loadEmoji() {
        if (emojiCatalog != null) return
        Thread {
            val c =
                runCatching {
                    val paint = android.graphics.Paint()
                    EmojiCatalog.parse(assets.open("emoji/emoji.txt").bufferedReader().readText()).filter { paint.hasGlyph(it) }
                }.getOrNull() ?: return@Thread
            emojiCatalog = c
            android.os.Handler(mainLooper).post { if (::view.isInitialized) view.invalidate() }
        }.start()
    }

    override val usage: EmojiUsage by lazy { EmojiUsage.parse(prefs.getString(EMOJI_USAGE, null)) }

    override fun typeEmoji(emoji: String) {
        currentInputConnection?.commitText(emoji, 1) ?: return
        usage.used(emoji)
        prefs.edit().putString(EMOJI_USAGE, usage.toText()).apply()
    }

    override fun pickedTone(
        item: EmojiItem,
        form: String,
    ) {
        usage.pickTone(item, form)
        prefs.edit().putString(EMOJI_USAGE, usage.toText()).apply()
    }

    override fun deleteBack() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    override fun showLetters() {
        showingClips = false
        if (showingGifs || gifSearching) leaveGifs()
        layer = Layer.ABC
        autoShift()
        rebuild()
    }

    override val query get() = search.toString()

    override fun startSearch() {
        searching = true
        search.clear()
        layer = Layer.ABC
        shift = false
        capsLock = false
        rebuild()
    }

    override fun clearQuery() {
        search.clear()
        rebuild()
    }

    /** Out of the search, back to the picker. */
    override fun endSearch() {
        searching = false
        search.clear()
        layer = Layer.EMOJI
        picker?.opened()
        rebuild()
    }

    /** A key pressed while searching: it edits the search, never the app's field. */
    private fun searchKey(key: Key) {
        when (val a = key.action) {
            is KeyAction.Type -> {
                search.append(if (shift || capsLock) a.text.uppercase() else a.text)
                if (shift && !capsLock) shift = false
            }
            KeyAction.Space -> if (search.isNotEmpty() && !search.endsWith(" ")) search.append(' ')
            KeyAction.Back -> if (search.isNotEmpty()) search.setLength(search.length - 1)
            KeyAction.Shift -> {
                capsLock = false
                shift = !shift
            }
            KeyAction.Layer -> layer = if (layer == Layer.ABC) Layer.NUM else Layer.ABC
            is KeyAction.Go -> layer = if (a.to == Layer.EMOJI) Layer.ABC else a.to
            KeyAction.Emoji, KeyAction.Return -> {
                endSearch()
                return
            }
            KeyAction.Mic -> {}
        }
        rebuild()
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        view = KeyboardView(this, this)
        picker = EmojiPicker(view, this).also { view.emoji = it }
        clipPanel = ClipPanel(view, this).also { view.clips = it }
        gifPanel = GifPanel(view, this).also { view.gifs = it }
        return view
    }

    override fun onStartInputView(
        info: EditorInfo,
        restarting: Boolean,
    ) {
        super.onStartInputView(info, restarting)
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        secret =
            (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) ||
            (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        layer = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) Layer.NUM else Layer.ABC
        capsLock = false
        searching = false
        showingClips = false
        if (showingGifs || gifSearching) leaveGifs()
        search.clear()
        // A copy made while no field had the keyboard (the listener only hears while it is bound) is noted now.
        noteClip()
        autoShift()
        view.look = look()
        view.mech = prefs.getBoolean(MECH, false)
        // Android's own bar under the keys (its keyboard switcher and hide arrow) is put away; the keyboard draws its own.
        if (android.os.Build.VERSION.SDK_INT >= 36) runCatching { window.window?.insetsController?.hide(android.view.WindowInsets.Type.captionBar()) }
        view.recheckInsets()
        rebuild()
    }

    /**
     * The live words left the field while he was still speaking or the last reading ran: the app sent them (its
     * send button, or return) or cleared the field. Then that speech is over, and its final text must not land in
     * the empty field afterwards (Boss, 2026-10-15: "after I sent it, the message stayed in the composer").
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val s = speech ?: return
        if (composing && candidatesStart < 0) {
            speech = null
            composing = false
            s.onText = null
            s.cancel()
            view.listening = false
            autoShift()
            rebuild()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        speech?.cancel()
        speech = null
        view.listening = false
        learning.values.forEach { it.finish() }
        saveLearning()
        super.onFinishInputView(finishingInput)
    }

    /** Lays the keys out for the window's width, on the screen the window is on. */
    private fun rebuild() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels / dm.density
        val h = dm.heightPixels / dm.density
        val was = screen
        screen = Screen.of(w, h)
        if (was != screen || aim == null) aim = loadAim(screen)
        view.layout = KeyGeometry.layout(w, screen, layer, view.mech, shift || capsLock, capsLock, if (searching || gifSearching) "done" else returnLabel(), split = prefs.getBoolean(SPLIT, true))
        view.mode =
            when {
                showingClips -> KeyboardView.Mode.CLIPS
                gifSearching -> KeyboardView.Mode.GIF_SEARCH
                showingGifs -> KeyboardView.Mode.GIFS
                searching -> KeyboardView.Mode.SEARCH
                layer == Layer.EMOJI -> KeyboardView.Mode.EMOJI
                else -> KeyboardView.Mode.KEYS
            }
        view.invalidate()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::view.isInitialized) rebuild()
    }

    // Only the keys take touches; the bubble's strip above them lets touches through to the app.
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!::view.isInitialized || !view.isShown) return
        val top = view.headroomPx
        // The GIFs pulled up lie over the app, which keeps the room it had above the keys.
        val app = view.appTopPx
        outInsets.contentTopInsets = app
        outInsets.visibleTopInsets = app
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, top, view.width, view.height)
    }

    // ---- Keys ----
    override fun hit(
        x: Float,
        y: Float,
    ) = view.layout?.hit(x, y, if (layer == Layer.ABC && prefs.getBoolean(AIM, true)) aim else null)

    override fun press(
        key: Key,
        x: Float,
        y: Float,
    ) {
        if (searching) {
            searchKey(key)
            return
        }
        if (gifSearching) {
            gifSearchKey(key)
            return
        }
        val ic = currentInputConnection ?: return
        // Typing while spoken words are still being written in (listening, or the last reading not back yet): those
        // words are kept as they show and that speech ends, then the key types after them. Before, the key's text
        // replaced the words being written, and then nothing was left (Boss, 2026-10-15: "I hit space. It clears
        // the entire... everything").
        if (key.action != KeyAction.Mic) speech?.let { s ->
            if (composing) {
                ic.finishComposingText()
                speech = null
                composing = false
                s.onText = null
                s.cancel()
                view.listening = false
            }
        }
        val learn = if (secret || layer != Layer.ABC) null else learning.getOrPut(screen) { loadLearning(screen) }
        when (val a = key.action) {
            is KeyAction.Type -> {
                val upper = layer == Layer.ABC && (shift || capsLock)
                ic.commitText(if (upper) a.text.uppercase() else a.text, 1)
                if (key.letter != null) learn?.letter(key, x, y, view.layout!!) else learn?.other()
                if (shift && !capsLock) shift = false
                if (a.text == "'" && layer != Layer.ABC) layer = Layer.ABC
                if (a.text in setOf(".", "?", "!")) autoShift()
            }
            KeyAction.Space -> {
                learn?.other()
                val now = SystemClock.uptimeMillis()
                val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
                // Two spaces quickly after a word: a full stop and a space, as on the iPhone.
                if (now - lastSpace < 600 && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(". ", 1)
                    lastSpace = 0
                } else {
                    ic.commitText(" ", 1)
                    lastSpace = now
                }
                if (layer != Layer.ABC && layer != Layer.EMOJI) layer = Layer.ABC
                autoShift()
            }
            KeyAction.Back -> {
                learn?.back()
                val selected = ic.getSelectedText(0)
                if (!selected.isNullOrEmpty()) ic.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                autoShift()
            }
            KeyAction.Shift -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastShift < 350) {
                    capsLock = !capsLock
                    shift = false
                } else {
                    capsLock = false
                    shift = !shift
                }
                lastShift = now
            }
            KeyAction.Layer -> layer = if (layer == Layer.ABC) Layer.NUM else Layer.ABC
            is KeyAction.Go -> layer = a.to
            KeyAction.Emoji -> {
                layer = if (layer == Layer.EMOJI) Layer.ABC else Layer.EMOJI
                if (layer == Layer.EMOJI) picker?.opened()
            }
            KeyAction.Mic -> mic()
            KeyAction.Return -> {
                learn?.other()
                enter()
            }
        }
        rebuild()
    }

    override fun slide(steps: Int) {
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(code) }
    }

    override fun swipeDown() {
        requestHideSelf(0)
    }

    override fun longPressEmoji() {
        if (canSplit) toggleSplit() else (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override val canSplit get() = screen != Screen.COVER
    override val split get() = prefs.getBoolean(SPLIT, true)

    override fun toggleSplit() {
        prefs.edit().putBoolean(SPLIT, !split).apply()
        rebuild()
    }

    /** Shift on where a sentence starts, as the field asks. */
    private fun autoShift() {
        if (capsLock || searching) return
        val info = currentInputEditorInfo ?: return
        shift = layer == Layer.ABC && info.inputType != InputType.TYPE_NULL && (currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0) != 0
    }

    private fun action(): Int {
        val info = currentInputEditorInfo ?: return EditorInfo.IME_ACTION_NONE
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return EditorInfo.IME_ACTION_NONE
        return info.imeOptions and EditorInfo.IME_MASK_ACTION
    }

    private fun returnLabel() =
        when (action()) {
            EditorInfo.IME_ACTION_SEND -> "send"
            EditorInfo.IME_ACTION_GO -> "go"
            EditorInfo.IME_ACTION_SEARCH -> "search"
            EditorInfo.IME_ACTION_NEXT -> "next"
            EditorInfo.IME_ACTION_DONE -> "done"
            else -> "return"
        }

    private fun enter() {
        val a = action()
        if (a in setOf(EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_DONE)) {
            currentInputConnection?.performEditorAction(a)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        autoShift()
    }

    // ---- The mic: Oma's own speech for now (Android's recognizer); Whisper is the next step. ----
    private fun mic() {
        // A plain toggle: the second tap ends voice mode at once, pulse and all; the last, better reading of
        // what was said still lands in the field a moment later.
        speech?.let {
            it.stop()
            view.listening = false
            return
        }
        if (secret) {
            Toast.makeText(this, "The mic is off in password fields", Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Soma needs the microphone permission for voice typing", Toast.LENGTH_LONG).show()
            return
        }
        val s = Speech(this)
        if (!s.available) {
            Toast.makeText(this, "No speech recognition on this phone", Toast.LENGTH_SHORT).show()
            return
        }
        speech = s
        view.listening = true
        // The words go into the field as he speaks, underlined as text still being written, and are set once he stops.
        val before = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val gap = if (before.isNotEmpty() && !before.endsWith(" ") && !before.endsWith("\n")) " " else ""
        composing = false
        s.onText = { heard ->
            if (speech === s && heard.isNotBlank()) {
                lastComposed = gap + heard.trim()
                currentInputConnection?.setComposingText(lastComposed, 1)
                composing = true
            }
        }
        s.start(hold = true) { said ->
            if (speech !== s) return@start
            speech = null
            composing = false
            view.listening = false
            val ic = currentInputConnection ?: return@start
            val t = said.trim()
            if (t.isNotEmpty()) ic.commitText(gap + t, 1) else ic.commitText("", 1)
            autoShift()
            rebuild()
        }
    }

    // ---- Sound: a short low "thock", as in the practice page; off unless asked for. ----
    private val thockTrack: AudioTrack? by lazy { runCatching { makeThock() }.getOrNull() }

    override fun thock(big: Boolean) {
        if (!prefs.getBoolean(SOUND, false)) return
        val t = thockTrack ?: return
        runCatching {
            t.stop()
            t.reloadStaticData()
            t.play()
        }
    }

    private fun makeThock(): AudioTrack {
        val rate = 44100
        val n = rate * 9 / 100
        val pcm = ShortArray(n)
        var phase = 0.0
        val noise = java.util.Random(7)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val f = 170.0 * exp(-t / 0.06 * kotlin.math.ln(170.0 / 60.0))
            phase += 2 * PI * f / rate
            val tone = sin(phase) * 0.35 * exp(-t / 0.025)
            val click = if (t < 0.02) (noise.nextDouble() * 2 - 1) * 0.12 * (1 - t / 0.02) else 0.0
            pcm[i] = ((tone + click) * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
        val track =
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(n * 2)
                .build()
        track.write(pcm, 0, n)
        return track
    }

    // ---- The look ----
    private fun look(): KeyboardLook = keyboardLook(ActivePack.pack, assets)

    // ---- Learning, kept where the agents (and Boss's reviews) can read it: the guest's ~/.local/share/oma-kb. ----
    private val learnDir: File
        get() {
            val guest = File(filesDir, "arch/root")
            return if (guest.isDirectory) File(guest, ".local/share/oma-kb") else File(filesDir, "keyboard")
        }

    private fun screenName(s: Screen) = s.name.lowercase()

    /** A reviewed table Boss approved, else the one learned from his practice (cover only). */
    private fun loadAim(s: Screen): Aim? =
        runCatching { KeyboardLearning.parseAim(File(learnDir, "aim-${screenName(s)}.json").readText()) }.getOrNull()
            ?: if (s == Screen.COVER) KeyGeometry.COVER_AIM else null

    private fun loadLearning(s: Screen) =
        KeyboardLearning().apply {
            runCatching { load(File(learnDir, "learn-${screenName(s)}.json").readText()) }
        }

    private fun saveLearning() {
        for ((s, l) in learning) {
            if (!l.dirty) continue
            runCatching {
                learnDir.mkdirs()
                val f = File(learnDir, "learn-${screenName(s)}.json")
                val tmp = File(learnDir, "learn-${screenName(s)}.json.tmp")
                tmp.writeText(l.toJson())
                tmp.renameTo(f)
            }
        }
        // Read back next time, so what was saved isn't counted twice.
        learning.clear()
    }

    companion object {
        const val PREFS = "oma_keyboard"
        /** Mechanical caps instead of the look's own keys. A new key (2026-10-15): the look's keys are the default now. */
        const val MECH = "style_mech"

        /** The inner screen's keys split into two thumb halves ([KeyGeometry.layout]); on unless he turns it off. */
        const val SPLIT = "split_inner"
        const val AIM = "aim"
        const val SOUND = "sound"
        const val HAPTICS = "haptics"

        /** Frequently used emoji and the skin tones picked ([EmojiUsage]). */
        const val EMOJI_USAGE = "emoji_usage"
        const val GIF_CUSTOMER = "gif_customer"

        @Volatile private var emojiCatalog: EmojiCatalog? = null
    }
}
