package com.lazymusic.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.Normalizer

/**
 * Поиск по своей библиотеке: строка поиска внутри плейлиста и подсказки,
 * где своё идёт раньше подсказок YouTube.
 *
 * Сравнение без регистра, диакритики и разницы «ё/е»: «земфира» найдёт
 * «Земфира», «maneskin» — «Måneskin». Каждое набранное слово должно найтись
 * в названии или исполнителе — так «кино звезда» находит «Кино — Звезда по
 * имени Солнце», а порядок слов неважен.
 */
object Find {

    private val MARKS = Regex("""\p{M}+""")
    private val SPACES = Regex("""\s+""")

    /** Трек из своего плейлиста; играть его — в этом плейлисте, с его места. */
    class Hit(val track: Track, val playlist: String)

    fun norm(text: String): String =
        MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKD), "").replace('ё', 'е')

    private fun words(query: String) = norm(query).trim().split(SPACES).filter { it.isNotEmpty() }

    /** Название, как его видит человек: своё, если трек переименовали. */
    private fun shown(track: Track) = Settings.trackName(track.id) ?: track.title

    fun matches(track: Track, query: String): Boolean {
        val want = words(query)
        if (want.isEmpty()) return true
        val hay = norm(shown(track) + " " + track.title + " " + track.artist)
        return want.all { hay.contains(it) }
    }

    // ── Подсказки из библиотеки ──────────────────────────────────────────────

    private class Entry(val hit: Hit, val title: String, val artist: String, val hay: String)

    @Volatile private var index: List<Entry> = emptyList()
    @Volatile private var indexed = -1L

    /**
     * Свои треки под набранное — лучшие сначала: где с набранного начинается
     * название, потом слово в названии, потом исполнитель, потом остальное.
     * Плейлисты разбираются заново, только когда библиотека менялась.
     */
    fun inLibrary(query: String, limit: Int = 4): List<Hit> {
        val want = words(query)
        if (want.isEmpty() || want.sumOf { it.length } < 2) return emptyList()
        val list = library()
        val first = want.joinToString(" ")
        return list.asSequence()
            .filter { e -> want.all { e.hay.contains(it) } }
            .map { e ->
                val rank = when {
                    e.title.startsWith(first) -> 0
                    e.title.contains(" $first") -> 1
                    e.artist.startsWith(first) -> 2
                    else -> 3
                }
                rank to e
            }
            .sortedBy { it.first }
            .take(limit)
            .map { it.second.hit }
            .toList()
    }

    private fun library(): List<Entry> {
        val revision = Settings.libraryRevision
        if (revision == indexed) return index
        val seen = HashSet<String>()
        val out = mutableListOf<Entry>()
        for (name in Settings.playlistNames()) {
            for (track in Settings.playlist(name)) {
                if (!seen.add(track.id)) continue
                val title = norm(shown(track))
                val artist = norm(track.artist)
                out += Entry(Hit(track, name), title, artist, "$title ${norm(track.title)} $artist")
            }
        }
        index = out
        indexed = revision
        return out
    }
}

/**
 * Подсказки под строкой поиска: сначала своё из плейлистов (нажатие —
 * играть), потом подсказки YouTube (нажатие — искать).
 *
 * Высота ограничена нарочно: клавиатура занимает низ экрана, и строки под
 * ней не нажать — касание уходит клавиатуре (проверено на S23).
 */
@Composable
fun HintCard(
    own: List<Find.Hit>,
    hints: List<String>,
    onOwn: (Find.Hit) -> Unit,
    onHint: (String) -> Unit
) {
    Card(Modifier.fillMaxWidth().heightIn(max = 184.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            own.forEach { hit ->
                val t = hit.track
                val title = Settings.trackName(t.id) ?: t.title
                HintRow(
                    Icons.Default.LibraryMusic,
                    if (t.artist.isBlank()) title else "$title — ${t.artist}",
                    MaterialTheme.colorScheme.primary
                ) { onOwn(hit) }
            }
            hints.forEach { hint ->
                HintRow(Icons.Default.Search, hint, MaterialTheme.colorScheme.onSurfaceVariant) { onHint(hint) }
            }
        }
    }
}

@Composable
private fun HintRow(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
