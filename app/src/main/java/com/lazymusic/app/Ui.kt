package com.lazymusic.app

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbDownOffAlt
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.ThumbUpOffAlt
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage

/**
 * Шрифты как у Lazy Music на ПК, а там — как у YouTube Music: текст —
 * Roboto, заголовки — Inter Tight (ближе всех к фирменному YouTube Sans,
 * который распространять нельзя). Оба вшиты, OFL — лицензии в assets.
 */
object Fonts {
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    private fun weights(id: Int) = FontFamily(
        listOf(400, 500, 600, 700).map { w ->
            Font(id, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
        }
    )

    val text = weights(R.font.roboto)
    val display = weights(R.font.inter_tight)
}

private fun typography(): Typography {
    val t = Typography()
    fun TextStyle.text() = copy(fontFamily = Fonts.text)
    fun TextStyle.display() = copy(fontFamily = Fonts.display, fontWeight = FontWeight.Bold)
    return Typography(
        displayLarge = t.displayLarge.display(), displayMedium = t.displayMedium.display(),
        displaySmall = t.displaySmall.display(), headlineLarge = t.headlineLarge.display(),
        headlineMedium = t.headlineMedium.display(), headlineSmall = t.headlineSmall.display(),
        titleLarge = t.titleLarge.display(), titleMedium = t.titleMedium.text(), titleSmall = t.titleSmall.display(),
        bodyLarge = t.bodyLarge.text(), bodyMedium = t.bodyMedium.text(), bodySmall = t.bodySmall.text(),
        labelLarge = t.labelLarge.text(), labelMedium = t.labelMedium.text(), labelSmall = t.labelSmall.text()
    )
}

/**
 * Тема. На Android 12+ берём цвета из обоев — плееру идёт быть в тон системе,
 * а рисовать свою палитру ради трёх экранов незачем.
 */
@Composable
fun LazyMusicTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, typography = typography(), content = content)
}

/** Значок приложения — «ленивый эквалайзер»: два столбика стоят, третий улёгся. */
val LazyEqIcon: ImageVector by lazy {
    ImageVector.Builder("lazyEq", 24.dp, 24.dp, 64f, 64f).apply {
        fun bar(x: Float, y: Float, w: Float, h: Float) = addPath(
            PathParser().parsePathString("M${x + 2},${y}h${w - 4}a2,2 0 0 1 2,2v${h - 4}a2,2 0 0 1 -2,2h-${w - 4}a2,2 0 0 1 -2,-2v-${h - 4}a2,2 0 0 1 2,-2z").toNodes(),
            fill = SolidColor(Color.Black)
        )
        bar(12f, 26f, 10f, 26f)
        bar(26f, 13f, 10f, 39f)
        bar(39f, 42f, 15f, 10f)
    }.build()
}

/**
 * 👎 «Не нравится». Состояние берёт у [Taste] само; на дизлайкнутом —
 * снимает дизлайк.
 */
@UnstableApi
@Composable
fun DislikeButton(track: Track, host: PlayerHost, modifier: Modifier = Modifier) {
    val disliked = Taste.list.any { it.id == track.id }
    IconButton(onClick = { Dislike.toggle(host, track) }, modifier = modifier) {
        Icon(
            if (disliked) Icons.Default.ThumbDown else Icons.Default.ThumbDownOffAlt,
            contentDescription = if (disliked) tr("Снять дизлайк") else tr("Не нравится"),
            tint = if (disliked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Обложка со скруглением. Пока картинка едет — заглушка того же размера. */
@Composable
fun Artwork(url: String?, size: Int, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(if (size >= 200) 20.dp else 10.dp)
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (url.isNullOrBlank()) {
            Icon(
                Icons.Default.Album,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size((size / 3).dp)
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                // Превью YouTube широкие (16:9), а место под обложку квадратное.
                // Без обрезки картинка вписывается с серыми полями сверху и снизу
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size.dp).clip(shape)
            )
        }
    }
}

/**
 * Строка трека. Нажатие — играть, три точки — меню (в плейлист, радио).
 * Играющий трек подсвечен цветом, а не значком: значок съедает ширину,
 * которой на телефоне и так мало.
 */
@Composable
fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    cached: Boolean,
    onPlay: () -> Unit,
    onMenu: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(track.artworkUrl, 48)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = listOfNotNull(
                track.artist.takeIf { it.isNotBlank() },
                if (cached) tr("скачан") else null
            ).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onMenu) {
            Icon(Icons.Default.MoreVert, contentDescription = tr("Ещё"))
        }
    }
}

/**
 * 👍 «Нравится» — пара к 👎. Состояние берёт у [Likes] само, поэтому
 * одинаково выглядит везде и перекрашивается, даже если лайк поставили из
 * уведомления. Сердце осталось только у списка «Понравившаяся музыка».
 */
@Composable
fun LikeButton(track: Track, onLike: (Track) -> Unit, modifier: Modifier = Modifier) {
    val liked = track.id in Likes
    IconButton(onClick = { onLike(track) }, modifier = modifier) {
        Icon(
            if (liked) Icons.Default.ThumbUp else Icons.Default.ThumbUpOffAlt,
            contentDescription = if (liked) tr("Убрать из понравившегося") else tr("Нравится"),
            tint = if (liked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Полоска над нижним меню: что играет сейчас, с 👎, 👍 и кнопкой паузы. */
@UnstableApi
@Composable
fun MiniPlayer(host: PlayerHost, onLike: (Track) -> Unit, onOpen: () -> Unit) {
    val track = host.current ?: return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            if (host.buffering) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Artwork(track.artworkUrl, 40)
                Column(Modifier.weight(1f)) {
                    MarqueeText(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (track.artist.isNotBlank()) {
                        MarqueeText(
                            text = track.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                DislikeButton(track, host)
                LikeButton(track, onLike)
                IconButton(onClick = host::toggle) {
                    Icon(
                        if (host.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (host.playing) tr("Пауза") else tr("Играть")
                    )
                }
            }
        }
    }
}

/**
 * Кружок вместо значка внутри кнопки, пока идёт работа.
 *
 * Нужен потому, что тонкая полоска где-то под полями ввода читается плохо:
 * запрос отрабатывает за доли секунды, полоска моргает, и кажется, что
 * нажатие не прошло. Отклик должен быть там, куда смотрит палец.
 */
@Composable
fun ButtonSpinner() {
    CircularProgressIndicator(
        modifier = Modifier.size(20.dp),
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.onPrimary
    )
}

/** Сообщение на пустом экране — чтобы он не выглядел сломанным. */
@Composable
fun EmptyHint(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Полоска с текстом ошибки. Пустую строку не показываем вовсе. */
@Composable
fun Notice(text: String?, tone: Color = MaterialTheme.colorScheme.error) {
    if (text.isNullOrBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = tone,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    )
}
