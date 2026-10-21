package com.lazymusic.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay

/**
 * Что играет сейчас: обложка, перемотка, управление и остаток очереди.
 *
 * Позицию плеер не сообщает событиями, поэтому её приходится опрашивать —
 * но только пока музыка идёт, чтобы не будить процессор впустую.
 */
@UnstableApi
@Composable
fun NowPlayingScreen(
    host: PlayerHost,
    cachedIds: Set<String>,
    busy: Boolean,
    gathering: Boolean,
    widening: Boolean,
    status: String?,
    onRadio: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onRemoveDownload: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onLike: (Track) -> Unit,
    onArtist: (String) -> Unit
) {
    val track = host.current
    if (track == null) {
        EmptyHint(tr("Ничего не играет.\nНайдите трек в поиске или откройте свой плейлист."))
        return
    }

    LaunchedEffect(host.playing) {
        while (host.playing) {
            host.refreshPosition()
            delay(500)
        }
        host.refreshPosition()
    }

    // Пока тянем ползунок, показываем своё значение, а не то, что у плеера
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            // Обложка 240, а не 280: под названием теперь строка кнопок, и с
            // большой обложкой «Играть» на S23 уходила под нижнее меню
            Spacer(Modifier.height(8.dp))
            Artwork(track.artworkUrl, 240)
            Spacer(Modifier.height(20.dp))

            // Название по центру, сердце — справа от него. Отступы у текста
            // одинаковые с обеих сторон, иначе центр уедет влево от обложки
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 56.dp)) {
                    // Длинное название не режется, а проезжает бегущей строкой
                    MarqueeText(
                        text = track.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        center = true
                    )
                    if (track.artist.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        MarqueeText(
                            text = track.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            center = true,
                            modifier = Modifier.clickable { onArtist(track.artist) }
                        )
                    }
                }
                // 👎 слева, сердце справа — как в плеере на ПК
                DislikeButton(
                    track = track,
                    host = host,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)
                )
                LikeButton(
                    track = track,
                    onLike = onLike,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
                )
            }

            // ── Действия над треком ──────────────────────────────────────────
            Spacer(Modifier.height(16.dp))
            // Под названием и строкой, которая листается вбок, — как в YouTube
            // Music: кнопок
            // четыре, в ширину телефона они не входят. Последняя уходит за
            // край — видно, что дальше есть ещё. Отступы — внутри прокрутки,
            // чтобы крайние кнопки доезжали до того же края, что и всё остальное
            val context = LocalContext.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionPill(
                    text = when {
                        gathering -> tr("Собираю…")
                        widening -> tr("Ищу похожих…")
                        else -> tr("Радио")
                    },
                    enabled = !gathering && !widening,
                    onClick = { onRadio(track) }
                ) {
                    if (gathering || widening) PillSpinner() else Icon(Icons.Default.Radio, contentDescription = null)
                }

                val downloading = Downloads.progress[track.id]
                val cached = track.id in cachedIds
                ActionPill(
                    text = when {
                        downloading != null -> "${(downloading * 100).toInt()}%"
                        cached -> tr("Скачан")
                        else -> tr("Скачать")
                    },
                    onClick = {
                        when {
                            downloading != null -> Downloads.cancel(track.id)
                            cached -> onRemoveDownload(track)
                            else -> onDownload(track)
                        }
                    }
                ) {
                    when {
                        downloading != null -> PillSpinner(downloading.coerceAtLeast(0.02f))
                        cached -> Icon(
                            Icons.Default.DownloadDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        else -> Icon(Icons.Default.Download, contentDescription = null)
                    }
                }

                ActionPill(text = tr("В плейлист"), onClick = { onAddToPlaylist(track) }) {
                    Icon(Icons.Default.PlaylistAdd, contentDescription = null)
                }

                ActionPill(text = tr("Поделиться"), onClick = { shareTrack(context, track) }) {
                    Icon(Icons.Default.Share, contentDescription = null)
                }
            }

            Notice(host.failure)
            // Радио собирается секунд десять, а подбор похожих — и того
            // дольше. Без этой строки экран выглядит так, будто нажатие
            // не прошло, и человек жмёт снова
            Notice(status, MaterialTheme.colorScheme.primary)

            // ── Перемотка ────────────────────────────────────────────────────
            val total = host.duration.coerceAtLeast(0L)
            val shown = if (dragging) dragValue.toLong() else host.position
            Spacer(Modifier.height(12.dp))
            Slider(
                value = shown.toFloat(),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { dragging = false; host.seekTo(dragValue.toLong()) },
                valueRange = 0f..(if (total > 0) total.toFloat() else 1f),
                enabled = total > 0,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatTime(shown), style = MaterialTheme.typography.labelSmall)
                Text(
                    if (total > 0) formatTime(total) else "--:--",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            // ── Управление ───────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = host::toggleShuffle) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = tr("Вперемешку"),
                        tint = if (host.shuffle) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = host::previous) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = tr("Назад"),
                        modifier = Modifier.size(34.dp))
                }
                FilledIconButton(
                    onClick = host::toggle,
                    modifier = Modifier.size(64.dp).padding(horizontal = 4.dp)
                ) {
                    Icon(
                        if (host.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (host.playing) tr("Пауза") else tr("Играть"),
                        modifier = Modifier.size(32.dp)
                    )
                }
                IconButton(onClick = host::next) {
                    Icon(Icons.Default.SkipNext, contentDescription = tr("Вперёд"),
                        modifier = Modifier.size(34.dp))
                }
                IconButton(onClick = host::cycleRepeat) {
                    Icon(
                        if (host.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne
                        else Icons.Default.Repeat,
                        contentDescription = tr("Повтор"),
                        tint = if (host.repeatMode == Player.REPEAT_MODE_OFF)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (host.queue.size > 1) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = tr("Дальше в очереди"),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(4.dp))
            }
        }

        itemsIndexed(host.queue) { index, item ->
            TrackRow(
                track = item,
                isCurrent = item.id == track.id,
                cached = item.id in cachedIds,
                onPlay = { host.play(host.queue, index) },
                onMenu = { onAddToPlaylist(item) }
            )
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

/**
 * Кнопка-«таблетка» строки действий: значок и подпись на полупрозрачной
 * подложке, как в YouTube Music. Подложка — от цвета текста, а не своя:
 * ложится и на светлую тему, и на тёмную, и на цвета из обоев.
 */
@Composable
private fun ActionPill(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.height(40.dp).padding(start = 14.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Кружок вместо значка: идёт сбор радио или скачивание ([progress] — доля). */
@Composable
private fun PillSpinner(progress: Float? = null) {
    if (progress == null) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
    else CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
}
