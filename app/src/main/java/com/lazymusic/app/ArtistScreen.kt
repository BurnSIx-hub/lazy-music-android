package com.lazymusic.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** «228 тыс.», «1,2 млн» — как на ПК. */
private fun fansShort(n: Long): String = when {
    n >= 1_000_000 -> String.format(if (n >= 10_000_000) "%.0f млн" else "%.1f млн", n / 1e6).replace('.', ',')
    n >= 1_000 -> "${Math.round(n / 1e3)} тыс."
    else -> "$n"
}

/** «1 подписчик», «228 тыс. подписчиков». */
private fun fans(n: Long): String {
    if (n >= 1_000) return "${fansShort(n)} подписчиков"
    val form = when {
        n % 100 in 11..14 -> "подписчиков"
        n % 10 == 1L -> "подписчик"
        n % 10 in 2..4 -> "подписчика"
        else -> "подписчиков"
    }
    return "$n $form"
}

/** Его треки из библиотеки и истории — по ключу исполнителя, без повторов. */
private fun ownTracks(name: String): List<Track> {
    val key = Taste.artistKey(name)
    val seen = HashSet<String>()
    val out = mutableListOf<Track>()
    fun take(t: Track) {
        if (Taste.artistKey(t.artist) == key && seen.add(t.id)) out += t
    }
    Settings.playlistNames().forEach { list -> Settings.playlist(list).forEach(::take) }
    Settings.recentTracks().forEach(::take)
    return out
}

/**
 * Страница исполнителя — как на ПК: его треки из библиотеки, песни из
 * YouTube Music, фото, число подписчиков и похожие из Deezer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArtistScreen(
    name: String,
    revision: Int,
    cachedIds: Set<String>,
    currentId: String,
    onBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onRadio: (Track) -> Unit,
    onTrackMenu: (Track) -> Unit
) {
    var songs by remember(name) { mutableStateOf<List<Track>?>(null) }
    var error by remember(name) { mutableStateOf("") }
    var info by remember(name) { mutableStateOf<Deezer.ArtistInfo?>(null) }
    val own = remember(name, revision) { ownTracks(name) }

    BackHandler(onBack = onBack)

    LaunchedEffect(name) {
        songs = runCatching { Radio.artistSongs(name) }
            .onFailure { error = it.message.orEmpty() }
            .getOrDefault(emptyList())
    }
    LaunchedEffect(name) {
        info = withContext(Dispatchers.IO) { runCatching { Deezer.artistInfo(name) }.getOrNull() }
    }

    val list = songs.orEmpty()
    val best = list.ifEmpty { own }
    val shownName = info?.name?.takeIf { Taste.artistKey(it) == Taste.artistKey(name) } ?: name
    val facts = listOfNotNull(
        if (own.isNotEmpty()) "в вашей библиотеке ${trackCount(own.size)}"
        else "в вашей библиотеке пока нет треков этого исполнителя",
        info?.fans?.takeIf { it > 0 }?.let { "${fans(it)} на Deezer" }
    ).joinToString(" · ")

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            IconButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                val pic = info?.picture
                Box(
                    Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (pic.isNullOrBlank()) {
                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        AsyncImage(pic, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(112.dp).clip(CircleShape))
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Исполнитель", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(shownName, style = MaterialTheme.typography.headlineSmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Text(facts, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Переносом: при крупном шрифте вторая кнопка уходит на новую строку целиком
            FlowRow(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { onPlay(best, 0) }, enabled = best.isNotEmpty()) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Играть")
                }
                OutlinedButton(onClick = { best.firstOrNull()?.let(onRadio) }, enabled = best.isNotEmpty()) {
                    Icon(Icons.Default.Radio, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Радио исполнителя")
                }
            }
        }

        if (own.isNotEmpty()) {
            item { Heading("В вашей библиотеке") }
            itemsIndexed(own, key = { _, t -> "own-" + t.id }) { i, t ->
                TrackRow(t, t.id == currentId, t.id in cachedIds, onPlay = { onPlay(own, i) }, onMenu = { onTrackMenu(t) })
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Heading("Песни")
                if (songs == null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        when {
            songs == null -> item { Hint("Ищу в YouTube Music…") }
            list.isEmpty() -> item { Hint(if (error.isNotBlank()) "Не нашлось: $error" else "В YouTube Music не нашлось.") }
            else -> itemsIndexed(list, key = { _, t -> "song-" + t.id }) { i, t ->
                TrackRow(t, t.id == currentId, t.id in cachedIds, onPlay = { onPlay(list, i) }, onMenu = { onTrackMenu(t) })
            }
        }

        val related = info?.related.orEmpty()
        if (related.isNotEmpty()) {
            item {
                Heading("Похожие исполнители")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(related, key = { it.name }) { a ->
                        Column(
                            Modifier.width(96.dp).clickable { onOpenArtist(a.name) },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (a.picture.isBlank()) Icon(Icons.Default.Person, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                else AsyncImage(a.picture, contentDescription = null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(88.dp).clip(CircleShape))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(a.name, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun Heading(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 6.dp)
)

@Composable
private fun Hint(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
)
