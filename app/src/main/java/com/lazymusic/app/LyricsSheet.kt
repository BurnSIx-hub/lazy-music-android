package com.lazymusic.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay

/**
 * Текст играющей песни — шторкой поверх «Сейчас», как вкладка «Текст» в
 * YouTube Music. Размеченный по времени идёт вслед за музыкой: звучащая
 * строка ярче, список сам подкручивается, нажатие на строку перематывает
 * к ней. Сменился трек — шторка ищет текст нового.
 */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun LyricsSheet(host: PlayerHost, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        val track = host.current
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 20.dp)) {
            Text(tr("Текст"), style = MaterialTheme.typography.titleLarge)
            if (track != null) {
                Text(
                    listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(12.dp))
            // Очередь кончилась и плеер опустел — показывать нечего
            if (track == null) {
                LaunchedEffect(Unit) { onDismiss() }
                return@Column
            }
            LyricsBody(host, track)
        }
    }
}

private sealed interface Look {
    data object Waiting : Look
    data object Missing : Look
    data class Ready(val found: Lyrics.Found) : Look
}

@UnstableApi
@Composable
private fun LyricsBody(host: PlayerHost, track: Track) {
    val context = LocalContext.current
    var look by remember(track.id) { mutableStateOf<Look>(Look.Waiting) }
    // Длина нужна, чтобы сверить разметку, — ждём, пока плеер её узнает
    val known = host.duration > 0
    LaunchedEffect(track.id, known) {
        if (!known) return@LaunchedEffect
        look = Look.Waiting
        look = runCatching { Lyrics.find(context, track, host.duration) }
            .onFailure { Diag.w("текст не нашёлся: ${Diag.chain(it)}") }
            .getOrNull()?.let { Look.Ready(it) } ?: Look.Missing
    }

    when (val l = look) {
        Look.Waiting -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(tr("Поиск текста…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Look.Missing -> Hint(tr("Текст не найден"))
        is Look.Ready -> when {
            l.found.instrumental -> Hint(tr("В этой песне нет слов"))
            l.found.synced -> Synced(host, l.found)
            else -> Plain(l.found)
        }
    }
}

/** Размеченный текст: звучащая строка ярче, список подкручивается сам. */
@UnstableApi
@Composable
private fun Synced(host: PlayerHost, found: Lyrics.Found) {
    // Положение плеера здесь нужно чаще, чем раз в полсекунды у «Сейчас»
    LaunchedEffect(host.playing) {
        while (host.playing) {
            host.refreshPosition()
            delay(200)
        }
        host.refreshPosition()
    }
    // Строку подсвечиваем чуть раньше: глаз читает с опережением
    val now = Lyrics.current(found.lines, host.position + 250)
    val list = rememberLazyListState()
    // Человек листает сам — три секунды не мешаем
    var touched by remember { mutableLongStateOf(0L) }
    LaunchedEffect(now) {
        if (now < 0 || System.currentTimeMillis() - touched < 3_000) return@LaunchedEffect
        list.animateScrollToItem((now - 2).coerceAtLeast(0))
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        touched = System.currentTimeMillis()
                    }
                }
            },
            contentPadding = PaddingValues(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            itemsIndexed(found.lines) { i, line ->
                val color = when {
                    i == now -> MaterialTheme.colorScheme.onSurface
                    i < now -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                }
                Text(
                    // Пустая строка в разметке — проигрыш: ставим ноту, чтобы было видно паузу
                    text = line.text.ifBlank { "♪" },
                    color = color,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = Fonts.display,
                    modifier = Modifier.fillMaxWidth().clickable { line.ms?.let(host::seekTo) }
                )
            }
            item { Source(found.source) }
        }
    }
}

@Composable
private fun Plain(found: Lyrics.Found) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            found.lines.joinToString("\n") { it.text },
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 27.sp)
        )
        // Чтобы не казалось, что подсветка сломалась
        Text(
            tr("Текст без разметки по времени"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Source(found.source)
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun Source(name: String) {
    Text(
        tr("Источник: {источник}", "источник" to name),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp)
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}
