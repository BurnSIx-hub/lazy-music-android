package com.lazymusic.app

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Похожие исполнители из открытого каталога Deezer. Ключа не требует.
 *
 * Зачем вообще чужой каталог. Микс YouTube `RD<id>` — это в основном один
 * и тот же исполнитель (проверено 2026-09-10), а «похожее по духу, но
 * другое» строится на том, кого люди слушают вместе. У Last.fm и Spotify
 * это своя статистика прослушиваний; у Deezer — та же идея, и публичная
 * часть каталога открыта без ключа и без регистрации.
 *
 * Отсюда приходят только **имена**: исполнитель и название. Звука здесь нет,
 * ссылок на Deezer мы не трогаем — найденное потом ищется на YouTube и
 * играет оттуда же, откуда играло всегда.
 *
 * **Чего это не умеет.** Нишевую музыку — игровые саундтреки, доуджин,
 * мелкие лейблы — каталог часто не знает вовсе и отвечает пустотой. Это
 * нормальный исход, а не ошибка: [Radio] в таком случае идёт другим путём.
 */
object Deezer {

    private const val TAG = "LazyMusic"
    private const val BASE = "https://api.deezer.com"

    /** Кандидат: имя исполнителя и название. К YouTube ещё не привязан. */
    data class Suggestion(val artist: String, val title: String) {
        /** Строка, которой этот трек ищется на YouTube. */
        val query: String get() = "$artist $title"
    }

    /**
     * Треки других исполнителей, близких к затравке.
     *
     * Порядок: сначала радио самого исполнителя — Deezer уже перемешал там
     * соседей по вкусу, — а если оно скупое, добираем по списку похожих
     * исполнителей их заглавные вещи.
     *
     * Треки самого затравочного исполнителя отбрасываются: их и так вдоволь
     * в ближнем круге, а сюда мы идём ровно за остальными.
     */
    fun similar(seedArtist: String, seedTitle: String, want: Int = 12): List<Suggestion> {
        val artist = findArtist(seedArtist, seedTitle) ?: return emptyList()
        Log.i(TAG, "Deezer опознал исполнителя: ${artist.name}")

        val out = LinkedHashMap<String, Suggestion>()
        collectRadio(artist, out, want)
        if (out.size < want) collectRelated(artist, out, want)

        Log.i(TAG, "Deezer предложил ${out.size} треков других исполнителей")
        return out.values.take(want)
    }

    /** Исполнитель для его страницы: фото, число поклонников, похожие. */
    class ArtistInfo(val name: String, val picture: String, val fans: Long, val related: List<Related>)

    class Related(val name: String, val picture: String)

    /**
     * Исполнитель в каталоге. null — каталог его не знает (нишевая музыка)
     * или под этим именем там кто-то другой. Как `artistInfo` на ПК.
     */
    fun artistInfo(name: String): ArtistInfo? {
        val clean = artistOf(name)
        if (clean.isBlank()) return null
        val found = fetch("$BASE/search/artist?limit=1&q=${enc(clean)}").optJSONArray("data")?.optJSONObject(0)
            ?: return null
        if (!matches(found.optString("name"), clean)) return null
        val related = runCatching {
            val arr = fetch("$BASE/artist/${found.optLong("id")}/related?limit=18").optJSONArray("data")
            (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val a = arr?.optJSONObject(i) ?: return@mapNotNull null
                Related(a.optString("name"), a.optString("picture_medium"))
            }.filter { it.name.isNotBlank() }
        }.getOrDefault(emptyList())
        return ArtistInfo(
            name = found.optString("name"),
            picture = listOf("picture_xl", "picture_big", "picture_medium")
                .map { found.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty(),
            fans = found.optLong("nb_fan"),
            related = related
        )
    }

    // ── Опознание исполнителя ────────────────────────────────────────────────

    private class Artist(val id: Long, val name: String)

    /**
     * Кто это.
     *
     * Сначала по имени: у роликов с автоканалов YouTube («... - Topic») оно
     * ровно такое, как в каталоге. Если не сошлось — ищем по названию трека
     * целиком: поиск Deezer сам разберёт строку вида «Автор — Песня», а
     * заодно вытащит имя из названий, где канал вообще не при чём.
     */
    private fun findArtist(seedArtist: String, seedTitle: String): Artist? {
        val name = artistOf(seedArtist)
        if (name.isNotEmpty()) {
            val found = runCatching {
                fetch("$BASE/search/artist?limit=1&q=${enc(name)}")
                    .optJSONArray("data")?.optJSONObject(0)
            }.getOrNull()
            val foundName = found?.optString("name").orEmpty()
            if (found != null && matches(foundName, name)) {
                return Artist(found.optLong("id"), foundName)
            }
        }

        val title = cleanTitle(seedTitle)
        if (title.isEmpty()) return null
        val track = runCatching {
            fetch("$BASE/search?limit=1&q=${enc(title)}")
                .optJSONArray("data")?.optJSONObject(0)
        }.getOrNull() ?: return null
        val obj = track.optJSONObject("artist") ?: return null
        val id = obj.optLong("id")
        return if (id > 0) Artist(id, obj.optString("name")) else null
    }

    // ── Источники ────────────────────────────────────────────────────────────

    /** Радио исполнителя: готовая подборка соседей по вкусу, один запрос. */
    private fun collectRadio(artist: Artist, out: MutableMap<String, Suggestion>, want: Int) {
        val data = runCatching { fetch("$BASE/artist/${artist.id}/radio").optJSONArray("data") }
            .onFailure { Log.w(TAG, "радио Deezer не отдалось: ${it.message}") }
            .getOrNull() ?: return
        for (i in 0 until data.length()) {
            if (out.size >= want) return
            add(data.optJSONObject(i), artist, out)
        }
    }

    /** Запасной путь: список похожих исполнителей и по паре заглавных вещей у каждого. */
    private fun collectRelated(artist: Artist, out: MutableMap<String, Suggestion>, want: Int) {
        val related = runCatching {
            fetch("$BASE/artist/${artist.id}/related?limit=$RELATED").optJSONArray("data")
        }.getOrNull() ?: return

        for (i in 0 until related.length()) {
            if (out.size >= want) return
            val other = related.optJSONObject(i) ?: continue
            val id = other.optLong("id").takeIf { it > 0 } ?: continue
            val top = runCatching {
                fetch("$BASE/artist/$id/top?limit=$TOP_EACH").optJSONArray("data")
            }.getOrNull() ?: continue
            for (j in 0 until top.length()) {
                if (out.size >= want) return
                add(top.optJSONObject(j), artist, out)
            }
        }
    }

    /**
     * Берём не больше [PER_ARTIST] вещей одного исполнителя: иначе радио
     * какого-нибудь Toby Fox съело бы половину подсказок, и дальше их всё
     * равно отсеяло бы сплетение очереди — только запросы бы потратили.
     */
    private fun add(track: JSONObject?, seed: Artist, out: MutableMap<String, Suggestion>) {
        val title = track?.optString("title").orEmpty().trim()
        val name = track?.optJSONObject("artist")?.optString("name").orEmpty().trim()
        if (title.isEmpty() || name.isEmpty()) return
        if (same(name, seed.name)) return
        val artist = norm(name)
        if (out.values.count { norm(it.artist) == artist } >= PER_ARTIST) return
        val key = "$artist|${norm(title)}"
        if (key !in out) out[key] = Suggestion(name, title)
    }

    // ── Мелочи ───────────────────────────────────────────────────────────────

    private const val RELATED = 5
    private const val TOP_EACH = 2
    private const val PER_ARTIST = 2

    /**
     * Убирает из названия ролика всё, что мешает поиску: пометки в скобках
     * вроде «(Official Video)», хвосты после вертикальной черты и лишние
     * пробелы. Само «Автор — Песня» не трогаем: поиск Deezer его понимает.
     */
    private fun cleanTitle(title: String): String = title
        .replace(Regex("\\([^)]*\\)"), " ")
        .replace(Regex("\\[[^\\]]*]"), " ")
        .substringBefore('|')
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Один и тот же исполнитель: буква в букву, если отбросить знаки и регистр. */
    private fun same(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        return x.isNotEmpty() && x == y
    }

    /**
     * Годится ли найденное имя за то, которое спрашивали.
     *
     * Помягче, чем [same]: у канала YouTube к имени часто прилипает хвост
     * вроде «Official» или «Music». Но короткие имена так сравнивать нельзя —
     * «Ado» нашлось бы в «Adore», — поэтому вложенность считаем только от
     * пяти букв.
     */
    private fun matches(found: String, asked: String): Boolean {
        val x = norm(found)
        val y = norm(asked)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val shorter = minOf(x.length, y.length)
        return shorter >= 5 && (x.contains(y) || y.contains(x))
    }

    private fun norm(value: String) = value.lowercase().filter { it.isLetterOrDigit() }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Ответ каталога. Свои ошибки Deezer кладёт в тело с кодом 200. */
    private fun fetch(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("Deezer ответил $code")
            val json = runCatching { JSONObject(body.ifBlank { "{}" }) }
                .getOrElse { throw IOException("Deezer ответил не разбираемым текстом") }
            json.optJSONObject("error")?.let { err ->
                throw IOException(err.optString("message").ifBlank { "Deezer отказал" })
            }
            return json
        } finally {
            conn.disconnect()
        }
    }
}
