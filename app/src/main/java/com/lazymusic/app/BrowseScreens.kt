package com.lazymusic.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ── Библиотека ───────────────────────────────────────────────────────────────

/**
 * Свои плейлисты. Открываются мгновенно и работают офлайн: треки хранятся
 * целиком в настройках, в API за ними ходить не нужно.
 *
 * @param revision меняется снаружи после правок — сигнал перечитать хранилище
 */
@Composable
fun LibraryScreen(
    revision: Int,
    cachedIds: Set<String>,
    cachedTracks: List<Track>,
    currentId: String,
    onPlay: (List<Track>, Int) -> Unit,
    onTrackMenu: (Track) -> Unit,
    onChanged: () -> Unit,
    onImportLikes: () -> Unit
) {
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var showCached by rememberSaveable { mutableStateOf(false) }
    var names by remember { mutableStateOf(emptyList<String>()) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(revision) { names = Settings.playlistNames() }

    // Внутри библиотеки «назад» возвращает к списку плейлистов. Обработчик
    // вложенный, поэтому срабатывает раньше того, что уводит на «Главную».
    BackHandler(enabled = showCached || opened != null) {
        showCached = false
        opened = null
    }

    if (showCached) {
        SimpleTrackList(
            title = "Скачано",
            subtitle = trackCount(cachedTracks.size) + ", играют без сети",
            tracks = cachedTracks,
            cachedIds = cachedIds,
            currentId = currentId,
            onBack = { showCached = false },
            onPlay = onPlay,
            onTrackMenu = onTrackMenu
        )
        return
    }

    val current = opened
    if (current != null) {
        // Своё состояние у каждого плейлиста: прокрутка и строка поиска
        // одного не переезжают в другой
        key(current) { PlaylistDetail(
            name = current,
            revision = revision,
            cachedIds = cachedIds,
            currentId = currentId,
            onBack = { opened = null },
            onPlay = onPlay,
            onTrackMenu = onTrackMenu,
            onChanged = onChanged,
            onImportLikes = onImportLikes
        ) }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Мои плейлисты",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, contentDescription = "Создать плейлист")
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            // Скачанное доступно всегда, в том числе без ключа API и без сети
            if (cachedTracks.isNotEmpty()) {
                item {
                    ListItem(
                        headlineContent = { Text("Скачано", fontWeight = FontWeight.Medium) },
                        supportingContent = {
                            Text(trackCount(cachedTracks.size) + ", играют без сети")
                        },
                        leadingContent = {
                            Icon(Icons.Default.DownloadDone, contentDescription = null)
                        },
                        trailingContent = {
                            IconButton(onClick = { onPlay(cachedTracks, 0) }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Играть")
                            }
                        },
                        modifier = Modifier.clickable { showCached = true }
                    )
                }
            }

            // Понравившееся стоит первым и есть всегда, даже пустое: это
            // место, куда кладёт 👍, и вход к списку из YouTube Music
            item {
                val count = Likes.ids.size
                ListItem(
                    headlineContent = { Text(Likes.LIST, fontWeight = FontWeight.Medium) },
                    supportingContent = {
                        Text(if (count == 0) "Здесь будут отображаться треки, которые вам понравились" else trackCount(count))
                    },
                    leadingContent = {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        IconButton(
                            onClick = { onPlay(Settings.playlist(Likes.LIST), 0) },
                            enabled = count > 0
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Играть")
                        }
                    },
                    modifier = Modifier.clickable { opened = Likes.LIST }
                )
            }

            val own = names.filter { it != Likes.LIST }
            if (own.isEmpty()) {
                item {
                    EmptyHint("Своих плейлистов пока нет.\nНажмите «плюс», а треки добавляйте из поиска.")
                }
            } else {
                items(own, key = { it }) { name ->
                    val tracks = Settings.playlist(name)
                    ListItem(
                        headlineContent = { Text(name, fontWeight = FontWeight.Medium) },
                        supportingContent = { Text(trackCount(tracks.size)) },
                        leadingContent = {
                            Icon(Icons.Default.QueueMusic, contentDescription = null)
                        },
                        trailingContent = {
                            IconButton(
                                onClick = { if (tracks.isNotEmpty()) onPlay(tracks, 0) },
                                enabled = tracks.isNotEmpty()
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Играть")
                            }
                        },
                        modifier = Modifier.clickable { opened = name }
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "Новый плейлист",
            confirm = "Создать",
            onDismiss = { creating = false },
            onConfirm = { name ->
                Settings.createPlaylist(name)
                creating = false
                onChanged()
            }
        )
    }
}

/** Список треков с шапкой и кнопкой «назад». Годится для любого готового набора. */
@Composable
private fun SimpleTrackList(
    title: String,
    subtitle: String,
    tracks: List<Track>,
    cachedIds: Set<String>,
    currentId: String,
    onBack: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onTrackMenu: (Track) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { if (tracks.isNotEmpty()) onPlay(tracks, 0) }) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Играть всё")
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                TrackRow(
                    track = track,
                    isCurrent = track.id == currentId,
                    cached = track.id in cachedIds,
                    onPlay = { onPlay(tracks, index) },
                    onMenu = { onTrackMenu(track) }
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

/** Содержимое одного плейлиста. */
@Composable
private fun PlaylistDetail(
    name: String,
    revision: Int,
    cachedIds: Set<String>,
    currentId: String,
    onBack: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onTrackMenu: (Track) -> Unit,
    onChanged: () -> Unit,
    onImportLikes: () -> Unit
) {
    var tracks by remember { mutableStateOf(emptyList<Track>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val liked = name == Likes.LIST
    // Поиск внутри плейлиста — по названию и исполнителю (см. [Find])
    var find by rememberSaveable { mutableStateOf("") }
    val shown = remember(tracks, find) {
        if (find.isBlank()) tracks.indices.toList()
        else tracks.indices.filter { Find.matches(tracks[it], find) }
    }
    val focus = LocalFocusManager.current

    // Понравившееся меняется и мимо этого экрана — 👍 в уведомлении,
    // при погашенном дисплее. Поэтому за ним следим ещё и по самим лайкам
    LaunchedEffect(name, revision, if (liked) Likes.ids else Unit) {
        tracks = Settings.playlist(name)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
            }
            Column(Modifier.weight(1f)) {
                MarqueeText(name, style = MaterialTheme.typography.titleLarge)
                // Скачан для игры без сети — сколько уже в кэше (Offline)
                val offline = Offline.isOn(name)
                val have = if (offline) tracks.count { it.id in cachedIds } else 0
                // В одну строку: «· ждёт Wi-Fi» переносилось и обрезалось шапкой (S23)
                MarqueeText(
                    if (!offline) trackCount(tracks.size)
                    else trackCount(tracks.size) + " · скачано $have" +
                        if (have < tracks.size && Offline.waitingWifi) " · для скачивания подключитесь к Wi-Fi" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { if (tracks.isNotEmpty()) onPlay(tracks, 0) }) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Играть всё")
            }
            // Скачать весь плейлист для игры без сети — как в Spotify
            val offline = Offline.isOn(name)
            IconButton(onClick = { Offline.toggle(name) }) {
                Icon(
                    if (offline) Icons.Filled.DownloadForOffline
                    else Icons.Outlined.DownloadForOffline,
                    contentDescription = if (offline) "Отменить скачивание" else "Скачать плейлист",
                    tint = if (offline) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
            }
            if (liked) {
                // Удалять понравившееся целиком нечем нарочно: список держит
                // на себе 👍 и завёлся бы заново первым же лайком.
                // Лишнее убирается по одному — тем же 👍
                IconButton(onClick = onImportLikes) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = "Забрать из YouTube Music"
                    )
                }
            } else {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Удалить плейлист")
                }
            }
        }

        // Строка поиска — там, где листать уже долго
        if (tracks.size > FIND_FROM) {
            OutlinedTextField(
                value = find,
                onValueChange = { find = it },
                placeholder = { Text("Поиск") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (find.isNotEmpty()) {
                        IconButton(onClick = { find = ""; focus.clearFocus() }) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть")
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

        if (tracks.isEmpty()) {
            EmptyHint(
                if (liked) "Здесь пусто.\nЛайкните трек или импортируйте с YouTube."
                else "Здесь пусто.\nДобавьте треки из поиска — кнопка «Ещё» у трека."
            )
        } else if (shown.isEmpty()) {
            EmptyHint("Ничего не нашлось.")
        } else {
            // Снимок списка — тот же, из которого посчитаны номера в shown.
            // Ключ, читавший сам tracks, падал у знакомой (2026-10-19): после
            // удаления трека подкачка строк между кадрами брала старые номера
            // и уже укороченный список — tracks[1178] из 1178
            val list = tracks
            LazyColumn(Modifier.fillMaxSize()) {
                // Найденное играет весь плейлист с этого места, а не одни
                // находки: после трека музыка идёт дальше по плейлисту
                items(shown, key = { list[it].id }) { index ->
                    val track = list[index]
                    TrackRow(
                        track = track,
                        isCurrent = track.id == currentId,
                        cached = track.id in cachedIds,
                        onPlay = { onPlay(list, index) },
                        onMenu = { onTrackMenu(track) }
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Вы уверены, что хотите удалить плейлист?") },
            text = { Text("«$name» и его список треков. Сами файлы в кэше останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    Settings.deletePlaylist(name)
                    confirmDelete = false
                    onChanged()
                    onBack()
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
            }
        )
    }
}

// ── Поиск ────────────────────────────────────────────────────────────────────

/**
 * Поиск и открытие чужого плейлиста по ссылке. Оба идут через ключ Data API:
 * поиск стоит 100 единиц квоты за запрос, страница плейлиста — одну.
 */
@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    hints: List<String>,
    ownHints: List<Find.Hit>,
    onOwnHint: (Find.Hit) -> Unit,
    onUseHint: (String) -> Unit,
    playlistUrl: String,
    onPlaylistUrlChange: (String) -> Unit,
    busy: Boolean,
    searching: Boolean,
    opening: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    status: String?,
    results: List<Track>,
    resultsTitle: String,
    cachedIds: Set<String>,
    currentId: String,
    hasKey: Boolean,
    onSearch: () -> Unit,
    onOpenPlaylist: () -> Unit,
    onPlay: (Int) -> Unit,
    onTrackMenu: (Track) -> Unit
) {
    val focus = LocalFocusManager.current
    // Подсказки живут, только пока строка в работе: ушли из неё — список
    // ни к чему, он лишь отодвигает находки
    var queryFocused by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text("Поиск") },
                    singleLine = true,
                    // Лупа на клавиатуре ищет так же, как кнопка рядом
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { focus.clearFocus(); onSearch() }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { queryFocused = it.isFocused }
                )
                Button(
                    onClick = { focus.clearFocus(); onSearch() },
                    enabled = !busy && hasKey && query.isNotBlank()
                ) {
                    // Кружок прямо в кнопке: отклик нужен там, куда смотрит палец
                    if (searching) ButtonSpinner() else {
                        Icon(Icons.Default.Search, contentDescription = "Найти")
                    }
                }
            }
            // Подсказки прямо под строкой, а не всплывающим меню: оно на
            // телефоне норовит закрыться от случайного касания.
            //
            // Высота ограничена нарочно. Клавиатура занимает низ экрана
            // (на S23 её край примерно на 1125 из 2340), и список, выросший
            // под неё, оказывается не просто некрасивым — нижние строки
            // просто не нажать, касание уходит клавиатуре. Проверено.
            if (queryFocused && (hints.isNotEmpty() || ownHints.isNotEmpty())) {
                Spacer(Modifier.height(4.dp))
                HintCard(
                    own = ownHints,
                    hints = hints,
                    onOwn = { focus.clearFocus(); onOwnHint(it) },
                    onHint = { focus.clearFocus(); onUseHint(it) }
                )
            }

            // Подпись над полем, а не в его рамке: там на S23 помещается
            // только «Ссылка на ролик или …», а в две строки она при наборе
            // наезжает на рамку
            Spacer(Modifier.height(18.dp))
            Text(
                "Ссылка на ролик или плейлист YouTube / Яндекс Музыки",
                // В одну строку на S23 не входит и мелким шрифтом — строки
                // выравниваются по длине, без одинокого «Музыки» внизу
                style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = playlistUrl,
                    onValueChange = onPlaylistUrlChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = { focus.clearFocus(); onOpenPlaylist() }
                    ),
                    modifier = Modifier.weight(1f)
                )
                // Ключ здесь не нужен: ссылку на ролик разбирает yt-dlp,
                // а не Data API. Плейлист без ключа скажет об этом сам
                Button(
                    onClick = { focus.clearFocus(); onOpenPlaylist() },
                    enabled = !busy && playlistUrl.isNotBlank()
                ) {
                    if (opening) ButtonSpinner() else Text("Открыть")
                }
            }
        }

        if (!hasKey) {
            Notice(
                "Без ключа API не работают поиск и плейлисты. " +
                    "Ссылка на ролик работает и без него — вставьте её в поле выше. " +
                    "Ключ задаётся в настройках, шестерёнка сверху.",
                MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Слот постоянной высоты: иначе при появлении полоски список под ней
        // подпрыгивает, и кажется, что нажатие промахнулось
        Box(Modifier.fillMaxWidth().height(3.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Notice(
            status,
            if (busy) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (results.isEmpty()) {
            EmptyHint("Здесь появятся находки.")
        } else {
            Text(
                resultsTitle,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(results, key = { _, t -> t.id }) { index, track ->
                    TrackRow(
                        track = track,
                        isCurrent = track.id == currentId,
                        cached = track.id in cachedIds,
                        onPlay = { onPlay(index) },
                        onMenu = { onTrackMenu(track) }
                    )
                }
                if (hasMore) {
                    item {
                        // Кнопкой, а не подгрузкой при прокрутке: страница
                        // стоит те же сто единиц квоты, что и новый поиск, —
                        // сотую долю суточной. Пусть тратится по нажатию,
                        // а не оттого, что список случайно долистали
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            OutlinedButton(onClick = onLoadMore, enabled = !busy) {
                                if (loadingMore) ButtonSpinner() else Text("Показать ещё 50")
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

// ── Общие окна ───────────────────────────────────────────────────────────────

/** Окно ввода имени — для создания плейлиста. */
@Composable
fun NameDialog(
    title: String,
    confirm: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Название") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(confirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

fun trackCount(n: Int): String {
    val form = when {
        n % 100 in 11..14 -> "треков"
        n % 10 == 1 -> "трек"
        n % 10 in 2..4 -> "трека"
        else -> "треков"
    }
    return "$n $form"
}

/** Со скольких треков у плейлиста появляется строка поиска. */
private const val FIND_FROM = 10
