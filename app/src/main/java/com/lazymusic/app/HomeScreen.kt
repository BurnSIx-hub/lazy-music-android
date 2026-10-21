package com.lazymusic.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Начальный экран: с чего продолжить.
 *
 * Открывается первым намеренно. «Сейчас играет» при холодном запуске пусто
 * и выглядит сломанным — а человек, открывший плеер, чаще всего хочет
 * дослушать то, на чём остановился.
 */
@Composable
fun HomeScreen(
    recent: List<Track>,
    picks: List<Track>,
    picksBusy: Boolean,
    cachedIds: Set<String>,
    currentId: String,
    playing: Boolean,
    onPlayRecent: (Int) -> Unit,
    onPlayPick: (Int) -> Unit,
    onRefreshPicks: () -> Unit,
    onTrackMenu: (Track) -> Unit,
    onArtist: (String) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    hints: List<String>,
    ownHints: List<Find.Hit>,
    onOwnHint: (Find.Hit) -> Unit,
    onUseHint: (String) -> Unit,
    onSearch: () -> Unit,
    wrapped: Wrapped.Summary?,
    onWrapped: () -> Unit
) {
    // Строка поиска сверху — как на ПК: ищет на вкладке «Поиск»
    val search: @Composable () -> Unit = {
        HomeSearch(query, onQueryChange, hints, ownHints, onOwnHint, onUseHint, onSearch)
    }
    if (recent.isEmpty()) {
        Column {
            search()
            EmptyHint(
                tr("Вы ещё ничего не слушали.\nЗагляните в «Поиск» или откройте " +
                    "«Плейлисты» — там есть скачанное.")
            )
        }
        return
    }

    val last = recent.first()
    val lastIsCurrent = last.id == currentId

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            search()
            // «Итоги года» — в декабре и январе, над всем остальным
            if (wrapped != null) {
                Spacer(Modifier.height(4.dp))
                WrappedCard(wrapped, onWrapped)
            }
            Text(
                text = if (lastIsCurrent && playing) tr("Сейчас играет") else tr("Продолжить"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clickable { onPlayRecent(0) }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Artwork(last.artworkUrl, 96)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = last.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (last.artist.isNotBlank()) {
                            Text(
                                text = last.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { onArtist(last.artist) }
                            )
                        }
                        if (last.id in cachedIds) {
                            Text(
                                text = tr("скачан"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = { onPlayRecent(0) },
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            if (lastIsCurrent && playing) Icons.Default.Pause
                            else Icons.Default.PlayArrow,
                            contentDescription = if (lastIsCurrent && playing) tr("Пауза") else tr("Играть"),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            if (picks.isNotEmpty() || picksBusy) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 20.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tr("Может понравиться"),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (picksBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).padding(end = 2.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                    } else {
                        IconButton(onClick = onRefreshPicks) {
                            Icon(Icons.Default.Refresh, contentDescription = tr("Обновить подборку"))
                        }
                    }
                }
                // Лентой вбок, а не списком: подборка — витрина, и она не должна
                // отодвигать «Недавнее», за которым сюда и приходят
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(picks, key = { _, t -> t.id }) { index, track ->
                        PickCard(track, onArtist) { onPlayPick(index) }
                    }
                }
                if (picks.isEmpty()) {
                    Text(
                        text = tr("Подбираю по вашему вкусу…"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            if (recent.size > 1) {
                Text(
                    text = tr("Недавнее"),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
                )
            }
        }

        itemsIndexed(recent.drop(1), key = { _, t -> t.id }) { index, track ->
            TrackRow(
                track = track,
                isCurrent = track.id == currentId,
                cached = track.id in cachedIds,
                // drop(1) сдвинул нумерацию — возвращаем её обратно
                onPlay = { onPlayRecent(index + 1) },
                onMenu = { onTrackMenu(track) }
            )
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

/**
 * Строка поиска на «Главной». Подсказки — прямо под ней: строка вверху
 * экрана, и до них не дотягивается клавиатура.
 */
@Composable
private fun HomeSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    hints: List<String>,
    ownHints: List<Find.Hit>,
    onOwnHint: (Find.Hit) -> Unit,
    onUseHint: (String) -> Unit,
    onSearch: () -> Unit
) {
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(tr("Поиск")) },
            leadingIcon = {
                IconButton(onClick = { focus.clearFocus(); onSearch() }) {
                    Icon(Icons.Default.Search, contentDescription = tr("Найти"))
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onSearch() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
        )
        if (focused && (hints.isNotEmpty() || ownHints.isNotEmpty())) {
            Spacer(Modifier.height(4.dp))
            HintCard(
                own = ownHints,
                hints = hints,
                onOwn = { focus.clearFocus(); onOwnHint(it) },
                onHint = { focus.clearFocus(); onUseHint(it) }
            )
        }
    }
}

/** Карточка витрины: обложка, название, исполнитель. Нажатие — играть. */
@Composable
private fun PickCard(track: Track, onArtist: (String) -> Unit, onPlay: () -> Unit) {
    Column(
        modifier = Modifier.width(132.dp).clickable(onClick = onPlay)
    ) {
        Artwork(track.artworkUrl, 132)
        Spacer(Modifier.height(6.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (track.artist.isNotBlank()) {
            Text(
                text = track.artist,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onArtist(track.artist) }
            )
        }
    }
}
