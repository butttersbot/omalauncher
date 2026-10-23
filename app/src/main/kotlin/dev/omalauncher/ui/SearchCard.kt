package dev.omalauncher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.phone.HomeDoor
import dev.omalauncher.Runtime
import dev.omalauncher.oma.newChatTab
import dev.omalauncher.search.Calculator
import dev.omalauncher.search.FileIndex
import dev.omalauncher.search.FileIndexJob
import dev.omalauncher.search.FileQuery
import dev.omalauncher.search.MacFiles
import dev.omalauncher.search.People
import androidx.compose.ui.draw.clip
import dev.omalauncher.search.PhotoIndex
import dev.omalauncher.search.PhotoIndexJob
import dev.omalauncher.search.PhotoMac
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.asImageBitmap
import dev.omalauncher.search.Candidate
import dev.omalauncher.search.Kind
import dev.omalauncher.search.SearchMemory
import dev.omalauncher.search.SearchRank
import dev.omalauncher.widgets.CockpitModules
import dev.omalauncher.widgets.annotations.HomeAnnotations
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.launch

/**
 * Soma's search, pulled down from home (Boss, 2026-10-20, after typecraft's oma-cast: "a search bar that truly can
 * search anything... the fastest search we can make", and accurate). A see-through card over the page, the part of
 * home behind it blurred: Clear by default, Smoke if ~/.config/omalauncher/search.conf says `glass=smoke` (no
 * button for it, as he asked). One card on both screens, wider on the open Fold.
 *
 * Nothing is typed: his favorites and what he uses most. Typing: the best match on top, then apps, Soma, settings,
 * and what to do with the words otherwise (ask Soma, search the web). Ranking is Raycast's ([SearchRank]), and it
 * learns from what he picks. Long press: open, favorite, alias, hide.
 *
 * Nothing of it is held while it is closed: it is made when it opens and dropped when it closes.
 */
@Composable
internal fun SearchCard(
    runtime: Runtime,
    state: LauncherState,
    home: GraphicsLayer,
) {
    val context = LocalContext.current
    val memory = remember { SearchStore.memory(context) }
    var query by remember { mutableStateOf("") }
    // A search put away without opening anything is still one of his recent searches (the 135 check: two searches,
    // nothing opened, and none showed). Two letters or more, as he left it.
    val left = androidx.compose.runtime.rememberUpdatedState(query)
    DisposableEffect(Unit) {
        onDispose {
            val q = left.value.trim()
            if (q.length >= 2) {
                memory.searched(q, System.currentTimeMillis())
                SearchStore.save(context, memory)
            }
        }
    }
    var acting by remember { mutableStateOf<SearchItem?>(null) }
    var aliasFor by remember { mutableStateOf<SearchItem?>(null) }
    var version by remember { mutableStateOf(0) }
    val smoke = remember { SearchStore.glass(context) == "smoke" }
    val ink = if (smoke) Color(0xFFF6F6F4) else Pack.foreground
    val muted = if (smoke) Color(0xB3F6F6F4) else Pack.muted
    val tint = if (smoke) Color(0x6B101012) else Pack.paper.copy(alpha = 0.42f)
    val line = if (smoke) Color(0xCCF6F6F4) else Pack.foreground
    val torch = remember { Torch(context) }
    DisposableEffect(Unit) { onDispose { torch.close() } }

    fun close() {
        state.searchOpen = false
    }

    // Everything there is to find, made once as it opens; [version] remakes it when he changes something of it.
    val items = remember(version) { searchItems(runtime, state, context, torch) }
    val now = System.currentTimeMillis()
    val ranked =
        remember(query, items, version) {
            if (query.isBlank()) emptyList() else SearchRank.rank(items.map { it.c }, query, memory, now).mapNotNull { c -> items.firstOrNull { it.c.id == c.id } }
        }
    val answer = remember(query) { Calculator.answer(query) }

    // Files, by name and by their text, from the index kept in its own process; read on each query, off the main thread.
    var fileActing by remember { mutableStateOf<FileQuery.Hit?>(null) }
    val db = remember { arrayOfNulls<android.database.sqlite.SQLiteDatabase>(1) }
    DisposableEffect(Unit) { onDispose { synchronized(db) { db[0]?.close(); db[0] = null } } }
    LaunchedEffect(Unit) {
        FileIndexJob.schedule(context)
        PhotoIndexJob.schedule(context)
    }
    // Photos by what is in them: the search's words made into a vector (on the Mac Pro), compared with every photo's.
    // The photos' vectors are read once while the card is open, and dropped with it.
    val photoDb = remember { arrayOfNulls<android.database.sqlite.SQLiteDatabase>(1) }
    val photoVectors = remember { arrayOfNulls<PhotoIndex.Vectors>(1) }
    DisposableEffect(Unit) { onDispose { synchronized(photoDb) { photoDb[0]?.close(); photoDb[0] = null; photoVectors[0] = null } } }
    val photos by androidx.compose.runtime.produceState(emptyList<PhotoIndex.Hit>(), query) {
        val q = query.trim()
        if (q.length < 3) {
            value = emptyList()
            return@produceState
        }
        // A pause in the typing first: the Mac is asked once for what he means, not once a letter.
        kotlinx.coroutines.delay(PHOTO_PAUSE_MS)
        value =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // The words name an app, a command or a person found: photos only if they are clearly what he means.
                val named = PhotoIndex.names(ranked.take(3).map { it.c.title } + People.find(context, q).map { it.name }, q)
                synchronized(photoDb) {
                    val d = photoDb[0] ?: PhotoIndex.open(context, writable = false).also { photoDb[0] = it } ?: return@synchronized emptyList()
                    val words = PhotoIndex.byWords(d, q)
                    // The Mac reads the words; when it can't be reached, the phone's own copy of the model does.
                    val vector = PhotoMac.text(PhotoMac.base(context), q) ?: dev.omalauncher.search.PhotoTextService.vector(context, q)
                    val near =
                        if (vector == null) {
                            emptyList()
                        } else {
                            val all = photoVectors[0] ?: PhotoIndex.vectors(d).also { photoVectors[0] = it }
                            PhotoIndex.hits(d, PhotoIndex.shown(PhotoIndex.nearest(all, vector, PHOTOS_MAX), named))
                        }
                    val shown = near.map { it.uri }.toSet()
                    (near + words.filter { it.uri !in shown }).take(PHOTOS_MAX)
                }
            }
    }
    val files by androidx.compose.runtime.produceState(FileResults(), query) {
        val q = query.trim()
        value =
            if (q.isEmpty()) {
                FileResults()
            } else {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    synchronized(db) {
                        val d = db[0] ?: FileIndex.open(context, writable = false).also { db[0] = it }
                        if (d == null) {
                            FileResults(indexing = true)
                        } else {
                            fun byRank(hits: List<FileQuery.Hit>, q: String) = FileQuery.rankNames(hits, q, System.currentTimeMillis()) { memory.frecency("file:$it", System.currentTimeMillis()) }.take(RERANK_NAMES)
                            var names = byRank(FileIndex.byName(d, q), q)
                            val texts = FileIndex.byText(d, q).filter { t -> names.none { it.path == t.path } }
                            // No name has these words: a typo, read as the name word it was most likely meant to be.
                            var fixed: String? = null
                            if (names.isEmpty() && texts.isEmpty()) {
                                FileIndex.byNameMisspelt(d, q)?.let { (f, hits) ->
                                    fixed = f
                                    names = byRank(hits, f)
                                }
                            }
                            FileResults(names, texts, indexing = FileIndex.count(d) == 0L, fixed = fixed)
                        }
                    }
                }
            }
    }

    // When the typing rests, the Mac's model orders the files by what they mean, the text matches especially (the
    // index has them only newest first). Until it answers, or if it can't be reached, they stay as they were.
    val reordered by androidx.compose.runtime.produceState<FileResults?>(null, files) {
        value = null
        val q = query.trim()
        if (q.length < 3 || (files.names.size < 2 && files.texts.size < 2)) return@produceState
        kotlinx.coroutines.delay(RERANK_PAUSE_MS)
        value =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val root = FileIndex.storageRoot()
                val home = FileIndex.linuxHome(context)
                val all = files.names + files.texts
                val near = PhotoMac.rerank(PhotoMac.base(context), q, all.map { FileQuery.meaning(it, FileQuery.folder(it.path, root, home)) }) ?: return@withContext null
                val n = files.names.size
                FileResults(
                    FileQuery.blend(files.names, near.copyOfRange(0, n), lexical = 0.6, query = q),
                    FileQuery.blend(files.texts, near.copyOfRange(n, near.size), lexical = 0.3, query = q),
                    files.indexing,
                    files.fixed,
                )
            }
    }
    val shownFiles = reordered ?: files

    // Files on the Mac Pro, by its Spotlight, when the typing rests; a pick is brought to the phone and opened.
    val macFiles by androidx.compose.runtime.produceState(emptyList<MacFiles.Hit>(), query) {
        val q = query.trim()
        if (q.length < 3) {
            value = emptyList()
            return@produceState
        }
        kotlinx.coroutines.delay(RERANK_PAUSE_MS)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MacFiles.search(PhotoMac.base(context), q) }
    }
    // People, by Android's own contacts search, as each letter comes: it is indexed and quick.
    var contactsAsk by remember { mutableStateOf(SearchStore.askContacts(context)) }
    val people by androidx.compose.runtime.produceState(emptyList<People.Person>(), query, version) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { People.find(context, query) }
    }
    // What was copied lately, as the Soma keyboard kept it.
    val clipHits = remember(query) { dev.omalauncher.keyboard.ClipHistory.of(context).find(query, System.currentTimeMillis()) }
    var personActing by remember { mutableStateOf<People.Person?>(null) }

    fun reach(intent: Intent) {
        memory.searched(query, System.currentTimeMillis())
        SearchStore.save(context, memory)
        close()
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    var macActing by remember { mutableStateOf<MacFiles.Hit?>(null) }
    var bringing by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun bring(
        hit: MacFiles.Hit,
        share: Boolean,
    ) {
        if (bringing != null) return
        bringing = hit.path
        scope.launch {
            val dest = File(MacFiles.folder(FileIndex.storageRoot()), hit.name)
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MacFiles.bring(PhotoMac.base(context), hit, dest) }
            bringing = null
            if (ok) {
                memory.visit("mac:${hit.path}", query, System.currentTimeMillis())
                SearchStore.save(context, memory)
                close()
                handFile(context, dest.path, share)
            } else {
                android.widget.Toast.makeText(context, "Couldn't bring ${hit.name} from the Mac Pro", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openFile(hit: FileQuery.Hit) {
        memory.visit("file:${hit.path}", query, System.currentTimeMillis())
        SearchStore.save(context, memory)
        close()
        handFile(context, hit.path, share = false)
    }

    fun run(item: SearchItem) {
        memory.visit(item.c.id, query, System.currentTimeMillis())
        SearchStore.save(context, memory)
        if (item.toggle == null) close()
        item.run()
        if (item.toggle != null) version++
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    Box(Modifier.fillMaxSize()) {
        // The page under it dims a little; a tap there puts the search away.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { close() },
        )
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .imePadding()
                .padding(top = 12.dp, bottom = 12.dp),
        ) {
            val wide = LocalConfiguration.current.screenWidthDp >= 600
            val cardWidth = if (wide) minOf(maxWidth * 0.64f, 720.dp) else maxWidth - 24.dp
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .width(cardWidth)
                    .heightIn(max = maxHeight)
                    .frostedSlice(home, tint)
                    .border(2.dp, line)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
            ) {
                // The field.
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).height(46.dp).border(2.dp, line).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText("⌕", style = Pack.body(Pack.size.large, muted))
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) BasicText("Search anything", style = Pack.body(17.sp, muted))
                            BasicTextField(
                                query,
                                { query = it },
                                Modifier.fillMaxWidth().focusRequester(focus),
                                singleLine = true,
                                textStyle = Pack.body(17.sp, ink),
                                cursorBrush = SolidColor(Pack.red),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions =
                                    KeyboardActions(onGo = {
                                        ranked.firstOrNull()?.let(::run) ?: answer?.let { copyText(context, it.plain) }
                                    }),
                            )
                        }
                        if (query.isNotEmpty()) BasicText("✕", Modifier.clickable { query = "" }.padding(6.dp), style = Pack.body(Pack.size.body, muted))
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(line.copy(alpha = 0.5f)))
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).clipToBounds(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp)) {
                    if (query.isBlank()) {
                        val favorites = memory.favorites().mapNotNull { id -> items.firstOrNull { it.c.id == id } }
                        if (favorites.isNotEmpty()) {
                            item { Section("Favorites", muted) }
                            item {
                                LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                    items(favorites, key = { it.c.id }) { f -> Favorite(runtime, f, ink, onOpen = { run(f) }, onLong = { acting = f }) }
                                }
                            }
                        }
                        val suggestions = memory.mostUsed(now).filter { memory.favorite(it) == null && !memory.hidden(it) }.mapNotNull { id -> items.firstOrNull { it.c.id == id } }.take(SUGGESTIONS)
                        if (suggestions.isNotEmpty()) {
                            item { Section("Suggestions", muted) }
                            items(suggestions, key = { "s:" + it.c.id }) { SearchResult(runtime, it, ink, muted, line, { run(it) }, { acting = it }) }
                        }
                        val recent = memory.recent(now)
                        if (recent.isNotEmpty()) {
                            item {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) { Section("Recent searches", muted) }
                                    BasicText(
                                        "Clear",
                                        Modifier.clickable {
                                            memory.forgetRecent()
                                            SearchStore.save(context, memory)
                                            version++
                                        }.padding(horizontal = 14.dp, vertical = 6.dp),
                                        style = Pack.body(Pack.size.small, Pack.red),
                                    )
                                }
                            }
                            item {
                                LazyRow(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(recent, key = { "r:$it" }) { r ->
                                        BasicText(r, Modifier.border(1.dp, line.copy(alpha = 0.6f)).clickable { query = r }.padding(horizontal = 10.dp, vertical = 6.dp), style = Pack.body(Pack.size.small, ink), maxLines = 1)
                                    }
                                }
                            }
                        }
                        if (favorites.isEmpty() && suggestions.isEmpty() && recent.isEmpty()) {
                            item { BasicText("Type to search apps, Soma and settings. Long-press a result to make it a favorite.", Modifier.padding(16.dp), style = Pack.body(Pack.size.small, muted)) }
                        }
                    } else {
                        answer?.let { a ->
                            item {
                                Column(
                                    Modifier.fillMaxWidth().padding(12.dp, 10.dp, 12.dp, 2.dp).border(2.dp, line).clickable { copyText(context, a.plain) }.padding(12.dp),
                                ) {
                                    BasicText("$query =", style = Pack.body(Pack.size.small, muted))
                                    BasicText(a.shown, style = Pack.display(30.sp, ink))
                                    BasicText("${a.kind} · tap to copy", style = Pack.body(Pack.size.small, muted))
                                }
                            }
                        }
                        ranked.firstOrNull()?.let { top ->
                            item { Section("Top hit", muted) }
                            item(key = "top") { SearchResult(runtime, top, ink, muted, line, { run(top) }, { acting = top }) }
                        }
                        val rest = ranked.drop(1)
                        for ((title, kinds) in SECTIONS) {
                            val here = rest.filter { it.c.kind in kinds }.take(PER_SECTION)
                            if (here.isEmpty()) continue
                            item { Section(title, muted) }
                            items(here, key = { it.c.id }) { SearchResult(runtime, it, ink, muted, line, { run(it) }, { acting = it }) }
                        }
                        if (people.isNotEmpty()) {
                            item { Section("People", muted) }
                            items(people, key = { "p:" + it.id }) { p ->
                                PersonRow(p, ink, muted, line, onOpen = { reach(People.open(p)) }, onCall = { n -> reach(People.call(n)) }, onText = { n -> reach(People.text(n)) }, onLong = { personActing = p })
                            }
                        } else if (contactsAsk && query.trim().length >= 2 && !People.allowed(context)) {
                            item {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    BasicText(
                                        "Find people here too: tap to let Soma read your contacts.",
                                        Modifier.weight(1f).clickable {
                                            activityOf(context)?.requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), CONTACTS_REQUEST)
                                        }.padding(14.dp),
                                        style = Pack.body(Pack.size.small, ink),
                                    )
                                    BasicText(
                                        "Not now",
                                        Modifier.clickable {
                                            SearchStore.declineContacts(context)
                                            contactsAsk = false
                                        }.padding(14.dp),
                                        style = Pack.body(Pack.size.small, muted),
                                    )
                                }
                            }
                        }
                        if (clipHits.isNotEmpty()) {
                            item { Section("Clipboard", muted) }
                            items(clipHits, key = { "c:" + it.at }) { c ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        memory.searched(query, System.currentTimeMillis())
                                        SearchStore.save(context, memory)
                                        copyText(context, c.text)
                                        close()
                                    }.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Box(Modifier.size(36.dp).border(1.dp, line), contentAlignment = Alignment.Center) { BasicText("⧉", style = Pack.body(Pack.size.body, ink)) }
                                    Column(Modifier.weight(1f)) {
                                        BasicText(dev.omalauncher.keyboard.ClipHistory.oneLine(c.text), style = Pack.body(Pack.size.body, ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        BasicText("Copied " + android.text.format.DateUtils.getRelativeTimeSpanString(c.at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS) + " · tap to copy again", style = Pack.body(Pack.size.small, muted), maxLines = 1)
                                    }
                                }
                            }
                        }
                        if (photos.isNotEmpty()) {
                            item { Section("Photos & videos", muted) }
                            item(key = "photos") {
                                PhotoGrid(photos, line) {
                                    memory.searched(query, System.currentTimeMillis())
                                    SearchStore.save(context, memory)
                                    close()
                                }
                            }
                        }
                        if (shownFiles.names.isNotEmpty() || shownFiles.texts.isNotEmpty()) {
                            item { Section(shownFiles.fixed?.let { "Files · for “$it”" } ?: "Files", muted) }
                            items(shownFiles.names.take(FILES_SHOWN), key = { "fn:" + it.path }) { FileRow(it, ink, muted, line, { openFile(it) }, { fileActing = it }) }
                            items(shownFiles.texts.take(FILES_SHOWN), key = { "ft:" + it.path }) { FileRow(it, ink, muted, line, { openFile(it) }, { fileActing = it }) }
                        }
                        if (macFiles.isNotEmpty()) {
                            item { Section("On the Mac Pro", muted) }
                            items(macFiles, key = { "mac:" + it.path }) { m ->
                                val where = "Mac Pro · " + MacFiles.where(m.path) + if (bringing == m.path) " · bringing it over…" else ""
                                FileRow(FileQuery.Hit(m.path, m.name, m.modified, m.size), ink, muted, line, { bring(m, share = false) }, { macActing = m }, where = where)
                            }
                        }
                        if (!FileIndex.storageAllowed()) {
                            item {
                                BasicText(
                                    "Allow file search: Soma needs All files access to find files on the phone. Tap to allow.",
                                    Modifier.fillMaxWidth().clickable {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        }
                                    }.padding(14.dp),
                                    style = Pack.body(Pack.size.small, ink),
                                )
                            }
                        } else if (files.indexing) {
                            item { BasicText("Files are still being indexed…", Modifier.padding(14.dp), style = Pack.body(Pack.size.small, muted)) }
                        }
                        item { Section("Use “${query.trim()}” with…", muted) }
                        items(fallbacks(context, query.trim()), key = { it.c.id }) { f ->
                            SearchResult(runtime, f, ink, muted, line, {
                                memory.searched(query, System.currentTimeMillis())
                                SearchStore.save(context, memory)
                                close()
                                f.run()
                            }, null)
                        }
                    }
                }
            }
        }
        acting?.let { item ->
            ResultActions(
                item,
                favorite = memory.favorite(item.c.id) != null,
                ink = ink,
                line = line,
                tint = tint,
                home = home,
                onOpen = {
                    acting = null
                    run(item)
                },
                onFavorite = {
                    memory.toggleFavorite(item.c.id)
                    SearchStore.save(context, memory)
                    acting = null
                    version++
                },
                onAlias = {
                    acting = null
                    aliasFor = item
                },
                onHide = {
                    memory.hide(item.c.id)
                    SearchStore.save(context, memory)
                    acting = null
                    version++
                },
                onDismiss = { acting = null },
            )
        }
        fileActing?.let { hit ->
            ActionSheet(
                hit.name,
                listOf(
                    "Open" to { fileActing = null; openFile(hit) },
                    "Share" to { fileActing = null; handFile(context, hit.path, share = true) },
                    "Copy path" to { fileActing = null; copyText(context, hit.path) },
                ),
                ink,
                line,
                tint,
                home,
            ) { fileActing = null }
        }
        personActing?.let { p ->
            val n = p.number
            ActionSheet(
                p.name,
                listOfNotNull(
                    n?.let { "Call $it" to { personActing = null; reach(People.call(it)) } },
                    n?.let { "Text $it" to { personActing = null; reach(People.text(it)) } },
                    "Open in Contacts" to { personActing = null; reach(People.open(p)) },
                    n?.let { "Copy number" to { personActing = null; copyText(context, it) } },
                ),
                ink,
                line,
                tint,
                home,
            ) { personActing = null }
        }
        macActing?.let { m ->
            ActionSheet(
                m.name,
                listOf(
                    "Bring to the phone and open" to { macActing = null; bring(m, share = false) },
                    "Bring to the phone and share" to { macActing = null; bring(m, share = true) },
                    "Copy its path on the Mac" to { macActing = null; copyText(context, m.path) },
                ),
                ink,
                line,
                tint,
                home,
            ) { macActing = null }
        }
        aliasFor?.let { item ->
            AliasEditor(item, memory.alias(item.c.id).orEmpty(), ink, line, tint, home) { alias ->
                if (alias != null) {
                    memory.setAlias(item.c.id, alias)
                    SearchStore.save(context, memory)
                    version++
                }
                aliasFor = null
            }
        }
    }
}

/** How many suggestions show with nothing typed, as Raycast's; and how many of each kind under the top hit. */
private const val SUGGESTIONS = 6
private const val PER_SECTION = 5

/** How many files show by name, and how many more by what is in them. */
private const val FILES_SHOWN = 5

/** How many name matches the Mac orders when typing rests (the text matches are the index's 30 newest), and after how long a rest. */
private const val RERANK_NAMES = 10
private const val RERANK_PAUSE_MS = 400L

/** How long typing rests before photos are looked for, and how many show at most. */
private const val PHOTO_PAUSE_MS = 250L
private const val PHOTOS_MAX = 12

/** Photos found, as a grid of small copies: a tap opens one in the gallery. Each copy is read as its cell shows. */
@Composable
private fun PhotoGrid(
    photos: List<PhotoIndex.Hit>,
    line: Color,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val across = if (LocalConfiguration.current.screenWidthDp >= 600) 6 else 3
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in photos.chunked(across)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (p in row) {
                    val thumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, p.uri) {
                        value =
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching { context.contentResolver.loadThumbnail(Uri.parse(p.uri), android.util.Size(256, 256), null).asImageBitmap() }.getOrNull()
                            }
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .border(1.dp, line)
                            .clickable {
                                onOpen()
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(p.uri), p.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            },
                    ) {
                        thumb?.let { Image(it, p.name, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                        if (p.kind != "image") {
                            BasicText(
                                if (p.kind == "gif") "GIF" else "▶",
                                Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color(0xCC111113)).padding(horizontal = 4.dp),
                                style = Pack.mono(9.sp, Color.White),
                            )
                        }
                    }
                }
                repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** What the file index found for a query: by name, by text, and whether it is still being made. */
private class FileResults(
    val names: List<FileQuery.Hit> = emptyList(),
    val texts: List<FileQuery.Hit> = emptyList(),
    val indexing: Boolean = false,
    /** The query as the names were found by, when it was read as having a typo. */
    val fixed: String? = null,
)

/** Hands the file at [path] to the app that opens (or, with [share], shares) its kind, through Soma's file provider. */
private fun handFile(
    context: Context,
    path: String,
    share: Boolean,
) {
    val file = File(path)
    val uri = runCatching { androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file) }.getOrNull() ?: return
    val mime = mimeOf(file.name) ?: "*/*"
    val intent =
        if (share) {
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), file.name)
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** The activity under [context], for asking a permission from it. */
private fun activityOf(context: Context): android.app.Activity? {
    var c: Context? = context
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

private const val CONTACTS_REQUEST = 7301

/** Someone found: their photo or first letter, name and number, and Call and Text at the side. */
@Composable
private fun PersonRow(
    person: People.Person,
    ink: Color,
    muted: Color,
    line: Color,
    onOpen: () -> Unit,
    onCall: (String) -> Unit,
    onText: (String) -> Unit,
    onLong: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val photo by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, person.photo) {
        value =
            person.photo?.let { uri ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(Uri.parse(uri))?.use { android.graphics.BitmapFactory.decodeStream(it) }?.asImageBitmap() }.getOrNull()
                }
            }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLong()
            }).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).border(1.dp, line, androidx.compose.foundation.shape.CircleShape).clipToBounds(), contentAlignment = Alignment.Center) {
            val p = photo
            if (p != null) {
                Image(p, person.name, Modifier.fillMaxSize().then(Modifier.clip(androidx.compose.foundation.shape.CircleShape)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            } else {
                BasicText(person.name.take(1).uppercase(), style = Pack.body(Pack.size.body, ink))
            }
        }
        Column(Modifier.weight(1f)) {
            BasicText(person.name, style = Pack.body(Pack.size.body, ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            person.number?.let { BasicText(it, style = Pack.body(Pack.size.small, muted), maxLines = 1) }
        }
        person.number?.let { n ->
            BasicText("Call", Modifier.border(1.dp, line).clickable { onCall(n) }.padding(horizontal = 10.dp, vertical = 6.dp), style = Pack.body(Pack.size.small, ink))
            BasicText("Text", Modifier.border(1.dp, line).clickable { onText(n) }.padding(horizontal = 10.dp, vertical = 6.dp), style = Pack.body(Pack.size.small, ink))
        }
    }
}

/** A file found: its kind, name and folder with when it changed, and for a match inside it, the words around it. */
@Composable
private fun FileRow(
    hit: FileQuery.Hit,
    ink: Color,
    muted: Color,
    line: Color,
    onOpen: () -> Unit,
    onLong: () -> Unit,
    /** Where it is, said otherwise than by its folder on the phone. */
    where: String? = null,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLong()
            }).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val ext = hit.name.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "FILE" }
        Box(Modifier.size(36.dp).border(1.dp, line), contentAlignment = Alignment.Center) {
            BasicText(ext, style = Pack.mono(9.sp, ink))
        }
        Column(Modifier.weight(1f)) {
            BasicText(hit.name, style = Pack.body(Pack.size.body, ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val place = where ?: FileQuery.folder(hit.path, FileIndex.storageRoot(), FileIndex.linuxHome(context))
            val date = android.text.format.DateUtils.getRelativeTimeSpanString(hit.modified, System.currentTimeMillis(), android.text.format.DateUtils.DAY_IN_MILLIS)
            BasicText("$place · $date", style = Pack.body(Pack.size.small, muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            hit.snippet?.let { BasicText(marked(it, ink), style = Pack.body(Pack.size.small, muted), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** The index's snippet with its matched words set in the ink, bold, the rest as it was. */
private fun marked(
    snippet: String,
    ink: Color,
): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        var bold = false
        for (c in snippet.replace(Regex("\\s+"), " ")) {
            when (c) {
                FileQuery.MARK_OPEN -> bold = true
                FileQuery.MARK_CLOSE -> bold = false
                else ->
                    if (bold) {
                        withStyle(androidx.compose.ui.text.SpanStyle(color = ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) { append(c) }
                    } else {
                        append(c)
                    }
            }
        }
    }

/** A short list of what can be done with something, on the same glass as the card. */
@Composable
private fun ActionSheet(
    title: String,
    rows: List<Pair<String, () -> Unit>>,
    ink: Color,
    line: Color,
    tint: Color,
    home: GraphicsLayer,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .padding(12.dp)
                .fillMaxWidth()
                .frostedSlice(home, tint)
                .border(2.dp, line),
        ) {
            BasicText(title, Modifier.padding(14.dp), style = Pack.body(Pack.size.body, ink).copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.fillMaxWidth().height(1.dp).background(line.copy(alpha = 0.5f)))
            for ((label, action) in rows) {
                BasicText(label, Modifier.fillMaxWidth().clickable(onClick = action).padding(horizontal = 14.dp, vertical = 13.dp), style = Pack.body(Pack.size.body, ink))
            }
        }
    }
}

private val SECTIONS = listOf("Apps" to setOf(Kind.APP), "Soma" to setOf(Kind.COMMAND, Kind.LINK), "Settings" to setOf(Kind.SETTING))

/** One thing search can show: what it is to the ranker, how it looks, and what a tap does. */
internal class SearchItem(
    val c: Candidate,
    /** An app's own icon, or null for a glyph. */
    val hit: AppHit? = null,
    val glyph: String = "•",
    /** For a switch: whether it is on now. */
    val toggle: (() -> Boolean)? = null,
    val run: () -> Unit,
)

/** The blurred part of home behind this node, under [tint]: the card's glass. Drawn on the GPU from home's own layer. */
@Composable
private fun Modifier.frostedSlice(
    home: GraphicsLayer,
    tint: Color,
): Modifier {
    val blur = rememberGraphicsLayer()
    val radius = with(LocalDensity.current) { 22.dp.toPx() }
    var at by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { at = it.positionInRoot() }
        .drawBehind {
            if (home.size.width > 0) {
                blur.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                blur.record(size = home.size) { drawLayer(home) }
                clipRect { translate(-at.x, -at.y) { drawLayer(blur) } }
            }
            drawRect(tint)
        }
}

@Composable
private fun Section(
    title: String,
    muted: Color,
) {
    BasicText(title.uppercase(), Modifier.padding(start = 14.dp, top = 14.dp, bottom = 4.dp), style = Pack.mono(10.5.sp, muted))
}

@Composable
private fun ResultIcon(
    runtime: Runtime,
    item: SearchItem,
    ink: Color,
    line: Color,
    size: androidx.compose.ui.unit.Dp,
) {
    val icon = item.hit?.icons(runtime)?.first
    if (icon != null) {
        AppIcon(icon, item.c.title, Modifier.size(size), mono = false)
    } else {
        Box(Modifier.size(size).border(1.dp, line), contentAlignment = Alignment.Center) {
            BasicText(item.glyph, style = Pack.body((size.value * 0.5f).sp, ink))
        }
    }
}

@Composable
private fun SearchResult(
    runtime: Runtime,
    item: SearchItem,
    ink: Color,
    muted: Color,
    line: Color,
    onOpen: () -> Unit,
    onLong: (() -> Unit)?,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick =
                    onLong?.let {
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            it()
                        }
                    },
            ).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ResultIcon(runtime, item, ink, line, 36.dp)
        Column(Modifier.weight(1f)) {
            BasicText(item.c.title, style = Pack.body(Pack.size.body, ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.c.subtitle?.let { BasicText(it, style = Pack.body(Pack.size.small, muted), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        val on = item.toggle?.invoke()
        if (on != null) {
            Box(Modifier.size(40.dp, 22.dp).border(2.dp, if (on) Pack.red else line).background(if (on) Pack.red else Color.Transparent), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.padding(3.dp).size(14.dp).background(if (on) Color.White else ink))
            }
        } else {
            BasicText(
                when (item.c.kind) {
                    Kind.APP -> "App"
                    Kind.COMMAND -> "Soma"
                    Kind.SETTING -> "Setting"
                    Kind.LINK -> ""
                    Kind.FILE -> "File"
                    Kind.CONTACT -> "Contact"
                },
                style = Pack.body(Pack.size.small, muted),
            )
        }
    }
}

@Composable
private fun Favorite(
    runtime: Runtime,
    item: SearchItem,
    ink: Color,
    onOpen: () -> Unit,
    onLong: () -> Unit,
) {
    Column(
        Modifier.width(70.dp).combinedClickable(onClick = onOpen, onLongClick = onLong).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ResultIcon(runtime, item, ink, ink, 48.dp)
        Spacer(Modifier.height(4.dp))
        BasicText(item.c.title, style = Pack.body(Pack.size.small, ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Long press on a result: what can be done with it, on the same glass. */
@Composable
private fun ResultActions(
    item: SearchItem,
    favorite: Boolean,
    ink: Color,
    line: Color,
    tint: Color,
    home: GraphicsLayer,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onAlias: () -> Unit,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .imePadding()
                .padding(12.dp)
                .fillMaxWidth()
                .frostedSlice(home, tint)
                .border(2.dp, line),
        ) {
            BasicText(item.c.title, Modifier.padding(14.dp), style = Pack.body(Pack.size.body, ink).copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
            Box(Modifier.fillMaxWidth().height(1.dp).background(line.copy(alpha = 0.5f)))
            val rows =
                listOf(
                    "Open" to onOpen,
                    (if (favorite) "Remove from favorites" else "Add to favorites") to onFavorite,
                    "Set alias…" to onAlias,
                    "Hide from search" to onHide,
                )
            for ((label, action) in rows) {
                BasicText(
                    label,
                    Modifier.fillMaxWidth().clickable(onClick = action).padding(horizontal = 14.dp, vertical = 13.dp),
                    style = Pack.body(Pack.size.body, if (label.startsWith("Hide")) Pack.red else ink),
                )
            }
        }
    }
}

/** A short name that brings [item] up first: typed exactly, it goes to the top. */
@Composable
private fun AliasEditor(
    item: SearchItem,
    current: String,
    ink: Color,
    line: Color,
    tint: Color,
    home: GraphicsLayer,
    done: (String?) -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).clickable(remember { MutableInteractionSource() }, indication = null) { done(null) }) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(12.dp)
                .fillMaxWidth()
                .frostedSlice(home, tint)
                .border(2.dp, line)
                .padding(14.dp),
        ) {
            BasicText("Alias for ${item.c.title}", style = Pack.body(Pack.size.body, ink))
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                text,
                { text = it },
                Modifier.fillMaxWidth().border(2.dp, line).padding(10.dp).focusRequester(focus),
                singleLine = true,
                textStyle = Pack.body(17.sp, ink),
                cursorBrush = SolidColor(Pack.red),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { done(text) }),
            )
            Spacer(Modifier.height(6.dp))
            BasicText("Typed exactly, it comes first. Empty removes it.", style = Pack.body(Pack.size.small, ink.copy(alpha = 0.7f)))
        }
    }
}

private fun copyText(
    context: Context,
    text: String,
) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("result", text))
}

/** Where search keeps what it remembers, and reads the glass. One memory per process, read once. */
internal object SearchStore {
    @Volatile private var memory: SearchMemory? = null

    private fun file(context: Context) = File(context.filesDir, "search/memory.txt")

    fun memory(context: Context): SearchMemory =
        memory ?: synchronized(this) {
            memory ?: SearchMemory.parse(runCatching { file(context).readText() }.getOrNull()).also { memory = it }
        }

    fun save(
        context: Context,
        m: SearchMemory,
    ) {
        val text = m.toText()
        thread(name = "search-memory") {
            runCatching {
                val f = file(context)
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, "memory.txt.tmp")
                tmp.writeText(text)
                tmp.renameTo(f)
            }
        }
    }

    /** Whether to offer to read contacts: until he says Not now. */
    fun askContacts(context: Context) = !context.getSharedPreferences("search", Context.MODE_PRIVATE).getBoolean("contacts_declined", false)

    fun declineContacts(context: Context) = context.getSharedPreferences("search", Context.MODE_PRIVATE).edit().putBoolean("contacts_declined", true).apply()

    /** `glass=clear` or `glass=smoke`, from the guest's ~/.config/omalauncher/search.conf: set by an agent when he asks. */
    fun glass(context: Context): String =
        runCatching {
            File(context.filesDir, "arch/root/.config/omalauncher/search.conf").readLines().firstNotNullOfOrNull { l ->
                l.trim().takeIf { it.startsWith("glass") }?.substringAfter('=')?.trim()
            }
        }.getOrNull() ?: "clear"
}

/** The flashlight as a switch in the results. */
private class Torch(
    context: Context,
) {
    private val cameras = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val id = runCatching { cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }.getOrNull()
    var on = false
        private set
    private val callback =
        object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(
                cameraId: String,
                enabled: Boolean,
            ) {
                if (cameraId == id) on = enabled
            }
        }

    init {
        if (id != null) runCatching { cameras.registerTorchCallback(callback, null) }
    }

    val available get() = id != null

    fun toggle() {
        val c = id ?: return
        runCatching { cameras.setTorchMode(c, !on) }.onSuccess { on = !on }
    }

    fun close() = runCatching { cameras.unregisterTorchCallback(callback) }
}

/** Everything search can find on the phone: apps, Soma's own commands, and Android's settings. */
private fun searchItems(
    runtime: Runtime,
    state: LauncherState,
    context: Context,
    torch: Torch,
): List<SearchItem> {
    val out = ArrayList<SearchItem>()
    for (hit in allApps(runtime)) {
        val (subtitle, kind) =
            when (hit) {
                is AppHit.Linux -> "Linux app" to Kind.APP
                is AppHit.Owl -> "Soma app" to Kind.APP
                is AppHit.Shortcut -> (runtime.androidApps.apps.firstOrNull { it.component.packageName == hit.shortcut.ref.packageName }?.label ?: "Shortcut") to Kind.COMMAND
                is AppHit.Android -> null to Kind.APP
            }
        out +=
            SearchItem(Candidate(hit.key, hit.label, kind, subtitle, listOfNotNull(hit.alias)), hit = hit) {
                runtime.usage.record(hit.key)
                when (hit) {
                    is AppHit.Linux -> runtime.launch(hit.app)
                    is AppHit.Android -> runtime.androidApps.open(hit.app)
                    is AppHit.Shortcut -> runtime.androidApps.open(hit.shortcut)
                    is AppHit.Owl -> runtime.openOwlApp(hit.spec.file.name)
                }
            }
    }
    // Soma's own.
    out += SearchItem(Candidate("soma:new-chat", "New chat", Kind.COMMAND, "Soma", listOf("agent", "ask", "claude", "grok")), glyph = "✦") {
        openOmaChat(context, thread = newChatTab(CockpitModules.host(context, runtime)))
    }
    out += SearchItem(Candidate("soma:chat", "Soma chat", Kind.COMMAND, "Soma", listOf("oma", "agent", "messages")), glyph = "✦") { openOmaChat(context) }
    out += SearchItem(Candidate("soma:overview", "All pages", Kind.COMMAND, "Soma · overview", listOf("overview", "pages")), glyph = "▦") { state.openOverview() }
    out += SearchItem(Candidate("soma:settings", "Soma settings", Kind.COMMAND, "Soma", listOf("preferences", "launcher")), glyph = "⚙") { state.settingsOpen = true }
    out += SearchItem(Candidate("soma:clear-ink", "Clear pen marks", Kind.COMMAND, "On this page", listOf("ink", "erase", "draw", "drawing")), glyph = "⌫") {
        HomeDoor.home?.showing?.let { HomeAnnotations.of(context).wipe(it) }
    }
    val notebook = runtime.widgets.notebook
    if (notebook.opened) {
        for (page in notebook.pages) {
            out += SearchItem(Candidate("page:${page.id}", page.name, Kind.COMMAND, "Home page", listOf("page")), glyph = "▤") { HomeDoor.ask(page.id) }
        }
    }
    val themes = runtime.themes
    for (id in themes.lookIds) {
        val name = id.split('-', '_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        out += SearchItem(Candidate("look:$id", name, Kind.COMMAND, if (id == themes.pickedPack) "Look · in use" else "Look", listOf("look", "theme", "style")), glyph = "◐") { themes.pickPack(id) }
    }
    // Android's settings: the screens he'd look for, by what they're called and what they do.
    fun setting(
        id: String,
        title: String,
        action: String,
        vararg keywords: String,
    ) {
        out += SearchItem(Candidate("set:$id", title, Kind.SETTING, "Settings", keywords.toList()), glyph = "⚙") {
            runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
    setting("wifi", "Wi-Fi", Settings.ACTION_WIFI_SETTINGS, "wifi", "internet", "network", "wlan")
    setting("internet", "Internet", Settings.Panel.ACTION_INTERNET_CONNECTIVITY, "wifi", "mobile data", "airplane", "connection")
    setting("bluetooth", "Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, "bt", "headphones", "buds", "pair")
    setting("display", "Display", Settings.ACTION_DISPLAY_SETTINGS, "brightness", "screen", "dark mode", "refresh rate", "font size")
    setting("sound", "Sounds and vibration", Settings.ACTION_SOUND_SETTINGS, "volume", "ringtone", "vibrate", "sound")
    setting("battery", "Battery", Intent.ACTION_POWER_USAGE_SUMMARY, "power", "charge", "charging", "battery saver")
    setting("dnd", "Do Not Disturb", "android.settings.ZEN_MODE_SETTINGS", "dnd", "silent", "focus", "quiet")
    setting("notifications", "Notifications", "android.settings.NOTIFICATION_SETTINGS", "alerts", "badges")
    setting("apps", "Apps", Settings.ACTION_APPLICATION_SETTINGS, "applications", "permissions", "default apps")
    setting("storage", "Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "space", "disk", "files")
    setting("location", "Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS, "gps")
    setting("security", "Security and privacy", Settings.ACTION_SECURITY_SETTINGS, "lock", "privacy", "permissions", "auto blocker")
    setting("accounts", "Accounts and backup", Settings.ACTION_SYNC_SETTINGS, "google", "sync", "backup")
    setting("keyboard", "Keyboard", Settings.ACTION_INPUT_METHOD_SETTINGS, "input", "language", "typing")
    setting("language", "Language", Settings.ACTION_LOCALE_SETTINGS, "locale", "region")
    setting("date", "Date and time", Settings.ACTION_DATE_SETTINGS, "clock", "time zone")
    setting("accessibility", "Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS, "a11y")
    setting("nfc", "NFC and payments", Settings.ACTION_NFC_SETTINGS, "tap to pay", "wallet")
    setting("hotspot", "Mobile hotspot", "android.settings.TETHER_SETTINGS", "tethering", "share internet")
    setting("data", "Data usage", Settings.ACTION_DATA_USAGE_SETTINGS, "mobile data", "cellular")
    setting("developer", "Developer options", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "developer", "adb", "usb debugging")
    setting("wireless-debugging", "Wireless debugging", "android.settings.ADB_WIFI_SETTINGS", "adb", "developer", "debug")
    setting("about", "About phone", Settings.ACTION_DEVICE_INFO_SETTINGS, "model", "software", "version", "imei")
    setting("update", "Software update", "android.settings.SYSTEM_UPDATE_SETTINGS", "update", "upgrade", "os")
    setting("wallpaper", "Wallpaper and style", Intent.ACTION_SET_WALLPAPER, "background", "lock screen")
    setting("all", "Settings", Settings.ACTION_SETTINGS, "preferences", "system")
    if (torch.available) {
        out += SearchItem(Candidate("set:torch", "Flashlight", Kind.SETTING, "Switch", listOf("torch", "light", "flash")), glyph = "✺", toggle = { torch.on }) { torch.toggle() }
    }
    return out
}

/** What to do with the words when nothing found is it: ask Soma, or search the web. */
private fun fallbacks(
    context: Context,
    query: String,
): List<SearchItem> {
    fun web(
        id: String,
        title: String,
        url: String,
        glyph: String,
    ) = SearchItem(Candidate("web:$id", title, Kind.LINK, query), glyph = glyph) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url + Uri.encode(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    return listOf(
        SearchItem(Candidate("ask:soma", "Ask Soma", Kind.LINK, query), glyph = "✦") { openOmaChat(context, query) },
        web("google", "Search Google", "https://www.google.com/search?q=", "G"),
        // Apps not installed yet: the Play Store's own search (Boss, 2026-10-20), or its web page if the store is missing.
        SearchItem(Candidate("web:play", "Search Play Store", Kind.LINK, query), glyph = "▷") {
            val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?c=apps&q=" + Uri.encode(query))).setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(store) }.onFailure {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?c=apps&q=" + Uri.encode(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        },
        web("youtube", "Search YouTube", "https://www.youtube.com/results?search_query=", "▶"),
        web("x", "Search X", "https://x.com/search?q=", "𝕏"),
    )
}
