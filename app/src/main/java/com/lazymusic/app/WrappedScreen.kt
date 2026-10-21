package com.lazymusic.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.math.sqrt
import java.time.format.TextStyle as DateStyle

/**
 * «Итоги года» — экраны, которые листают, как сторис: жест вбок или
 * нажатие справа и слева. Каждый экран проявляется, когда на него
 * пришли: числа набегают, столбики растут. Данные — [Wrapped.Summary].
 */

private enum class Page { INTRO, MINUTES, TRACKS, ARTISTS, DAYS, TIME, MONTH, FINAL }

private fun pagesOf(s: Wrapped.Summary) = Page.entries.filter {
    when (it) {
        Page.TRACKS -> s.topTracks.isNotEmpty()
        Page.ARTISTS -> s.topArtists.isNotEmpty()
        Page.DAYS -> s.days > 0
        else -> true
    }
}

/** У каждого экрана свой цвет; у времени суток — под стать ему. */
private fun brushOf(page: Page, part: Wrapped.Part): Brush {
    val (a, b) = when (page) {
        Page.INTRO -> 0xFF2B0A3D to 0xFFE0262F
        Page.MINUTES -> 0xFFC81E2B to 0xFFFF8A3D
        Page.TRACKS -> 0xFF0B3540 to 0xFF14A098
        Page.ARTISTS -> 0xFF1B1F6B to 0xFF7B3FE4
        Page.DAYS -> 0xFF0B4F2E to 0xFF7CB342
        Page.TIME -> when (part) {
            Wrapped.Part.NIGHT -> 0xFF0B1026 to 0xFF4A2F9A
            Wrapped.Part.MORNING -> 0xFFFF8F3D to 0xFFFF5E7E
            Wrapped.Part.DAY -> 0xFF1E6FE0 to 0xFF5FC8FF
            Wrapped.Part.EVENING -> 0xFFE8472C to 0xFF6A1B9A
        }
        Page.MONTH -> 0xFF7A0B3A to 0xFFFF4D8D
        Page.FINAL -> 0xFF19191C to 0xFF4A0F16
    }
    return Brush.linearGradient(listOf(Color(a), Color(b)))
}

private val INTRO_BRUSH = Brush.linearGradient(listOf(Color(0xFF2B0A3D), Color(0xFFE0262F)))

@Composable
fun WrappedScreen(s: Wrapped.Summary, onPlay: (List<Track>) -> Unit, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val pages = remember(s) { pagesOf(s) }
        val pager = rememberPagerState { pages.size }
        val scope = rememberCoroutineScope()
        fun go(to: Int) {
            if (to in pages.indices) scope.launch { pager.animateScrollToPage(to) }
        }
        // Фото главного исполнителя — одно на два экрана
        var photo by remember(s) { mutableStateOf<String?>(null) }
        LaunchedEffect(s) {
            val name = s.topArtists.firstOrNull()?.name ?: return@LaunchedEffect
            photo = withContext(Dispatchers.IO) { runCatching { Deezer.artistInfo(name)?.picture }.getOrNull() }
        }

        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { i ->
                    val page = pages[i]
                    val active = pager.settledPage == i
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(brushOf(page, s.part))
                            .pointerInput(i) {
                                // Как у сторис: левая треть — назад, остальное — вперёд
                                detectTapGestures { o -> go(if (o.x < size.width / 3f) i - 1 else i + 1) }
                            }
                            .windowInsetsPadding(WindowInsets.systemBars)
                            .padding(start = 24.dp, end = 24.dp, top = 72.dp, bottom = 28.dp)
                    ) {
                        when (page) {
                            Page.INTRO -> Intro(s, active)
                            Page.MINUTES -> Minutes(s, active)
                            Page.TRACKS -> Tracks(s, active)
                            Page.ARTISTS -> Artists(s, photo, active)
                            Page.DAYS -> Days(s, active)
                            Page.TIME -> TimeOfDay(s, active)
                            Page.MONTH -> TopMonth(s, active)
                            Page.FINAL -> Final(s, photo, active) {
                                onPlay(s.topTracks.map { Track(it.id, it.title, it.artist, Radio.thumbnailUrl(it.id)) })
                            }
                        }
                    }
                }
                // Полоски, как у сторис, и крестик
                Column(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = 12.dp, end = 4.dp, top = 8.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        pages.indices.forEach { k ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White.copy(alpha = if (k <= pager.currentPage) 0.95f else 0.3f))
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(LazyEqIcon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Text(
                            "Lazy Music",
                            style = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                            modifier = Modifier.weight(1f).padding(start = 6.dp)
                        )
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = tr("Закрыть"))
                        }
                    }
                }
            }
        }
    }
}

/** Карточка на «Главной»: в сезон итогов, над «Продолжить». */
@Composable
fun WrappedCard(s: Wrapped.Summary, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(INTRO_BRUSH)
            .clickable(onClick = onOpen)
            .padding(start = 18.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                tr("Итоги {год}", "год" to s.year),
                style = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.Bold, fontSize = 24.sp),
                color = Color.White
            )
            Text(
                tr("Что и сколько вы слушали за год"),
                style = TextStyle(fontFamily = Fonts.text, fontSize = 14.sp),
                color = Color.White.copy(alpha = 0.85f)
            )
        }
        LazyBars(active = true, height = 44.dp)
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

// ── Экраны ───────────────────────────────────────────────────────────────────

@Composable
private fun Intro(s: Wrapped.Summary, active: Boolean) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.CenterStart)) {
            Text(tr("Итоги года"), style = label(), modifier = Modifier.rise(active, 100))
            Text("${s.year}", style = huge(112.sp), modifier = Modifier.rise(active, 250))
            Spacer(Modifier.height(8.dp))
            LazyBars(active, 72.dp, Modifier.rise(active, 450))
            Spacer(Modifier.height(24.dp))
            Text(tr("Посмотрим, что вы слушали"), style = title(), modifier = Modifier.rise(active, 650))
        }
        Text(
            tr("Нажмите справа или листайте"),
            style = body(),
            modifier = Modifier.align(Alignment.BottomCenter).rise(active, 1100).graphicsLayer { alpha *= 0.75f }
        )
    }
}

@Composable
private fun Minutes(s: Wrapped.Summary, active: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(tr("В этом году вы слушали музыку"), style = label(), modifier = Modifier.rise(active, 100))
        Text(number(counted(s.minutes, active)), style = huge(if (s.minutes >= 100_000) 76.sp else 92.sp), modifier = Modifier.rise(active, 200))
        Text(word(s.minutes, "минуту", "минуты", "минут", "minute", "minutes"), style = title(30.sp), modifier = Modifier.rise(active, 300))
        Spacer(Modifier.height(36.dp))
        Text(
            tr(
                "{треков} от {исполнителей}",
                "треков" to plural(s.trackCount.toLong(), "трека", "треков", "треков", "track", "tracks"),
                "исполнителей" to plural(s.artistCount.toLong(), "исполнителя", "исполнителей", "исполнителей", "artist", "artists")
            ),
            style = title(),
            modifier = Modifier.rise(active, 1300)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Подряд это {часов} ч", "часов" to number(s.minutes / 60)),
            style = body(),
            modifier = Modifier.rise(active, 1500)
        )
    }
}

@Composable
private fun Tracks(s: Wrapped.Summary, active: Boolean) {
    Column(Modifier.fillMaxSize()) {
        Text(tr("Ваши любимые треки"), style = headline(), modifier = Modifier.rise(active, 100))
        Spacer(Modifier.height(20.dp))
        val first = s.topTracks.first()
        Row(Modifier.rise(active, 250), verticalAlignment = Alignment.CenterVertically) {
            BigCover(first.id, 132.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("№1", style = label())
                Text(first.title, style = title(22.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (first.artist.isNotBlank()) {
                    Text(first.artist, style = body(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                Text(plays(first.plays), style = body(), modifier = Modifier.graphicsLayer { alpha = 0.8f })
            }
        }
        Spacer(Modifier.height(20.dp))
        s.topTracks.drop(1).take(4).forEachIndexed { k, t ->
            TrackLine(k + 2, t, Modifier.rise(active, 550 + k * 130))
        }
    }
}

@Composable
private fun Artists(s: Wrapped.Summary, photo: String?, active: Boolean) {
    val first = s.topArtists.first()
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(tr("Исполнитель года"), style = headline(), modifier = Modifier.rise(active, 100))
        Spacer(Modifier.height(24.dp))
        ArtistPhoto(photo, 196.dp, Modifier.rise(active, 250))
        Spacer(Modifier.height(16.dp))
        Text(
            first.name,
            style = huge(if (first.name.length > 14) 34.sp else 44.sp),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.rise(active, 400)
        )
        Text(
            tr("{минут} мин музыки", "минут" to number(first.minutes)),
            style = body(),
            modifier = Modifier.rise(active, 500)
        )
        Spacer(Modifier.height(24.dp))
        s.topArtists.drop(1).forEachIndexed { k, a ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp).rise(active, 700 + k * 120),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${k + 2}", style = title(), modifier = Modifier.width(32.dp))
                Text(a.name, style = title(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(tr("{минут} мин", "минут" to number(a.minutes)), style = body())
            }
        }
    }
}

@Composable
private fun Days(s: Wrapped.Summary, active: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(tr("Музыка звучала"), style = label(), modifier = Modifier.rise(active, 100))
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.rise(active, 200)) {
            Text(number(counted(s.days.toLong(), active)), style = huge(96.sp))
            Spacer(Modifier.width(10.dp))
            Text(
                word(s.days.toLong(), "день", "дня", "дней", "day", "days"),
                style = title(30.sp),
                modifier = Modifier.padding(bottom = 18.dp)
            )
        }
        Text(
            tr("из {дней}", "дней" to plural(s.span.toLong(), "дня", "дней", "дней", "day", "days")),
            style = title(),
            modifier = Modifier.rise(active, 350).graphicsLayer { alpha *= 0.8f }
        )
        Spacer(Modifier.height(28.dp))
        DayGrid(s, active, Modifier.rise(active, 500))
        Spacer(Modifier.height(28.dp))
        if (s.streak > 1) {
            Text(
                tr("Самая длинная серия — {дней} подряд", "дней" to plural(s.streak.toLong(), "день", "дня", "дней", "day", "days")),
                style = title(),
                modifier = Modifier.rise(active, 900)
            )
            Spacer(Modifier.height(8.dp))
        }
        s.bestDay?.let { day ->
            Text(
                tr("Больше всего музыки было {дата} — {минут} мин", "дата" to dayName(day), "минут" to number(s.bestDayMinutes)),
                style = body(),
                modifier = Modifier.rise(active, 1050)
            )
        }
    }
}

@Composable
private fun TimeOfDay(s: Wrapped.Summary, active: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(tr("Чаще всего вы слушаете"), style = label(), modifier = Modifier.rise(active, 100))
        // «по вечерам» в 60 пунктов на S23 уже переносилась
        val part = tr(s.part.title)
        Text(part, style = huge(if (part.length > 10) 44.sp else 52.sp), modifier = Modifier.rise(active, 250))
        Spacer(Modifier.height(36.dp))
        Bars(s.hours, s.peakHour, active, 170.dp, listOf("0:00", "6:00", "12:00", "18:00", "23:00"), perBar = false)
        Spacer(Modifier.height(20.dp))
        Text(
            tr("Самый музыкальный час — {час}", "час" to "${s.peakHour}:00"),
            style = title(),
            modifier = Modifier.rise(active, 1100)
        )
    }
}

@Composable
private fun TopMonth(s: Wrapped.Summary, active: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(tr("Самый музыкальный месяц"), style = label(), modifier = Modifier.rise(active, 100))
        Text(monthName(s.topMonth), style = huge(60.sp), modifier = Modifier.rise(active, 250))
        Text(
            tr("{минут} мин музыки", "минут" to number(s.months[s.topMonth - 1])),
            style = title(),
            modifier = Modifier.rise(active, 400)
        )
        Spacer(Modifier.height(36.dp))
        Bars(s.months, s.topMonth - 1, active, 170.dp, (1..12).map { monthLetter(it) }, perBar = true)
    }
}

@Composable
private fun Final(s: Wrapped.Summary, photo: String?, active: Boolean, onPlay: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(active, 100)) {
            Text(tr("Итоги {год}", "год" to s.year), style = huge(40.sp), modifier = Modifier.weight(1f))
            if (s.topArtists.isNotEmpty()) ArtistPhoto(photo, 72.dp)
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.rise(active, 250)) {
            TopList(tr("Исполнители"), s.topArtists.map { it.name }, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            TopList(tr("Треки"), s.topTracks.take(5).map { it.title }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.rise(active, 400)) {
            Stat(tr("Минут"), number(s.minutes), Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Stat(tr("Дней с музыкой"), number(s.days.toLong()), Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1f))
        if (s.topTracks.isNotEmpty()) {
            Button(
                onClick = onPlay,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().height(52.dp).rise(active, 600)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(tr("Слушать любимое"), style = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.SemiBold, fontSize = 16.sp))
            }
        }
    }
}

// ── Части ────────────────────────────────────────────────────────────────────

@Composable
private fun TopList(heading: String, items: List<String>, modifier: Modifier) {
    Column(modifier) {
        Text(heading, style = label())
        Spacer(Modifier.height(6.dp))
        items.forEachIndexed { k, name ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("${k + 1}", style = body(), modifier = Modifier.width(20.dp).graphicsLayer { alpha = 0.7f })
                Text(name, style = body().copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Stat(heading: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(heading, style = label())
        Text(value, style = huge(36.sp))
    }
}

@Composable
private fun TrackLine(rank: Int, t: Wrapped.TopTrack, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$rank", style = title(), modifier = Modifier.width(28.dp))
        Artwork(Radio.thumbnailUrl(t.id), 48)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = body().copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.artist.isNotBlank()) {
                Text(t.artist, style = body(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.graphicsLayer { alpha = 0.75f })
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(plays(t.plays), style = body(13.sp), modifier = Modifier.graphicsLayer { alpha = 0.8f })
    }
}

/**
 * Обложка трека покрупнее. Широкая (hq720) есть не у всех роликов — тогда
 * 4:3 с полосами сверху и снизу, их срезает увеличение; как на ПК.
 */
@Composable
private fun BigCover(id: String, size: Dp) {
    val tries = remember(id) { listOf("hq720", "hqdefault", "mqdefault").map { "https://i.ytimg.com/vi/$id/$it.jpg" } }
    var at by remember(id) { mutableIntStateOf(0) }
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.12f))) {
        AsyncImage(
            model = tries[at],
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = { if (at < tries.size - 1) at++ },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                if (at == 1) { scaleX = 1.34f; scaleY = 1.34f }
            }
        )
    }
}

@Composable
private fun ArtistPhoto(url: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Person, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(size / 2))
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Столбики: часы суток или месяцы. Выделенный — белый, остальные — полупрозрачные. */
@Composable
private fun Bars(values: List<Long>, highlight: Int, active: Boolean, height: Dp, labels: List<String>, perBar: Boolean) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) grow.animateTo(1f, tween(1000, 450, FastOutSlowInEasing)) else grow.snapTo(0f)
    }
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
    Column {
        Row(
            Modifier.fillMaxWidth().height(height),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(if (perBar) 6.dp else 3.dp)
        ) {
            values.forEachIndexed { i, v ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight((v / max * grow.value).coerceIn(0.02f, 1f))
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(Color.White.copy(alpha = if (i == highlight) 1f else 0.35f))
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = if (perBar) Arrangement.spacedBy(6.dp) else Arrangement.SpaceBetween
        ) {
            labels.forEach {
                Text(
                    it,
                    style = body(12.sp),
                    textAlign = TextAlign.Center,
                    modifier = (if (perBar) Modifier.weight(1f) else Modifier).graphicsLayer { alpha = 0.75f }
                )
            }
        }
    }
}

/**
 * Календарь: строка — месяц, клетка — день, чем больше музыки — тем ярче.
 * Месяцы — с первого, где что-то звучало: первый год считали не с января.
 */
@Composable
private fun DayGrid(s: Wrapped.Summary, active: Boolean, modifier: Modifier) {
    val heard = s.daily.filterValues { it > 0 }
    if (heard.isEmpty()) return
    val from = heard.keys.minOf { it.monthValue }
    val to = maxOf(heard.keys.maxOf { it.monthValue }, if (LocalDate.now().year == s.year) LocalDate.now().monthValue else 12)
    val months = (from..to).toList()
    val max = heard.values.max().coerceAtLeast(1L).toFloat()
    val show = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) show.animateTo(1f, tween(900, 500)) else show.snapTo(0f)
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val labelW = 22.dp
        val cell = (maxWidth - labelW) / 31
        Row {
            Column(Modifier.width(labelW)) {
                months.forEach { m ->
                    Box(Modifier.height(cell), contentAlignment = Alignment.CenterStart) {
                        Text(monthLetter(m), style = body(10.sp), modifier = Modifier.graphicsLayer { alpha = 0.7f })
                    }
                }
            }
            Canvas(Modifier.width(cell * 31).height(cell * months.size)) {
                val c = size.width / 31f
                val gap = c * 0.2f
                months.forEachIndexed { r, m ->
                    // Строки проявляются по очереди, сверху вниз
                    val appear = ((show.value * months.size) - r).coerceIn(0f, 1f)
                    for (d in 1..YearMonth.of(s.year, m).lengthOfMonth()) {
                        val v = s.daily[LocalDate.of(s.year, m, d)] ?: 0L
                        val a = if (v <= 0) 0.1f else 0.3f + 0.7f * sqrt(v / max)
                        drawRoundRect(
                            color = Color.White.copy(alpha = a * appear),
                            topLeft = Offset((d - 1) * c + gap / 2, r * c + gap / 2),
                            size = Size(c - gap, c - gap),
                            cornerRadius = CornerRadius(c * 0.25f)
                        )
                    }
                }
            }
        }
    }
}

/** Значок приложения в движении: два столбика пляшут, третий лежит и иногда потягивается. */
@Composable
private fun LazyBars(active: Boolean, height: Dp, modifier: Modifier = Modifier) {
    val beat = rememberInfiniteTransition(label = "bars")
    val a by beat.animateFloat(0.45f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "a")
    val b by beat.animateFloat(1f, 0.55f, infiniteRepeatable(tween(680), RepeatMode.Reverse), label = "b")
    val c by beat.animateFloat(0f, 1f, infiniteRepeatable(tween(2600), RepeatMode.Restart), label = "c")
    val lazy = if (c > 0.85f) 1.6f else 1f
    val w = height / 4
    Row(modifier.height(height), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(w / 2)) {
        Box(Modifier.width(w).fillMaxHeight(if (active) a * 0.7f else 0.5f).clip(RoundedCornerShape(w / 4)).background(Color.White))
        Box(Modifier.width(w).fillMaxHeight(if (active) b else 0.8f).clip(RoundedCornerShape(w / 4)).background(Color.White))
        Box(Modifier.width(w * 1.5f).height(w * 0.9f * lazy).clip(RoundedCornerShape(w / 4)).background(Color.White))
    }
}

// ── Анимация ─────────────────────────────────────────────────────────────────

/** Проявиться снизу, когда экран стал текущим; ушли с экрана — спрятаться. */
@Composable
private fun Modifier.rise(active: Boolean, delay: Int): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) a.animateTo(1f, tween(650, delay, FastOutSlowInEasing)) else a.snapTo(0f)
    }
    return graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 40.dp.toPx()
    }
}

/** Число, набегающее от нуля. */
@Composable
private fun counted(target: Long, active: Boolean): Long {
    val p = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) p.animateTo(1f, tween(1600, 300, FastOutSlowInEasing)) else p.snapTo(0f)
    }
    return (target * p.value).roundToLong()
}

// ── Текст ────────────────────────────────────────────────────────────────────

private fun huge(size: TextUnit) = TextStyle(
    fontFamily = Fonts.display, fontWeight = FontWeight.Bold, fontSize = size,
    lineHeight = size * 1.05f, letterSpacing = (-1).sp
)

private fun headline() = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp)

private fun title(size: TextUnit = 20.sp) = TextStyle(
    fontFamily = Fonts.display, fontWeight = FontWeight.SemiBold, fontSize = size, lineHeight = size * 1.2f
)

private fun label() = TextStyle(fontFamily = Fonts.text, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp)

private fun body(size: TextUnit = 15.sp) = TextStyle(fontFamily = Fonts.text, fontSize = size, lineHeight = size * 1.35f)

private fun locale(): Locale = if (Lang.english) Locale.US else Locale.forLanguageTag("ru")

private fun number(n: Long): String = NumberFormat.getIntegerInstance(locale()).format(n)

/** Только слово из «12 минут» — число рядом крупно и набегает. */
private fun word(n: Long, one: String, few: String, many: String, enOne: String, enMany: String) =
    plural(n, one, few, many, enOne, enMany).substringAfter(' ')

private fun plays(n: Int) = plural(n.toLong(), "раз", "раза", "раз", "play", "plays")

private fun monthName(m: Int) =
    Month.of(m).getDisplayName(DateStyle.FULL_STANDALONE, locale()).replaceFirstChar { it.titlecase(locale()) }

private fun monthLetter(m: Int) = Month.of(m).getDisplayName(DateStyle.NARROW_STANDALONE, locale()).uppercase(locale())

private fun dayName(d: LocalDate) =
    d.format(DateTimeFormatter.ofPattern(if (Lang.english) "MMMM d" else "d MMMM", locale()))
