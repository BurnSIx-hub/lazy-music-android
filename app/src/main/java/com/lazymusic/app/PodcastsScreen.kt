package com.lazymusic.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/** Со скольких выпусков у канала появляется строка поиска — как у плейлиста. */
private const val FIND_FROM = 10

fun episodeCount(n: Int): String = plural(n.toLong(), "выпуск", "выпуска", "выпусков", "episode", "episodes")

fun chapterCount(n: Int): String = plural(n.toLong(), "глава", "главы", "глав", "chapter", "chapters")

/**
 * Вкладка «Подкасты»: начатые выпуски, новые и каналы; по нажатию на
 * канал — его выпуски. Как на ПК (`podcasts.js`).
 */
@Composable
fun PodcastsScreen(
    currentId: String,
    playing: Boolean,
    onPlay: (List<Track>, Int) -> Unit,
    onToggle: () -> Unit,
    onNote: (String) -> Unit
) {
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = opened != null) { opened = null }

    val book = Podcasts.book(opened)
    if (book != null) {
        key(book.id) {
            BookDetail(book, currentId, playing, onPlay, onToggle, onBack = { opened = null })
        }
        return
    }
    val channel = opened?.let { id -> Podcasts.channel(id) }
    if (channel != null) {
        key(channel.id) {
            ChannelDetail(channel, currentId, playing, onPlay, onToggle, onBack = { opened = null }, onNote = onNote)
        }
        return
    }

    val busy = Podcasts.refreshing == Podcasts.ALL || Podcasts.refreshing == Podcasts.ADD
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // «Подкасты» уже в шапке — здесь, как у плейлистов, «Мои …»
            Text(tr("Мои подкасты"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (Podcasts.channels.isNotEmpty()) {
                IconButton(onClick = {
                    scope.launch {
                        val added = Podcasts.refresh()
                        onNote(when {
                            added == null -> tr("Не все каналы обновились")
                            added > 0 -> tr("Новых выпусков: {N}", "N" to added)
                            else -> tr("Новых выпусков нет")
                        })
                    }
                }, enabled = !busy) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, contentDescription = tr("Проверить новые выпуски"))
                }
            }
            IconButton(onClick = { adding = true }, enabled = !busy) {
                Icon(Icons.Default.Link, contentDescription = tr("Открыть по ссылке"))
            }
        }

        val progress = Podcasts.progress
        // Выпуски по ссылке и главы книг — тоже в «Продолжить слушать»
        val shelf = Podcasts.books.map(Podcasts::shelfOf)
        val raw = Podcasts.channels.flatMap { ch -> ch.episodes.map { ch to it } } +
            shelf.flatMap { w -> w.episodes.map { w to it } } +
            Podcasts.singles.mapNotNull { s ->
                Podcasts.channelOf(s.e.id)?.takeIf { !Podcasts.isSubscribed(it.id) && !Podcasts.isBook(it) }
                    ?.let { ch -> ch.episodes.firstOrNull { it.id == s.e.id }?.let { ch to it } }
            }
        // Ролик может прийти дважды (книга и выпуск по ссылке) — ключи списка должны быть разными
        val all = raw.distinctBy { it.second.id }
        val hasStarted = all.any { (_, e) -> progress[e.id]?.let { !it.done && it.p > 0 } == true }
        if (Podcasts.channels.isEmpty() && Podcasts.books.isEmpty() && !hasStarted) {
            EmptyHint(tr("Здесь будут каналы YouTube, которые вы слушаете как подкасты, и аудиокниги.\nНажмите на значок ссылки и вставьте ссылку на канал, выпуск или книгу."))
        } else {
            val started = all.filter { (_, e) -> progress[e.id]?.let { !it.done && it.p > 0 } == true }
                .sortedByDescending { (_, e) -> progress[e.id]?.at ?: 0 }.take(8)
            val fresh = all.filter { (_, e) -> Podcasts.isNew(e) }.sortedByDescending { (_, e) -> e.added }.take(20)
            LazyColumn(Modifier.fillMaxSize()) {
                if (started.isNotEmpty()) {
                    item { Section(tr("Продолжить слушать")) }
                    items(started, key = { "s" + it.second.id }) { (ch, e) ->
                        EpisodeRow(ch, e, currentId, playing, showChannel = true, onToggle = onToggle) {
                            onPlay(Podcasts.tracks(ch), ch.episodes.indexOf(e))
                        }
                    }
                }
                if (fresh.isNotEmpty()) {
                    item { Section(tr("Новые выпуски")) }
                    items(fresh, key = { "n" + it.second.id }) { (ch, e) ->
                        EpisodeRow(ch, e, currentId, playing, showChannel = true, onToggle = onToggle) {
                            onPlay(Podcasts.tracks(ch), ch.episodes.indexOf(e))
                        }
                    }
                }
                if (Podcasts.books.isNotEmpty()) {
                    item { Section(tr("Книги")) }
                    items(Podcasts.books, key = { "b" + it.id }) { b ->
                        val st = Podcasts.bookState(b)
                        ListItem(
                            headlineContent = { Text(b.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(bookInfo(b, st), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Cover(b.art, 96) },
                            modifier = Modifier.clickable { opened = b.id }
                        )
                    }
                }
                if (Podcasts.channels.isNotEmpty()) item { Section(tr("Каналы")) }
                items(Podcasts.channels, key = { "c" + it.id }) { ch ->
                    val n = ch.episodes.count(Podcasts::isNew)
                    ListItem(
                        headlineContent = { Text(ch.name, fontWeight = FontWeight.Medium) },
                        supportingContent = {
                            Text(episodeCount(ch.episodes.size) + if (n > 0) " · " + tr("новых: {N}", "N" to n) else "")
                        },
                        leadingContent = { Avatar(ch.avatar, 48) },
                        modifier = Modifier.clickable { opened = ch.id }
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    if (adding) {
        OpenLinkDialog(
            onDismiss = { adding = false },
            onChannel = { id -> adding = false; opened = id },
            onBook = { id -> adding = false; opened = id },
            onEpisode = { ch, i -> adding = false; onPlay(Podcasts.tracks(ch), i) }
        )
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun Avatar(url: String, size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (url.isBlank()) Icon(Icons.Default.Podcasts, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(size.dp).clip(CircleShape))
    }
}

/** Сколько осталось: «осталось 23 мин», «осталось 1 ч 5 мин». */
private fun left(sec: Int): String {
    val m = (sec / 60.0).let { kotlin.math.round(it).toInt() }.coerceAtLeast(1)
    return if (m < 60) tr("осталось {N} мин", "N" to m) else tr("осталось {ч} ч {м} мин", "ч" to m / 60, "м" to m % 60)
}

@Composable
private fun EpisodeRow(
    ch: Podcasts.Channel,
    e: Podcasts.Episode,
    currentId: String,
    playing: Boolean,
    showChannel: Boolean = false,
    onToggle: () -> Unit,
    onPlay: () -> Unit
) {
    val p = Podcasts.progress[e.id]
    val dur = p?.d?.takeIf { it > 0 } ?: e.dur
    val done = p?.done == true
    val current = e.id == currentId
    val accent = MaterialTheme.colorScheme.primary
    val info = listOfNotNull(
        ch.name.takeIf { showChannel && it != e.title },
        dur.takeIf { it > 0 }?.let { formatTime(it * 1000L) },
        when {
            done -> tr("прослушано")
            (p?.p ?: 0) > 0 -> left(dur - p!!.p)
            Podcasts.isNew(e) -> tr("новый")
            else -> null
        }
    ).joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (current) onToggle() else onPlay() }
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(Radio.thumbnailUrl(e.id), 48)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    current -> accent
                    done -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                info,
                style = MaterialTheme.typography.bodySmall,
                color = if (Podcasts.isNew(e) && !current) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!done && (p?.p ?: 0) > 0 && dur > 0) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (p!!.p.toFloat() / dur).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                )
            }
        }
        IconButton(onClick = { Podcasts.setDone(e.id, !done) }) {
            Icon(
                if (done) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                contentDescription = if (done) tr("Снять отметку «прослушано»") else tr("Отметить прослушанным"),
                tint = if (done) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun ChannelDetail(
    ch: Podcasts.Channel,
    currentId: String,
    playing: Boolean,
    onPlay: (List<Track>, Int) -> Unit,
    onToggle: () -> Unit,
    onBack: () -> Unit,
    onNote: (String) -> Unit
) {
    var find by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val eps = ch.episodes
    val shown = remember(eps, find) {
        if (find.isBlank()) eps.indices.toList()
        else eps.indices.filter { Find.matches(Track(eps[it].id, eps[it].title), find) }
    }
    val busy = Podcasts.refreshing == ch.id || Podcasts.refreshing == Podcasts.ALL
    val mine = Podcasts.isSubscribed(ch.id)
    if (!mine) LaunchedEffect(ch.id) { Podcasts.loadGuest(ch) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = tr("Назад")) }
            Avatar(ch.avatar, 40)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                MarqueeText(ch.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    episodeCount(eps.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            // Играть — с первого недослушанного: новые сверху, как на YouTube
            IconButton(onClick = {
                val first = eps.indexOfFirst { Podcasts.progress[it.id]?.done != true }.coerceAtLeast(0)
                if (eps.isNotEmpty()) onPlay(Podcasts.tracks(ch), first)
            }) { Icon(Icons.Default.PlayArrow, contentDescription = tr("Играть")) }
            if (mine) {
                IconButton(onClick = {
                    scope.launch {
                        val added = Podcasts.refresh(only = ch)
                        onNote(when {
                            added == null -> tr("Канал не обновился")
                            added > 0 -> tr("Новых выпусков: {N}", "N" to added)
                            else -> tr("Новых выпусков нет")
                        })
                    }
                }, enabled = !busy) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, contentDescription = tr("Проверить новые выпуски"))
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Default.Delete, contentDescription = tr("Отписаться"))
                }
            } else if (busy || Podcasts.guestLoading == ch.id) {
                CircularProgressIndicator(Modifier.padding(horizontal = 14.dp).size(20.dp), strokeWidth = 2.dp)
            } else {
                Button(onClick = {
                    scope.launch {
                        val why = Podcasts.subscribe(ch)
                        onNote(if (why == null) tr("Канал «{канал}» добавлен", "канал" to ch.name)
                            else tr("Не удалось добавить канал: {причина}", "причина" to why))
                    }
                }, contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.padding(start = 4.dp, end = 4.dp)) {
                    Text(tr("Подписаться"), maxLines = 1)
                }
            }
        }

        if (eps.size > FIND_FROM) {
            OutlinedTextField(
                value = find,
                onValueChange = { find = it },
                placeholder = { Text(tr("Поиск")) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (find.isNotEmpty()) {
                        IconButton(onClick = { find = ""; focus.clearFocus() }) {
                            Icon(Icons.Default.Close, contentDescription = tr("Закрыть"))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        when {
            eps.isEmpty() -> EmptyHint(tr("На канале пока нет выпусков."))
            shown.isEmpty() -> EmptyHint(tr("Ничего не нашлось."))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { eps[it].id }) { i ->
                    EpisodeRow(ch, eps[i], currentId, playing, onToggle = onToggle) { onPlay(Podcasts.tracks(ch), i) }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Отписаться от «{канал}»?", "канал" to ch.name)) },
            text = { Text(tr("Канал пропадёт из подкастов на этом телефоне и на компьютере.")) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; Podcasts.remove(ch); onBack() }) { Text(tr("Отписаться")) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Отмена")) } }
        )
    }
}

@Composable
private fun OpenLinkDialog(
    onDismiss: () -> Unit,
    onChannel: (String) -> Unit,
    onBook: (String) -> Unit,
    onEpisode: (Podcasts.Channel, Int) -> Unit
) {
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val busy = Podcasts.refreshing == Podcasts.ADD
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Открыть по ссылке")) },
        text = {
            Column {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it; error = null },
                    label = { Text(tr("Ссылка на канал, выпуск или книгу")) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Книга — плейлист, где глава отдельным роликом, или один длинный ролик."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            val go = { asBook: Boolean ->
                scope.launch {
                    when (val r = Podcasts.open(link, asBook)) {
                        is Podcasts.Opened.Page -> onChannel(r.channelId)
                        is Podcasts.Opened.BookPage -> onBook(r.bookId)
                        is Podcasts.Opened.Play -> onEpisode(r.channel, r.index)
                        is Podcasts.Opened.Failed -> error = tr("Не удалось открыть ссылку: {причина}", "причина" to r.why)
                    }
                }
                Unit
            }
            Row {
                TextButton(enabled = link.isNotBlank() && !busy, onClick = { go(true) }) { Text(tr("Добавить книгу")) }
                TextButton(enabled = link.isNotBlank() && !busy, onClick = { go(false) }) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(tr("Открыть"))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Отмена")) } }
    )
}

@Composable
private fun Cover(url: String, width: Int) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .width(width.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

/** «осталось 2 ч 30 мин», «прослушано» или общая длина. */
private fun bookInfo(b: Podcasts.Book, st: Podcasts.BookState): String = when {
    st.done -> tr("прослушано")
    st.started && st.total > 0 -> left(st.total - st.heard)
    st.total > 0 -> formatTime(st.total * 1000L)
    else -> chapterCount(b.chapters.size)
}

@Composable
private fun BookDetail(
    b: Podcasts.Book,
    currentId: String,
    playing: Boolean,
    onPlay: (List<Track>, Int) -> Unit,
    onToggle: () -> Unit,
    onBack: () -> Unit
) {
    var find by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val st = Podcasts.bookState(b)
    val w = remember(b) { Podcasts.shelfOf(b) }
    val shown = remember(b, find) {
        if (find.isBlank()) b.chapters.indices.toList()
        else b.chapters.indices.filter { Find.matches(Track(b.chapters[it].id, b.chapters[it].title), find) }
    }
    val play = { chapter: Int? -> Podcasts.bookPlay(b, chapter)?.let { (list, i) -> onPlay(list, i) }; Unit }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = tr("Назад")) }
            Cover(b.art, 72)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                MarqueeText(b.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(b.author.ifBlank { null }, chapterCount(b.chapters.size), bookInfo(b, st)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (st.started && !st.done && st.total > 0) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (st.heard.toFloat() / st.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                    )
                }
            }
            IconButton(onClick = { play(null) }) {
                Icon(Icons.Default.PlayArrow, contentDescription = if (st.started) tr("Продолжить") else tr("Играть"))
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, contentDescription = tr("Убрать книгу"))
            }
        }

        if (b.chapters.size > FIND_FROM) {
            OutlinedTextField(
                value = find,
                onValueChange = { find = it },
                placeholder = { Text(tr("Поиск")) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (find.isNotEmpty()) {
                        IconButton(onClick = { find = ""; focus.clearFocus() }) {
                            Icon(Icons.Default.Close, contentDescription = tr("Закрыть"))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        if (shown.isEmpty()) EmptyHint(tr("Ничего не нашлось."))
        else LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { "${b.chapters[it].id}:$it" }) { i ->
                if (b.isVideo) ChapterRow(b, i, st, currentId, playing, onToggle) { play(i) }
                else EpisodeRow(w, w.episodes[i], currentId, playing, onToggle = onToggle) { play(i) }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Убрать книгу «{книга}»?", "книга" to b.title)) },
            text = { Text(tr("Книга пропадёт на этом телефоне и на компьютере.")) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; Podcasts.removeBook(b); onBack() }) { Text(tr("Убрать")) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Отмена")) } }
        )
    }
}

/** Глава длинного ролика: номер, название, с какого места и сколько длится. */
@Composable
private fun ChapterRow(
    b: Podcasts.Book,
    i: Int,
    st: Podcasts.BookState,
    currentId: String,
    playing: Boolean,
    onToggle: () -> Unit,
    onPlay: () -> Unit
) {
    val c = b.chapters[i]
    val here = currentId == c.id
    val current = st.started && st.at == i
    val heard = !st.done && c.dur > 0 && st.heard >= c.start + c.dur
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (current && here) onToggle() else onPlay() }
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            (i + 1).toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (current) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                c.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    current -> accent
                    st.done || heard -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(formatTime(c.start * 1000L), c.dur.takeIf { it > 0 }?.let { formatTime(it * 1000L) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (current && here && playing) Icon(Icons.Default.PlayArrow, contentDescription = null, tint = accent)
    }
}
